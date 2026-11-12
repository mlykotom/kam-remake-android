# KaM Remake for Android

Native **ARM64 (`arm64-v8a`)** Android port of [**KaM Remake**](https://github.com/reyandme/kam_remake) — play *Knights and Merchants: The Shattered Kingdom* and *The Peasants Rebellion* natively on your Android phone or tablet, with **no emulator and no Wine**.

![KaM Remake on Android — Widescreen Gameplay](android/docs/images/gameplay_overview.png)

> [!IMPORTANT]
> **No copyrighted game assets are included in this repository or in release APKs.**
> To play, you must own a legitimate copy of ***Knights and Merchants*** (e.g. from [Steam](https://store.steampowered.com/app/253900/Knights_and_Merchants/) or [GOG.com](https://www.gog.com/en/game/knights_and_merchants_the_peasants_rebellion)) and prepare the game files using the free official [**KaM Remake** installer](https://www.kamremake.com/download/).

---

## Features Beyond the Desktop Version

While preserving 100% of the authentic *KaM Remake* simulation, campaigns, single-player maps, tutorials, Map Editor, replays, music, and cutscene videos, this Android edition adds mobile-first UX and touch controls tailored for phones and tablets:

### 1. "Clash of Clans"-Style Touch Building Placement & Smart Tool Toggle
- **Draggable Footprint Preview:** Selecting a house in the Build panel spawns a live footprint preview in the center of your view instead of placing immediately on your first tap. Drag the footprint directly to position it tile-by-tile, while dragging anywhere else pans the camera.
- **Floating Confirm / Cancel Buttons:** Dedicated **Confirm (`✓`)** and **Cancel (`✗`)** touch buttons float right beneath the building preview. Confirming a house automatically switches to the **Road** tool so you can connect it immediately.
- **Tap-to-Toggle Build Tools:** Tapping an already-selected Road, Field, Winefield, or Demolish button deselects it cleanly, preventing accidental road placement while scrolling.

![Touch Building Placement](android/docs/images/touch_building_placement.png)

### 2. RTS Touch Gestures, 2.5× Troop Direction Selector & Haptic Feedback
- **Zero-Latency Tap & Multi-Gesture Right-Click:** Single tap fires left-click immediately; **double-tap**, **long-press (250 ms)**, or **two-finger tap** triggers right-click; pinch zooms smoothly around your fingers.
- **2.5× Enlarged Troop Direction Flower:** Double-tap-and-hold (or long-press-and-drag) on the battlefield to orient troop formations with a **2.5× scaled direction flower** that stays clearly visible around your fingertip.
- **Tactile Haptics:** Subtle haptic ticks confirm UI clicks, build actions, and right-click commands.

![2.5x Troop Direction Selector](android/docs/images/troop_direction.png)

### 3. Top-Left Touch Speed & Pause Controls
- **One-Tap Game Speed Bar:** Always-accessible **Pause (`⏸`)** and speed buttons (**`x1`**, **`x3`**, **`x6`**, **`x10`** / mission-specific multipliers) sit right next to the game clock in the top-left corner — no `P` or `F5`–`F8` keyboard keys needed. Tapping anywhere on the pause overlay resumes play.

### 4. Widescreen Adaptation, Independent Scrollable Sidebar & Live UI Scaling
- **Independent Sidebar Scaling & Vertical Scroll:** Scale the left sidebar independently (`125%` by default, up to `200%` in *Game Settings*) and **scroll it vertically by touch** whenever build grids, troop panels, or wares exceed phone height — in both Gameplay and the Map Editor.
- **Three-Finger Tap Port Settings:** Tap anywhere with **three fingers** to adjust global in-game interface scaling (`0.5×` – `2.0×`) on the fly while keeping main menus crisp.
- **Camera Cutout Safe Insets & Full Rotation:** Automatically shifts gameplay and editor UI clear of punch-hole cameras and notches, supports landscape/portrait rotation, and works seamlessly with **USB / Bluetooth mice and keyboards**.

![Independent Sidebar Scaling & Settings](android/docs/images/interface_scaling.png)

![Fullscreen Widescreen Main Menu](android/docs/images/main_menu.png)

### 5. Smart Soft Keyboard, Save Quality-of-Life & Built-In Data Importer
- **Viewport Keyboard Shift:** The Android soft keyboard opens only when you tap an editable text box and shifts the screen upward so zoomed sidebar text fields stay visible above the keyboard.
- **Auto-Indexed Saves:** Save filenames auto-increment (`Save 1`, `Save 2`, …), sort naturally, and support loading saves from older *KaM Remake* revisions.
- **In-App Game Data Setup:** Import your *KaM Remake* `.zip` archive or unpacked folder directly through Android's system file picker on first launch — no PC command line or `adb` required on your phone.

![In-App Game Data Setup](android/docs/images/setup_import.png)

---

## Touch Controls Reference

| Touch Gesture / Input | In-Game Action |
| :--- | :--- |
| **Single tap** | Left-click (select unit/building, press UI button, place active road/field) |
| **Tap active build tool again** | Toggle build/road/field/demolish tool off |
| **Drag building preview** | Move building footprint tile-by-tile before confirming (`✓` / `✗`) |
| **One-finger drag on map** | Pan camera (or paint continuously when Road / Field / Editor brush is active) |
| **Double-tap on map** | Right-click (move/attack order; **hold & drag on 2nd tap** to pick troop facing) |
| **Long-press (250 ms)** | Right-click on map or right-clickable UI (Market/Storehouse `±10`, flags; **hold & drag** for troop facing) |
| **Two-finger tap** | Right-click |
| **Pinch / two-finger drag** | Zoom in & out / pan camera |
| **Vertical drag on left sidebar** | Scroll sidebar up & down when taller than the screen |
| **Three-finger tap** | Open Android Interface Scale dialog (`0.5×` – `2.0×`) |
| **Android Back button / gesture** | `Esc` (skip intro video, cancel building placement, close active panel) |
| **USB / Bluetooth Mouse & Keyboard** | Full desktop controls (hover, left/right/middle click, scroll wheel, hotkeys) |

---

## Installation

### Step 1: Buy the Official Game & Prepare KaM Remake Data
1. Purchase ***Knights and Merchants*** officially on [**Steam**](https://store.steampowered.com/app/253900/Knights_and_Merchants/) or [**GOG.com**](https://www.gog.com/en/game/knights_and_merchants_the_peasants_rebellion) (or use your original CD).
   - *Note for Steam users:* Launch *Knights and Merchants* at least once on Steam before running the Remake installer so it detects your installation.
2. Download and run the official [**KaM Remake** installer](https://www.kamremake.com/download/) on your PC (it verifies your original game and generates the combined Remake game folder).
   - *macOS / Linux alternative:* If you already have the full KaM Remake installer `.exe`, you can unpack it with `innoextract`:
     ```sh
     brew install innoextract   # or apt install innoextract
     innoextract -s -d kam_data kam_remake_full_installer.exe
     ```

### Step 2A: Easy Installation on Your Phone (Recommended — No ADB Needed)
1. On your computer, compress your installed **`KaM Remake`** folder (containing `data`, `Campaigns`, `Maps`, `MapsMP`, `Music`, `Sounds`, `Tutorials`) into a `.zip` file (e.g. `KaM_Remake.zip`), and copy it to your Android device (via USB file transfer, Quick Share, Google Drive, etc.).
2. Download the latest **`app-release.apk`** from the [**Releases**](../../releases) page and install it on your Android device.
3. Open **KaM Remake** and tap **Import KaM Remake .zip** (or **Select Unpacked Folder** if you copied the unzipped directory). The app will verify `data/defines/houses.dat`, unpack the assets into its app storage, and start automatically.

### Step 2B: Developer Build from Source (macOS)
Requirements: macOS (Apple Silicon or Intel), `brew install fpc innoextract`, Android SDK with NDK `29.0.13599879` & CMake, JDK 17+.

```sh
git clone --recurse-submodules https://github.com/mlykotom/kam-remake-android.git
cd kam-remake-android

# 1. Build cross-compiler toolchain (FPC aarch64-android, LazUtils, gl4es, OpenAL, vorbis, minimp3 — ~20 min once)
android/toolchain/setup_toolchain.sh

# 2. Extract game data from your KaM Remake installer (for personal bundled APK or ADB push)
android/toolchain/extract_data.sh ~/Downloads/kam_remake_installer.exe

# 3a. Build engine-only Release APK (~28 MB, safe to distribute — contains NO game data)
cd android && KAM_BUILD_TYPE=Release ./build_apk.sh

# 3b. Or build a personal all-in-one APK with your own game data bundled (~850 MB, DO NOT redistribute)
cd android && KAM_BUILD_TYPE=Release KAM_BUNDLE_DATA=1 ./build_apk.sh
```

See [`android/README.md`](android/README.md) for architecture details, `adb` data push (`push_data.sh`), custom release signing (`KAM_KEYSTORE_FILE`), and end-to-end device smoke tests (`selftest.sh`).

---

## System Requirements & Tested Devices

### Minimum Requirements
- **CPU / ABI:** 64-bit ARM (`arm64-v8a` / `aarch64`)
- **OS:** Android 8.0 Oreo (API 26) or newer (targets Android 15 / API 35)
- **Graphics:** OpenGL ES 2.0+ hardware acceleration
- **Storage:** ~1 GB free space for extracted game data

### Tested On
| Device / Environment | OS Version | Notes |
| :--- | :--- | :--- |
| **Google Pixel 9** | Android 15 / 16+ | Physical device — tested full campaign & skirmish gameplay, touch building placement, gestures, haptics, camera cutout insets, audio, and video cutscenes |
| **Android Emulator (`arm64-v8a`)** | Android 15 & 16 (API 35–36) | Tested across phone (`20:9` / `19.5:9`) and foldable/resizable (`Resizable` AVD) form factors |
| **Built-In Content Smoke Suite (`selftest.sh`)** | Android 15 / 16 (`arm64-v8a`) | Automated on-device execution of campaigns, tutorials, single-player maps, AI, OpenGL rendering, and `aarch64` PascalScript mission scripts |

---

## Known Limitations

- **Online Multiplayer is hidden by default:** Upstream *KaM Remake*'s official multiplayer authentication unit is closed-source and not part of the public repository.
- **Cross-platform scripted savegames:** Saves or replays recorded on the 32/64-bit Windows build whose PascalScript mission variables serialize 80-bit x87 `Extended` floats may not load on `arm64` (which uses 64-bit IEEE `Double`). Saves created on Android load normally.

---

## Acknowledgements & Credits

- ***Knights and Merchants: The Shattered Kingdom* & *The Peasants Rebellion*** — Created by **Joymania Entertainment** and published by **TopWare Interactive**. All original game art, sounds, music, and campaigns are © their respective owners.
- **[KaM Remake](https://github.com/reyandme/kam_remake)** — Created and maintained by **Krom**, **Lewin**, **Rey**, **Toxic**, and the [KaM Remake community](https://www.kamremake.com/) ([original README](README.KaM_Remake.md)).
- **Initial Android ARM64 Port Foundation** — [**Vadim Makarov (`Vadim170`)**](https://github.com/Vadim170/kam-remake-android) for the foundational `aarch64-android` Free Pascal port, LCL compatibility shims, `gl4es` & native BASS audio bridge, and reproducible toolchain scripts.
- **Open-Source Libraries** — [Free Pascal](https://www.freepascal.org/) & Lazarus `LazUtils`, [gl4es](https://github.com/ptitSeb/gl4es), [OpenAL Soft](https://openal-soft.org/), `libogg` / `libvorbis`, `minimp3`, [PascalScript](https://github.com/remobjects/pascalscript), and [libVLC for Android](https://code.videolan.org/videolan/vlc-android).

---

## License & Disclaimer ("Use As-Is")

### License
The engine and Android port source code in this repository are licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)** — the same license as upstream *KaM Remake*. See [`LICENSE.txt`](LICENSE.txt) for the full license text.

### Disclaimer
- **Unofficial Community Port:** This project is an independent, non-commercial fan/community port. It is **not** affiliated with, sponsored by, or endorsed by TopWare Interactive, Joymania Entertainment, Valve, GOG, or the upstream *KaM Remake* team (please report Android-specific issues in this repository rather than upstream).
- **Provided "AS IS" Without Warranty:** This software is provided **"AS IS"**, without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement. In no event shall the authors or copyright holders be liable for any claim, damages, data loss, or other liability arising from the use of this software. Use entirely at your own risk.
