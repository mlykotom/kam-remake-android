# Agent Instructions (`kam-remake-android`)

This repository is the native Android (`arm64` / `aarch64-android`) port of **KaM Remake** (Knights and Merchants Remake).

## 1. Android CLI (`android`) & Device Workflows

When working on Android tasks, always prefer the **`android` CLI** (`/Users/mlyko/.local/bin/android`) and consult the `android-cli` and `android-device-hub` skills.

- **Emulators (AVDs)**:
  - Check existing/running devices first: `android info`, `android emulator list`, or `adb devices`
  - Start an emulator (waits until booted and ready): `android emulator start <avd_name>`
  - Stop / create / remove an AVD: `android emulator stop <avd_name>`, `android emulator create`, `android emulator remove`
  - Or manage and mirror devices via the **Android Device Hub** sidecar (`android-device-hub` skill).
- **Installing & Running APKs**:
  - Fast delta install: `android install --apks=<path> [--device=<serial>]`
  - Build, deploy, and launch: `android run [--apks=<path>] [--activity=<name>] [--device=<serial>]`
- **Screen Capture, UI Inspection & Input**:
  - Read `references/interact.md` in the `android-cli` skill before using `android layout` or `android screen`.
  - Because KaM Remake renders its UI in OpenGL (`GLSurfaceView` + `gl4es`), `android layout` only sees the host Android view hierarchy; use `android screen capture --device=<serial> [--annotate] -o <file.png>` to inspect in-game UI and **always visually inspect the resulting PNG**.
  - Resolve annotated bounding box coordinates: `android screen resolve --screenshot=<file.png> --string="tap #N"` (e.g. `adb -s <serial> shell input $(android screen resolve --screenshot=<file.png> --string="tap #N")`).
  - For `adb shell input text`, ensure the text field is focused first, replace spaces with `%s`, and escape shell metacharacters.
- **SDK & Documentation**:
  - Manage SDK packages: `android sdk list --all`, `android sdk install <pkg>`, `android sdk update`
  - Search/fetch official Android docs: `android docs search "<query>"`, `android docs fetch <kb_url>`

## 2. Android Port Build & Test Scripts (`android/`)

See [`android/README.md`](android/README.md) for full architecture and build details:

- **Build APK**: `cd android && ./build_apk.sh` (`KAM_BUILD_TYPE=Release KAM_BUNDLE_DATA=1 ./build_apk.sh` for release with bundled game data)
- **Push Game Data**: `cd android && ANDROID_SERIAL=<device> ./push_data.sh`
- **Run & Collect Symbolized Logs**: `cd android && ANDROID_SERIAL=<device> ./run_test.sh`
- **End-to-End Smoke Test**: `cd android && ANDROID_SERIAL=<device> ./selftest.sh [campaigns|campaigns-all|maps|all]`
- **FPC 3.2.2 Compatibility**: The Android engine (`android/pascal/kamcore.lpr` -> `libkamcore.so`) compiles with Free Pascal 3.2.2 (`aarch64-android`). Ensure any shared Pascal changes in `src/` compile under FPC 3.2.2 (avoid Delphi-only inline `var` declarations in code compiled by FPC).

## 3. Upstream KaM Remake Rules (`.ai/rules/`)

Follow the upstream project rules in `.ai/rules/` when modifying engine/gameplay code in `src/`:

- [`.ai/rules/agent-rules.md`](.ai/rules/agent-rules.md) — Core principles, workflow, determinism, savegame/replay safety
- [`.ai/rules/project-rules.md`](.ai/rules/project-rules.md) — Deterministic simulation boundary (`KaMRandom`, integer/fixed-point rules, collection order, `Save`/`Load`/`SyncCRC`)
- [`.ai/rules/coding-rules.md`](.ai/rules/coding-rules.md) — Object Pascal coding standards (note: must stay compatible with FPC 3.2.2 for Android)
- [`.ai/rules/styleguide.md`](.ai/rules/styleguide.md) — Naming conventions (`TKM*`, `a` args, `f` fields, `g` globals, `FreeAndNil`)
- [`.ai/rules/comments.md`](.ai/rules/comments.md) — Commenting style guide
- [`.ai/rules/project-layout.md`](.ai/rules/project-layout.md) — Repository layout (`src/`, `android/`, `Utils/`)
- [`.ai/rules/project.md`](.ai/rules/project.md) — Project overview and architecture
- [`.ai/rules/tools-and-utils.md`](.ai/rules/tools-and-utils.md) — Tools and utilities reference
