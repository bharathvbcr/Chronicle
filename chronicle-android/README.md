# Chronicle for Android

Capture text, photos, and voice into a user-selected folder. Browse synced Timeline, Notes, and Brain while offline; pair with the Mac for full-vault recall and supported API operations.

[Setup](../docs/SETUP.md) · [Architecture](../docs/ARCHITECTURE.md) · [Contract v1.11](CONTRACT.md)

## First run

1. Install a source-built APK.
2. Select the Syncthing-shared Chronicle data folder with the system folder picker; grant persistent access.
3. Save a text entry. Current captures use `_capture/entries/` and `_attachments/`.
4. Wait for Syncthing, run `process` on the Mac, then wait for the filed journal and Brain to sync back.
5. Optionally scan the Mac's connection QR in Settings for LAN features.

A paired connection is not a complete folder-sync check. If the app loses storage access, re-pick the folder. Current processing uses vault layout **2**; use matching PC and Android builds when migrating an existing vault.

## Screens and storage

| Screen | Purpose |
| --- | --- |
| Capture | Text, type, mood, tags, photo and voice input |
| Timeline | Day-grouped entries, search/filter, edits to unprocessed captures |
| Notes | Editable PARA notes and knowledge; journal browsing and LAN amendments |
| Brain | Offline graph and local browsing, optional Mac recall |
| Portfolio | Resume points from `10-Work/ResumePoints/` |
| Settings | Vault selection, pairing, theme, Health Connect, provider/consent settings |

Knowledge Base maps to `30-Knowledge/`. Notes maps to `00-Inbox/`, `10-Work/`, `20-Personal/`, and `90-Archive/`. Journal uses `40-Journal/` plus generated aggregates. Journal fence amendments go through the Mac API; Android does not rewrite journal files directly through SAF. Derived content and `Upcoming.md` are read-only. Legacy `kb/notes/` is retired.

The app uses SAF rather than a separate capture database. The Mac owns processing/filed state after creation. Brain user edits are per-device curation operations; the Mac produces the derived graph.

## Build

Use JDK 21, the Android SDK required by [app/build.gradle.kts](app/build.gradle.kts), and the checked-in Gradle wrapper. The current configuration declares compile SDK 36 with minor API level 1, target SDK 36, and minimum SDK 26. Treat those as build settings, not a tested-device list.

From this directory:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.chronicle.app/.MainActivity
```

Or install through `./gradlew :app:installDebug`. Configure the SDK locally; do not commit machine-specific settings or credentials.

Release builds require the locally provisioned `keystore.properties`. The Gradle configuration fails release tasks when it is absent; there is no debug-signing fallback for a release. Do not publish a debug APK as a signed production release.

## Verification

```bash
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

These commands do not establish physical camera/microphone behavior, Health Connect permission flows, storage-provider atomicity, Syncthing delivery, or live certificate pairing. Exercise those on devices separately.

## AI and network boundaries

On-device Gemini Nano features depend on device/runtime availability. Ollama LAN uses private-host validation; Grok is optional and requires cloud consent, with credentials in protected preferences. Android does not provide the Mac's Vertex adapter. Full-vault Mac recall requires a reachable paired server.

Optional entry-text encryption is not whole-vault encryption. Read the [architecture boundaries](../docs/ARCHITECTURE.md#data-and-network-boundaries) and [contract](CONTRACT.md) before configuring it.

## Source navigation

Read [AGENTS.md](AGENTS.md), [CLAUDE.md](CLAUDE.md), and `.devcouncil/repo_map.json`. The app lives under `app/src/main/java/com/chronicle/app/`; storage is owned by `VaultRepository` and `SafStorage`, LAN by `net/ServeClient`, cloud by `net/CloudLlmClient`, and on-device AI by `ai/GenAiService`.

For stale notes, missing audio, pairing failures, or lost SAF access, use [troubleshooting](../docs/OPERATIONS.md#troubleshooting).
