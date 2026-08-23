"""Process unprocessed entries: transcribe, vision, file-once journal, flip processed."""

from __future__ import annotations

import logging
from datetime import date
from pathlib import Path

from . import brain as brain_mod
from . import captions as captions_mod
from . import config as config_mod
from . import e2ee as e2ee_mod
from . import llm
from . import ollama as ollama_mod  # embeds / local vision fallback
from . import transcribe as transcribe_mod
from .entries import entry_day, load_all_entries, load_unprocessed, save_entry
from .journal import file_entry, get_filed, is_file_ready
from .lock import vault_process_lock
from .media_paths import MediaPathError, safe_media_path
from .notes import regenerate_daily_for_days
from .paths import resolve_chronicle_dir
from .upcoming import regenerate_upcoming
from .vault_layout import require_layout_version

log = logging.getLogger("chronicle.process")


def _media_path(root: Path, rel: str) -> Path:
    return safe_media_path(root, rel)


def _describe_image(root: Path, image_path: Path, *, model: str | None) -> str | None:
    """Vision via active provider; cloud requires vision_cloud_consent; else Ollama."""
    cfg = config_mod.ensure_config(root)
    pname = llm.provider_name(cfg)
    if llm.is_cloud_provider(pname):
        try:
            llm.require_vision_cloud_consent(
                pname,
                cloud_consent=bool(cfg.llm.cloud_consent),
                vision_consent=bool(cfg.llm.vision_cloud_consent),
            )
        except llm.LlmError as e:
            log.warning("Cloud vision blocked: %s — trying local Ollama", e)
            return ollama_mod.try_describe_image(image_path, model=model)
        provider = llm.try_get_provider(cfg)
        if provider is None:
            return ollama_mod.try_describe_image(image_path, model=model)
        return provider.try_describe_image(image_path, model=model)
    provider = llm.try_get_provider(cfg)
    if provider is None:
        return ollama_mod.try_describe_image(image_path, model=model)
    return provider.try_describe_image(image_path, model=model)


def process_entry(
    root: Path,
    entry,
    *,
    cfg,
    dry_run: bool = False,
    image_captions: dict[str, str] | None = None,
) -> tuple[object, bool]:
    """
    Process a single entry. Returns (entry, changed).
    Fills audio text, collects image captions; does not flip processed here.
    Locked E2EE entries are skipped entirely — never write plaintext into an
    encrypted entry while its vault is locked.
    """
    captions = image_captions if image_captions is not None else {}
    changed = False
    models = cfg.models

    if e2ee_mod.entry_locked(entry, root):
        log.warning(
            "Entry %s is e2ee-encrypted and the vault is locked; "
            "unlock the vault before processing it",
            entry.id,
        )
        return entry, False

    # Transcription
    if (not (entry.text or "").strip()) and entry.audio:
        parts: list[str] = []
        for rel in entry.audio:
            try:
                ap = _media_path(root, rel)
            except MediaPathError as e:
                log.warning("Skipping bad audio path %s: %s", rel, e)
                continue
            if dry_run:
                log.info("[dry-run] would transcribe %s", rel)
                continue
            text = transcribe_mod.transcribe(ap)
            if text:
                parts.append(text)
            else:
                log.info("No transcript for %s (whisper unavailable or empty)", rel)
        if parts and not dry_run:
            entry.text = "\n\n".join(parts).strip()
            changed = True
            log.info("Filled text for %s from audio (%d chars)", entry.id, len(entry.text))

    # Vision describe images
    for rel in entry.images:
        if rel in captions:
            continue
        try:
            ip = _media_path(root, rel)
        except MediaPathError as e:
            log.warning("Skipping bad image path %s: %s", rel, e)
            captions[rel] = ""
            continue
        if dry_run:
            log.info("[dry-run] would vision-describe %s", rel)
            continue
        if not ip.is_file():
            log.warning("Image missing: %s", rel)
            captions[rel] = ""
            continue
        desc = _describe_image(root, ip, model=models.vision)
        captions[rel] = desc or ""
        if desc:
            log.info("Described image %s", rel)
        else:
            log.info("Skipped vision for %s (provider unavailable or no consent)", rel)

    return entry, changed


def _ready_to_mark_processed(entry) -> bool:
    """File-ready: (no audio) OR (text non-empty). Captions best-effort."""
    return is_file_ready(entry)


def run_process(
    chronicle_dir: Path | str | None = None,
    *,
    dry_run: bool = False,
    run_brain: bool = True,
    regen_all_days: bool = False,
) -> dict:
    """
    Process all processed=false entries, mark processed when file-ready,
    file-once into 40-Journal, write derived daily chrome, optionally brain.

    ``regen_all_days`` (used by ``chronicle rebuild``) regenerates derived chrome
    and re-files with amend gate for every entry day.
    """
    root = resolve_chronicle_dir(chronicle_dir)
    with vault_process_lock(root):
        return _run_process(
            root,
            dry_run=dry_run,
            run_brain=run_brain,
            regen_all_days=regen_all_days,
        )


def _run_process(
    root: Path,
    *,
    dry_run: bool,
    run_brain: bool,
    regen_all_days: bool,
) -> dict:
    cfg = config_mod.ensure_config(root)
    require_layout_version(root, cfg=cfg)

    unprocessed = load_unprocessed(root)
    image_captions = captions_mod.load_captions(root)
    days: set[date] = set()
    processed_ids: list[str] = []
    pending_mark: list = []
    filed_results: list[dict] = []

    log.info(
        "Processing %d unprocessed entr%s in %s%s",
        len(unprocessed),
        "y" if len(unprocessed) == 1 else "ies",
        root,
        " (dry-run)" if dry_run else "",
    )

    for entry in unprocessed:
        if e2ee_mod.entry_locked(entry, root):
            log.warning(
                "Skipping locked e2ee entry %s (unlock the vault to process)",
                entry.id,
            )
            continue
        entry, changed = process_entry(
            root, entry, cfg=cfg, dry_run=dry_run, image_captions=image_captions
        )
        days.add(entry_day(entry, fallback_tz=cfg.timezone))
        if not dry_run and changed:
            save_entry(root, entry)

        if not _ready_to_mark_processed(entry):
            log.info(
                "Leaving unprocessed (empty transcript with audio): %s",
                entry.id,
            )
            continue

        if dry_run:
            processed_ids.append(entry.id)
            log.info("[dry-run] would mark processed: %s", entry.id)
        else:
            pending_mark.append(entry)

    if not dry_run and image_captions:
        captions_mod.save_captions(root, image_captions)

    # Flip processed before filing (state: captured → processed → filed)
    if not dry_run:
        for entry in pending_mark:
            entry.processed = True
            save_entry(root, entry)
            processed_ids.append(entry.id)
            log.info("Marked processed: %s", entry.id)

    if regen_all_days:
        all_e = load_all_entries(root, fallback_tz=cfg.timezone)
        for e in all_e:
            days.add(entry_day(e, fallback_tz=cfg.timezone))

    # File-once + derived daily chrome
    written = regenerate_daily_for_days(
        root,
        days,
        image_captions=image_captions,
        vault_mirror=cfg.vault_mirror,
        dry_run=dry_run,
        fallback_tz=cfg.timezone,
    )

    # Retry stuck: processed && !filed (any day). Locked e2ee entries stay
    # unfiled until the vault is unlocked (never file an empty fence).
    if not dry_run:
        for entry in load_all_entries(root, fallback_tz=cfg.timezone):
            if entry.processed and not get_filed(entry) and is_file_ready(entry):
                if e2ee_mod.entry_locked(entry, root):
                    log.warning("Not filing locked e2ee entry %s", entry.id)
                    continue
                fr = file_entry(root, entry, image_captions=image_captions, dry_run=False)
                filed_results.append(fr)
                log.info("Filed stuck entry %s → %s", entry.id, fr.get("action"))

    brain_result = None
    if run_brain and not dry_run:
        brain_result = brain_mod.run_brain(root, dry_run=False)
    elif run_brain and dry_run:
        log.info("[dry-run] would run chronicle brain")

    index_result = None
    if not dry_run:
        from . import index_store

        try:
            index_result = index_store.run_index(root, dry_run=False, force=False)
        except Exception:  # noqa: BLE001 — deliberate: index failure must not undo process
            log.exception("index refresh failed after process")
    elif dry_run:
        log.info("[dry-run] would refresh search index")

    try:
        regenerate_upcoming(root, dry_run=dry_run)
    except Exception:  # noqa: BLE001 — deliberate: Upcoming.md is derived, must not fail process
        log.exception("Upcoming.md regeneration failed after process")

    return {
        "processed": processed_ids,
        "days": sorted(d.isoformat() for d in days),
        "notes_written": [str(p) for p in written],
        "filed": filed_results,
        "dry_run": dry_run,
        "brain": brain_result,
        "index": index_result,
    }
