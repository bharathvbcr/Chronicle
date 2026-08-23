//! Vault-wide exclusive process lock (flock on index/process.lock),
//! re-entrant per thread, with optional bounded wait (python blocks forever;
//! the API layer uses bounded/try waits to avoid wedging the worker pool).

use std::cell::RefCell;
use std::fs::{File, OpenOptions};
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use crate::errors::ChronicleError;

thread_local! {
    static DEPTH: RefCell<u32> = const { RefCell::new(0) };
    static LOCK_FILE: RefCell<Option<File>> = const { RefCell::new(None) };
}

pub struct ProcessGuard {
    _priv: (),
}

impl Drop for ProcessGuard {
    fn drop(&mut self) {
        DEPTH.with(|d| {
            let mut depth = d.borrow_mut();
            *depth -= 1;
            if *depth == 0 {
                LOCK_FILE.with(|f| {
                    if let Some(file) = f.borrow_mut().take() {
                        unsafe {
                            libc::flock(file.as_raw_fd(), libc::LOCK_UN);
                        }
                        // File closes on drop.
                    }
                });
            }
        });
    }
}

#[derive(Debug, thiserror::Error)]
pub enum LockError {
    #[error("vault is busy: another process holds the lock")]
    Busy,
    #[error("timed out waiting for vault lock")]
    Timeout,
    #[error("lock io error: {0}")]
    Io(String),
}

impl From<LockError> for ChronicleError {
    fn from(e: LockError) -> Self {
        ChronicleError::msg(e.to_string())
    }
}

fn lock_path(root: &Path) -> PathBuf {
    root.join("index").join("process.lock")
}

/// Acquire the vault lock. `timeout == None` → block indefinitely (python
/// parity); `Some(Duration::ZERO)` → single try (single-flight); otherwise
/// poll LOCK_NB until the deadline.
pub fn vault_lock(root: &Path, timeout: Option<Duration>) -> Result<ProcessGuard, LockError> {
    if DEPTH.with(|d| *d.borrow() > 0) {
        DEPTH.with(|d| *d.borrow_mut() += 1);
        return Ok(ProcessGuard { _priv: () });
    }
    let path = lock_path(root);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| LockError::Io(e.to_string()))?;
    }
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .map_err(|e| LockError::Io(e.to_string()))?;

    let deadline = timeout.map(|t| Instant::now() + t);
    loop {
        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc == 0 {
            LOCK_FILE.with(|f| *f.borrow_mut() = Some(file));
            DEPTH.with(|d| *d.borrow_mut() += 1);
            return Ok(ProcessGuard { _priv: () });
        }
        let errno = std::io::Error::last_os_error();
        if errno.raw_os_error() != Some(libc::EWOULDBLOCK) {
            return Err(LockError::Io(errno.to_string()));
        }
        match deadline {
            None => std::thread::sleep(Duration::from_millis(50)),
            Some(dl) => {
                if Instant::now() >= dl {
                    return Err(if timeout == Some(Duration::ZERO) { LockError::Busy } else { LockError::Timeout });
                }
                std::thread::sleep(Duration::from_millis(25));
            }
        }
    }
}

/// Blocking forever — parity with python `with vault_process_lock(root)`.
pub fn vault_lock_blocking(root: &Path) -> Result<ProcessGuard, LockError> {
    vault_lock(root, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn try_lock_busy_then_reentrant() {
        let dir = tempfile::tempdir().unwrap();
        {
            let _g = vault_lock(dir.path(), Some(Duration::ZERO)).unwrap();
            // Re-entrant within the same thread never blocks (python depth counter).
            let _g2 = vault_lock(dir.path(), Some(Duration::ZERO)).unwrap();
        }
        {
            let holder = std::thread::spawn({
                let p = dir.path().to_path_buf();
                move || {
                    let _g = vault_lock(&p, None).unwrap();
                    std::thread::sleep(Duration::from_millis(300));
                }
            });
            std::thread::sleep(Duration::from_millis(60));
            assert!(matches!(vault_lock(dir.path(), Some(Duration::ZERO)), Err(LockError::Busy)));
            holder.join().unwrap();
        }
        // Released after guards drop.
        let _g3 = vault_lock(dir.path(), Some(Duration::ZERO)).unwrap();
    }

    #[test]
    fn cross_thread_contention_times_out() {
        let dir = tempfile::tempdir().unwrap();
        let holder = std::thread::spawn({
            let p = dir.path().to_path_buf();
            move || {
                let _g = vault_lock(&p, None).unwrap();
                std::thread::sleep(Duration::from_millis(400));
            }
        });
        std::thread::sleep(Duration::from_millis(80));
        let res = vault_lock(dir.path(), Some(Duration::from_millis(100)));
        assert!(matches!(res, Err(LockError::Timeout)));
        holder.join().unwrap();
        let res2 = vault_lock(dir.path(), Some(Duration::from_millis(500)));
        assert!(res2.is_ok());
    }
}
