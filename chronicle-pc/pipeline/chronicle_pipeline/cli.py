"""Chronicle CLI — process, brain, rollup, index, rebuild-markdown-index, topics, serve, export, backup, watch, doctor, rebuild, init-vault-structure, migrate, migrate-kb, migrate-v2, cutover-kb, import-legacy, import-knowledgebase, enrich-kb."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

COMMANDS = (
    "process",
    "brain",
    "rollup",
    "index",
    "rebuild-markdown-index",
    "topics",
    "serve",
    "pair",
    "unpair",
    "pairs",
    "unlock",
    "lock",
    "e2ee-setup",
    "export",
    "backup",
    "watch",
    "doctor",
    "rebuild",
    "init-vault-structure",
    "migrate",
    "migrate-kb",
    "migrate-v2",
    "migrate-kb-para",  # alias for migrate-v2
    "cutover-kb",
    "migrate-journal-v2",
    "import-legacy",
    "import-knowledgebase",
    "enrich-kb",
)


def _setup_logging(verbose: bool = False) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(levelname)s %(name)s: %(message)s",
    )


def _print_result(result: object) -> None:
    if result is None:
        return
    if isinstance(result, (dict, list)):
        print(json.dumps(result, indent=2, default=str))
    else:
        print(result)


def run_rebuild(chronicle_dir: str | Path | None = None, *, dry_run: bool = False) -> dict:
    """Rebuild all derived state: notes, brain, rollup, topics, index + markdown index."""
    from . import brain, index_store, markdown_index, process, rollup, topics
    from .paths import resolve_chronicle_dir

    root = resolve_chronicle_dir(chronicle_dir)
    # Re-process: regenerate dailies for all days (process with no unprocessed still regenerates)
    # Force note regen by running process (marks nothing new) then brain/rollup/topics/index
    p = process.run_process(
        root, dry_run=dry_run, run_brain=False, regen_all_days=True
    )
    b = brain.run_brain(root, dry_run=dry_run)
    r = rollup.run_rollup(root, dry_run=dry_run)
    t = topics.run_topics(root, dry_run=dry_run)
    idx = index_store.run_index(root, dry_run=dry_run, force=True)
    md = markdown_index.rebuild_markdown_index(root, dry_run=dry_run)
    return {
        "process": p,
        "brain": b,
        "rollup": r,
        "topics": t,
        "index": idx,
        "markdown_index": md,
        "dry_run": dry_run,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="chronicle",
        description="Chronicle PC pipeline — process journal folder, build brain, serve dashboard",
    )
    parser.add_argument("--version", action="version", version="chronicle-pipeline 0.1.0")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command")

    def add_dir(p: argparse.ArgumentParser) -> None:
        p.add_argument(
            "--chronicle-dir",
            default=None,
            help="Path to Chronicle folder root (or set CHRONICLE_DIR)",
        )
        p.add_argument("--dry-run", action="store_true", help="Preview without writing")

    for name in COMMANDS:
        backup_help = (
            "Zip vault (excludes index/). Run before migrate; "
            "store the zip outside the Syncthing share"
        )
        p = sub.add_parser(
            name,
            help=backup_help if name == "backup" else f"chronicle {name}",
        )
        if name == "import-legacy":
            p.add_argument("dir", help="Legacy journal directory")
            add_dir(p)
        elif name == "import-knowledgebase":
            from .import_knowledgebase import DEFAULT_KB_SOURCE

            p.add_argument(
                "--source",
                default=None,
                help=(
                    "Path to KnowledgeBase brain.json "
                    f"(default: {DEFAULT_KB_SOURCE})"
                ),
            )
            p.add_argument(
                "--apply",
                action="store_true",
                help="Force chronicle brain after writing ops",
            )
            p.add_argument(
                "--no-apply",
                action="store_true",
                help="Skip brain even if the vault has entries",
            )
            add_dir(p)
        elif name == "migrate-kb":
            from .migrate_kb import DEFAULT_KB_ROOT

            p.add_argument(
                "--kb-root",
                default=None,
                help=f"KnowledgeBase folder (default: {DEFAULT_KB_ROOT})",
            )
            p.add_argument(
                "--source",
                default=None,
                help="Path to KnowledgeBase brain.json (default: <kb-root>/brain.json)",
            )
            p.add_argument(
                "--apply",
                action="store_true",
                help="Force chronicle brain after writing ops",
            )
            p.add_argument(
                "--no-apply",
                action="store_true",
                help="Skip brain after migration",
            )
            add_dir(p)
        elif name in ("migrate-v2", "migrate-kb-para"):
            p.add_argument(
                "--apply",
                action="store_true",
                help=(
                    "Write PARA copies + seed files (default is dry-run). "
                    "Requires --i-have-backup."
                ),
            )
            p.add_argument(
                "--i-have-backup",
                action="store_true",
                help="Confirm you ran `chronicle backup` (zip outside Syncthing share)",
            )
            p.add_argument(
                "--seed-only",
                action="store_true",
                help="Only seed PARA chrome / CLAUDE / skills; skip kb/notes copies",
            )
            add_dir(p)
        elif name == "cutover-kb":
            p.add_argument(
                "--apply",
                action="store_true",
                help=(
                    "Move/quarantine leftover kb/notes into PARA and rewrite "
                    "brain/graph.json docs (default is dry-run). "
                    "Requires --i-have-backup. ALWAYS run `chronicle backup` first."
                ),
            )
            p.add_argument(
                "--i-have-backup",
                action="store_true",
                help=(
                    "Confirm you ran `chronicle backup` "
                    "(zip outside the Syncthing-shared folder)"
                ),
            )
            add_dir(p)
        elif name == "migrate-journal-v2":
            p.add_argument(
                "--apply",
                action="store_true",
                help=(
                    "Apply file-once path cutover to layout_version 2 "
                    "(_capture/, _attachments/, 40-Journal/). Default is dry-run. "
                    "Requires --i-have-backup. Co-release APK + CLI."
                ),
            )
            p.add_argument(
                "--i-have-backup",
                action="store_true",
                help=(
                    "Confirm you ran `chronicle backup` "
                    "(zip outside the Syncthing-shared folder)"
                ),
            )
            add_dir(p)
        elif name == "export":
            p.add_argument("--format", default="chronosflow", help="Export format")
            p.add_argument("path", nargs="?", default=None, help="Output path")
            p.add_argument("--chronicle-dir", default=None)
        elif name == "backup":
            p.add_argument(
                "path",
                nargs="?",
                default=None,
                help=(
                    "Output zip path (default: sibling of vault). "
                    "Prefer a path outside the Syncthing-shared folder"
                ),
            )
            p.add_argument(
                "--force",
                action="store_true",
                help="Overwrite an existing backup zip",
            )
            p.add_argument("--chronicle-dir", default=None)
        elif name == "doctor":
            add_dir(p)
            p.add_argument(
                "--fix",
                "--apply",
                dest="fix",
                action="store_true",
                help=(
                    "Apply JSON sync-conflict repairs + ops compaction. "
                    "Default: report-only (also reports stuck unfiled + "
                    "40-Journal fence/hash issues). Markdown conflicts never auto-merged."
                ),
            )
        elif name == "serve":
            p.add_argument("--port", type=int, default=8765)
            p.add_argument(
                "--host",
                default="127.0.0.1",
                help="Bind address when --no-lan (ignored when LAN is on; then 0.0.0.0)",
            )
            p.add_argument(
                "--lan",
                action=argparse.BooleanOptionalAction,
                default=True,
                help=(
                    "Bind 0.0.0.0, advertise LAN IP, print QR for phone pairing "
                    "(default: on). Use --no-lan for localhost-only."
                ),
            )
            p.add_argument(
                "--no-tls",
                action="store_true",
                help=(
                    "Serve LAN over cleartext http instead of the default "
                    "self-signed https with SPKI pinning (discouraged)."
                ),
            )
            p.add_argument("--chronicle-dir", default=None)
        elif name == "pair":
            p.add_argument("device", help="Device name to pair (e.g. phone, tablet)")
            p.add_argument(
                "--qr/--no-qr",
                dest="qr",
                action="store_true",
                default=True,
                help="Print an ASCII QR with the new device token (default: on)",
            )
            p.add_argument(
                "--show-token",
                dest="show_token",
                action="store_true",
                default=False,
                help="Print the full pairing token (it is a secret)",
            )
        elif name == "unpair":
            p.add_argument("device", help="Device name to revoke")
        elif name == "pairs":
            pass  # list paired devices; no arguments
        elif name in ("unlock", "lock"):
            p.add_argument(
                "--passphrase-env",
                default="CHRONICLE_E2EE_PASSPHRASE",
                help="Env var holding the passphrase for unlock (default: CHRONICLE_E2EE_PASSPHRASE)",
            )
        elif name == "e2ee-setup":
            add_dir(p)
            p.add_argument(
                "--passphrase-env",
                default="CHRONICLE_E2EE_PASSPHRASE",
                help="Env var holding the passphrase (default: CHRONICLE_E2EE_PASSPHRASE)",
            )
            p.add_argument(
                "--disable",
                action="store_true",
                help="Disable e2ee in config.json (existing text_enc blobs stay encrypted)",
            )
            p.add_argument(
                "--rotate",
                action="store_true",
                help=(
                    "Rotate the passphrase: reseals every encrypted entry "
                    "under fresh KDF params (requires the current passphrase)"
                ),
            )
        elif name == "watch":
            p.add_argument("--chronicle-dir", default=None)
            p.add_argument("--debounce", type=float, default=2.0)
        elif name == "index":
            add_dir(p)
            p.add_argument("--force", action="store_true", help="Re-embed everything")
            p.add_argument(
                "--write-markdown",
                action="store_true",
                help=(
                    "Also rewrite _system/index.md (agent shortlist from PARA + "
                    "40-Journal). Same as chronicle rebuild-markdown-index."
                ),
            )
        elif name == "rebuild-markdown-index":
            add_dir(p)
            p.help = (
                "Rewrite _system/index.md from PARA + journal day files "
                "(agent shortlist; sqlite index/ stays RAG SoT)"
            )
        elif name == "init-vault-structure":
            add_dir(p)
            p.add_argument(
                "--refresh-skills",
                action="store_true",
                help=(
                    "Overwrite seed skill bodies + CLAUDE.md / conventions / "
                    "para-cutover that still mention dual-read (create-only is default)"
                ),
            )
        elif name == "enrich-kb":
            add_dir(p)
            p.add_argument(
                "--force",
                action="store_true",
                help="Re-enrich all KB notes even if unchanged",
            )
        else:
            add_dir(p)

    args = parser.parse_args(argv)
    _setup_logging(getattr(args, "verbose", False))

    if not args.command:
        parser.print_help()
        return 0

    try:
        return _dispatch(args)
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        return 130
    except Exception as e:  # noqa: BLE001
        logging.getLogger("chronicle").exception(
            "CLI command failed (%s): %s", type(e).__name__, e
        )
        print(f"error: {type(e).__name__}: {e}", file=sys.stderr)
        return 1


def _dispatch(args: argparse.Namespace) -> int:
    cmd = args.command
    dry = getattr(args, "dry_run", False)
    cdir = getattr(args, "chronicle_dir", None)

    if cmd == "process":
        from .process import run_process

        _print_result(run_process(cdir, dry_run=dry, run_brain=True))
    elif cmd == "brain":
        from .brain import run_brain

        _print_result(run_brain(cdir, dry_run=dry))
    elif cmd == "rollup":
        from .rollup import run_rollup

        _print_result(run_rollup(cdir, dry_run=dry))
    elif cmd == "index":
        from . import markdown_index
        from .index_store import run_index

        idx = run_index(cdir, dry_run=dry, force=getattr(args, "force", False))
        if getattr(args, "write_markdown", False):
            md = markdown_index.rebuild_markdown_index(cdir, dry_run=dry)
            _print_result({**idx, "markdown_index": md})
        else:
            _print_result(idx)
    elif cmd == "rebuild-markdown-index":
        from .markdown_index import rebuild_markdown_index

        _print_result(rebuild_markdown_index(cdir, dry_run=dry))
    elif cmd == "enrich-kb":
        from . import index_store, kb_enrich

        enrich = kb_enrich.run_kb_enrich(
            cdir, force=getattr(args, "force", False)
        )
        idx = index_store.run_index(cdir, dry_run=dry)
        _print_result({**enrich, "index": idx})
    elif cmd == "topics":
        from .topics import run_topics

        _print_result(run_topics(cdir, dry_run=dry))
    elif cmd == "serve":
        from .serve import run_serve

        run_serve(
            cdir,
            host=args.host,
            port=args.port,
            lan=getattr(args, "lan", True),
            tls=False if getattr(args, "no_tls", False) else None,
        )
    elif cmd == "pair":
        import json as _json

        from .pairstore import PairStore
        from .serve import print_ascii_qr, qr_payload_string

        store = PairStore.default_path()
        token = store.add_device(args.device)
        base = "https://<mac-lan-ip>:8765"
        if args.qr:
            print_ascii_qr(base, token=token)
        elif getattr(args, "show_token", False):
            # Explicit opt-in: full payload (contains the secret).
            print(qr_payload_string(base, token=token))
        else:
            masked = f"{token[:6]}…{token[-4:]}"
            print(_json.dumps({"v": 2, "base": base, "token": masked}))
            print("Token masked to protect scrollback; re-run with --show-token")
            print("for the full payload (or scan the --qr version).")
        print(f"Paired device {args.device!r}. Scan in Android Settings → Scan Mac QR.")
        return 0
    elif cmd == "unpair":
        from .pairstore import PairStore

        store = PairStore.default_path()
        if store.remove_device(args.device):
            print(f"Revoked device {args.device!r}.")
        else:
            print(f"No such device: {args.device}")
            return 1
        return 0
    elif cmd == "pairs":
        from .pairstore import PairStore

        devices = PairStore.default_path().list_devices()
        _print_result({"devices": devices})
        return 0
    elif cmd == "unlock":
        import getpass
        import os as _os

        from . import e2ee
        from .paths import resolve_chronicle_dir

        root = resolve_chronicle_dir(cdir)
        phrase = _os.environ.get(args.passphrase_env, "") or getpass.getpass(
            "Chronicle E2EE passphrase: "
        )
        try:
            e2ee.unlock(root, phrase)
        except e2ee.E2eeError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        # CLI unlock is per-process; serve unlocks via POST /auth/e2ee/unlock.
        print("Passphrase verified. (Serve processes must unlock via /auth/e2ee/unlock.)")
        return 0
    elif cmd == "lock":
        from . import e2ee
        from .index_store import purge_locked_entries

        e2ee.lock(cdir)
        purged = purge_locked_entries(cdir)
        suffix = f" Purged {purged} sealed document(s) from the search index." if purged else ""
        print(f"Vault key dropped for this process.{suffix}")
        return 0
    elif cmd == "e2ee-setup":
        import getpass
        import os as _os

        from . import e2ee
        from .paths import resolve_chronicle_dir

        root = resolve_chronicle_dir(cdir)
        if getattr(args, "disable", False):
            block = e2ee.load_e2ee_config(root)
            if block is None:
                print("e2ee is not configured; nothing to disable.")
                return 0
            block["enabled"] = False
            e2ee.save_e2ee_config(block, root)
            print("e2ee disabled (existing text_enc blobs remain encrypted).")
            return 0
        phrase = _os.environ.get(args.passphrase_env, "") or getpass.getpass(
            "New Chronicle E2EE passphrase: "
        )
        confirm = _os.environ.get(args.passphrase_env, "") or getpass.getpass(
            "Confirm passphrase: "
        )
        if phrase != confirm or not phrase:
            print("error: passphrases empty or do not match", file=sys.stderr)
            return 1
        if getattr(args, "rotate", False):
            from .lock import vault_process_lock

            old_phrase = _os.environ.get("CHRONICLE_E2EE_OLD_PASSPHRASE", "") or getpass.getpass(
                "Current passphrase: "
            )
            new_phrase = _os.environ.get(args.passphrase_env, "") or getpass.getpass(
                "New passphrase: "
            )
            confirm = _os.environ.get(args.passphrase_env, "") or getpass.getpass(
                "Confirm new passphrase: "
            )
            if new_phrase != confirm or not new_phrase:
                print("error: passphrases empty or do not match", file=sys.stderr)
                return 1
            with vault_process_lock(root):
                try:
                    stats = e2ee.rotate_passphrase(root, old_phrase, new_phrase)
                except e2ee.E2eeError as err:
                    print(f"error: {err}", file=sys.stderr)
                    return 1
            print(
                f"Passphrase rotated. Resealed {stats['resealed']} entr"
                f"{'y' if stats['resealed'] == 1 else 'ies'}"
                + (
                    f"; skipped {stats['skipped_corrupt']} unreadable file(s)."
                    if stats["skipped_corrupt"]
                    else "."
                )
            )
            print("Phone: unlock with the NEW passphrase — it re-reads params automatically.")
            return 0

        existing = e2ee.load_e2ee_config(root)
        if existing is not None and existing.get("enabled"):
            # Overwriting the block would mint a new salt: every existing
            # text_enc blob (Mac or phone) becomes unreadable forever.
            print(
                "error: e2ee is already enabled for this vault. "
                "Rotating the passphrase requires resealing every entry "
                "(not yet implemented). To start over you must decrypt or "
                "delete all text_enc entries first, then remove the 'e2ee' "
                "key from config.json.",
                file=sys.stderr,
            )
            return 1
        if existing is not None:
            # Previously disabled: re-enable ONLY with the original params.
            # Verifying the check blob proves the passphrase matches the key
            # that sealed existing entries; minting fresh params here would
            # silently orphan them.
            try:
                e2ee.unlock(root, phrase)
            except e2ee.E2eeError as err:
                print(
                    f"error: {err} — this vault has stored e2ee parameters "
                    "from a previous setup; enter the original passphrase.",
                    file=sys.stderr,
                )
                return 1
            e2ee.lock(root)
            existing["enabled"] = True
            e2ee.save_e2ee_config(existing, root)
            print(
                "e2ee re-enabled with the original parameters "
                "(previously sealed entries stay readable)."
            )
            return 0
        block = e2ee.default_e2ee_block(phrase)
        e2ee.save_e2ee_config(block, root)
        print("e2ee enabled. Phone: Settings → Encryption → set the same passphrase.")
        return 0
    elif cmd == "export":
        from .export import run_export

        _print_result(run_export(cdir, format=args.format, path=args.path))
    elif cmd == "backup":
        from .backup import run_backup

        _print_result(
            run_backup(cdir, path=args.path, force=getattr(args, "force", False))
        )
    elif cmd == "watch":
        from .watch import run_watch

        run_watch(cdir, debounce_s=args.debounce)
    elif cmd == "doctor":
        from .doctor import run_doctor

        _print_result(
            run_doctor(cdir, dry_run=dry, fix=getattr(args, "fix", False))
        )
    elif cmd == "rebuild":
        _print_result(run_rebuild(cdir, dry_run=dry))
    elif cmd == "init-vault-structure":
        from .migrate_v2 import seed_vault_chrome
        from .paths import resolve_chronicle_dir
        from .upcoming import regenerate_upcoming

        root = resolve_chronicle_dir(cdir)
        refresh = bool(getattr(args, "refresh_skills", False))
        seeded = seed_vault_chrome(root, dry_run=dry, refresh_skills=refresh)
        upcoming_written = regenerate_upcoming(root, dry_run=dry)
        _print_result(
            {
                "chronicle_dir": str(root),
                "dry_run": dry,
                "refresh_skills": refresh,
                "seeded": seeded,
                "seeded_count": len(seeded),
                "upcoming_written": upcoming_written,
            }
        )
    elif cmd == "migrate":
        from .migrate import run_migrate

        _print_result(run_migrate(cdir, dry_run=dry))
    elif cmd == "import-legacy":
        from .import_legacy import run_import_legacy

        _print_result(run_import_legacy(args.dir, cdir, dry_run=dry))
    elif cmd == "import-knowledgebase":
        from .import_knowledgebase import run_import_knowledgebase

        apply: bool | None = None
        if getattr(args, "apply", False):
            apply = True
        elif getattr(args, "no_apply", False):
            apply = False
        _print_result(
            run_import_knowledgebase(
                cdir,
                source=getattr(args, "source", None),
                dry_run=dry,
                apply=apply,
            )
        )
    elif cmd == "migrate-kb":
        from .migrate_kb import run_migrate_kb

        apply_kb: bool | None = None
        if getattr(args, "apply", False):
            apply_kb = True
        elif getattr(args, "no_apply", False):
            apply_kb = False
        _print_result(
            run_migrate_kb(
                cdir,
                kb_root=getattr(args, "kb_root", None),
                source=getattr(args, "source", None),
                dry_run=dry,
                apply=apply_kb,
            )
        )
    elif cmd in ("migrate-v2", "migrate-kb-para"):
        from .migrate_v2 import run_migrate_v2

        apply_v2 = bool(getattr(args, "apply", False))
        _print_result(
            run_migrate_v2(
                cdir,
                dry_run=dry or not apply_v2,
                apply=apply_v2,
                i_have_backup=bool(getattr(args, "i_have_backup", False)),
                seed_only=bool(getattr(args, "seed_only", False)),
            )
        )
    elif cmd == "cutover-kb":
        from .cutover_kb import run_cutover_kb

        apply_c = bool(getattr(args, "apply", False))
        _print_result(
            run_cutover_kb(
                cdir,
                dry_run=dry or not apply_c,
                apply=apply_c,
                i_have_backup=bool(getattr(args, "i_have_backup", False)),
            )
        )
    elif cmd == "migrate-journal-v2":
        from .migrate_journal_v2 import run_migrate_journal_v2

        apply_j = bool(getattr(args, "apply", False))
        _print_result(
            run_migrate_journal_v2(
                cdir,
                dry_run=dry or not apply_j,
                apply=apply_j,
                i_have_backup=bool(getattr(args, "i_have_backup", False)),
            )
        )
    else:
        print(f"unknown command: {cmd}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
