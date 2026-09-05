"""Integrity checks, orphan media, sync-conflict repair, ops compaction."""

from __future__ import annotations

import datetime
import json
import logging
import os
import re
import shutil
import sqlite3
import time

from pathlib import Path
from typing import Any

from . import curation as curation_mod
from . import path_map
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

    for _rel, md_path in path_map.iter_knowledge_md(root):
        try:
            content = md_path.read_text(encoding="utf-8")
        except OSError:
            continue
        for m in re.finditer(r"(_attachments/[A-Za-z0-9._\- /]+)", content):
            matched = m.group(1).rstrip(")\"'>")
            referenced_img.add(matched)


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

    # --- Operational Checks (Phase 1 hardening) ---
    checks: dict[str, dict[str, Any]] = {}

    # 1. layout_dirs (at layout 2: _capture/entries, _attachments, _system/derived, 40-Journal)
    try:
        from .config import load_config

        cfg = load_config(root)
        layout_version = getattr(cfg, "layout_version", 1) or 1
        journal_cfg = getattr(cfg, "journal", None)
        journal_enabled = True
        if isinstance(journal_cfg, dict):
            journal_enabled = journal_cfg.get("enabled", True)
        elif hasattr(journal_cfg, "enabled"):
            journal_enabled = bool(journal_cfg.enabled)
    except Exception:  # noqa: BLE001
        layout_version = 2
        journal_enabled = True

    if not journal_enabled:
        checks["layout_dirs"] = {"ran": False, "status": "ok", "detail": "journal disabled"}
    else:
        if layout_version >= 2:
            required = [
                root / "_capture" / "entries",
                root / "_attachments",
                root / "_system" / "derived",
                root / "40-Journal",
            ]
            missing = [str(p.relative_to(root)) for p in required if not p.is_dir()]
            if missing:
                checks["layout_dirs"] = {"ran": True, "status": "fail", "detail": {"missing": missing}}
                report["ok"] = False
            else:
                checks["layout_dirs"] = {"ran": True, "status": "ok", "detail": "all layout 2 directories present"}
        else:
            checks["layout_dirs"] = {"ran": True, "status": "ok", "detail": f"layout_version {layout_version}"}

    # 2. serve (parse index/serve.json, probe the pid with signal 0; dead pid is fail)
    if not journal_enabled:
        checks["serve"] = {"ran": False, "status": "ok", "detail": "journal disabled"}
    else:
        serve_json = root / "index" / "serve.json"
        if not serve_json.is_file():
            checks["serve"] = {"ran": True, "status": "warn", "detail": "serve not running (no index/serve.json)"}
        else:
            try:
                serve_data = json.loads(serve_json.read_text(encoding="utf-8"))
                pid = serve_data.get("pid")
                if not isinstance(pid, int):
                    checks["serve"] = {"ran": True, "status": "fail", "detail": "serve.json missing pid"}
                    report["ok"] = False
                    if fix:
                        serve_json.unlink()
                        report.setdefault("repairs", []).append(f"unlinked {serve_json.name}")
                else:
                    try:
                        os.kill(pid, 0)
                        checks["serve"] = {
                            "ran": True,
                            "status": "ok",
                            "detail": {"pid": pid, "base": serve_data.get("base")},
                        }
                    except OSError:
                        checks["serve"] = {
                            "ran": True,
                            "status": "fail",
                            "detail": f"stale serve.json with dead pid {pid}",
                        }
                        report["ok"] = False
                        if fix:
                            serve_json.unlink()
                            report.setdefault("repairs", []).append(f"unlinked stale {serve_json.name}")
            except Exception as e:  # noqa: BLE001
                checks["serve"] = {"ran": True, "status": "fail", "detail": f"unreadable serve.json: {e}"}
                report["ok"] = False
                if fix:
                    try:
                        serve_json.unlink()
                        report.setdefault("repairs", []).append(f"unlinked corrupt {serve_json.name}")
                    except OSError:
                        pass

    # 3. index_freshness (sqlite max(updated_at) vs newest PARA mtime, unindexed, unembedded)
    from .index_store import index_db_path


    db_path = index_db_path(root)
    knowledge_notes = list(path_map.iter_knowledge_md(root))
    newest_mtime = max((p.stat().st_mtime for _, p in knowledge_notes), default=0.0)

    if not db_path.is_file():
        if knowledge_notes:
            checks["index_freshness"] = {
                "ran": True,
                "status": "fail",
                "detail": {"unindexed": len(knowledge_notes), "error": "no search index found"},
            }
            report["ok"] = False
        else:
            checks["index_freshness"] = {
                "ran": True,
                "status": "ok",
                "detail": {"unindexed": 0, "unembedded": 0},
            }
    else:
        try:
            con = sqlite3.connect(str(db_path))
            con.row_factory = sqlite3.Row
            tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if "documents" not in tables:
                checks["index_freshness"] = {
                    "ran": True,
                    "status": "fail",
                    "detail": {"unindexed": len(knowledge_notes), "error": "documents table missing"},
                }
                report["ok"] = False
            else:
                row = con.execute("SELECT max(updated_at) FROM documents").fetchone()
                max_updated_at_str = row[0] if row else None

                indexed_paths = {
                    r[0] for r in con.execute("SELECT path FROM documents WHERE path IS NOT NULL")
                }
                indexed_ids = {r[0] for r in con.execute("SELECT id FROM documents")}

                from .paths import content_hash

                live_content_hashes = {
                    content_hash(p.read_text(encoding="utf-8", errors="replace"))
                    for rel, p in knowledge_notes
                    if not (rel.startswith("90-Archive/") or "/90-Archive/" in f"/{rel}")
                }

                unindexed_paths = []
                for rel, p in knowledge_notes:
                    if rel in indexed_paths or rel in indexed_ids:
                        continue
                    if (rel.startswith("90-Archive/") or "/90-Archive/" in f"/{rel}"):
                        try:
                            if content_hash(p.read_text(encoding="utf-8", errors="replace")) in live_content_hashes:
                                continue  # D3 dedup-skipped
                        except OSError:
                            pass
                    unindexed_paths.append(rel)
                unindexed_count = len(unindexed_paths)

                unembedded_row = con.execute(
                    "SELECT count(*) FROM documents WHERE embedding_json IS NULL OR embedding_json = '' OR embedding_json = '[]'"
                ).fetchone()
                unembedded_count = unembedded_row[0] if unembedded_row else 0

                is_stale = False
                if max_updated_at_str:
                    try:
                        max_dt = datetime.datetime.fromisoformat(max_updated_at_str.replace(" ", "T"))
                        if newest_mtime > max_dt.timestamp() + 1.0:
                            is_stale = True
                    except Exception:
                        pass
                elif knowledge_notes:
                    is_stale = True

                detail = {
                    "unindexed": unindexed_count,
                    "unembedded": unembedded_count,
                    "sqlite_max_updated_at": max_updated_at_str,
                    "newest_para_mtime": (
                        datetime.datetime.fromtimestamp(newest_mtime).isoformat()
                        if newest_mtime else None
                    ),
                }
                if unindexed_count > 0 or unembedded_count > 0 or is_stale:
                    checks["index_freshness"] = {
                        "ran": True,
                        "status": "fail",
                        "detail": detail,
                    }
                    report["ok"] = False
                else:
                    checks["index_freshness"] = {
                        "ran": True,
                        "status": "ok",
                        "detail": detail,
                    }
            con.close()
        except Exception as e:  # noqa: BLE001
            checks["index_freshness"] = {
                "ran": False,
                "status": "fail",
                "detail": f"sqlite read error: {e}",
            }
            report["ok"] = False

    # 4. sync (.stfolder marker and .stignore present, else warn)
    stfolder = root / ".stfolder"
    stignore = root / ".stignore"
    missing_sync = []
    if not stfolder.exists():
        missing_sync.append(".stfolder")
    if not stignore.is_file():
        missing_sync.append(".stignore")
    if missing_sync:
        checks["sync"] = {
            "ran": True,
            "status": "warn",
            "detail": f"missing {', '.join(missing_sync)}",
        }
    else:
        checks["sync"] = {"ran": True, "status": "ok", "detail": "syncthing markers present"}

    # 5. timezone (config versus the /etc/localtime zone, warn on mismatch)
    sys_tz = None
    try:
        localtime_p = Path("/etc/localtime")
        if localtime_p.exists():
            resolved = localtime_p.resolve()
            parts = resolved.parts
            if "zoneinfo" in parts:
                idx = parts.index("zoneinfo")
                sys_tz = "/".join(parts[idx + 1:])
    except Exception:  # noqa: BLE001
        pass

    try:
        from .config import load_config

        cfg_tz = getattr(load_config(root), "timezone", None)
    except Exception:  # noqa: BLE001
        cfg_tz = None

    if not sys_tz or not cfg_tz:
        checks["timezone"] = {
            "ran": bool(cfg_tz or sys_tz),
            "status": "ok",
            "detail": {"config_timezone": cfg_tz, "system_timezone": sys_tz},
        }
    elif sys_tz == cfg_tz:
        checks["timezone"] = {
            "ran": True,
            "status": "ok",
            "detail": {"timezone": cfg_tz},
        }
    else:
        checks["timezone"] = {
            "ran": True,
            "status": "warn",
            "detail": {"config_timezone": cfg_tz, "system_timezone": sys_tz},
        }

    # 6. cli_on_path (shutil.which("chronicle"), warn)
    chronicle_bin = shutil.which("chronicle")
    if chronicle_bin:
        checks["cli_on_path"] = {
            "ran": True,
            "status": "ok",
            "detail": {"path": chronicle_bin},
        }
    else:
        checks["cli_on_path"] = {
            "ran": True,
            "status": "warn",
            "detail": "chronicle not found on PATH",
        }

    # 7. backup_age (newest zip under ~/Backups/Chronicle, warn past 7 days)
    backup_dir = Path.home() / "Backups" / "Chronicle"
    zips = list(backup_dir.glob("*.zip")) if backup_dir.is_dir() else []
    if not zips:
        checks["backup_age"] = {
            "ran": True,
            "status": "warn",
            "detail": "no backup found in ~/Backups/Chronicle",
        }
    else:
        newest_zip = max(zips, key=lambda p: p.stat().st_mtime)
        age_s = time.time() - newest_zip.stat().st_mtime
        age_days = round(age_s / 86400, 1)
        if age_s > 7 * 86400:
            checks["backup_age"] = {
                "ran": True,
                "status": "warn",
                "detail": {"newest_zip": newest_zip.name, "age_days": age_days, "warn": "older than 7 days"},
            }
        else:
            checks["backup_age"] = {
                "ran": True,
                "status": "ok",
                "detail": {"newest_zip": newest_zip.name, "age_days": age_days},
            }

    report["checks"] = checks

    log.info(
        "Doctor ok=%s issues=%d conflicts=%d stuck_unfiled=%d dual_copies=%d",
        report["ok"],
        len(report["entry_issues"]),
        len(report["sync_conflicts"]),
        len(stuck),
        len(report.get("dual_read_copies") or []),
    )
    return report
