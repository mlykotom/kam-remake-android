# Agent Instructions (`kam-remake-android`)

This repository is the native Android (`arm64` / `aarch64-android`) port of **KaM Remake** (Knights and Merchants Remake).

## 1. Android CLI (`android`) & Device Workflows

When working on Android tasks, always prefer the **`android` CLI** (`android`, typically in `$HOME/.local/bin/android`) and consult the `android-cli` and `android-device-hub` skills.

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
- **Fast Native / APK Iteration**:
  - `build_apk.sh` always wipes `build/units_main` and rebuilds `libkamcore.so` from scratch (~75s) due to an FPC 3.2.2 incremental generics bug.
  - To compile only `libkamcore.so`: `./android/build_native.sh`
  - If `libkamcore.so` and JNI libs are already in `android/app/src/main/jniLibs/arm64-v8a/`, package the debug APK in ~10s via `cd android && ./gradlew assembleDebug` and test with `NOBUILD=1 ANDROID_SERIAL=<device> ./run_test.sh`.
- **Game Data (`KAM_DATA`) & Android 14/15 Scoped Storage**:
  - Pass `KAM_DATA=<path/to/kam_remake_assets/app>` (or let scripts default to `$KAM_TOOLCHAIN/kamres/app`) to `push_data.sh` or `build_apk.sh`.
  - Push Game Data: `cd android && KAM_DATA=<path/to/kam_remake_assets/app> ANDROID_SERIAL=<device> ./push_data.sh`
  - On Android 14/15 (`targetSdkVersion 35`), `adb push` creates subdirectories in `/sdcard/Android/data/org.kamremake.android/files` owned by `shell:ext_data_rw` (`0770`), which the app UID (`u0_a*`) cannot traverse (causing *"Game data not found"*). On an emulator, fix ownership after pushing: `adb -s <device> root && adb -s <device> shell "chown -R \$(stat -c %U /data/media/0/Android/data/org.kamremake.android/files):ext_data_rw /data/media/0/Android/data/org.kamremake.android/files" && adb -s <device> unroot`.
- **Run & Collect Symbolized Logs**: `cd android && ANDROID_SERIAL=<device> ./run_test.sh` (engine logs are also written on-device to `/sdcard/Android/data/org.kamremake.android/files/Logs/`)
- **End-to-End Smoke Test**: `cd android && ANDROID_SERIAL=<device> ./selftest.sh [campaigns|campaigns-all|maps|all]`
- **FPC 3.2.2 Compatibility & Upstream Rebases**:
  - The Android engine (`android/pascal/kamcore.lpr` -> `libkamcore.so`) compiles with Free Pascal 3.2.2 (`aarch64-android`, `-MDelphi`).
  - Ensure shared Pascal code in `src/` avoids Delphi-only inline `var` declarations (`var x := ...`, `for var I := ...`) and `System.*` unit scope prefixes (use `SysUtils`, not `System.SysUtils`). When rebasing onto `upstream/master` (`https://github.com/reyandme/kam_remake.git`), scan upstream diffs for inline `var` and `System.` imports.
  - Upstream `.pas` files use Windows CRLF (`\r\n`) line endings and `latin1` encoding. If standard edit tools fail on CRLF, use `python3 android/tools/rep.py <file>` to perform exact replacements while preserving CRLF.

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

## 4. Git Workflow

- **Use Pull Requests (Never Push Directly to `main`)**: Do not commit or push directly to `main`. Always branch from `main`, commit your changes on the branch, push the branch to `origin`, and open a Pull Request against `main` (e.g., `gh pr create`).
- **Monitor CI & Merge**: After opening a PR, monitor the GitHub Actions CI workflow (`.github/workflows/android.yml` — APK build and unit tests, e.g., via `gh pr checks --watch` or `gh run watch`) until all checks pass. If CI fails, diagnose and fix the failure on the PR branch and re-verify. Once CI is green, merge the PR into `main` (e.g., `gh pr merge`) and sync local `main`.
- **Always Commit When Done**: When a task or conversation is complete, always commit your changes to git (while leaving `src/ext/pascalscript_submodule`'s local toolchain patch uncommitted).
