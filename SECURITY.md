# Security

Chronicle is local-first: no accounts, no product telemetry, no cloud calls
unless you explicitly enable a BYOK model. The vault is plain files on disk.

## Reporting

Open a private security advisory on the repository rather than a public issue.

## Dependency advisories

Dependabot watches `package-lock.json` and `Cargo.lock`. Patch advisories by
updating the lock file; anything that cannot be patched is recorded below with
the reason, and each exception is enforced by a test so it cannot quietly
outlive its justification.

### Accepted risk: GHSA-wrw7-89jp-8q8g — `glib` unsoundness

| | |
| --- | --- |
| Advisory | Unsoundness in `Iterator`/`DoubleEndedIterator` for `glib::VariantStrIter` |
| Severity | Moderate |
| Affected | `glib >= 0.15.0, < 0.20.0` — locked at 0.18.5 |
| Manifest | `chronicle-pc/desktop/src-tauri/Cargo.lock` |
| Status | Accepted; not reachable in shipped builds |

**Why it is not patched.** `glib` is not a direct dependency. It arrives only
through Tauri's Linux backend:

```
glib 0.18.5 <- atk <- gtk 0.18.2 <- libappindicator <- tray-icon <- tauri 2.11.5
```

`gtk 0.18.2` pins `glib ^0.18`, and `tauri 2.11.5` — the latest published
release — still pins `gtk ^0.18`. `cargo update -p glib --precise 0.20.0`
fails to resolve. No version combination fixes this today; it requires Tauri
to move off gtk3-rs.

**Why it is not exploitable here.** Chronicle desktop ships macOS only
(`desktop/README.md`), where Tauri uses WKWebView and the gtk stack is never
compiled — `cargo tree --target aarch64-apple-darwin -i glib` returns nothing.
`bundle.targets` is pinned to `["app", "dmg"]` so no Linux artifact is
produced. Before this was pinned, `targets` was `"all"`, which did permit a
vulnerable Linux build; narrowing it is what makes this exception true rather
than merely likely.

**How the exception is enforced.** `chronicle-pc/tests/test_desktop_bundle_targets.py`
fails if a Linux bundle target is re-enabled while `glib` is still below
0.20.0, and fails once `glib` reaches 0.20.0 so the exception and this section
get removed. The guard retires itself; it does not depend on anyone
remembering why it exists.

**Review trigger.** Revisit when Tauri releases a version built on gtk-rs 0.20
or later, or if Linux support is ever added — whichever comes first. The test
will fail in either case.
