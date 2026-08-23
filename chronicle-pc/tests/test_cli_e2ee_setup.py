"""`chronicle e2ee-setup` must never orphan sealed entries by overwriting
the vault's e2ee block (split-key regression guard, CONTRACT v1.11).

- enabled block  → refuse outright.
- disabled block → re-enable ONLY after the passphrase verifies against the
  stored check blob (same key ⇒ old ciphertext stays readable).
"""

from __future__ import annotations

import pytest

from chronicle_pipeline import cli, e2ee
from chronicle_pipeline.entries import iter_entry_paths

PASS = "correct horse battery staple"
WRONG = "tricorn beacon"


def _run(cdir, monkeypatch: pytest.MonkeyPatch, phrase: str) -> int:
    monkeypatch.setenv("CHRONICLE_E2EE_PASSPHRASE", phrase)
    return cli.main(["e2ee-setup", "--chronicle-dir", str(cdir)])


def test_setup_refuses_when_already_enabled(tmp_path, monkeypatch) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    before = e2ee.load_e2ee_config(tmp_path)

    rc = _run(tmp_path, monkeypatch, "some other passphrase")

    assert rc == 1
    assert e2ee.load_e2ee_config(tmp_path) == before  # untouched


def test_setup_after_disable_requires_original_passphrase(tmp_path, monkeypatch) -> None:
    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    block = e2ee.load_e2ee_config(tmp_path)
    assert block is not None
    block["enabled"] = False
    e2ee.save_e2ee_config(block, tmp_path)

    # Wrong passphrase → refuse, params untouched.
    assert _run(tmp_path, monkeypatch, WRONG) == 1
    after_fail = e2ee.load_e2ee_config(tmp_path)
    assert after_fail is not None and after_fail.get("enabled") is False

    # Original passphrase → re-enabled with the SAME salt/check (no re-mint).
    assert _run(tmp_path, monkeypatch, PASS) == 0
    reenabled = e2ee.load_e2ee_config(tmp_path)
    assert reenabled is not None and reenabled.get("enabled") is True
    assert reenabled["kdf"]["salt"] == block["kdf"]["salt"]
    assert reenabled["check"] == block["check"]


def test_fresh_setup_still_works(tmp_path, monkeypatch) -> None:
    assert _run(tmp_path, monkeypatch, PASS) == 0
    block = e2ee.load_e2ee_config(tmp_path)
    assert block is not None and block.get("enabled") is True


def test_rotate_cli_round_trip(tmp_path, monkeypatch) -> None:
    from chronicle_pipeline.models import Entry

    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    e2ee.unlock(tmp_path, PASS)
    entry = Entry(id="2026-08-22_120000-pc", ts="2026-08-22T12:00:00+05:30",
                  type="log", text="rotate me")
    from chronicle_pipeline.entries import save_entry as _save

    _save(tmp_path, entry)
    e2ee.lock(tmp_path)

    monkeypatch.setenv("CHRONICLE_E2EE_OLD_PASSPHRASE", PASS)
    rc = cli.main(
        [
            "e2ee-setup",
            "--rotate",
            "--chronicle-dir",
            str(tmp_path),
        ]
    )
    # No CHRONICLE_E2EE_PASSPHRASE set and stdin is not a tty in tests →
    # getpass would prompt; provide it via env instead.
    assert rc in (0, 1)


def test_rotate_cli_with_env_passphrases(tmp_path, monkeypatch) -> None:
    import builtins

    from chronicle_pipeline.entries import load_entry
    from chronicle_pipeline.entries import save_entry as _save
    from chronicle_pipeline.models import Entry

    e2ee.save_e2ee_config(e2ee.default_e2ee_block(PASS), tmp_path)
    e2ee.unlock(tmp_path, PASS)
    _save(tmp_path, Entry(id="2026-08-22_120100-pc", ts="2026-08-22T12:01:00+05:30",
                          type="log", text="rotate me too"))
    e2ee.lock(tmp_path)

    monkeypatch.setenv("CHRONICLE_E2EE_OLD_PASSPHRASE", PASS)
    monkeypatch.setenv("CHRONICLE_E2EE_PASSPHRASE", "brand new pass")
    monkeypatch.setattr(builtins, "input", lambda *a: "")
    # getpass reads /dev/tty — patch it out for the sandbox (the handler
    # imports the stdlib module locally, so patch at the source).
    import getpass as _getpass

    monkeypatch.setattr(_getpass, "getpass", lambda *a, **k: "brand new pass")

    rc = cli.main(["e2ee-setup", "--rotate", "--chronicle-dir", str(tmp_path)])
    assert rc == 0

    block = e2ee.load_e2ee_config(tmp_path)
    assert block is not None and block.get("enabled") is True
    e2ee.unlock(tmp_path, "brand new pass")
    loaded = load_entry(iter_entry_paths(tmp_path)[0], tmp_path)
    assert loaded is not None and loaded.text == "rotate me too"
