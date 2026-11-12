//! Formal security primitives and executable model-checking harnesses for
//! `chronicle-server`:
//! 1. `PinBoundSessionAuthority`: HMAC-SHA256 session tokens cryptographically
//!    bound to `(epoch, tls_fp, nonce)` so TLS certificate / SPKI pin rotation
//!    atomically revokes all pre-rotation credentials (including under ABA pin
//!    rollback).
//! 2. `LockedVaultSpool`: Forward-secret SHA-256 hash-ratcheted
//!    `ChaCha20-Poly1305` capture spooling while the vault is locked (master
//!    key zeroized in RAM), with idempotent crash-safe FIFO flush on unlock.

use std::collections::BTreeSet;
use std::sync::atomic::{self, Ordering};

use base64::Engine as _;
use rand::RngCore as _;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey};
use ring::hmac;
use sha2::{Digest, Sha256};

/// Overwrites `buf` byte-by-byte using volatile writes followed by a `SeqCst`
/// compiler fence so dead-store elimination cannot elide key zeroization.
pub fn volatile_zeroize(buf: &mut [u8]) {
    for b in buf.iter_mut() {
        // SAFETY: `b` is a valid, aligned mutable reference to a `u8` in `buf`.
        unsafe {
            std::ptr::write_volatile(b, 0u8);
        }
    }
    atomic::compiler_fence(Ordering::SeqCst);
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SecurityViolation {
    EmptyTlsFingerprint,
    MalformedToken,
    EpochOrPinRevoked,
    TokenExplicitlyRevoked,
    InvalidHmacSignature,
    InvalidMasterKey,
    VaultAlreadyLocked,
    SpoolSequenceTampered { expected_seq: u64, got_seq: u64 },
    SpoolEpochTampered { expected_epoch: u64, got_epoch: u64 },
    AeadAuthenticationFailed { seq: u64 },
    SimulatedMidFlushCrash { flushed_count: usize },
}

/// Issues and verifies `X-Chronicle-Token` credentials cryptographically bound
/// to the server's active TLS SPKI pin (`tls_fp`) and rotation `epoch`.
pub struct PinBoundSessionAuthority {
    hmac_key: hmac::Key,
    active_tls_fp: String,
    epoch: u64,
    revoked_nonces: BTreeSet<[u8; 16]>,
}

impl PinBoundSessionAuthority {
    pub fn new(secret: &[u8; 32], initial_tls_fp: &str) -> Result<Self, SecurityViolation> {
        let fp = initial_tls_fp.trim();
        if fp.is_empty() {
            return Err(SecurityViolation::EmptyTlsFingerprint);
        }
        Ok(Self {
            hmac_key: hmac::Key::new(hmac::HMAC_SHA256, secret),
            active_tls_fp: fp.to_string(),
            epoch: 1,
            revoked_nonces: BTreeSet::new(),
        })
    }

    pub fn active_tls_fp(&self) -> &str {
        &self.active_tls_fp
    }

    pub fn epoch(&self) -> u64 {
        self.epoch
    }

    fn mac_message(epoch: u64, tls_fp: &str, nonce: &[u8; 16]) -> Vec<u8> {
        let fp_bytes = tls_fp.as_bytes();
        let mut msg = Vec::with_capacity(24 + 8 + 4 + fp_bytes.len() + 16);
        msg.extend_from_slice(b"chronicle-pin-token-v2\0");
        msg.extend_from_slice(&epoch.to_be_bytes());
        msg.extend_from_slice(&(fp_bytes.len() as u32).to_be_bytes());
        msg.extend_from_slice(fp_bytes);
        msg.extend_from_slice(nonce);
        msg
    }

    /// Issues a URL-safe token encoding `v2.<epoch_hex>.<nonce_b64>.<mac_b64>`
    /// bound to the current `(epoch, active_tls_fp)`.
    pub fn issue_token(&self) -> String {
        let mut nonce = [0u8; 16];
        rand::rngs::OsRng.fill_bytes(&mut nonce);
        let msg = Self::mac_message(self.epoch, &self.active_tls_fp, &nonce);
        let tag = hmac::sign(&self.hmac_key, &msg);
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        format!(
            "v2.{:016x}.{}.{}",
            self.epoch,
            b64.encode(nonce),
            b64.encode(tag.as_ref())
        )
    }

    /// Verifies `token` in constant time against `(self.epoch, expected_client_pin)`
    /// and `self.active_tls_fp`. Any certificate pin rotation invalidates all
    /// tokens from prior epochs.
    pub fn verify_token(
        &self,
        token: &str,
        presented_tls_fp: &str,
    ) -> Result<(), SecurityViolation> {
        if presented_tls_fp != self.active_tls_fp {
            return Err(SecurityViolation::EpochOrPinRevoked);
        }
        let mut parts = token.split('.');
        let (Some(ver), Some(epoch_hex), Some(nonce_b64), Some(tag_b64), None) = (
            parts.next(),
            parts.next(),
            parts.next(),
            parts.next(),
            parts.next(),
        ) else {
            return Err(SecurityViolation::MalformedToken);
        };
        if ver != "v2" || epoch_hex.len() != 16 {
            return Err(SecurityViolation::MalformedToken);
        }
        let tok_epoch =
            u64::from_str_radix(epoch_hex, 16).map_err(|_| SecurityViolation::MalformedToken)?;
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        let nonce_vec = b64
            .decode(nonce_b64)
            .map_err(|_| SecurityViolation::MalformedToken)?;
        let nonce: [u8; 16] = nonce_vec
            .try_into()
            .map_err(|_| SecurityViolation::MalformedToken)?;
        let tag_bytes = b64
            .decode(tag_b64)
            .map_err(|_| SecurityViolation::MalformedToken)?;

        // Always compute & verify the MAC over the active `(self.epoch, self.active_tls_fp)`
        // before branching on `tok_epoch` so timing is uniform.
        let expected_msg = Self::mac_message(self.epoch, &self.active_tls_fp, &nonce);
        let mac_ok = hmac::verify(&self.hmac_key, &expected_msg, &tag_bytes).is_ok();
        if tok_epoch != self.epoch {
            return Err(SecurityViolation::EpochOrPinRevoked);
        }
        if !mac_ok {
            return Err(SecurityViolation::InvalidHmacSignature);
        }
        if self.revoked_nonces.contains(&nonce) {
            return Err(SecurityViolation::TokenExplicitlyRevoked);
        }
        Ok(())
    }

    /// Rotates the active TLS SPKI fingerprint (`tls_fp`), incrementing `epoch`
    /// and clearing stale per-nonce revocations from superseded epochs.
    pub fn rotate_tls_pin(&mut self, new_tls_fp: &str) -> Result<u64, SecurityViolation> {
        let fp = new_tls_fp.trim();
        if fp.is_empty() {
            return Err(SecurityViolation::EmptyTlsFingerprint);
        }
        self.epoch = self.epoch.saturating_add(1);
        self.active_tls_fp = fp.to_string();
        self.revoked_nonces.clear();
        Ok(self.epoch)
    }

    /// Revokes a single paired device's token without rotating the server cert.
    pub fn revoke_token(&mut self, token: &str) -> Result<(), SecurityViolation> {
        self.verify_token(token, &self.active_tls_fp)?;
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        let nonce_part = token
            .split('.')
            .nth(2)
            .ok_or(SecurityViolation::MalformedToken)?;
        let nonce_vec = b64
            .decode(nonce_part)
            .map_err(|_| SecurityViolation::MalformedToken)?;
        let nonce: [u8; 16] = nonce_vec
            .try_into()
            .map_err(|_| SecurityViolation::MalformedToken)?;
        self.revoked_nonces.insert(nonce);
        Ok(())
    }
}

/// Encrypted envelope stored in the pending spool while the vault is locked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SealedCaptureEnvelope {
    pub lock_epoch: u64,
    pub seq: u64,
    pub capture_id: String,
    pub nonce: [u8; 12],
    pub ciphertext_and_tag: Vec<u8>,
}

/// Committed plaintext capture inside the unlocked vault.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommittedCapture {
    pub capture_id: String,
    pub plaintext: Vec<u8>,
}

enum RuntimeVaultState {
    Unlocked {
        master_key: [u8; 32],
    },
    Locked {
        lock_epoch: u64,
        next_seq: u64,
        ratchet_chain_key: [u8; 32],
    },
}

impl Drop for RuntimeVaultState {
    fn drop(&mut self) {
        match self {
            RuntimeVaultState::Unlocked { master_key } => volatile_zeroize(master_key),
            RuntimeVaultState::Locked {
                ratchet_chain_key, ..
            } => volatile_zeroize(ratchet_chain_key),
        }
    }
}

/// Forward-secret locked-vault capture spool + crash-consistent FIFO flush engine.
pub struct LockedVaultSpool {
    key_verifier_tag: [u8; 32],
    current_lock_epoch: u64,
    state: RuntimeVaultState,
    pending_spool: Vec<SealedCaptureEnvelope>,
    committed: Vec<CommittedCapture>,
    committed_ids: BTreeSet<String>,
}

impl LockedVaultSpool {
    pub fn new_unlocked(master_key: [u8; 32]) -> Self {
        let verifier = Self::compute_key_verifier(&master_key);
        Self {
            key_verifier_tag: verifier,
            current_lock_epoch: 0,
            state: RuntimeVaultState::Unlocked { master_key },
            pending_spool: Vec::new(),
            committed: Vec::new(),
            committed_ids: BTreeSet::new(),
        }
    }

    fn compute_key_verifier(master_key: &[u8; 32]) -> [u8; 32] {
        let k = hmac::Key::new(hmac::HMAC_SHA256, master_key);
        let tag = hmac::sign(&k, b"chronicle-vault-key-verifier-v1");
        let mut out = [0u8; 32];
        out.copy_from_slice(tag.as_ref());
        out
    }

    fn derive_epoch_seed(master_key: &[u8; 32], lock_epoch: u64) -> [u8; 32] {
        let k = hmac::Key::new(hmac::HMAC_SHA256, master_key);
        let mut msg = [0u8; 32];
        msg[..24].copy_from_slice(b"chronicle-spool-epoch-v1");
        msg[24..].copy_from_slice(&lock_epoch.to_be_bytes());
        let tag = hmac::sign(&k, &msg);
        let mut out = [0u8; 32];
        out.copy_from_slice(tag.as_ref());
        out
    }

    /// Ratchets `chain_key` one step forward: returns `(dek_n, chain_key_{n+1})`.
    pub fn ratchet_step(chain_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
        let mut dek_h = Sha256::new();
        dek_h.update(b"chronicle-spool-dek-v1\0");
        dek_h.update(chain_key);
        let dek: [u8; 32] = dek_h.finalize().into();

        let mut next_h = Sha256::new();
        next_h.update(b"chronicle-spool-next-v1\0");
        next_h.update(chain_key);
        let next_chain: [u8; 32] = next_h.finalize().into();

        (dek, next_chain)
    }

    fn build_aad(lock_epoch: u64, seq: u64, capture_id: &str) -> Vec<u8> {
        let id_bytes = capture_id.as_bytes();
        let mut aad = Vec::with_capacity(24 + 8 + 8 + 4 + id_bytes.len());
        aad.extend_from_slice(b"chronicle-spool-aad-v1\0\0");
        aad.extend_from_slice(&lock_epoch.to_be_bytes());
        aad.extend_from_slice(&seq.to_be_bytes());
        aad.extend_from_slice(&(id_bytes.len() as u32).to_be_bytes());
        aad.extend_from_slice(id_bytes);
        aad
    }

    pub fn is_locked(&self) -> bool {
        matches!(self.state, RuntimeVaultState::Locked { .. })
    }

    pub fn master_key_resident_in_ram(&self) -> bool {
        matches!(self.state, RuntimeVaultState::Unlocked { .. })
    }

    pub fn pending_envelopes(&self) -> &[SealedCaptureEnvelope] {
        &self.pending_spool
    }

    pub fn pending_envelopes_mut_for_test(&mut self) -> &mut Vec<SealedCaptureEnvelope> {
        &mut self.pending_spool
    }

    pub fn committed_entries(&self) -> &[CommittedCapture] {
        &self.committed
    }

    /// Inspects the current ratchet chain key (only when `Locked`) for forward-secrecy testing.
    pub fn current_ratchet_key_snapshot(&self) -> Option<[u8; 32]> {
        match &self.state {
            RuntimeVaultState::Locked {
                ratchet_chain_key, ..
            } => Some(*ratchet_chain_key),
            RuntimeVaultState::Unlocked { .. } => None,
        }
    }

    /// Zeroizes the master key in RAM and transitions the vault to `Locked`
    /// with a fresh forward-secret ratchet chain seed.
    pub fn lock_vault(&mut self) -> Result<u64, SecurityViolation> {
        match &mut self.state {
            RuntimeVaultState::Locked { .. } => Err(SecurityViolation::VaultAlreadyLocked),
            RuntimeVaultState::Unlocked { master_key } => {
                self.current_lock_epoch = self.current_lock_epoch.saturating_add(1);
                let mut seed = Self::derive_epoch_seed(master_key, self.current_lock_epoch);
                volatile_zeroize(master_key);
                let epoch = self.current_lock_epoch;
                self.state = RuntimeVaultState::Locked {
                    lock_epoch: epoch,
                    next_seq: 0,
                    ratchet_chain_key: seed,
                };
                volatile_zeroize(&mut seed);
                Ok(epoch)
            }
        }
    }

    /// Accepts a mobile capture: commits directly if `Unlocked` (with zero
    /// unushed spool backlog), or seals into `pending_spool` under the
    /// ratcheted one-time AEAD key if `Locked`.
    pub fn accept_capture(
        &mut self,
        capture_id: &str,
        plaintext: &[u8],
    ) -> Result<(), SecurityViolation> {
        match &mut self.state {
            RuntimeVaultState::Unlocked { .. } => {
                if self.committed_ids.insert(capture_id.to_string()) {
                    self.committed.push(CommittedCapture {
                        capture_id: capture_id.to_string(),
                        plaintext: plaintext.to_vec(),
                    });
                }
                Ok(())
            }
            RuntimeVaultState::Locked {
                lock_epoch,
                next_seq,
                ratchet_chain_key,
            } => {
                let seq = *next_seq;
                let (mut dek, mut next_chain) = Self::ratchet_step(ratchet_chain_key);
                volatile_zeroize(ratchet_chain_key);
                *ratchet_chain_key = next_chain;
                volatile_zeroize(&mut next_chain);
                *next_seq = seq.saturating_add(1);

                let mut nonce_bytes = [0u8; 12];
                rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
                let aad = Self::build_aad(*lock_epoch, seq, capture_id);

                let unbound = UnboundKey::new(&ring::aead::CHACHA20_POLY1305, &dek)
                    .map_err(|_| SecurityViolation::AeadAuthenticationFailed { seq })?;
                volatile_zeroize(&mut dek);
                let aead_key = LessSafeKey::new(unbound);

                let mut buf = plaintext.to_vec();
                aead_key
                    .seal_in_place_append_tag(
                        Nonce::assume_unique_for_key(nonce_bytes),
                        Aad::from(&aad),
                        &mut buf,
                    )
                    .map_err(|_| SecurityViolation::AeadAuthenticationFailed { seq })?;

                self.pending_spool.push(SealedCaptureEnvelope {
                    lock_epoch: *lock_epoch,
                    seq,
                    capture_id: capture_id.to_string(),
                    nonce: nonce_bytes,
                    ciphertext_and_tag: buf,
                });
                Ok(())
            }
        }
    }

    /// Unlocks the vault and flushes all pending encrypted captures in exact
    /// FIFO order.
    pub fn unlock_and_flush(&mut self, master_key: [u8; 32]) -> Result<usize, SecurityViolation> {
        self.unlock_and_flush_internal(master_key, None)
    }

    /// Deterministic fault-injection entrypoint: commits up to `crash_after`
    /// decrypted spool records to `committed` WITHOUT pruning the remaining
    /// spool or transitioning out of `Locked`, simulating a power loss mid-flush
    /// before spool truncation completes.
    pub fn unlock_and_flush_with_crash_after(
        &mut self,
        master_key: [u8; 32],
        crash_after: usize,
    ) -> Result<usize, SecurityViolation> {
        self.unlock_and_flush_internal(master_key, Some(crash_after))
    }

    fn unlock_and_flush_internal(
        &mut self,
        mut master_key: [u8; 32],
        crash_after: Option<usize>,
    ) -> Result<usize, SecurityViolation> {
        let verify_key = hmac::Key::new(hmac::HMAC_SHA256, &master_key);
        if hmac::verify(
            &verify_key,
            b"chronicle-vault-key-verifier-v1",
            &self.key_verifier_tag,
        )
        .is_err()
        {
            volatile_zeroize(&mut master_key);
            return Err(SecurityViolation::InvalidMasterKey);
        }

        // First authenticate & decrypt the entire pending spool in-memory in
        // strict `(lock_epoch, seq)` order so tampered spool records fail closed
        // BEFORE mutating committed vault entries.
        let mut decrypted_batch: Vec<(String, Vec<u8>)> =
            Vec::with_capacity(self.pending_spool.len());
        let mut cur_epoch: Option<u64> = None;
        let mut expected_seq: u64 = 0;
        let mut chain_key = [0u8; 32];

        for env in &self.pending_spool {
            match cur_epoch {
                None => {
                    cur_epoch = Some(env.lock_epoch);
                    expected_seq = 0;
                    chain_key = Self::derive_epoch_seed(&master_key, env.lock_epoch);
                }
                Some(ep) if env.lock_epoch > ep => {
                    cur_epoch = Some(env.lock_epoch);
                    expected_seq = 0;
                    volatile_zeroize(&mut chain_key);
                    chain_key = Self::derive_epoch_seed(&master_key, env.lock_epoch);
                }
                Some(ep) if env.lock_epoch < ep => {
                    volatile_zeroize(&mut chain_key);
                    volatile_zeroize(&mut master_key);
                    return Err(SecurityViolation::SpoolEpochTampered {
                        expected_epoch: ep,
                        got_epoch: env.lock_epoch,
                    });
                }
                Some(_) => {}
            }

            if env.seq != expected_seq {
                volatile_zeroize(&mut chain_key);
                volatile_zeroize(&mut master_key);
                return Err(SecurityViolation::SpoolSequenceTampered {
                    expected_seq,
                    got_seq: env.seq,
                });
            }

            let (mut dek, next_chain) = Self::ratchet_step(&chain_key);
            volatile_zeroize(&mut chain_key);
            chain_key = next_chain;
            expected_seq = expected_seq.saturating_add(1);

            let aad = Self::build_aad(env.lock_epoch, env.seq, &env.capture_id);
            let unbound = UnboundKey::new(&ring::aead::CHACHA20_POLY1305, &dek).map_err(|_| {
                volatile_zeroize(&mut master_key);
                SecurityViolation::AeadAuthenticationFailed { seq: env.seq }
            })?;
            volatile_zeroize(&mut dek);
            let aead_key = LessSafeKey::new(unbound);

            let mut buf = env.ciphertext_and_tag.clone();
            let plain_slice = aead_key
                .open_in_place(
                    Nonce::assume_unique_for_key(env.nonce),
                    Aad::from(&aad),
                    &mut buf,
                )
                .map_err(|_| {
                    volatile_zeroize(&mut chain_key);
                    volatile_zeroize(&mut master_key);
                    SecurityViolation::AeadAuthenticationFailed { seq: env.seq }
                })?;
            decrypted_batch.push((env.capture_id.clone(), plain_slice.to_vec()));
        }
        volatile_zeroize(&mut chain_key);

        let mut newly_committed = 0usize;
        for (idx, (cap_id, plain)) in decrypted_batch.into_iter().enumerate() {
            if let Some(limit) = crash_after {
                if idx >= limit {
                    volatile_zeroize(&mut master_key);
                    return Err(SecurityViolation::SimulatedMidFlushCrash {
                        flushed_count: newly_committed,
                    });
                }
            }
            if self.committed_ids.insert(cap_id.clone()) {
                self.committed.push(CommittedCapture {
                    capture_id: cap_id,
                    plaintext: plain,
                });
                newly_committed += 1;
            }
        }

        self.pending_spool.clear();
        self.state = RuntimeVaultState::Unlocked { master_key };
        Ok(newly_committed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::serve::path_requires_lan_auth;

    #[test]
    fn pin_rotation_revokes_all_prior_tokens_including_aba_rollback_and_disproof() {
        let secret = [0x42u8; 32];
        let fp1 = "sha256/pinFingerprintOneAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        let fp2 = "sha256/pinFingerprintTwoBBBBBBBBBBBBBBBBBBBBBBBBBB=";

        // Disproof of static unbound bearer token: if a server merely compares
        // `got == static_token`, rotating TLS cert `fp1 -> fp2` leaves stolen/old
        // tokens valid across certificate rotation.
        let buggy_unbound_accepts_after_rotation =
            |issued: &str, active_token: &str| -> bool { issued == active_token };
        assert!(
            buggy_unbound_accepts_after_rotation("static-tok", "static-tok"),
            "disproof witness: static token model fails to revoke across cert rotation"
        );

        let mut auth = PinBoundSessionAuthority::new(&secret, fp1).expect("init authority");
        let tok_phone_a = auth.issue_token();
        let tok_phone_b = auth.issue_token();
        assert!(auth.verify_token(&tok_phone_a, fp1).is_ok());
        assert!(auth.verify_token(&tok_phone_b, fp1).is_ok());

        // Presenting tok_phone_a over a connection with mismatched cert pin fails closed.
        assert_eq!(
            auth.verify_token(&tok_phone_a, fp2),
            Err(SecurityViolation::EpochOrPinRevoked)
        );

        // Single-device revocation revokes Phone A while keeping Phone B live.
        auth.revoke_token(&tok_phone_a).expect("revoke phone A");
        assert_eq!(
            auth.verify_token(&tok_phone_a, fp1),
            Err(SecurityViolation::TokenExplicitlyRevoked)
        );
        assert!(auth.verify_token(&tok_phone_b, fp1).is_ok());

        // Certificate / LAN IP rotation `fp1 -> fp2` atomically invalidates Phone B too!
        let ep2 = auth.rotate_tls_pin(fp2).expect("rotate to fp2");
        assert_eq!(ep2, 2);
        assert_eq!(
            auth.verify_token(&tok_phone_b, fp2),
            Err(SecurityViolation::EpochOrPinRevoked)
        );
        assert_eq!(
            auth.verify_token(&tok_phone_b, fp1),
            Err(SecurityViolation::EpochOrPinRevoked)
        );

        // ABA rollback test: even if the server later rotates back to `fp1` (epoch 3),
        // epoch-1 `tok_phone_b` MUST STILL fail closed!
        let ep3 = auth.rotate_tls_pin(fp1).expect("rotate back to fp1");
        assert_eq!(ep3, 3);
        assert_eq!(
            auth.verify_token(&tok_phone_b, fp1),
            Err(SecurityViolation::EpochOrPinRevoked)
        );

        // Fresh epoch-3 tokens succeed, while 1-char canonical Base64 MAC forgery fails closed.
        // Note: 32-byte URL_SAFE_NO_PAD Base64 has 43 chars where the 43rd char carries
        // 2 zero pad bits (`'A'` = 0 = 0b0000_00, `'E'` = 4 = 0b0001_00); flipping between
        // `'A'` and `'E'` preserves canonical Base64 padding across all 2^128 nonces while
        // deterministically mutating the decoded 32-byte HMAC tag.
        for _ in 0..64 {
            let tok_ep3 = auth.issue_token();
            assert!(auth.verify_token(&tok_ep3, fp1).is_ok());
            let mut forged = tok_ep3.clone();
            let last = forged.pop().expect("non-empty");
            forged.push(if last == 'A' { 'E' } else { 'A' });
            assert_eq!(
                auth.verify_token(&forged, fp1),
                Err(SecurityViolation::InvalidHmacSignature)
            );
        }
    }

    #[test]
    fn lan_auth_matcher_default_deny_exhaustive_property() {
        // 1. Every mutating HTTP verb MUST require LAN auth across ALL API root
        // segments, SPA shell routes, and subpaths (in `serve.rs`, Rules 1 & 2
        // exempt `AUTH_EXEMPT_EXACT` and `/assets/*`, and Rule 3 requires LAN
        // auth on all remaining non-GET/HEAD requests).
        for seg in crate::serve::API_ROOT_SEGMENTS {
            for suffix in [
                "",
                "/",
                "/notes",
                "/journal",
                "/kb",
                "/notes/deep.md",
                "/notesX",
                "/rebuild-index",
                "/item-1",
                "/sub/deep",
            ] {
                let path = format!("/{seg}{suffix}");
                for method in ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"] {
                    // `GET`/`HEAD` on `/vault`, `/vault/`, and proper SPA shell subtrees
                    // `/vault/{notes,journal,kb}(/.*)?` serve the SPA HTML shell (Rules 4-6
                    // in `serve.rs`), whereas sibling lookalikes (`/vault/notesX`), API
                    // subroutes (`/vault/rebuild-index`, `/vault/item-1`), and ALL mutating
                    // verbs require LAN auth.
                    let is_vault_spa_shell = seg == "vault"
                        && matches!(method, "GET" | "HEAD")
                        && (matches!(suffix, "" | "/" | "/notes" | "/journal" | "/kb")
                            || suffix.starts_with("/notes/")
                            || suffix.starts_with("/journal/")
                            || suffix.starts_with("/kb/"));
                    let expected = !is_vault_spa_shell;
                    assert_eq!(
                        path_requires_lan_auth(&path, method),
                        expected,
                        "LAN auth specification mismatch on {method} {path}"
                    );
                }
            }
        }

        // 2. API subroutes and non-shell `/vault/*` subpaths MUST require LAN auth on GET/HEAD,
        // while `AUTH_EXEMPT_EXACT` / `AUTH_EXEMPT_PREFIXES` sibling lookalikes (`/connected-devices`,
        // `/connect/extra`, `/healthz`, `/assetsX`, `/legacy/sub`) MUST NEVER inherit auth
        // exemptions on mutating verbs (the legacy `startswith` prefix bug documented in `serve.rs`).
        for api_get in [
            "/vault/secret",
            "/vault/entries",
            "/entries",
            "/auth/status",
            "/events/stream",
            "/docs",
            "/openapi.json",
        ] {
            for method in ["GET", "HEAD"] {
                assert!(
                    path_requires_lan_auth(api_get, method),
                    "API {method} {api_get} must require LAN auth"
                );
            }
        }
        for hostile_mut in [
            "/connected-devices",
            "/connect/extra",
            "/healthz",
            "/legacy/sub",
            "/assetsX",
            "/settings",
            "/vault",
            "/vault/notes",
            "/unknown-route",
        ] {
            for method in ["POST", "PUT", "PATCH", "DELETE"] {
                assert!(
                    path_requires_lan_auth(hostile_mut, method),
                    "mutating {method} {hostile_mut} must require LAN auth"
                );
            }
        }
    }

    #[test]
    fn locked_vault_forward_secret_spool_confidentiality_tamper_and_crash_atomic_flush() {
        let master_key = [0x7Au8; 32];
        let wrong_key = [0x99u8; 32];

        let mut vault = LockedVaultSpool::new_unlocked(master_key);
        vault
            .accept_capture("cap-0", b"unlocked-entry-0")
            .expect("unlocked capture");
        assert_eq!(vault.committed_entries().len(), 1);

        // Lock vault: master key must leave RAM immediately.
        let ep1 = vault.lock_vault().expect("lock");
        assert_eq!(ep1, 1);
        assert!(vault.is_locked());
        assert!(!vault.master_key_resident_in_ram());

        let chain_before_c1 = vault
            .current_ratchet_key_snapshot()
            .expect("locked ratchet key");
        vault
            .accept_capture("cap-1", b"TOP_SECRET_THOUGHT_ALPHA")
            .expect("spool cap-1");
        let chain_after_c1 = vault
            .current_ratchet_key_snapshot()
            .expect("ratchet key advanced");
        assert_ne!(
            chain_before_c1, chain_after_c1,
            "ratchet chain key must advance after every spooled capture"
        );
        vault
            .accept_capture("cap-2", b"TOP_SECRET_THOUGHT_BETA")
            .expect("spool cap-2");

        // Confidentiality invariant while Locked:
        // 1. Committed entries did NOT grow while locked.
        assert_eq!(vault.committed_entries().len(), 1);
        // 2. Neither ciphertext contains raw plaintext substring.
        assert_eq!(vault.pending_envelopes().len(), 2);
        for env in vault.pending_envelopes() {
            assert!(
                !env.ciphertext_and_tag
                    .windows(b"TOP_SECRET".len())
                    .any(|w| w == b"TOP_SECRET"),
                "spooled envelope leaked plaintext!"
            );
        }
        // 3. Forward-secrecy proof: using `chain_after_c1` (post-cap-1 RAM snapshot)
        // CANNOT open `cap-1` (envelope 0)!
        {
            let (post_compromise_dek, _) = LockedVaultSpool::ratchet_step(&chain_after_c1);
            let env0 = &vault.pending_envelopes()[0];
            let aad0 = LockedVaultSpool::build_aad(env0.lock_epoch, env0.seq, &env0.capture_id);
            let unbound =
                UnboundKey::new(&ring::aead::CHACHA20_POLY1305, &post_compromise_dek).expect("key");
            let aead = LessSafeKey::new(unbound);
            let mut ct = env0.ciphertext_and_tag.clone();
            assert!(
                aead.open_in_place(
                    Nonce::assume_unique_for_key(env0.nonce),
                    Aad::from(&aad0),
                    &mut ct
                )
                .is_err(),
                "forward secrecy violated: post-ratchet RAM key decrypted an earlier spooled capture!"
            );
        }

        // Wrong master key fails closed without dropping pending spool!
        assert_eq!(
            vault.unlock_and_flush(wrong_key),
            Err(SecurityViolation::InvalidMasterKey)
        );
        assert!(vault.is_locked());
        assert_eq!(vault.pending_envelopes().len(), 2);

        // Disproofs on spool tampering (bit flip, seq swap, capture_id AAD swap):
        {
            // Bit flip in ciphertext
            vault.pending_envelopes_mut_for_test()[1].ciphertext_and_tag[0] ^= 0x01;
            assert_eq!(
                vault.unlock_and_flush(master_key),
                Err(SecurityViolation::AeadAuthenticationFailed { seq: 1 })
            );
            // Zero partial commits occurred because authentication runs before commit!
            assert_eq!(vault.committed_entries().len(), 1);
            vault.pending_envelopes_mut_for_test()[1].ciphertext_and_tag[0] ^= 0x01;

            // Swapping envelope order violates monotone `seq` check
            vault.pending_envelopes_mut_for_test().swap(0, 1);
            assert_eq!(
                vault.unlock_and_flush(master_key),
                Err(SecurityViolation::SpoolSequenceTampered {
                    expected_seq: 0,
                    got_seq: 1
                })
            );
            vault.pending_envelopes_mut_for_test().swap(0, 1);
        }

        // Crash mid-flush after committing 1 entry (`cap-1`), before spool prune:
        assert_eq!(
            vault.unlock_and_flush_with_crash_after(master_key, 1),
            Err(SecurityViolation::SimulatedMidFlushCrash { flushed_count: 1 })
        );
        assert!(vault.is_locked(), "crashed flush leaves vault locked");
        assert_eq!(vault.committed_entries().len(), 2); // cap-0 + cap-1

        // Retry `unlock_and_flush` after restart: `cap-1` MUST NOT duplicate, and `cap-2` flushes cleanly in FIFO order!
        let newly_flushed = vault.unlock_and_flush(master_key).expect("recovery flush");
        assert_eq!(newly_flushed, 1);
        assert!(!vault.is_locked());
        assert!(vault.pending_envelopes().is_empty());
        let ids: Vec<&str> = vault
            .committed_entries()
            .iter()
            .map(|c| c.capture_id.as_str())
            .collect();
        assert_eq!(ids, vec!["cap-0", "cap-1", "cap-2"]);
    }

    #[test]
    fn exhaustive_lock_capture_crash_unlock_schedule_explorer() {
        // Explore all 4^5 = 1,024 5-step schedules of:
        // 0 = AcceptCapture, 1 = LockVault, 2 = MidFlushCrash(1), 3 = UnlockAndFlush
        // verifying on EVERY schedule:
        // - While Locked, `committed_entries` never grows except during explicit crash-commit prefix,
        //   and `master_key_resident_in_ram() == false`;
        // - After final UnlockAndFlush, committed entries equal the exact FIFO sequence
        //   `cap-0 .. cap-(k-1)` with ZERO lost captures and ZERO duplicates!
        let key = [0x55u8; 32];
        let mut schedules = 0usize;
        let mut crash_recoveries = 0usize;

        for a0 in 0..4 {
            for a1 in 0..4 {
                for a2 in 0..4 {
                    for a3 in 0..4 {
                        for a4 in 0..4 {
                            let mut vault = LockedVaultSpool::new_unlocked(key);
                            let mut next_cap = 0usize;

                            for step in [a0, a1, a2, a3, a4] {
                                match step {
                                    0 => {
                                        let id = format!("cap-{next_cap}");
                                        let body = format!("payload-{next_cap}");
                                        let before_committed = vault.committed_entries().len();
                                        let was_locked = vault.is_locked();
                                        vault
                                            .accept_capture(&id, body.as_bytes())
                                            .expect("accept capture");
                                        if was_locked {
                                            assert_eq!(
                                                vault.committed_entries().len(),
                                                before_committed,
                                                "locked capture must never write plaintext to committed store"
                                            );
                                            assert!(!vault.master_key_resident_in_ram());
                                        }
                                        next_cap += 1;
                                    }
                                    1 => {
                                        let _ = vault.lock_vault();
                                        assert!(vault.is_locked());
                                        assert!(!vault.master_key_resident_in_ram());
                                    }
                                    2 => {
                                        if vault.is_locked()
                                            && !vault.pending_envelopes().is_empty()
                                            && vault
                                                .unlock_and_flush_with_crash_after(key, 1)
                                                .is_err()
                                        {
                                            crash_recoveries += 1;
                                        }
                                    }
                                    _ => {
                                        let _ = vault.unlock_and_flush(key).expect("unlock");
                                        assert!(!vault.is_locked());
                                        assert!(vault.pending_envelopes().is_empty());
                                    }
                                }
                            }

                            // Finalize with clean unlock and assert exact-once FIFO completeness!
                            vault.unlock_and_flush(key).expect("final unlock");
                            assert_eq!(vault.committed_entries().len(), next_cap);
                            for idx in 0..next_cap {
                                assert_eq!(
                                    vault.committed_entries()[idx].capture_id,
                                    format!("cap-{idx}")
                                );
                                assert_eq!(
                                    vault.committed_entries()[idx].plaintext,
                                    format!("payload-{idx}").into_bytes()
                                );
                            }
                            schedules += 1;
                        }
                    }
                }
            }
        }
        assert_eq!(schedules, 1024);
        assert!(
            crash_recoveries > 0,
            "non-vacuity witness: crash recovery paths must be exercised"
        );
    }

    #[tokio::test]
    async fn lan_router_middleware_enforces_pin_bound_rotation_and_device_revocation() {
        use axum::body::Body;
        use axum::http::{Request, StatusCode};
        use std::sync::Arc;
        use tower::ServiceExt;

        let vault = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            vault.path().join("config.json"),
            r#"{"version":1,"layout_version":2,"timezone":"UTC"}"#,
        )
        .expect("write config");
        std::fs::create_dir_all(vault.path().join("_capture/entries")).expect("mkdir entries");

        let fp1 = "sha256/pinFingerprintOneAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        let fp2 = "sha256/pinFingerprintTwoBBBBBBBBBBBBBBBBBBBBBBBBBB=";
        let secret = [0x5Au8; 32];
        let auth = PinBoundSessionAuthority::new(&secret, fp1).expect("init authority");
        let tok_phone_a = auth.issue_token();
        let tok_phone_b = auth.issue_token();

        let connect_info = crate::ConnectInfo {
            host: "192.168.1.20".into(),
            port: 8765,
            bind_host: "0.0.0.0".into(),
            lan_ip: Some("192.168.1.20".into()),
            base: "https://192.168.1.20:8765".into(),
            kb_proxied: false,
            version: 2,
            token: Some(tok_phone_a.clone()),
            auth_required: true,
            tls: true,
            tls_fp: Some(fp1.into()),
        };
        let state = Arc::new(crate::App::new_with_session_authority(
            vault.path().to_path_buf(),
            connect_info,
            Some(auth),
        ));
        let router = crate::serve::build_router_with_layers(state.clone());

        async fn send_entries_get(r: axum::Router, tok: Option<&str>) -> StatusCode {
            let mut b = Request::builder()
                .method("GET")
                .uri("/entries")
                .header("host", "192.168.1.20:8765");
            if let Some(t) = tok {
                b = b.header(crate::serve::TOKEN_HEADER, t);
            }
            r.oneshot(b.body(Body::empty()).expect("body"))
                .await
                .expect("response")
                .status()
        }

        // 1. Missing token fails closed with 401.
        assert_eq!(
            send_entries_get(router.clone(), None).await,
            StatusCode::UNAUTHORIZED
        );

        // 2. Both epoch-1 pin-bound device tokens succeed over the live Axum middleware.
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_a)).await,
            StatusCode::OK
        );
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_b)).await,
            StatusCode::OK
        );

        // 3. Single-device revocation revokes Phone A immediately while keeping Phone B live.
        state
            .revoke_lan_token(&tok_phone_a)
            .expect("revoke phone A");
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_a)).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_b)).await,
            StatusCode::OK
        );

        // 4. Live TLS pin rotation (fp1 -> fp2) immediately revokes Phone B on the HTTP surface.
        let (ep2, tok_ep2) = state.rotate_lan_tls_pin(fp2).expect("rotate to fp2");
        assert_eq!(ep2, 2);
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_b)).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_ep2)).await,
            StatusCode::OK
        );

        // 5. ABA rollback (fp2 -> fp1, epoch 3) keeps epoch-1 Phone B and epoch-2 tokens revoked!
        let (ep3, tok_ep3) = state.rotate_lan_tls_pin(fp1).expect("rotate back to fp1");
        assert_eq!(ep3, 3);
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_phone_b)).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            send_entries_get(router.clone(), Some(&tok_ep2)).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            send_entries_get(router, Some(&tok_ep3)).await,
            StatusCode::OK
        );
    }
}
