# Chronicle documentation

Start with [Setup](SETUP.md) for a new installation, or [Operations](OPERATIONS.md) for an existing vault.

| Guide | Covers |
| --- | --- |
| [Setup](SETUP.md) | Source builds, Mac and Android, models, Syncthing, pairing, demo migration |
| [Architecture](ARCHITECTURE.md) | Runtime ownership, file lifecycle, vault paths, network and encryption boundaries |
| [Operations](OPERATIONS.md) | Processing, watcher, backup, recovery, migration, troubleshooting |
| [Development](DEVELOPMENT.md) | Repo navigation, test/build commands, verification limits, website ownership |
| [PC reference](../chronicle-pc/README.md) | Native vs Python command availability |
| [Desktop](../chronicle-pc/desktop/README.md) | Tauri startup, assets, packaging |
| [Frontend](../chronicle-pc/frontend/README.md) | React UI, development proxy, build and tests |
| [Android](../chronicle-android/README.md) | Capture, SAF, device builds and tests |
| [Contract](../chronicle-pc/CONTRACT.md) | Shared REST and file-format specification, version 1.11 |
| [Security policy](../SECURITY.md) | Security review scope and trust boundaries |

These guides describe the source in this checkout. The [development guide](DEVELOPMENT.md#evidence-and-release-boundaries) separates implementation evidence from installation, device, and live-service qualification.

Historical material: `KnowledgeBase/` is archived; `second-brain-setup-prompt.md` describes a generic vault design and is not the product setup procedure. Agent prompt files under `pipeline/chronicle_pipeline/agents/` and `server/assets/agents/` are runtime inputs, not user guides.
