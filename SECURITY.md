# Security

Chronicle is local-first: no accounts, no product telemetry, no cloud calls
unless you explicitly enable a BYOK model. The vault is plain files on disk.

## Reporting

Open a private security advisory on the repository rather than a public issue.

## What is protected, and what is not

**LAN transport.** When `serve` binds anything other than loopback it
terminates TLS with a self-signed certificate generated once into
`~/.config/chronicle/tls/` (0700 dir, 0600 key). The pairing QR is a v2 payload
carrying `tls_fp` — the base64 SHA-256 of the certificate's SubjectPublicKeyInfo
— which the phone pins directly, so a LAN attacker cannot impersonate the Mac
even with a valid CA-issued certificate. If TLS material cannot be prepared,
LAN serving **fails** rather than falling back to cleartext; use `--no-lan`.

Loopback is served as plain HTTP on a separately bound socket. It never leaves
the machine, and the desktop WebView has no way to be told to trust a
self-signed certificate. Every route — not just `/connect` — checks the `Host`
header against an allowlist, so a DNS-rebinding page cannot reach the vault
same-origin. Set `CHRONICLE_EXTRA_HOSTS` (comma-separated) if you front the
server with a custom DNS name or reverse proxy.

**The pairing token is a full vault credential.** It is bound to one endpoint
identity: change the address or the certificate pin and the phone drops the
token and asks you to re-pair by QR. mDNS discovery is unauthenticated, so it
is treated as an address suggestion only and never carries a credential.

**Cloud models are opt-in and split.** `llm.cloud_consent` covers text.
Uploading photos additionally requires `llm.vision_cloud_consent`; without it
image description falls back to local Ollama rather than sending the bytes.

**E2EE is field-level and opt-in.** It seals entry `text` for unfiled captures.
Filed `40-Journal` prose is intentionally plaintext for Obsidian compatibility,
and attachments are not encrypted. Two limits worth knowing:

- The **native Rust server has no E2EE support**. Rather than writing plaintext
  beside your ciphertext, it refuses to serve or write to a vault with
  `e2ee.enabled=true`. Use the Python `chronicle serve` for encrypted vaults.
- A capture taken on the phone while the vault is locked is **not** written to
  the vault in the clear. It is parked encrypted outside the vault (Android
  Keystore) and filed on the next unlock.

**The vault itself is plain files.** Anything with read access to the folder —
including every Syncthing peer you have paired — can read it. E2EE narrows that
for unfiled capture text only; it is not whole-vault encryption.

## Dependency advisories

Dependabot watches `package-lock.json` and `Cargo.lock`. Patch advisories by
updating the lock file; anything that cannot be patched is recorded below with
the reason, and each exception is enforced by a test so it cannot quietly
outlive its justification.

### Accepted risk: GHSA-wrw7-89jp-8q8g — `glib` unsoundness

| | |
| --- | --- |
| Advisory | Unsoundness in `Iterator`/`DoubleEndedIterator` for `glib::VariantStrIter` |
| Severity | Moderate |
| Affected | `glib >= 0.15.0, < 0.20.0` — locked at 0.18.5 |
| Manifest | `chronicle-pc/desktop/src-tauri/Cargo.lock` |
| Status | Accepted; not reachable in shipped builds |

**Why it is not patched.** `glib` is not a direct dependency. It arrives only
through Tauri's Linux backend:

```
glib 0.18.5 <- atk <- gtk 0.18.2 <- libappindicator <- tray-icon <- tauri 2.11.5
```

`gtk 0.18.2` pins `glib ^0.18`, and `tauri 2.11.5` — the latest published
release — still pins `gtk ^0.18`. `cargo update -p glib --precise 0.20.0`
fails to resolve. No version combination fixes this today; it requires Tauri
to move off gtk3-rs.

**Why it is not exploitable here.** Chronicle desktop ships macOS only
(`desktop/README.md`), where Tauri uses WKWebView and the gtk stack is never
compiled — `cargo tree --target aarch64-apple-darwin -i glib` returns nothing.
`bundle.targets` is pinned to `["app", "dmg"]` so no Linux artifact is
produced. Before this was pinned, `targets` was `"all"`, which did permit a
vulnerable Linux build; narrowing it is what makes this exception true rather
than merely likely.

**How the exception is enforced.** `chronicle-pc/tests/test_desktop_bundle_targets.py`
fails if a Linux bundle target is re-enabled while `glib` is still below
0.20.0, and fails once `glib` reaches 0.20.0 so the exception and this section
get removed. The guard retires itself; it does not depend on anyone
remembering why it exists.

**Review trigger.** Revisit when Tauri releases a version built on gtk-rs 0.20
or later, or if Linux support is ever added — whichever comes first. The test
will fail in either case.
