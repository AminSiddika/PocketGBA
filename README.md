# PocketAdvance

**PocketAdvance** is a pure-Kotlin Game Boy Advance (GBA) emulator for Android built with Jetpack Compose and Material 3. It implements an ARM7TDMI CPU, MMU, scanline PPU, DMA/timers/APU, cartridge GPIO & Game Boy Player rumble, automatic GBA BIOS discovery, metadata & box art scraping, an interactive Shader Manager, 1.5×–8× turbo speed controls, and a full `.cht` Cheat Code Manager.

---

## Key Features

### Pure-Kotlin GBA Emulation Core
- **CPU (`Arm7TdmiCpu`)**: ARM and Thumb instruction sets, IRQ/SWI exception vectors, banked registers, and cycle-accurate HALT/VBlankIntrWait handling.
- **MMU (`GbaMmu`)**: BIOS (16 KB), EWRAM (256 KB), IWRAM (32 KB), I/O, Palette, VRAM (96 KB), OAM, SRAM/EEPROM/Flash64/Flash128 save memory, and 4 DMA + 4 Timer channels.
- **PPU (`GbaPpu`)**: Scanline renderer supporting Modes 0–5, sprites, affine backgrounds, and 240×160 RGBA framebuffers.
- **APU (`GbaApu`)**: Direct Sound FIFO A/B mixing and turbo audio softening above 2× speed.
- **Built-in Homebrew Demo (`HomebrewRomBuilder`)**: Includes *Pixel Quest*, a playable Mode 3 homebrew GBA ROM stored locally so the emulator can be tested immediately without external files.

### GBA Rumble Support
- **Cartridge GPIO Rumble**: Emulates GBA cartridge GPIO ports (`0x080000C4`–`0x080000C8`, bit 3 motor drive used by *Drill Dozer* and *WarioWare: Twisted!*).
- **Game Boy Player Serial Rumble**: Detects serial link rumble control packets (`SIODATA32` at `0x04000120` + `SIOCNT` at `0x04000128`, triggering `IRQ_SERIAL`).
- **Android Haptic Engine (`RumbleHapticEngine`)**: Drives device vibration via `VibratorManager` (Android 12+) / `Vibrator` (Android 7+) with adjustable intensity (`25%`–`100%`), a **Test Rumble** button in Settings, and a live `RUMBLE` badge in the player HUD.

### GBA Metadata & Box Art Scraper
- **Offline Cartridge Header Decoder (`GbaMetadataScraper`)**: Extracts game code (`AGB-XXXX`), region (USA / Japan / Europe / Korea / China), publisher from Nintendo maker codes, CRC32, save hardware, and sensor flags (Rumble, RTC Clock, Gyro/Tilt, Solar Sensor).
- **Online No-Intro & Libretro Scraper**: Matches ROM CRC32 and serial against the Libretro No-Intro GBA DAT (cached 7 days in app storage) and downloads `Named_Boxarts` / `Named_Titles` PNG cover art into local app cache.
- **Library Shelf & Cartridge Inspector**: Displays cover thumbnails, publisher/region/year metadata, hardware badges (`RUMBLE`, `RTC`), a toolbar **Scrape All** button, and a **Cartridge Info** dialog per ROM.

### GBA BIOS Auto (`GbaBiosManager`)
- **Resolution Modes**: Supports `Auto`, `Custom`, and built-in `HLE` BIOS modes.
- **Automatic Discovery**: Scans app-internal and app-scoped external storage (`filesDir`, `getExternalFilesDir`, and `bios/` subfolders) for valid 16 KB (`16,384` bytes) GBA BIOS images with ARM reset vector validation.
- **Archive & Folder Scanning**: Imports `.bin`, `.rom`, or `.zip` archives directly, or scans user-selected folders via Android SAF (`OpenDocumentTree`).
- **CRC32 Verification**: Flags the official Nintendo GBA BIOS (`CRC32 81977335`) with a verified badge and offers a **Skip BIOS Intro** boot option.

### Shaders Manager (`ShaderManagerSheet`)
- Built-in visual presets: Crisp Nearest, LCD3x Grid, CRT Scanlines, Warm GBA SP, Desaturated Original, and Vivid Color Boost.
- Live 240×160 LCD preview with a draggable split-screen before/after comparator.
- Import custom `.agsl`, `.glsl`, `.glslp`, `.json`, or `.ini` shader profiles and pin shaders globally or per ROM.

### Fast-Forward Speed Toggle (1.5× – 8×)
- Dedicated **Speed Toggle** pad beside the D-pad in both **Portrait** and **Landscape** layouts.
- Tap to toggle boost (`1.5×`, `2×`, `3×`, `4×`, `6×`, `8×`), or swipe/long-press to cycle speed steps on the fly.

### Save States (`.ss0` – `.ss9`) & Battery Saves (`.sav`)
- Compressed full-hardware save states (CPU, MMU, APU, PPU framebuffer, GPIO rumble state, thumbnail PNG, and metadata).
- Auto-saves `.ss0` on pause/exit with one-tap **Resume SS0 / SS1** directly from Library cards, plus full multi-slot manager (`.ss1`, `.ss2`, etc.) and `.sav` export/import.

### Cheat Code Manager (`ChtFileParser` & `CheatDecoder`)
- Supports **CodeBreaker / GameShark SP**, **Action Replay v1/v3**, **GameShark v1/v3**, and **Raw** memory codes.
- Imports and exports `.cht` (Libretro / Pizza Boy / VBA / mGBA) and `.zip` cheat packs with duplicate filtering, search, format filters, collapsible pack groups, batch toggling, and live decoded address preview.

---

## Tech Stack
- **Language**: Kotlin 2.4+
- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **Persistence**: Room (`AppDatabase` v3 with migrations) + Jetpack DataStore Preferences
- **Testing**: JUnit 4 + Robolectric (`testDebugUnitTest`)
- **Min / Target SDK**: Android API 24 (`minSdk = 24`) to API 36 (`targetSdk = 36`)
- **Zero Broad Storage Permissions**: Uses Storage Access Framework (`OpenDocument`, `OpenDocumentTree`, `CreateDocument`) and app-scoped storage; only `VIBRATE` and `INTERNET` permissions are declared.

---

## Building & Testing

### Local Gradle Commands
Run the unit and Robolectric test suite:
```bash
gradle :app:testDebugUnitTest --no-daemon
```

Build a Debug APK:
```bash
gradle assembleDebug --no-daemon
```

### GitHub Actions CI
The repository includes `.github/workflows/android-ci.yml`, which runs on pushes, pull requests, and `workflow_dispatch`:
1. Executes `gradle testDebugUnitTest --no-daemon` and uploads JUnit HTML test reports.
2. Builds the Debug APK (`gradle assembleDebug --no-daemon`) and uploads it as the `PocketAdvance-debug-apk` workflow artifact.
3. Publishes the Debug APK to GitHub Releases automatically when pushing a `v*` tag.
