"""Phase 4 migrate-journal-v2: path cutover to file-once layout.

Moves:
  entries/ → _capture/entries/
  img/ + audio/ → _attachments/yyyy/MM/ (rewrites JSON + MD embeds only for moved files)
  notes/{weekly,monthly,yearly,topics} → _system/derived/
  notes/daily replaced by filing into 40-Journal/

Requires --i-have-backup. Bumps config layout_version to 2 only when safe
(no skipped_exists collisions, no divergent dual-id entry twins).
"""

from __future__ import annotations

import hashlib
import logging
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .config import load_config, save_config
from .entries import load_all_entries, save_entry
from .journal import file_entry, is_file_ready
from .paths import atomic_write_text, resolve_chronicle_dir
from .vault_layout import CURRENT_LAYOUT_VERSION
from .vault_paths import (
    capture_entries_dir,
    legacy_entries_dir,
    media_rewrite_legacy_to_attachments,
)

log = logging.getLogger("chronicle.migrate_journal_v2")

_MEDIA_IN_MD = re.compile(
    r"(!?\[[^\]]*\]\()(img|audio)/(\d{4}/\d{2}/[^)\s]+)(\))"
)


def _move_tree(src: Path, dest: Path, *, dry_run: bool, stats: dict[str, int]) -> None:
    if not src.exists():
        return
    if dry_run:
        log.info("[dry-run] would move %s → %s", src, dest)
        stats["moved"] += 1
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        # Merge: move children that don't collide
        if src.is_dir() and dest.is_dir():
            for child in src.iterdir():
                target = dest / child.name
                if target.exists():
                    stats["skipped_exists"] += 1
                    continue
                shutil.move(str(child), str(target))
                stats["moved"] += 1
            # Remove empty leftovers
            try:
                next(src.iterdir())
            except StopIteration:
                src.rmdir()
            return
        stats["skipped_exists"] += 1
        return
    shutil.move(str(src), str(dest))
    stats["moved"] += 1


def _rewrite_media_in_text(text: str, *, moved_media: set[str]) -> tuple[str, int]:
    """Rewrite MD embeds only when the underlying media file actually moved."""
    count = 0

    def repl(m: re.Match[str]) -> str:
        nonlocal count
        legacy_rel = f"{m.group(2)}/{m.group(3)}"
        if legacy_rel not in moved_media:
            return m.group(0)
        count += 1
        return f"{m.group(1)}_attachments/{m.group(3)}{m.group(4)}"

    return _MEDIA_IN_MD.sub(repl, text), count


def _rewrite_entry_media(entry, *, moved_media: set[str]) -> bool:
    """Rewrite JSON media paths only for files that actually moved."""
    changed = False
    new_images = []
    for rel in entry.images:
        if (rel.startswith("img/") or rel.startswith("audio/")) and rel in moved_media:
            rewritten = media_rewrite_legacy_to_attachments(rel)
            if rewritten != rel:
                changed = True
            new_images.append(rewritten)
        else:
            new_images.append(rel)
    new_audio = []
    for rel in entry.audio or []:
        if (rel.startswith("img/") or rel.startswith("audio/")) and rel in moved_media:
            rewritten = media_rewrite_legacy_to_attachments(rel)
            if rewritten != rel:
                changed = True
            new_audio.append(rewritten)
        else:
            new_audio.append(rel)
    if changed:
        entry.images = new_images
        entry.audio = new_audio
    return changed


def _file_digest(path: Path) -> str | None:
    try:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def detect_entry_twins(root: Path) -> list[dict[str, Any]]:
    """Find same-id JSON under both ``_capture/entries/`` and legacy ``entries/``.

    Divergent twins (different content hash) block layout_version bump.
    """
    capture = capture_entries_dir(root)
    legacy = legacy_entries_dir(root)
    if not capture.is_dir() or not legacy.is_dir():
        return []

    legacy_by_id: dict[str, Path] = {}
    for path in legacy.rglob("*.json"):
        if path.name.startswith(".") or ".sync-conflict" in path.name:
            continue
        legacy_by_id[path.stem] = path

    twins: list[dict[str, Any]] = []
    for path in capture.rglob("*.json"):
        if path.name.startswith(".") or ".sync-conflict" in path.name:
            continue
        eid = path.stem
        other = legacy_by_id.get(eid)
        if other is None:
            continue
        c_hash = _file_digest(path)
        l_hash = _file_digest(other)
        divergent = bool(c_hash and l_hash and c_hash != l_hash)
        twins.append(
            {
                "id": eid,
                "capture": path.relative_to(root).as_posix(),
                "legacy": other.relative_to(root).as_posix(),
                "divergent": divergent,
            }
        )
    return twins


def run_migrate_journal_v2(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = True,
    apply: bool = False,
    i_have_backup: bool = False,
) -> dict[str, Any]:
    """
    Hard cutover to layout_version 2 (file-once).

    Default dry_run=True. apply requires --i-have-backup.
    Refuses layout_version bump when media/entry collisions were skipped or
    divergent dual-id entry twins exist.
    """
    root = resolve_chronicle_dir(chronicle_dir)

    if apply and not i_have_backup:
        raise ValueError(
            "Refusing --apply without --i-have-backup. "
            "Run `chronicle backup` first (zip outside Syncthing share), then "
            "re-run: chronicle migrate-journal-v2 --apply --i-have-backup"
        )

    effective_dry = dry_run or not apply
    if apply and not dry_run:
        effective_dry = False

    stats: dict[str, int] = {
        "moved": 0,
        "skipped_exists": 0,
        "entries_rewritten": 0,
        "md_rewrites": 0,
        "filed": 0,
        "filed_skipped": 0,
        "derived_moved": 0,
    }
    plans: list[str] = []
    moved_media: set[str] = set()

    # 1) entries/ → _capture/entries/
    src_e = root / "entries"
    dest_e = root / "_capture" / "entries"
    if src_e.exists():
        plans.append(f"move {src_e.relative_to(root)} → {dest_e.relative_to(root)}")
        _move_tree(src_e, dest_e, dry_run=effective_dry, stats=stats)

    # 2) img/ + audio/ → _attachments/ (track which legacy rels actually moved)
    for kind in ("img", "audio"):
        src_m = root / kind
        if not src_m.is_dir():
            continue
        for path in sorted(src_m.rglob("*")):
            if not path.is_file() or path.name.startswith("."):
                continue
            try:
                rel = path.relative_to(src_m).as_posix()  # yyyy/MM/file
            except ValueError:
                continue
            legacy_rel = f"{kind}/{rel}"
            dest = root / "_attachments" / rel
            plans.append(f"move {legacy_rel} → _attachments/{rel}")
            if effective_dry:
                if dest.exists():
                    stats["skipped_exists"] += 1
                else:
                    stats["moved"] += 1
                    moved_media.add(legacy_rel)
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists():
                stats["skipped_exists"] += 1
                continue
            shutil.move(str(path), str(dest))
            stats["moved"] += 1
            moved_media.add(legacy_rel)
        if not effective_dry and src_m.is_dir():
            # prune empty dirs
            for d in sorted(src_m.rglob("*"), reverse=True):
                if d.is_dir():
                    try:
                        d.rmdir()
                    except OSError:
                        pass
            try:
                src_m.rmdir()
            except OSError:
                pass

    # 3) Rewrite media strings in entry JSON — only for files that moved
    if not effective_dry:
        for entry in load_all_entries(root):
            if _rewrite_entry_media(entry, moved_media=moved_media):
                save_entry(root, entry)
                stats["entries_rewritten"] += 1
    else:
        for entry in load_all_entries(root):
            if any(
                r in moved_media
                for r in list(entry.images) + list(entry.audio or [])
            ):
                stats["entries_rewritten"] += 1

    # 4) Rewrite embeds in existing markdown (notes/, 40-Journal/, PARA, …)
    md_roots = [
        root / "notes",
        root / "40-Journal",
        root / "_system",
    ]
    for area in ("00-Inbox", "10-Work", "20-Personal", "30-Knowledge", "90-Archive"):
        md_roots.append(root / area)
    for base in md_roots:
        if not base.is_dir():
            continue
        for path in base.rglob("*.md"):
            if ".sync-conflict" in path.name:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except OSError:
                continue
            new_text, n = _rewrite_media_in_text(text, moved_media=moved_media)
            if n:
                plans.append(f"rewrite embeds in {path.relative_to(root)} ({n})")
                stats["md_rewrites"] += n
                if not effective_dry:
                    atomic_write_text(path, new_text)

    # 5) notes/{weekly,monthly,yearly,topics} → _system/derived/
    for sub in ("weekly", "monthly", "yearly", "topics"):
        src = root / "notes" / sub
        dest = root / "_system" / "derived" / sub
        if src.exists():
            plans.append(f"move notes/{sub} → _system/derived/{sub}")
            before = stats["moved"]
            _move_tree(src, dest, dry_run=effective_dry, stats=stats)
            if stats["moved"] > before:
                stats["derived_moved"] += 1

    # 6) File processed entries into 40-Journal (never force-overwrite fences)
    # Missing layout_version on disk = pre-file-once (treat as 1 for migrate only).
    cfg = load_config(root, default_layout_version=1)
    entries = load_all_entries(root, fallback_tz=cfg.timezone)
    for entry in entries:
        if not entry.processed or not is_file_ready(entry):
            continue
        if effective_dry:
            stats["filed"] += 1
            plans.append(f"would file {entry.id} → 40-Journal/")
            continue
        # Never force: missing filed_content_hash + existing fence → skip/conflict
        fr = file_entry(root, entry, dry_run=False, force=False)
        if fr.get("action") in ("insert", "amend", "unchanged") or fr.get("filed"):
            stats["filed"] += 1
        elif fr.get("action") == "skip":
            stats["filed_skipped"] += 1
            plans.append(
                f"skip file {entry.id}: {fr.get('skipped_reason', 'unknown')}"
            )

    # Leave notes/daily in place as legacy (optional); do not delete.
    # Humans/agents should prefer 40-Journal after cutover.

    twins = detect_entry_twins(root)
    divergent_twins = [t for t in twins if t.get("divergent")]
    bump_ok = stats["skipped_exists"] == 0 and not divergent_twins
    layout_bumped = False

    # 7) Bump layout_version only when cutover is clean
    if effective_dry:
        if bump_ok:
            plans.append(f"would set layout_version={CURRENT_LAYOUT_VERSION}")
        else:
            reasons = []
            if stats["skipped_exists"]:
                reasons.append(f"skipped_exists={stats['skipped_exists']}")
            if divergent_twins:
                reasons.append(f"divergent_twins={len(divergent_twins)}")
            plans.append(
                "would REFUSE layout_version bump (" + ", ".join(reasons) + ")"
            )
    else:
        if bump_ok:
            cfg = load_config(root, default_layout_version=1)
            cfg.layout_version = CURRENT_LAYOUT_VERSION
            save_config(cfg, root)
            layout_bumped = True
            plans.append(f"set layout_version={CURRENT_LAYOUT_VERSION}")
        else:
            reasons = []
            if stats["skipped_exists"]:
                reasons.append(f"skipped_exists={stats['skipped_exists']}")
            if divergent_twins:
                reasons.append(f"divergent_twins={len(divergent_twins)}")
            msg = "REFUSED layout_version bump (" + ", ".join(reasons) + ")"
            plans.append(msg)
            log.error("%s — resolve collisions/twins, then re-run migrate", msg)

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    return {
        "chronicle_dir": str(root),
        "when": when,
        "dry_run": effective_dry,
        "stats": stats,
        "plans": plans[:80],
        "plan_count": len(plans),
        "moved_media": sorted(moved_media)[:50],
        "entry_twins": twins,
        "layout_bumped": layout_bumped,
        "layout_version": (
            CURRENT_LAYOUT_VERSION
            if layout_bumped or (effective_dry and bump_ok)
            else getattr(
                load_config(root, default_layout_version=1), "layout_version", None
            )
        ),
        "hint": (
            "This was a dry-run. After `chronicle backup`, apply with: "
            "chronicle migrate-journal-v2 --apply --i-have-backup"
            if effective_dry
            else (
                "Applied. Co-release APK. Prefer 40-Journal for prose; JSON keeps structured fields."
                if layout_bumped
                else "Partial apply: layout_version NOT bumped — resolve skipped_exists / "
                "divergent entry twins, then re-run migrate-journal-v2."
            )
        ),
        "demo_vault": (
            "demo-vault/ may still be layout 1 — run migrate-journal-v2 on a copy, "
            "or document the migrate path in SETUP."
        ),
    }
