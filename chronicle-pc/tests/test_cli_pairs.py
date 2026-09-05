"""Test CLI pair, pairs, and unpair commands."""

from __future__ import annotations

import json
from pathlib import Path
import pytest

from chronicle_pipeline import cli


def test_cli_pairs_roundtrip(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    pairing_file = tmp_path / "pairing.json"
    monkeypatch.setenv("CHRONICLE_PAIRING_FILE", str(pairing_file))

    # 1. Initially empty
    rc = cli.main(["pairs"])
    assert rc == 0
    out = capsys.readouterr().out
    data = json.loads(out)
    assert data == {"devices": []}

    # 2. Pair a device
    rc = cli.main(["pair", "pixel-9", "--show-token"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "https://<mac-lan-ip>:8765" in out

    # 3. List devices
    rc = cli.main(["pairs"])
    assert rc == 0
    out = capsys.readouterr().out
    data = json.loads(out)
    assert any(d["name"] == "pixel-9" for d in data["devices"])


    # 4. Unpair device
    rc = cli.main(["unpair", "pixel-9"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "Revoked device 'pixel-9'" in out

    # 5. List devices again
    rc = cli.main(["pairs"])
    assert rc == 0
    out = capsys.readouterr().out
    data = json.loads(out)
    assert data["devices"] == []
