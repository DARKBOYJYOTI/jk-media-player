<p align="center">
  <img src="assets/icon.png" width="128" height="128" alt="JK Media Player Icon" style="border-radius: 28px;" />
</p>

<h1 align="center">JK Media Player — Open-Source Music &amp; Video Player for Android</h1>

<p align="center">
  A free, ad-free, open-source music player and video player for Android. Built with Kotlin, Jetpack
  Compose (Material 3) and the LibVLC Android NDK engine, it plays local audio and video offline,
  handles MKV, MP4, AVI, FLV, WMV, MOV, TS and WebM, shows synchronized lyrics, and keeps working
  without accounts, trackers or cloud lock-in.
</p>

<p align="center">
  <a href="https://github.com/darkboyjyoti/jk-media-player/releases/latest"><img src="https://img.shields.io/badge/Release-v1.1.0-FA2D48?style=flat-square&logo=android" alt="Download APK" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-536DFE?style=flat-square" alt="License: GPLv3" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin" alt="Kotlin" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose" /></a>
  <a href="https://www.videolan.org/vlc/download-android.html"><img src="https://img.shields.io/badge/Core-LibVLC%203.7.8-FF8800?style=flat-square&logo=vlcmediaplayer" alt="LibVLC" /></a>
</p>

---

## Overview

Most media players on Android either focus only on music or only on video. **JK Media Player** combines both into a single lightweight package using a **Dual-Launcher Architecture**:
- **Music Launcher (`.MusicLauncher`)**: Opens directly into a clean, modern music experience with synced lyrics, dynamic artwork mesh gradients, and bit-perfect audio output.
- **Video Launcher (`.VideoLauncher`)**: Opens directly into a directory-based video explorer with gesture controls, +200% LibVLC volume boost, hardware decoding, and subtitle support.

Both launchers share the same underlying audio service, playback queue, and local metadata cache without getting in each other's way.

---

## Why JK Media Player

- **One app, two focused launchers.** A music launcher for songs, albums, artists, folders, playlists and synced lyrics, and a separate video launcher for folders and files. Each opens straight into its own experience instead of burying everything behind one mixed home screen.
- **Open source and auditable.** GPLv3, built in public, with no hidden SDKs.
- **No ads, no trackers, no account.** Nothing phones home. Online artwork and lyrics are optional and off the critical path.
- **Offline first.** The library is read through Android MediaStore, so playback starts without a network.
- **Universal decoding.** LibVLC handles the containers and codecs that hardware decoders reject, with automatic software fallback.
- **Made for big local libraries.** Blacklist folders and files, ignore very short clips, resume where you stopped, and scan without duplicate entries.

---

## Key Features

### 🎵 Music Experience
* **Audio-First Design**: Strict file filtering prevents video files from cluttering your music library.
* **Now-Playing Sheet**: Dynamic mesh ambient gradient that matches album art, circular play/pause button, and 1:1 real-time drag physics.
* **Synchronized Lyrics**: Free online synchronized karaoke lyrics via public LRCLIB integration, with tap-to-seek support.
* **Bit-Perfect Playback**: Direct native Android `AudioTrack` output stream with zero software DSP distortion.
* **Up Next Queue**: Reorder, remove, or jump to tracks on the fly with drag handles.
* **Built-in Ringtone Maker**: Fast audio trimmer with milliseconds precision that outputs clean `.mp3` files directly to your system ringtone library.

### 🎬 Video Experience
* **Universal Codec Decoding**: Powered by LibVLC 3.7.8 for smooth playback of MKV, MP4, AVI, FLV, WMV, MOV, TS, WebM, and more.
* **Hardware Acceleration**: Full support for MediaCodec hardware decoding with automatic software fallback and live HW/SW mode sync.
* **Intuitive Gestures**:
  - Left edge swipe: Screen brightness.
  - Right edge swipe: Media volume (0–100%) and LibVLC software preamp boost (101–200%).
  - Horizontal swipe: Scrubbing with high-precision time preview.
  - Double tap: ±10s seeking.
* **Aspect Ratio & Zoom**: One-tap toggling between Fit, Stretch, and Cropped plus smooth pinch-to-zoom while keeping subtitles anchored on screen.
* **Multi-Script Subtitles & Audio**: Load embedded `.ass` / `.ssa` / `.srt` tracks (including Hindi/Devanagari, Bengali, Arabic, CJK via Fontconfig with non-blocking background font caching) or pick external `.srt` / `.vtt` files.
* **Picture-in-Picture & Background Audio**: Seamless PiP with optional Auto-PiP toggle in Settings, plus background audio playback with system media controls.
* **Picture Profiles**: Native LibVLC Standard, Vivid, and Cinema color profiles with automatic software pipeline handoff.
* **Quick Action Menus & Onboarding**: Long-press bottom action sheets across folders, videos, and search, guided onboarding with Music/Video blacklisting, and a daily GitHub Releases update checker.
* **Media Info Dialog**: Instant on-device hardware specs showing container, video/audio codecs, resolution, bitrate, and frame rate without network calls.

---

## Tech Stack

| Component | Technology |
| :--- | :--- |
| **Language** | Kotlin 2.2.10 |
| **UI Framework** | Jetpack Compose (BOM 2024.10.00) + Material 3 |
| **Media Core** | LibVLC Android NDK (`org.videolan.android:libvlc-all:3.7.8`) |
| **Build Toolchain** | Android Gradle Plugin 8.10.1 / Gradle 8.11.1 |
| **Image Loading** | Coil 2.7.0 (with `VideoFrameDecoder`) |
| **Media Service** | AndroidX Media (`MediaSessionCompat`, Foreground Playback Service) |
| **SDK Levels** | Compile SDK: Android 16 (API 36) / Target SDK: Android 15 (API 35) / Min SDK: Android 7.0 (API 24) |

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (or newer)
- JDK 17 or higher
- Android SDK with Platform 36

### Build Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/darkboyjyoti/jk-media-player.git
   cd jk-media-player
   ```

2. **Build Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Build Optimized Release APK:**
   ```bash
   ./gradlew assembleRelease
   ```
   The generated release APKs will be available in:
   `app/build/outputs/apk/release/`

---

## Privacy & Permissions

JK Media Player respects user privacy:
- **No telemetry, trackers, or analytics**.
- **No user accounts or logins required**.
- **No API keys or cloud dependencies**: Metadata and lyrics are fetched through open public endpoints (LRCLIB, iTunes Search API, Deezer).
- Storage permissions are strictly used to discover and play local media files on your device.

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.
See the [LICENSE](LICENSE) file for details.

In plain words:

- **You can use, copy, modify and redistribute** this app, including for your own project.
- **You must keep it open source.** Any derivative work you distribute has to stay under GPLv3 and you have to publish its source.
- **You must keep the credits.** Copyright notices, the licence text and the third-party acknowledgements below stay in place, so the original project is credited.
- **No warranty.** The software is provided as is, without any guarantee.

If you only want permissive reuse and do not care whether forks stay open, a permissive licence (Apache-2.0) would fit better. GPLv3 is the right choice when the goal is: fork it, improve it, but keep it open and keep the credit.

### Third-Party Acknowledgments
- **LibVLC / VideoLAN**: Multimedia engine licensed under LGPL v2.1+ / GPL v3 ([videolan.org](https://www.videolan.org/)).
- **Inter**: Typeface by Rasmus Andersson, licensed under the SIL Open Font License 1.1. Full licence text: [`licenses/Inter-OFL.txt`](licenses/Inter-OFL.txt).
- **LRCLIB**: Synchronized lyrics database.
- **Coil**: Kotlin image loading library.
- **Material Design Icons**: Google Apache 2.0 icons.
- **AndroidX / Jetpack Compose**: Apache 2.0.

---

## Development Note

Parts of this project were developed with the help of AI coding agents. Every change was reviewed, built, and tested by the maintainer before release; the maintainer remains responsible for the code and for any third-party material it uses.

---

## FAQ

**Is JK Media Player free?**
Yes. It is free and open source under GPLv3, with no ads, no subscriptions and no paid tiers.

**Does it collect data?**
No telemetry, no analytics, no accounts. Artwork and lyrics lookups are optional HTTPS requests to public endpoints (LRCLIB, iTunes Search, Deezer) and can be avoided by keeping the device offline.

**Does it play MKV, AVI, FLV, TS and subtitles?**
Yes. Video playback runs on LibVLC, so most containers and codecs decode without conversion, and embedded or external `.srt` / `.vtt` subtitles can be attached.

**Can I build my own version?**
Yes. Clone the repository and run `./gradlew assembleRelease`, or download an APK from the releases page.

**Can I fork it for my own app?**
Yes, under GPLv3: keep the source open, keep the copyright notices and credit this project.

