"""pc-to-phone-sync must not extract onto shared-storage roots or an unmarked dir.

The JDK check must read the whole `java -version` stream. Piping it through
`head` drops a real JDK 21 with SIGPIPE (exit 141) under `pipefail`.
"""

from __future__ import annotations

import hashlib
import os
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "pc-to-phone-sync.sh"
REAL_VENV = Path("/Users/bharath/Code/apps/Chronicle/chronicle-pc/.venv")

ROOTS = (
    "/sdcard",
    "/sdcard/",
    "/sdcard/.",
    "/storage/emulated/0",
    "/storage/emulated/0/",
    "/sdcard/Chronicle/..",
)

ADB_STUB = r"""#!/bin/bash
set -u
log="${ADB_LOG:?}"
printf '%s\n' "$*" >> "$log"
cmd="${1:-}"
shift || true
case "$cmd" in
  devices)
    printf 'List of devices attached\n'
    printf 'stub-serial\tdevice\n'
    exit 0
    ;;
  exec-out)
    if [[ -n "${PREFS_XML:-}" && -f "${PREFS_XML}" ]]; then
      cat "${PREFS_XML}"
      exit 0
    fi
    exit 1
    ;;
  push)
    exit 0
    ;;
  shell)
    remote="$*"
    printf 'shell %s\n' "$remote" >> "$log"
    case "$remote" in
      *get-current-user*)
        printf '0\n'
        exit 0
        ;;
    esac
    harness='
      tar() {
        printf "tar %s\n" "$*" >> "$ADB_LOG"
        return "${REMOTE_TAR_STATUS:-0}"
      }
      rm() { return 0; }
      mkdir() { return 0; }
      md5sum() {
        printf "%s  %s\n" "${CONFIG_MD5}" "$1"
      }
      test() {
        local op="$1"
        local path="$2"
        local kind="dir"
        if [[ "$op" == "-f" ]]; then
          kind="file"
        elif [[ "$op" == "-d" ]]; then
          kind="dir"
        else
          return 1
        fi
        grep -Fqx -- "${kind} ${path}" "$PHONE_MANIFEST"
      }
      eval "$1"
    '
    if [[ "$remote" == *"tar -xf"* ]]; then
      # adb often exits 0 when the remote tar fails. Swallow that status.
      bash -c "$harness" _ "$remote"
      exit 0
    fi
    bash -c "$harness" _ "$remote"
    exit $?
    ;;
esac
exit 1
"""

SANDBOX_PROFILE = """(version 1)
(deny default)
(allow process-exec)
(allow process-fork)
(allow signal)
(allow sysctl-read)
(allow file-read*)
(allow file-write*)
(allow file-ioctl)
(allow network*)
(allow mach*)
(allow ipc*)
(allow system*)
(deny file-read* (subpath "/opt/homebrew/opt/openjdk@21"))
(deny file-read* (subpath "/opt/homebrew/Cellar/openjdk@21"))
(deny file-read* (subpath "/usr/local/opt/openjdk@21"))
"""


def _prefs(tree_id: str) -> str:
    return (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        f'    <string name="vault_uri">content://com.android.externalstorage.documents/tree/{tree_id}</string>\n'
        "</map>\n"
    )


def _java_stub(home: Path, first_line: str) -> None:
    bin_dir = home / "bin"
    bin_dir.mkdir(parents=True)
    java = bin_dir / "java"
    # Second write hits SIGPIPE (141) when a reader such as `head -1` closes early.
    java.write_text(
        "#!/bin/sh\n"
        f"echo '{first_line}' >&2\n"
        "sleep 0.4\n"
        "echo 'OpenJDK Runtime Environment' >&2 || exit 141\n"
        "exit 0\n",
        encoding="utf-8",
    )
    java.chmod(0o755)


def _layout(tmp: Path) -> tuple[Path, str]:
    vault = tmp / "vault"
    vault.mkdir()
    cfg = vault / "config.json"
    cfg.write_text('{"layout_version": 2}\n', encoding="utf-8")
    digest = hashlib.md5(cfg.read_bytes()).hexdigest()
    return vault, digest


def _run(
    tmp: Path,
    args: list[str],
    *,
    manifest: list[str] | None = None,
    prefs_xml: str | None = None,
    remote_status: str = "0",
    java_home: Path | None = None,
    java_tool: Path | None = None,
    sandbox: bool = False,
    gradlew: bool = False,
) -> tuple[subprocess.CompletedProcess[str], str, Path]:
    if not REAL_VENV.is_dir():
        raise AssertionError(f"missing Chronicle venv at {REAL_VENV}")
    root = tmp / "tree"
    (root / "scripts").mkdir(parents=True)
    script = root / "scripts" / "pc-to-phone-sync.sh"
    script.symlink_to(SCRIPT)
    (root / "chronicle-pc").mkdir()
    (root / "chronicle-pc" / ".venv").symlink_to(REAL_VENV, target_is_directory=True)
    android = root / "chronicle-android"
    android.mkdir()
    gradle_marker = tmp / "gradlew-ran"
    if gradlew:
        exe = android / "gradlew"
        exe.write_text(
            "#!/bin/sh\necho ran >> \"$GRADLE_MARKER\"\nexit 99\n",
            encoding="utf-8",
        )
        exe.chmod(0o755)

    vault, digest = _layout(tmp)
    bindir = tmp / "bin"
    bindir.mkdir()
    adb = bindir / "adb"
    adb.write_text(ADB_STUB, encoding="utf-8")
    adb.chmod(0o755)
    manifest_path = tmp / "phone-manifest"
    manifest_path.write_text("\n".join(manifest or []) + "\n", encoding="utf-8")
    prefs_path = tmp / "prefs.xml"
    if prefs_xml is not None:
        prefs_path.write_text(prefs_xml, encoding="utf-8")
    log_path = tmp / "adb.log"
    (tmp / "tmpdir").mkdir()

    env = os.environ.copy()
    for key in (
        "JAVA_HOME",
        "CHRONICLE_PHONE_DIR",
        "ANDROID_SERIAL",
        "CHRONICLE_JAVA_HOME_TOOL",
    ):
        env.pop(key, None)
    env.update(
        {
            "PATH": os.pathsep.join(
                [
                    str(bindir),
                    "/usr/bin",
                    "/bin",
                    "/usr/sbin",
                    "/sbin",
                    "/opt/homebrew/bin",
                ]
            ),
            "CHRONICLE_DIR": str(vault),
            "TMPDIR": str(tmp / "tmpdir"),
            "ADB_LOG": str(log_path),
            "PHONE_MANIFEST": str(manifest_path),
            "PREFS_XML": str(prefs_path) if prefs_xml is not None else "",
            "REMOTE_TAR_STATUS": remote_status,
            "CONFIG_MD5": digest,
            "GRADLE_MARKER": str(gradle_marker),
            "VIRTUAL_ENV_DISABLE_PROMPT": "1",
        }
    )
    if java_home is not None:
        env["JAVA_HOME"] = str(java_home)
    if java_tool is not None:
        env["CHRONICLE_JAVA_HOME_TOOL"] = str(java_tool)

    command = ["/bin/bash", str(script), *args]
    if sandbox:
        # Hide this machine's real JDK 21. Otherwise the unmodified script
        # skips a SIGPIPE'd JAVA_HOME and still prints OK: JAVA_HOME.
        profile = tmp / "jdk.sb"
        profile.write_text(SANDBOX_PROFILE, encoding="utf-8")
        command = ["sandbox-exec", "-f", str(profile), *command]

    proc = subprocess.run(
        command,
        cwd=tmp,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    log = log_path.read_text(encoding="utf-8") if log_path.is_file() else ""
    return proc, log, gradle_marker


def _blob(proc: subprocess.CompletedProcess[str]) -> str:
    return proc.stdout + proc.stderr


def test_prefs_primary_empty_relative_does_not_tar(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android"],
        manifest=["dir /sdcard"],
        prefs_xml=_prefs("primary%3A"),
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "prefs: /sdcard" not in blob
    assert "PHONE_DIR=/sdcard" not in blob


def test_prefs_primary_slash_does_not_tar(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android"],
        manifest=["dir /sdcard"],
        prefs_xml=_prefs("primary%3A%2F"),
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "prefs: /sdcard" not in blob
    assert "PHONE_DIR=/sdcard" not in blob


def test_prefs_primary_double_slash_does_not_tar(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android"],
        manifest=["dir /sdcard"],
        prefs_xml=_prefs("primary%3A%2F%2F"),
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "prefs: /sdcard" not in blob
    assert "PHONE_DIR=/sdcard" not in blob


def test_prefs_primary_empty_does_not_fall_through_to_probe(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android"],
        manifest=[
            "dir /sdcard/Chronicle",
            "file /sdcard/Chronicle/config.json",
            "dir /sdcard/Chronicle/_capture",
        ],
        prefs_xml=_prefs("primary%3A"),
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "phone dir from probe" not in blob
    assert "MD5 match" not in blob


@pytest.mark.parametrize("phone_dir", ROOTS)
def test_phone_dir_storage_root_refused(tmp_path: Path, phone_dir: str) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android", "--phone-dir", phone_dir],
        manifest=["dir /sdcard", "dir /storage/emulated/0"],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "refusing phone vault root" in blob


@pytest.mark.parametrize("phone_dir", ROOTS)
def test_phone_dir_storage_root_refused_even_if_forced(tmp_path: Path, phone_dir: str) -> None:
    proc, log, _marker = _run(
        tmp_path,
        [
            "--skip-process",
            "--skip-launch",
            "--skip-android",
            "--force-unmarked-dir",
            "--phone-dir",
            phone_dir,
        ],
        manifest=[
            "dir /sdcard",
            "file /sdcard/config.json",
            "dir /sdcard/_capture",
            "dir /storage/emulated/0",
            "file /storage/emulated/0/config.json",
            "dir /storage/emulated/0/_capture",
        ],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "unknown flag" not in blob
    assert "refusing phone vault root" in blob


@pytest.mark.parametrize("phone_dir", ("/", "/sdcard/.."))
def test_normalized_filesystem_root_refused_when_forced(tmp_path: Path, phone_dir: str) -> None:
    proc, log, _marker = _run(
        tmp_path,
        [
            "--skip-process",
            "--skip-launch",
            "--skip-android",
            "--force-unmarked-dir",
            "--phone-dir",
            phone_dir,
        ],
        manifest=["dir /"],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "unknown flag" not in blob
    assert "refusing phone vault root" in blob
    assert "MD5 match" not in blob


def test_parent_of_emulated_zero_keeps_slash_separators(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        [
            "--skip-process",
            "--skip-launch",
            "--skip-android",
            "--force-unmarked-dir",
            "--phone-dir",
            "/storage/emulated/0/..",
        ],
        manifest=["dir /storage/emulated"],
    )
    blob = _blob(proc)
    assert "unknown flag" not in blob
    assert "storage emulated" not in log
    assert "storage emulated" not in blob
    assert "tar -xf" in log
    assert "-C '/storage/emulated'" in log
    assert "PHONE_DIR=/storage/emulated" in blob
    assert proc.returncode == 0


def test_dotdot_back_to_sdcard_root_is_still_refused(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        [
            "--skip-process",
            "--skip-launch",
            "--skip-android",
            "--force-unmarked-dir",
            "--phone-dir",
            "/sdcard/../sdcard",
        ],
        manifest=["dir /sdcard", "file /sdcard/config.json", "dir /sdcard/_capture"],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "refusing phone vault root" in blob


def test_unmarked_existing_dir_does_not_tar(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android", "--phone-dir", "/sdcard/Notes"],
        manifest=["dir /sdcard/Notes"],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "not a Chronicle vault" in blob


def test_missing_dir_does_not_mkdir_or_tar(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android", "--phone-dir", "/sdcard/Missing"],
        manifest=[],
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "tar -xf" not in log
    assert "mkdir" not in log
    assert "not a Chronicle vault" in blob


def test_unmarked_dir_extracts_when_forced(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        [
            "--skip-process",
            "--skip-launch",
            "--skip-android",
            "--force-unmarked-dir",
            "--phone-dir",
            "/sdcard/Notes",
        ],
        manifest=["dir /sdcard/Notes"],
    )
    assert "unknown flag" not in _blob(proc)
    assert "tar -xf" in log
    assert proc.returncode == 0
    assert "MD5 match" in _blob(proc)


def test_marked_dir_extracts_only_after_remote_status_0(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android", "--phone-dir", "/sdcard/Chronicle"],
        manifest=[
            "dir /sdcard/Chronicle",
            "file /sdcard/Chronicle/config.json",
            "dir /sdcard/Chronicle/_capture",
        ],
        remote_status="0",
    )
    blob = _blob(proc)
    assert proc.returncode == 0
    assert "tar -xf" in log
    assert "__CHRONICLE_RC__" in log
    assert "MD5 match" in blob


def test_remote_tar_failure_is_not_reported_as_md5_match(tmp_path: Path) -> None:
    proc, log, _marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-android", "--phone-dir", "/sdcard/Chronicle"],
        manifest=[
            "dir /sdcard/Chronicle",
            "file /sdcard/Chronicle/config.json",
            "dir /sdcard/Chronicle/_capture",
        ],
        remote_status="7",
    )
    blob = _blob(proc)
    assert "tar -xf" in log
    assert proc.returncode != 0
    assert "MD5 match" not in blob
    assert "extract failed" in blob
    assert "left" in blob


def test_java21_sigpipe_stub_is_accepted(tmp_path: Path) -> None:
    home = tmp_path / "jdk21"
    _java_stub(home, 'openjdk version "21.0.2"')
    missing_tool = tmp_path / "no-such-java-home"
    proc, _log, marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--phone-dir", "/sdcard/Chronicle"],
        manifest=[
            "dir /sdcard/Chronicle",
            "file /sdcard/Chronicle/config.json",
            "dir /sdcard/Chronicle/_capture",
        ],
        java_home=home,
        java_tool=missing_tool,
        sandbox=True,
        gradlew=False,
    )
    blob = _blob(proc)
    assert "OK: JAVA_HOME" in blob
    assert str(home) in blob
    assert "JDK 21 required" not in blob
    assert not marker.exists()
    assert not (tmp_path / "tree" / "chronicle-android" / "gradlew").exists()


def test_java17_is_rejected_before_gradlew(tmp_path: Path) -> None:
    home = tmp_path / "jdk17"
    _java_stub(home, 'openjdk version "17.0.9"')
    missing_tool = tmp_path / "no-such-java-home"
    proc, _log, marker = _run(
        tmp_path,
        ["--skip-process", "--skip-launch", "--skip-push"],
        java_home=home,
        java_tool=missing_tool,
        sandbox=False,
        gradlew=True,
    )
    blob = _blob(proc)
    assert proc.returncode != 0
    assert "JDK 21 required" in blob
    assert not marker.exists()
