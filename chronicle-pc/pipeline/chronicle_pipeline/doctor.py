"""Integrity checks, orphan media, sync-conflict repair, ops compaction."""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path
from typing import Any

from . import curation as curation_mod
from .entries import ID_RE, load_all_entries, load_entry
from .journal import detect_journal_hash_mismatches, get_filed, is_file_ready
from .paths import resolve_chronicle_dir
from .vault_paths import JOURNAL_DIR, capture_entries_dir, legacy_entries_dir

log = logging.getLogger("chronicle.doctor")

# Syncthing conflict marker in filenames (any extension).
SYNC_CONFLICT_MARK = "sync-conflict"


def _is_sync_conflict(path: Path) -> bool:
    return SYNC_CONFLICT_MARK in path.name


def _primary_name_for_conflict(conflict: Path) -> str:
    """Recover canonical filename, preserving the original extension."""
    base = conflict.name.split(".sync-conflict", 1)[0]
    return base + conflict.suffix


def _iter_sync_conflicts(root: Path) -> list[Path]:
    """Vault-wide scan for *sync-conflict* files (entries, notes, kb, media, …)."""
    if not root.is_dir():
        return []
    skip_dirs = {"index", ".git", ".venv", "__pycache__"}
    out: list[Path] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(root).parts
        if any(part in skip_dirs for part in rel_parts):
            continue
        if _is_sync_conflict(path):
            out.append(path)
    return out


def _validate_entry_file(path: Path) -> list[str]:
    issues: list[str] = []
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        return [f"unreadable JSON: {e}"]
    e = load_entry(path)
    if e is None:
        issues.append("failed pydantic validation")
        return issues
    if not ID_RE.match(e.id):
        issues.append(f"bad id pattern: {e.id}")
    if path.stem != e.id:
        issues.append(f"filename stem {path.stem} != id {e.id}")
    return issues


def run_doctor(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
    fix: bool = False,
    compact_ops: bool | None = None,
) -> dict[str, Any]:
    """Integrity report. Mutations (JSON conflict repair, ops compact) require ``fix=True``.

    Markdown (and other non-JSON) sync-conflicts are always report-only — never auto-merged.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    # compact defaults on only when applying fixes
    do_compact = compact_ops if compact_ops is not None else fix
    report: dict[str, Any] = {
        "chronicle_dir": str(root),
        "entry_issues": [],
        "orphans": {"images": [], "audio": []},
        "sync_conflicts": [],
        "journal_conflicts": [],
        "stuck_unfiled": [],
        "journal_fence_issues": [],
        "dual_read_copies": [],
        "brain_staleness": None,
        "ops_compaction": None,
        "fix": fix,
        "ok": True,
    }

    # Schema / entry validation (skip conflict copies) — dual-read capture + legacy
    for entries_dir in (capture_entries_dir(root), legacy_entries_dir(root)):
        if not entries_dir.is_dir():
            continue
        for path in sorted(entries_dir.rglob("*.json")):
            if _is_sync_conflict(path):
                continue
            issues = _validate_entry_file(path)
            if issues:
                report["entry_issues"].append(
                    {"path": str(path.relative_to(root)), "issues": issues}
                )
                report["ok"] = False

    # Vault-wide sync-conflict scan — preserve original relative paths/extensions
    for path in _iter_sync_conflicts(root):
        rel = str(path.relative_to(root)).replace("\\", "/")
        report["sync_conflicts"].append(rel)
        # Journal MD conflicts: report under journal_conflicts; never auto-delete
        if rel.startswith(f"{JOURNAL_DIR}/") or "/40-Journal/" in f"/{rel}":
            report["journal_conflicts"].append(rel)

    entries = load_all_entries(root)
    referenced_img = set()
    referenced_audio = set()
    for e in entries:
        referenced_img.update(e.images)
        referenced_audio.update(e.audio or [])

    for media_kind, refs, bucket in (
        ("img", referenced_img, "images"),
        ("audio", referenced_audio, "audio"),
        ("_attachments", referenced_img | referenced_audio, "images"),
    ):
        media_root = root / media_kind
        if not media_root.is_dir():
            continue
        for path in media_root.rglob("*"):
            if not path.is_file() or path.name.startswith(".") or path.suffix == ".md":
                continue
            if _is_sync_conflict(path):
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            if rel not in refs:
                # Attachments orphans go to images or audio by extension
                if media_kind == "_attachments":
                    bucket_name = "audio" if rel.endswith(".m4a") else "images"
                    if rel not in report["orphans"][bucket_name]:
                        report["orphans"][bucket_name].append(rel)
                else:
                    report["orphans"][bucket].append(rel)

    # Stuck: processed && !filed (doctor retry queue)
    for e in entries:
        if e.processed and not get_filed(e) and is_file_ready(e):
            report["stuck_unfiled"].append(e.id)
            report["ok"] = False

    # Missing fence / hash mismatch (amend gate drift)
    for issue in detect_journal_hash_mismatches(root, entries):
        report["journal_fence_issues"].append(issue)
        report["ok"] = False

    # Sync conflict repair: JSON only (entry conflicts). Markdown/other = report-only.
    # Mutations require --fix/--apply; default is report-only.
    for rel in list(report["sync_conflicts"]):
        conflict = root / rel
        if conflict.suffix.lower() != ".json":
            # Never auto-merge markdown or non-JSON conflicts
            continue
        if not fix:
            continue
        primary_name = _primary_name_for_conflict(conflict)
        primary = conflict.with_name(primary_name)
        if not primary.exists():
            if dry_run:
                log.info("[dry-run] would promote conflict %s → %s", conflict.name, primary_name)
            else:
                shutil.move(str(conflict), str(primary))
                log.info("Promoted sync-conflict to %s", primary)
            report.setdefault("repairs", []).append(f"promoted {rel}")
        else:
            # Keep newer; quarantine older conflict aside (do not destroy — rename to .bak)
            try:
                if conflict.stat().st_mtime > primary.stat().st_mtime:
                    bak = primary.with_suffix(primary.suffix + ".older.bak")
                    if dry_run:
                        log.info("[dry-run] would replace %s with newer conflict", primary.name)
                    else:
                        shutil.move(str(primary), str(bak))
                        shutil.move(str(conflict), str(primary))
                        report.setdefault("repairs", []).append(f"replaced {primary.name} from conflict")
                else:
                    bak = conflict.with_suffix(conflict.suffix + ".bak")
                    if dry_run:
                        log.info("[dry-run] would quarantine older conflict %s", conflict.name)
                    else:
                        shutil.move(str(conflict), str(bak))
                        report.setdefault("repairs", []).append(f"quarantined {rel}")
            except OSError as e:
                log.warning("Conflict repair failed for %s: %s", rel, e)
                report["ok"] = False

    # Brain staleness
    graph = root / "brain" / "graph.json"
    if graph.is_file():
        try:
            gen = json.loads(graph.read_text(encoding="utf-8")).get("generated")
            report["brain_staleness"] = {"graph_generated": gen, "present": True}
        except (OSError, json.JSONDecodeError):
            report["brain_staleness"] = {"present": False, "error": "unreadable"}
            report["ok"] = False
    else:
        report["brain_staleness"] = {"present": False}
        if entries:
            report["ok"] = False

    if do_compact:
        stats = curation_mod.compact_ops(root, dry_run=dry_run or not fix)
        report["ops_compaction"] = stats

    json_conflicts = [c for c in report["sync_conflicts"] if c.lower().endswith(".json")]
    md_conflicts = [c for c in report["sync_conflicts"] if not c.lower().endswith(".json")]
    if json_conflicts and not fix:
        log.info(
            "Doctor found %d JSON sync-conflict(s); re-run with --fix to repair",
            len(json_conflicts),
        )
    if md_conflicts:
        log.info(
            "Doctor found %d non-JSON sync-conflict(s) (report-only; merge manually): %s",
            len(md_conflicts),
            ", ".join(md_conflicts[:5]) + ("…" if len(md_conflicts) > 5 else ""),
        )

    # Guard: vault_mirror must not point at the vault root (self-copy hazard)
    try:
        from .config import load_config

        cfg = load_config(root)
        mirror = (cfg.vault_mirror or "").strip()
        if mirror:
            mirror_path = Path(mirror).expanduser().resolve()
            vault_path = root.resolve()
            if mirror_path == vault_path:
                msg = (
                    f"vault_mirror ({mirror_path}) points at the vault root "
                    f"— self-copy hazard"
                )
                report.setdefault("config_issues", []).append(msg)
                report["ok"] = False
                log.warning("%s", msg)
    except Exception as e:  # noqa: BLE001
        log.debug("vault_mirror check skipped: %s", e)

    # Orphans are reported but never auto-deleted
    if report["orphans"]["images"] or report["orphans"]["audio"]:
        log.info(
            "Orphan media (not deleted): %d images, %d audio",
            len(report["orphans"]["images"]),
            len(report["orphans"]["audio"]),
        )

    stuck = report.get("stuck_unfiled") or []
    if stuck:
        log.info(
            "Stuck unfiled (processed && !filed, file-ready): %d — re-run "
            "`chronicle process` to retry file-once into 40-Journal/: %s",
            len(stuck),
            ", ".join(stuck[:8]) + ("…" if len(stuck) > 8 else ""),
        )

    fence_issues = report.get("journal_fence_issues") or []
    if fence_issues:
        log.info(
            "Journal fence/hash issues (amend gate — human/agent edits preserved "
            "when on-disk hash ≠ filed_content_hash): %d",
            len(fence_issues),
        )

    # Leftover kb/notes after dual-read cutover — run cutover-kb
    try:
        from . import path_map as path_map_mod

        dual = path_map_mod.find_dual_copy_pairs(root)
        leftover: list[str] = []
        legacy_root = root / "kb" / "notes"
        if legacy_root.is_dir():
            for path in sorted(legacy_root.rglob("*.md")):
                if path.name.startswith(".") or ".sync-conflict" in path.name:
                    continue
                if path_map_mod.is_chrome_basename(path.name):
                    continue
                leftover.append(path_map_mod.vault_rel(root, path))
        report["dual_read_copies"] = dual
        report["legacy_kb_leftover"] = leftover
        if dual or leftover:
            log.warning(
                "Leftover kb/notes/ (dual-read retired) — run "
                "`chronicle backup` then "
                "`chronicle cutover-kb --apply --i-have-backup`: "
                "%d dual pair(s), %d leftover file(s); examples: %s",
                len(dual),
                len(leftover),
                ", ".join(
                    (
                        [f"{p['para']} ↔ {p['legacy']}" for p in dual[:3]]
                        + leftover[:3]
                    )[:5]
                )
                + ("…" if (len(dual) + len(leftover)) > 5 else ""),
            )
    except Exception as e:  # noqa: BLE001
        log.debug("legacy kb leftover check skipped: %s", e)
        report["dual_read_copies"] = []
        report["legacy_kb_leftover"] = []

    log.info(
        "Doctor ok=%s issues=%d conflicts=%d stuck_unfiled=%d dual_copies=%d",
        report["ok"],
        len(report["entry_issues"]),
        len(report["sync_conflicts"]),
        len(stuck),
        len(report.get("dual_read_copies") or []),
    )
    return report
