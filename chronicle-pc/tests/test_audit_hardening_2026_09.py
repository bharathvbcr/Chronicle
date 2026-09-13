"""Hardening regression tests from the 2026-09 audit.

Each test targets a specific finding and fails against the pre-fix code:
- legacy-import media references must not write outside the vault;
- a Vertex `location` must not be able to redirect an ADC bearer token;
- an unconsented cloud provider must never receive a live credential.

These attack the system the way a hostile imported bundle or a synced
config.json written by a compromised peer would.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from chronicle_pipeline.import_legacy import convert_legacy_entry, run_import_legacy
from chronicle_pipeline.llm.protocol import LlmError
from chronicle_pipeline.llm.vertex_provider import VertexProvider, validate_location

# --------------------------------------------------------------------------
# Finding 6 — legacy import wrote attacker-chosen paths outside the vault
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "hostile",
    [
        "img/../../../pwned.txt",
        "img/../../pwned.txt",
        "img/2026/07/../../../../pwned.jpg",
    ],
)
def test_convert_drops_traversing_image_reference(hostile: str) -> None:
    raw = {"id": "2026-06-01_0915", "ts": "2026-06-01T09:15:00+05:30", "images": [hostile]}
    entry = convert_legacy_entry(raw)
    assert entry.images == [], f"traversing reference survived conversion: {hostile}"


def test_convert_drops_traversing_audio_reference() -> None:
    raw = {
        "id": "2026-06-01_0915",
        "ts": "2026-06-01T09:15:00+05:30",
        "audio": ["audio/../../../pwned.m4a"],
    }
    assert convert_legacy_entry(raw).audio == []


def test_convert_keeps_legitimate_media_references() -> None:
    raw = {
        "id": "2026-06-01_0915",
        "ts": "2026-06-01T09:15:00+05:30",
        "images": ["img/2026/06/photo.jpg"],
        "audio": ["audio/2026/06/note.m4a"],
    }
    entry = convert_legacy_entry(raw)
    assert entry.images == ["img/2026/06/photo.jpg"]
    assert entry.audio == ["audio/2026/06/note.m4a"]


def test_import_legacy_cannot_write_outside_vault(tmp_path: Path) -> None:
    """End-to-end: a crafted bundle must not create a file beside the vault."""
    vault = tmp_path / "Chronicle"
    (vault / "_capture" / "entries").mkdir(parents=True)
    (vault / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}), encoding="utf-8"
    )

    legacy = tmp_path / "legacy"
    (legacy / "entries").mkdir(parents=True)
    (legacy / "img").mkdir(parents=True)
    # The source the copier would find, by basename.
    (legacy / "img" / "pwned.txt").write_text("owned", encoding="utf-8")
    (legacy / "entries" / "2026-06-01_0915.json").write_text(
        json.dumps(
            {
                "id": "2026-06-01_0915",
                "ts": "2026-06-01T09:15:00+00:00",
                "type": "log",
                "text": "hello",
                "images": ["img/../../pwned.txt"],
            }
        ),
        encoding="utf-8",
    )

    sentinel = tmp_path / "pwned.txt"
    assert not sentinel.exists()

    run_import_legacy(legacy, vault, dry_run=False)

    assert not sentinel.exists(), "legacy import wrote outside the vault root"
    # The entry itself still imports — we drop the reference, not the data.
    entries = list((vault / "_capture" / "entries").rglob("*.json"))
    assert entries, "entry should still be imported"
    assert json.loads(entries[0].read_text(encoding="utf-8"))["text"] == "hello"


def test_import_legacy_refuses_symlinked_parent(tmp_path: Path) -> None:
    """A symlinked media directory inside the vault must not become an exit."""
    vault = tmp_path / "Chronicle"
    (vault / "_capture" / "entries").mkdir(parents=True)
    (vault / "config.json").write_text(
        json.dumps({"version": 1, "layout_version": 2, "timezone": "UTC"}), encoding="utf-8"
    )
    outside = tmp_path / "outside"
    outside.mkdir()
    (vault / "img").symlink_to(outside, target_is_directory=True)

    legacy = tmp_path / "legacy"
    (legacy / "entries").mkdir(parents=True)
    (legacy / "img").mkdir(parents=True)
    (legacy / "img" / "shot.jpg").write_bytes(b"jpegbytes")
    (legacy / "entries" / "2026-06-01_0915.json").write_text(
        json.dumps(
            {
                "id": "2026-06-01_0915",
                "ts": "2026-06-01T09:15:00+00:00",
                "images": ["img/2026/06/shot.jpg"],
            }
        ),
        encoding="utf-8",
    )

    run_import_legacy(legacy, vault, dry_run=False)
    assert not (outside / "2026" / "06" / "shot.jpg").exists(), (
        "media copy followed a symlinked parent out of the vault"
    )


# --------------------------------------------------------------------------
# Finding 9 — Vertex location redirected an ADC bearer token
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "hostile",
    [
        "attacker.example/",           # terminates the authority early
        "attacker.example/x",
        "us-central1.evil.com",
        "us-central1@evil.com",
        "us-central1:8443",
        "../../etc",
        "us central1",
        "US-CENTRAL1",
        "",
    ],
)
def test_vertex_location_rejects_url_delimiters(hostile: str) -> None:
    with pytest.raises(LlmError):
        validate_location(hostile)


@pytest.mark.parametrize(
    "region", ["us-central1", "europe-west4", "asia-northeast1", "northamerica-northeast1", "global"]
)
def test_vertex_location_accepts_real_regions(region: str) -> None:
    assert validate_location(region) == region


def test_vertex_provider_refuses_hostile_location_before_any_token() -> None:
    """Construction must fail, so no credential is ever fetched or sent."""
    with pytest.raises(LlmError):
        VertexProvider(project="my-project", location="attacker.example/")


def test_vertex_provider_refuses_hostile_project_and_model() -> None:
    with pytest.raises(LlmError):
        VertexProvider(project="proj/../../etc", location="us-central1")
    with pytest.raises(LlmError):
        VertexProvider(project="p", location="us-central1", default_model="a/b")


def test_vertex_provider_builds_endpoint_on_googleapis_host() -> None:
    p = VertexProvider(project="my-project", location="us-central1")
    url = p._endpoint(p.default_model)
    assert url.startswith("https://us-central1-aiplatform.googleapis.com/v1/")


# --------------------------------------------------------------------------
# Finding 10 — symlinks escaped the vault root
# --------------------------------------------------------------------------


def _para_vault(tmp_path: Path) -> Path:
    """Canonical vault root — a real vault path has no symlinked ancestor."""
    root = (tmp_path / "Chronicle").resolve()
    (root / "10-Work" / "Projects").mkdir(parents=True)
    (root / "30-Knowledge").mkdir(parents=True)
    return root


def test_read_refuses_md_symlink_to_outside_file(tmp_path: Path) -> None:
    from chronicle_pipeline import path_map

    root = _para_vault(tmp_path)
    secret = tmp_path / "outside-secret.md"
    secret.write_text("TOP SECRET", encoding="utf-8")
    (root / "30-Knowledge" / "leak.md").symlink_to(secret)

    assert path_map.resolve_read_abs(root, "30-Knowledge/leak.md") is None
    assert path_map.resolve_read(root, "30-Knowledge/leak.md") is None


def test_write_refuses_existing_target_through_symlinked_dir(tmp_path: Path) -> None:
    from chronicle_pipeline import path_map

    root = _para_vault(tmp_path)
    outside = tmp_path / "outside-dir"
    outside.mkdir()
    (outside / "victim.md").write_text("original", encoding="utf-8")
    (root / "30-Knowledge" / "escape").symlink_to(outside, target_is_directory=True)

    dest = None
    try:
        dest = path_map.resolve_write(root, "30-Knowledge/escape/victim.md")
    except ValueError:
        pass  # refused outright is also correct
    if dest is not None:
        assert dest.resolve().is_relative_to(root), (
            f"write resolved outside the vault: {dest}"
        )
    assert (outside / "victim.md").read_text(encoding="utf-8") == "original"


def test_ordinary_para_paths_still_resolve(tmp_path: Path) -> None:
    from chronicle_pipeline import path_map

    root = _para_vault(tmp_path)
    note = root / "10-Work" / "Projects" / "Harbor.md"
    note.write_text("# Harbor\n", encoding="utf-8")

    assert path_map.resolve_read_abs(root, "10-Work/Projects/Harbor.md") == note
    assert path_map.resolve_write(root, "10-Work/Projects/Harbor.md") == note
    created = path_map.resolve_write(root, "30-Knowledge/New Note.md", create=True)
    assert created.resolve().is_relative_to(root)


# --------------------------------------------------------------------------
# Finding 11 — DNS rebinding reached vault routes (only /connect was guarded)
# --------------------------------------------------------------------------


def _guarded_client(chronicle_dir: Path):
    from fastapi.testclient import TestClient

    from chronicle_pipeline.serve import TOKEN_HEADER, create_app

    app = create_app(
        chronicle_dir,
        connect_info={
            "base": "http://127.0.0.1:8765",
            "host": "127.0.0.1",
            "bind_host": "127.0.0.1",
            "token": "tok",
            "auth_required": True,
            "tls": False,
        },
    )
    return TestClient(app), {TOKEN_HEADER: "tok"}


@pytest.mark.parametrize("route", ["/entries", "/health", "/models", "/connect"])
def test_rebinding_host_is_refused_on_every_route(chronicle_dir: Path, route: str) -> None:
    """A rebound attacker origin is same-origin, so CORS never fires; the Host
    allowlist is the only boundary and must cover more than /connect."""
    client, hdr = _guarded_client(chronicle_dir)
    resp = client.get(route, headers={**hdr, "Host": "evil.example.com"})
    assert resp.status_code == 403, (
        f"{route} accepted a rebinding Host header ({resp.status_code})"
    )
    assert "invalid Host header" in resp.text


def test_rebinding_host_is_refused_on_mutations(chronicle_dir: Path) -> None:
    client, hdr = _guarded_client(chronicle_dir)
    resp = client.post(
        "/entries",
        json={"type": "log", "text": "planted"},
        headers={**hdr, "Host": "evil.example.com"},
    )
    assert resp.status_code == 403


@pytest.mark.parametrize("host", ["127.0.0.1", "127.0.0.1:8765", "localhost:8765"])
def test_legitimate_hosts_still_work(chronicle_dir: Path, host: str) -> None:
    client, hdr = _guarded_client(chronicle_dir)
    assert client.get("/health", headers={**hdr, "Host": host}).status_code == 200


def test_operator_override_allows_a_custom_hostname(
    chronicle_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CHRONICLE_EXTRA_HOSTS", "chronicle.example.internal")
    client, hdr = _guarded_client(chronicle_dir)
    resp = client.get("/health", headers={**hdr, "Host": "chronicle.example.internal"})
    assert resp.status_code == 200
