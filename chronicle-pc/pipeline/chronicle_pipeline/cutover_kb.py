"""Hard dual-read cutover: quarantine leftover kb/notes/** into PARA.

After `migrate-v2` copies, this command finishes the job:

1. For every remaining ``kb/notes/**`` note:
   - If a PARA same-suffix peer exists → quarantine under ``90-Archive/_legacy-kb/``
     (compare when archive dest already exists — never blind-unlink).
   - Else → move into the classified PARA area (default ``30-Knowledge/``).
2. Run ``link_repair`` for each move/quarantine.
3. Rewrite ``brain/graph.json`` ``doc`` fields + emit ``set_doc`` curation ops.
4. Leave an empty ``kb/notes/`` tombstone (keep ``kb/files/`` + ``kb/knowledge.json``);
   never delete ``.sync-conflict-*`` leftovers.

**Always run ``chronicle backup`` first** (zip outside the Syncthing share).
Default is dry-run; ``--apply`` requires ``--i-have-backup``.
"""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import curation as curation_mod
from .link_repair import repair_links_after_move
from .migrate_v2 import classify_kb_note
from .path_map import (
    LEGACY_KB_NOTES,
    PARA_AREAS,
    is_chrome_basename,
    is_legacy_kb_path,
    vault_rel,
)
from .paths import atomic_write_text, resolve_chronicle_dir

log = logging.getLogger("chronicle.cutover_kb")

BACKUP_LOUD = (
    "BACKUP REQUIRED: Run `chronicle backup` first and store the zip "
    "**outside** the Syncthing-shared Chronicle folder. "
    "Cutover moves/quarantines every remaining kb/notes/** file."
)

MIGRATE_HINT = (
    "Legacy kb/notes/ dual-read is retired. "
    "Run `chronicle cutover-kb --apply --i-have-backup`, then use PARA paths "
    "(e.g. 30-Knowledge/…, 00-Inbox/…)."
)


def classify_for_cutover(rel_under_kb_notes: str, text: str = "") -> str:
    """PARA destination for cutover; unclear notes default to 30-Knowledge/."""
    dest = classify_kb_note(rel_under_kb_notes, text)
    if dest.startswith("00-Inbox/"):
        return "30-Knowledge/" + dest[len("00-Inbox/") :]
    return dest


def _para_peer_for_suffix(root: Path, suffix: str) -> str | None:
    """First existing PARA same-suffix peer, or None."""
    for area in PARA_AREAS:
        cand = root / area / suffix
        if cand.is_file():
            return f"{area}/{suffix}"
    return None


def _file_digest(path: Path) -> str | None:
    try:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def plan_cutover(root: Path) -> list[dict[str, str]]:
    """List planned actions for each leftover kb/notes/** .md file."""
    legacy = root / "kb" / "notes"
    plans: list[dict[str, str]] = []
    if not legacy.is_dir():
        return plans
    for src in sorted(legacy.rglob("*.md")):
        if src.name.startswith(".") or ".sync-conflict" in src.name:
            continue
        if is_chrome_basename(src.name):
            continue
        suffix = src.relative_to(legacy).as_posix()
        legacy_rel = f"{LEGACY_KB_NOTES}/{suffix}"
        peer = _para_peer_for_suffix(root, suffix)
        if peer:
            plans.append(
                {
                    "src": legacy_rel,
                    "dest": f"90-Archive/_legacy-kb/{suffix}",
                    "action": "quarantine",
                    "peer": peer,
                }
            )
            continue
        try:
            text = src.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        dest_rel = classify_for_cutover(suffix, text)
        plans.append(
            {
                "src": legacy_rel,
                "dest": dest_rel,
                "action": "move",
            }
        )
    return plans


def _unique_quarantine_path(root: Path, dest: Path) -> Path:
    """Pick a non-colliding quarantine path next to dest."""
    if not dest.exists():
        return dest
    stem = dest.stem
    suffix = dest.suffix
    parent = dest.parent
    n = 1
    while True:
        candidate = parent / f"{stem}.cutover-conflict-{n}{suffix}"
        if not candidate.exists():
            return candidate
        n += 1


def _apply_plan_item(root: Path, item: dict[str, str], *, dry_run: bool) -> str:
    """Apply one plan item. Returns outcome: moved|quarantined|identical|compared_quarantine|skipped."""
    src = root / item["src"]
    dest = root / item["dest"]
    action = item["action"]
    if dry_run:
        log.info(
            "[dry-run] would %s %s → %s",
            action,
            item["src"],
            item["dest"],
        )
        return action
    if not src.is_file():
        log.warning("source missing, skip: %s", item["src"])
        return "skipped"
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        # Compare instead of blind unlink — preserve divergent leftovers.
        src_hash = _file_digest(src)
        dest_hash = _file_digest(dest)
        if src_hash and dest_hash and src_hash == dest_hash:
            src.unlink()
            log.info("dest identical; removed leftover %s", item["src"])
            return "identical"
        alt = _unique_quarantine_path(root, dest)
        shutil.move(str(src), str(alt))
        item["dest"] = vault_rel(root, alt)
        log.info(
            "dest exists and differs; quarantined %s → %s",
            item["src"],
            item["dest"],
        )
        return "compared_quarantine"
    shutil.move(str(src), str(dest))
    return action


def rewrite_graph_docs(
    root: Path,
    mapping: dict[str, str],
    *,
    dry_run: bool,
) -> int:
    """Rewrite brain/graph.json node doc fields using kb/notes → PARA mapping.

    Also remaps any remaining ``kb/notes/…`` docs via classify/peer lookup when
    not present in [mapping].
    """
    graph_path = root / "brain" / "graph.json"
    if not graph_path.is_file():
        return 0

    try:
        data = json.loads(graph_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        log.warning("could not read graph.json: %s", e)
        return 0

    nodes = data.get("nodes")
    if not isinstance(nodes, list):
        return 0

    changed = 0
    for node in nodes:
        if not isinstance(node, dict):
            continue
        doc = node.get("doc")
        if not isinstance(doc, str) or not is_legacy_kb_path(doc):
            continue
        new_doc = mapping.get(doc)
        if new_doc is None:
            suffix = doc[len(LEGACY_KB_NOTES) + 1 :]
            peer = _para_peer_for_suffix(root, suffix)
            if peer:
                new_doc = peer
            else:
                archived = root / "90-Archive" / "_legacy-kb" / suffix
                if archived.is_file():
                    new_doc = f"90-Archive/_legacy-kb/{suffix}"
                else:
                    new_doc = classify_for_cutover(suffix)
        if new_doc != doc:
            node["doc"] = new_doc
            changed += 1

    if changed and not dry_run:
        atomic_write_text(
            graph_path,
            json.dumps(data, indent=2, ensure_ascii=False) + "\n",
        )
        log.info("rewrote %d graph.json doc field(s)", changed)
    elif changed:
        log.info("[dry-run] would rewrite %d graph.json doc field(s)", changed)
    return changed


def emit_cutover_set_doc_ops(
    root: Path,
    mapping: dict[str, str],
    *,
    dry_run: bool,
) -> int:
    """Append set_doc ops so brain replay keeps PARA docs (not kb/notes/)."""
    graph_path = root / "brain" / "graph.json"
    if not graph_path.is_file():
        return 0
    try:
        data = json.loads(graph_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return 0

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    by_node: dict[str, dict[str, Any]] = {}

    # After graph rewrite, emit set_doc for every node with a non-legacy doc.
    for node in data.get("nodes") or []:
        if not isinstance(node, dict):
            continue
        nid = node.get("id")
        doc = node.get("doc")
        if not nid or not isinstance(doc, str) or is_legacy_kb_path(doc):
            continue
        by_node[str(nid)] = {
            "op": "set_doc",
            "ts": when,
            "device": "pc",
            "node": str(nid),
            "doc": doc,
        }

    # Ensure mapping targets win for nodes that still reference old/new paths.
    for old_doc, new_doc in mapping.items():
        for node in data.get("nodes") or []:
            if not isinstance(node, dict):
                continue
            if node.get("doc") in (new_doc, old_doc):
                nid = str(node.get("id") or "")
                if not nid:
                    continue
                by_node[nid] = {
                    "op": "set_doc",
                    "ts": when,
                    "device": "pc",
                    "node": nid,
                    "doc": new_doc,
                }

    final_ops = list(by_node.values())
    if dry_run:
        log.info("[dry-run] would append %d set_doc ops", len(final_ops))
        return len(final_ops)

    for op in final_ops:
        curation_mod.append_op(root, op, device="pc")
    if final_ops:
        log.info("Appended %d set_doc ops after cutover", len(final_ops))
    return len(final_ops)


def _empty_legacy_tree(root: Path, *, dry_run: bool) -> dict[str, int]:
    """Remove leftover files under kb/notes/; leave empty tombstone dir.

    Never deletes ``.sync-conflict-*`` files (leave for doctor / human).
    """
    stats = {"removed_files": 0, "removed_dirs": 0, "kept_sync_conflicts": 0}
    legacy = root / "kb" / "notes"
    if not legacy.is_dir():
        if not dry_run:
            legacy.mkdir(parents=True, exist_ok=True)
            gitkeep = legacy / ".gitkeep"
            if not gitkeep.exists():
                gitkeep.write_text("", encoding="utf-8")
        return stats

    # Collect files then dirs (deepest first)
    files = [p for p in legacy.rglob("*") if p.is_file()]
    for path in files:
        if ".sync-conflict" in path.name:
            stats["kept_sync_conflicts"] += 1
            log.info("keeping sync-conflict (not tombstone-deleted): %s", vault_rel(root, path))
            continue
        if dry_run:
            log.info("[dry-run] would remove leftover %s", vault_rel(root, path))
            stats["removed_files"] += 1
            continue
        path.unlink()
        stats["removed_files"] += 1

    dirs = sorted(
        (p for p in legacy.rglob("*") if p.is_dir()),
        key=lambda p: len(p.parts),
        reverse=True,
    )
    for d in dirs:
        if dry_run:
            stats["removed_dirs"] += 1
            continue
        try:
            d.rmdir()
            stats["removed_dirs"] += 1
        except OSError:
            pass

    if not dry_run:
        legacy.mkdir(parents=True, exist_ok=True)
        gitkeep = legacy / ".gitkeep"
        if not gitkeep.exists():
            gitkeep.write_text("", encoding="utf-8")
    return stats


def run_cutover_kb(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = True,
    apply: bool = False,
    i_have_backup: bool = False,
) -> dict[str, Any]:
    """
    Hard-cutover leftover kb/notes into PARA / quarantine.

    Default dry_run=True. Set apply=True (and i_have_backup=True) to write.
    """
    root = resolve_chronicle_dir(chronicle_dir)

    log.warning("%s", BACKUP_LOUD)

    if apply and not i_have_backup:
        raise ValueError(
            "Refusing --apply without --i-have-backup. "
            "Run `chronicle backup` first (zip outside Syncthing share), then "
            "re-run: chronicle cutover-kb --apply --i-have-backup"
        )

    effective_dry = dry_run or not apply
    if apply and not dry_run:
        effective_dry = False

    plans = plan_cutover(root)
    # Graph docs should point at PARA (peer or move dest), never at quarantine.
    graph_mapping: dict[str, str] = {}
    stats = {
        "moved": 0,
        "quarantined": 0,
        "identical": 0,
        "compared_quarantine": 0,
        "skipped": 0,
        "link_repairs": 0,
    }

    for item in plans:
        action = item["action"]
        graph_mapping[item["src"]] = item.get("peer") or item["dest"]
        try:
            outcome = _apply_plan_item(root, item, dry_run=effective_dry)
        except OSError as e:
            log.warning("failed %s %s: %s", action, item["src"], e)
            stats["skipped"] += 1
            continue
        if outcome == "skipped":
            stats["skipped"] += 1
            continue
        if outcome == "identical":
            stats["identical"] += 1
        elif outcome == "compared_quarantine":
            stats["compared_quarantine"] += 1
        elif outcome == "move":
            stats["moved"] += 1
        elif outcome == "quarantine":
            stats["quarantined"] += 1

        # link_repair: rewrite vault wikilinks from legacy src → PARA target
        link_target = item.get("peer") or item["dest"]
        if not effective_dry and outcome not in ("skipped",):
            try:
                rr = repair_links_after_move(
                    root,
                    item["src"],
                    link_target,
                    log_changelog=True,
                )
                stats["link_repairs"] += rr.replacements
            except OSError as e:
                log.warning("link_repair failed for %s: %s", item["src"], e)
        elif effective_dry:
            stats["link_repairs"] += 0  # counted after apply only

    graph_rewrites = rewrite_graph_docs(root, graph_mapping, dry_run=effective_dry)
    set_doc_ops = emit_cutover_set_doc_ops(
        root, graph_mapping, dry_run=effective_dry
    )
    tombstone = _empty_legacy_tree(root, dry_run=effective_dry)

    when = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    result: dict[str, Any] = {
        "chronicle_dir": str(root),
        "when": when,
        "dry_run": effective_dry,
        "plans": plans if effective_dry else plans[:50],
        "plan_count": len(plans),
        "stats": stats,
        "graph_doc_rewrites": graph_rewrites,
        "set_doc_ops": set_doc_ops,
        "tombstone": tombstone,
        "kept": ["kb/files/", "kb/knowledge.json"],
        "backup_required": BACKUP_LOUD,
        "hint": (
            "This was a dry-run. After `chronicle backup`, apply with: "
            "chronicle cutover-kb --apply --i-have-backup"
            if effective_dry
            else (
                "Applied. kb/notes/ is an empty tombstone; knowledge is PARA-only. "
                "Co-release Android + PC with CONTRACT v1.10."
            )
        ),
    }
    return result
