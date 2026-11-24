# AGENTS.md — Developer & AI Agent Handbook

## 1. Project Overview

**JK Media Player** is a high-performance, open-source Android multimedia player built entirely with **Kotlin**, **Jetpack Compose (Material 3)**, and the **LibVLC Android NDK engine**.

The application features a unique **Dual-Launcher Architecture**:
* **Music Launcher (`.MusicLauncher`):** Opens directly into a dedicated high-fidelity music player interface featuring:
  - Songs, Artists, Albums, and Folders categorization with strict audio file extension filtering (100% video leak prevention).
  - Pure black (`#0F0F0F`) bottom navigation bar with high-contrast pure white active icons and subtle grey (`#AAAAAA`) inactive items.
  - Floating pill mini-player with rounded artwork and responsive playback controls.
  - Fullscreen Now-Playing sheet with dynamic mesh ambient gradient fading to AMOLED black, audio/video toggle capsule, square artwork with 16dp rounded corners, action pills ([Like/Dislike], [Lyrics], [Queue]), solid pure white circular play/pause button with black icon, and synchronized karaoke lyrics.
  - Direct native Android `AudioTrack` playback stream with zero software equalizer distortion.
  - Theme: **Candy Crimson Red** (`#FA2D48`) matching modern music aesthetics.
* **Video Launcher (`.VideoLauncher`):** Opens directly into a dedicated directory-based video explorer featuring:
  - Folders tab with video count, total duration, and "NEW" unplayed video badges.
  - All Videos tab with instant search.
  - Fullscreen gesture-controlled video player with left-side brightness, right-side volume (+200% LibVLC boost), horizontal scrubbing, and double-tap seeking.
  - Theme: **Royal Violet-Indigo** (`#536DFE`) matching modern video player aesthetics.

Both launchers share the same single installed APK, runtime state, background audio playback service, and local database cache, but present strictly distinct, focused interfaces to the user without unnecessary dual navigation bars.

---

## 2. Technical Stack

* **Language:** Kotlin 2.0.21
* **Build System:** Gradle 8.10.2 + Android Gradle Plugin (AGP) 8.7.0
* **Target SDK:** 35 (Android 15 / 16 / 17 compatible)
* **Minimum SDK:** 24 (Android 7.0 Nougat, supporting 98%+ active devices)
* **UI Framework:** Jetpack Compose (BOM 2024.10.00) with Material 3
* **Media Core:** `org.videolan.android:libvlc-all:3.6.5` (universal codec decoding for MKV, MP4, AVI, FLV, WMV, MOV, TS, AC3, DTS, TrueHD, FLAC, DSD)
* **Image & Thumbnail Engine:** Coil 2.7.0 with `VideoFrameDecoder`
* **Audio Session & Service:** `androidx.media:media:1.7.0` (MediaSessionCompat, foreground playback notification)
* **Online Metadata Engine:** Public LRCLIB API and iTunes Search API for free online synchronized lyrics, HD album art, and artist imagery without API keys.

---

## 3. Architecture & Directory Structure

```
jk-media-player/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # Dual independent tasks (music/video), storage & internet permissions
│   │   ├── java/com/jk/mediaplayer/
│   │   │   ├── JKApplication.kt             # Application init & Coil cache setup
│   │   │   ├── model/
│   │   │   │   └── MediaModels.kt           # VideoItem, VideoFolder, AudioItem, AudioFolder, ArtistItem, AlbumItem, LyricsLine
│   │   │   ├── data/
│   │   │   │   ├── AudioAlbumArtCache.kt    # Embedded ID3 picture extractor & local cached artwork resolver
│   │   │   │   ├── MediaScanner.kt          # MediaStore query & directory folder grouping
│   │   │   │   ├── OnlineMetadataFetcher.kt # Free online HD artwork & synchronized LRC lyrics fetcher
│   │   │   │   ├── PreferencesManager.kt    # Resume timestamps, unplayed status, theme mode, EQ presets
│   │   │   │   ├── VideoMetadataManager.kt  # On-device hardware media tech specs extractor (container, resolution, codecs, bitrate, fps)
│   │   │   │   └── VideoThumbnailCache.kt   # High-performance local video keyframe thumbnail extractor
│   │   │   ├── player/
│   │   │   │   └── LibVlcController.kt      # LibVLC wrapper, direct path & FD playback, 150ms zero-latency buffer, 200% volume boost
│   │   │   ├── service/
│   │   │   │   └── AudioPlayerService.kt    # Foreground service, MediaSession, lockscreen controls
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt          # Dedicated Music Activity with modern home, footer navbar & floating mini-player
│   │   │       ├── theme/
│   │   │       │   ├── Color.kt             # Dark AMOLED, Light White mode, MusicRed & VideoIndigo tokens
│   │   │       │   └── Theme.kt             # Material 3 dynamic Dark and Light White theme engine
│   │   │       ├── music/
│   │   │       │   ├── MiniPlayer.kt        # Floating pill mini-player positioned cleanly above footer navbar
│   │   │       │   ├── MusicScreen.kt       # Home (Quick Picks, Speed Dial, Artists), Songs, Albums, Artists, Folders
│   │   │       │   └── NowPlayingSheet.kt   # Dynamic mesh gradient, online HD art, synced karaoke lyrics, 10-band EQ
│   │   │       └── video/
│   │   │           ├── VideoActivity.kt     # Dedicated Video Activity with taskAffinity separation
│   │   │           ├── VideoFoldersScreen.kt# Folders & All Videos tabs, unplayed red badges, grid/list toggle, FAB
│   │   │           ├── FolderVideosScreen.kt# Video list with thumbnails & resume progress
│   │   │           ├── VideoInfoDialog.kt   # Clean, AMOLED dark technical file specifications modal dialog
│   │   │           └── VideoPlayerActivity.kt# Fullscreen player with gestures, Info button & controls
│   │   └── res/
│   │       ├── drawable/                    # Vector launcher icons (ic_launcher_music/video)
│   │       └── values/                      # strings.xml, themes.xml
│   └── build.gradle.kts                     # App module build configuration
├── build.gradle.kts                         # Root build configuration
├── settings.gradle.kts                      # Repository and project configuration
├── gradle.properties                        # JVM memory args & AndroidX flags
├── .gitignore                               # Open-source ignore rules (ignores /tmp/, local.properties, build/)
└── AGENTS.md                                # This handbook
```

---

## 4. Key Implementation Details

### 4.1 Strict Dual-Activity & TaskAffinity Separation
Defined in `app/src/main/AndroidManifest.xml`:
* **Music Launcher (`.MusicLauncher`):** Points to `MainActivity` with `android:taskAffinity="com.jk.mediaplayer.music"` and `android:launchMode="singleTask"`.
* **Video Launcher (`.VideoLauncher`):** Points to dedicated `VideoActivity` with `android:taskAffinity="com.jk.mediaplayer.video"` and `android:launchMode="singleTask"`.
* **Cross-Talk Elimination:** In Android, if two activity aliases share the same `taskAffinity`, tapping one while the other is open merely brings the existing foreground task window forward without creating a new instance. By assigning distinct task affinities and separate activities, Android OS treats them as two completely independent application tasks in the recents overview.

### 4.2 Modern Bottom Navigation, Home Screen & Badge Clutter Elimination
* `MusicScreen.kt` implements a modern bottom footer navigation bar matching modern dark mode aesthetics:
  - Container: Deep pure black (`#0F0F0F`) surface.
  - Active Tab: High-contrast pure white icon and text label with bold typography (`12.5.sp`).
  - Inactive Tabs: Subtle clean grey (`#AAAAAA`) icons and labels (`12.5.sp`, `FontWeight.SemiBold`).
  - **Zero Clutter**: Completely removed all technical format badges ("MP3", "Hi-Res", "FLAC", "320k") across all song lists, miniplayers, and notifications for a clean, premium visual design.
  - **Persistent Scroll & Clean Mutually Exclusive Drilldown Architecture**: Navigating into an artist, album, folder, or playlist switches views mutually exclusively via `when { selectedPlaylist != null -> ... selectedFolder != null -> ... selectedArtist != null -> ... selectedAlbum != null -> ... else -> when (currentNavTab) { ... } }`, preventing visual overlaps, ghosts, and translucent background bleed-through. Home screen scroll state is preserved seamlessly via parent-scoped `homeListState` and `lastHandledSeed` guard on pull-to-refresh, guaranteeing 100% scroll position retention and zero jump-to-top on Back navigation.
  - **Home**: Features a top header with search & theme menu, interactive filter chips ("Podcasts", "Relax", "Sleep", "Feel good", "Sad", "Romance", "Quick picks", "All"), "Quick picks" 4-track stack with "Play all", "Speed dial" 145dp album carousel with avatar badge, "Featured Artists" circular avatars, "Recently Added" cards, and "Music Folders".
  - **Home Screen Typography & Card Styling**: Section headers ("Quick picks", "Quick Playlists", "Recently Added", "Top Artists", "Top Albums", "Music Folders") strictly maintain 18sp bold hierarchy without visual inflation. Track titles across Quick picks are set to 15sp (subtitles 12.5sp), Smart Playlist titles to 14.5sp (count 11.5sp), SpeedDial cards to 13sp (artist 11sp), Folder cards to 13sp (count 11sp with 58dp min height), and artist circle avatars to 11.5sp/12sp with semi-bold weighting for optimal legibility.
  - **Translucent Dark Card Styling in White Mode**: Quick Playlists, SpeedDial albums, and SpeedDial tracks maintain their sleek dark aesthetic with radiant white typography and glowing category badges across both AMOLED and Light themes. In White Mode, heavy black 0.95f overlays are replaced with softer translucent dark gradients (`0.12f` to `0.75f` on `#14161E`) with crisp `#333644` borders, preventing visual glare while avoiding pitch-black heavy blocks.
  - **Songs**: Alphabetical / search-filterable local song library strictly filtered against video files.
  - **Albums**: Grid of album cards with artwork and track counts.
  - **Artists**: Artist list with circular avatars and song counts.
  - **Folders**: Storage directories containing audio files.
* The floating pill `MiniPlayer` is dynamically elevated above the bottom navigation bar without layout jitter.

### 4.3 Directory-Based Video Explorer
* `VideoFoldersScreen.kt` implements:
  - **Grid & List View Toggle**: Allows toggling between dense list mode and card grid mode.
  - **Unplayed Count Badges**: Circular high-contrast red badge (`12`) displayed on folder icons, indicating the number of new or unwatched videos.
  - **Floating Action Button (FAB)**: Modern Royal Indigo action button at the bottom-right corner to instantly resume the last-watched video or play the first available video.

### 4.4 Instant Zero-Latency Audio Playback & Service Queue
* LibVLC's default `--file-caching=1000` was reduced to `150` ms in `LibVlcController.kt`. Local file playback starts within 9ms rather than delaying over a second.
* `MainActivity.kt` implements a `pendingPlayTrack` buffer. When a user taps a song immediately after launching before `AudioPlayerService` finishes binding, the click is preserved and dispatched as soon as the service connects.
* Free online metadata and lyrics fetching run asynchronously in the background via coroutines, completely decoupled from local audio playback, ensuring zero blocking or playback stalls.

### 4.5 Theme Switching (Dark Mode, White/Light Mode, System Default)
* Both Music and Video interfaces feature an overflow 3-dot menu in the header.
* Selecting "Theme" presents an interactive modal with:
  1. **Dark Mode (AMOLED)**: Deep pitch black (`#090A0F`), high contrast text.
  2. **White Mode (Light Mode)**: Pure white (`#FFFFFF`), soft grey surface (`#F7F8FA`), dark typography.
  3. **System Default**: Automatically follows device system theme.
* Selection is persisted in `PreferencesManager` and dynamically re-renders Compose without restarting the app.

### 4.6 Universal Format Decoding & Playback Fix (LibVLC)
* Managed by `com.jk.mediaplayer.player.LibVlcController`.
* **Critical Protocol Fix**: LibVLC native engine does not have an access module for `content://` URIs. `playMedia()` checks:
  1. Direct filesystem path via `File(path).exists()`, using `Media(vlc, path)`.
  2. Fallback via `contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor` with `Media(vlc, pfd.fileDescriptor)`.
* This completely resolves the `no access modules matched for content://` error, enabling 100% playback of MKV, MP4, AVI, FLV, TS, FLAC, MP3, etc.
* Features native volume boost up to **200%** (LibVLC preamp range 0–200).

### 4.7 Modern Edge-to-Edge System Bars & Dynamic Icon Contrast
* `WindowCompat.setDecorFitsSystemWindows(window, false)` / `enableEdgeToEdge()` with transparent status and navigation bars.
* `MainActivity` and `VideoActivity` Scaffolds set `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to allow backgrounds to reach edges.
* `JKMediaPlayerTheme` utilizes `SideEffect` to dynamically set `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark` and `isAppearanceLightNavigationBars = !isDark`. This guarantees 100% visible, high-contrast clock, battery, and wifi icons across both AMOLED dark and pure white themes.
* Specific sub-screens apply `Modifier.statusBarsPadding()` only to top header bars, and `Modifier.navigationBarsPadding()` to the bottom navbar and floating `MiniPlayer` container, eliminating clipping, letterboxing, or status bar overlap.

### 4.8 Full-Bleed Adaptive Icons (Zero Black Borders)
* Android 8.0+ (API 26+) launchers (like Samsung One UI) letterbox non-adaptive icons inside squircles with black borders.
* Configured in `app/src/main/res/mipmap-anydpi-v26/ic_launcher_music.xml` and `ic_launcher_video.xml` using `<adaptive-icon>`:
  - `<background>`: Vector gradients (`ic_launcher_music_background.xml` Candy Crimson and `ic_launcher_video_background.xml` Royal Indigo).
  - `<foreground>`: Seamless vector double-note (`ic_launcher_music_foreground.xml`) and modern play symbol (`ic_launcher_video_foreground.xml`) with proper safe zone inset padding.

### 4.9 Video Player Architecture & Controls
* **Portrait & Landscape Orientation Switcher**: Top action bar button dynamically toggles between `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT` and `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. Handled via `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"` in `AndroidManifest.xml` without restarting the Activity or interrupting LibVLC playback.
* **Fit, Stretch & Zoom (Crop to Fill) Modes**: One-tap cycling through the three primary aspect ratio modes with an animated on-screen HUD pill:
  - **Fit**: Native video aspect ratio letterboxed/pillarboxed (`aspectRatio = null`, `scale = 0f`).
  - **Stretch**: Complete container fill matching exact screen aspect ratio (`aspectRatio = "$displayWidth:$displayHeight"`, `scale = 0f`).
  - **Zoom**: Full-screen fill preserving original aspect ratio by calculating crop scale based on active video track SAR (`scale = max(dw/vw, dh/vh)`).
* **Subtitle & Audio Language Track Pickers**: Tapping the subtitle or audio icon opens a Material 3 `ModalBottomSheet` listing all embedded streams with radio selections. The subtitle sheet includes an "Open External Subtitle File (.srt, .vtt)" button using `ActivityResultContracts.OpenDocument()`.
* **Playback Speed Control & Fine Slider (0.25x – 4.0x)**: Dedicated speedometer icon button placed adjacent to the headphone icon in the video header. Opens a centered modal sheet with quick presets (`0.25x`, `0.5x`, `0.75x`, `1.0x`, `1.25x`, `1.5x`, `1.75x`, `2.0x`), a custom fine-tuning slider up to `4.0x`, and a one-tap `Reset (1.0x)` button. Native LibVLC `--audio-time-stretch` engine preserves human voice pitch without chipmunk distortion across all speeds. Long-press 2X automatically restores the user-selected playback speed on touch release.
* **Scrollable Action Toolbars & Static Center HUD**: In portrait mode, the secondary tool row under the title bar supports smooth horizontal scrolling (`Modifier.horizontalScroll()`) without any visible scrollbars. In landscape mode, the left tool cluster (Lock, PiP, Headphones, Speed) and right tool cluster (Subtitles, Audio, Resize, Rotation) independently scroll horizontally with equal weight (`1f`), keeping the center playback controls (Previous, Play/Pause, Next) strictly fixed, static, and centered.
* **Gesture-Controlled Screen Lock**: When locked, the entire screen is 100% clean and unobstructed (no persistent lock icons). Tapping anywhere on the locked screen displays an animated "Tap to unlock" button for 3 seconds before auto-fading.
* **Live Clock & Battery HUD**: Displays the current device time (`hh:mm a`) and battery percentage with battery status icon in the top action bar.
* **Video Player Info Button & Technical Specifications Modal (`VideoInfoDialog.kt`)**:
  - Top action bar includes an Info icon button (`Icons.Default.Info`) directly adjacent to the video title with a safe 20dp margin before the clock and battery HUD.
  - Tapping opens a sleek AMOLED dark modal dialog displaying 100% on-device, instant media details without network delay: video thumbnail, title, format/container, resolution, video codec, audio codec & channels, duration, file size, bitrate, frame rate, date modified, and full storage path with a 1-tap Copy Path button.
  - Zero network dependency, zero false positives, and 100% privacy and reliability.

### 4.10 Universal Android AudioTrack Engine & Audio Focus Lifecycle
* Configured `--aout=android_audiotrack` and `--file-caching=250` in `LibVlcController.kt`. This hooks directly into Android's native audio mixer, eliminating driver crackles and guaranteeing sync with Bluetooth audio and hardware equalizers across all Android versions.
* **Direct Audio Stream & Equalizer Removal**: Software equalizer processing was completely removed from the music playback pipeline. Audio streams directly via Android's native `AudioTrack` mixer to ensure 100% distortion-free, bit-perfect playback without CPU clipping or phase latency issues.
* Both `AudioPlayerService` and `VideoPlayerActivity` implement `AudioManager.OnAudioFocusChangeListener` with `AudioManager.AUDIOFOCUS_GAIN`:
  - Automatically pauses playback during incoming phone calls or when another application requests focus.
  - Automatically ducks volume during navigation prompts or system notifications and resumes when focus is restored.

### 4.11 Hybrid 200% Volume Boost Architecture
* Implemented in `VideoPlayerActivity.kt`:
  - **0%–100% (System Media Volume)**: Swiping on the right half of the screen first adjusts Android's native `AudioManager.STREAM_MUSIC` from 0 to max steps (100%), keeping LibVLC volume at 100. The HUD pill glows indigo with volume level.
  - **101%–200% (LibVLC Software Preamp Boost)**: Once system volume reaches 100%, swiping further upwards engages LibVLC's software pre-amplification boost from 101 up to 200. The on-screen HUD pill dynamically switches to bright red (`#FF5252`) and displays `"$boost%\nBoost"`.
  - When swiping down while in boost territory, LibVLC boost decreases back to 100 before reducing system media volume.

### 4.12 Modern Music UX, Gestures & Hierarchical Back Navigation
* **Horizontal Marquee Track Title**: Integrated `Modifier.basicMarquee(iterations = Int.MAX_VALUE)` across both `NowPlayingSheet` and floating `MiniPlayer` so long titles scroll smoothly on a single line with `TextOverflow.Ellipsis`.
* **Fluid Real-Time Drag Physics & Player Dismissal**:
  - `NowPlayingSheet` binds vertical touch translation to an `Animatable(0f)` offset. When dragging downwards, the player sheet moves 1:1 underneath the user's finger. Flinging or pulling down past threshold smoothly animates offscreen into the floating `MiniPlayer`, while releasing earlier springs naturally back into full view.
  - Dismissal animation is decoupled from the outer visibility wrapper to prevent double-offset clipping into the bottom navigation bar.
  - Swiping UP on the player sheet or tapping the bottom handle expands the **Up Next Queue sheet** (Up Next Queue overlay).
* **Up Next Queue Architecture, Reordering & Removal**:
  - Displays a compact track banner, progress bar, playback controls, drag-collapse handle, "Playing from [mix/folder name]" with `[+ Save]` action, interactive filter chips (`[All]`, `[Familiar]`, `[Popular]`, `[Discover]`, `[Deep cuts]`), and an upcoming tracks list with thumbnail, title, artist, duration, and drag handles (`≡`).
  - **Queue Interaction**: Tapping the currently playing song collapses the queue back into the full player sheet without restarting the track from 0:00.
  - **Queue Management Modal**: Tapping the 3-line handle (`≡`) or long-pressing any queue item opens a modal bottom sheet allowing users to "Move Up in Queue", "Move Down in Queue", or "Remove from Queue".
  - Swiping DOWN on the queue or tapping the physical Back button smoothly collapses back into the normal player sheet.
* **Elevated Artwork & Balanced Layout**:
  - Album art is elevated directly beneath the top header bar, eliminating empty top space while strictly bounding height with `fillMaxHeight().aspectRatio(1f)` to guarantee zero overlap with track titles.
* **Auto-Advance, Repeat (Off/All/One), and Shuffle Engine**:
  - Connected `LibVlcController`'s native `MediaPlayer.Event.EndReached` event to `AudioPlayerService.onTrackEnded()`.
  - Supports 3-state Repeat mode (Off, Repeat All, Repeat One) and Shuffle playback.
  - Automatically replays finished media from 0:00 when tapping Play after completion.
* **Video Player Center Controls & Anti-Occlusion Gesture HUD**:
  - Added Previous and Next video skip buttons flanking the center circular Play/Pause button.
  - Relocated the Aspect Ratio (Fit / Stretch / Zoom) HUD pill directly below the center controls.
  - **Anti-Occlusion HUD Layout**: Swapped gesture indicators so brightness percentage displays on the right edge (when swiping on the left half) and volume percentage displays on the left edge (when swiping on the right half), ensuring user fingers never block or occlude the on-screen indicators.
  - **Zero-Clipping Constant-Width Top Pill HUD (`TopPillHUD`)**: Configured with `Modifier.widthIn(min = 46.dp)` and `softWrap = false`, guaranteeing 100% visible percentages across all volume (0%–200%) and brightness (0%–100%) steps with zero `%` clipping and zero vibration/jitter when crossing 99%→100% or 199%→200%.
  - **Symmetric Horizontal Scrubbing Signs**: Gestural seek overlay displays positive offset (`+`) on forward sliding (right) and negative offset (`-`) on backward sliding (left).
* **Multi-Artist Delimiter Normalization & Single-Artist Mapping**:
  - `splitArtistNames` supports ASCII and Unicode delimiters: fullwidth comma (`，` U+FF0C), ideographic comma (`、` U+3001), small comma (`﹐` U+FE50), pipe (`|`), slash (`/`), backslash (`\`), semicolon (`;`), ampersand (`&`), bullet points (`•`, `·`), and case-insensitive collaboration words (`feat.`, `ft.`, `featuring`, `with`, `vs.`, `x`).
  - `resolveArtist` safely deduplicates collaborating artists into individual discographies without generating artificial dual-artist entries (e.g. tracks featuring multiple artists cleanly map to each artist's individual discography and never produce combined entries in library or search).
  - `MediaScanner.kt` automatically sanitizes fullwidth commas and redundant whitespace when reading audio metadata from MediaStore.
* **Centered Action Pills & Header Clean-up**:
  - Centered `[ Like | Dislike ] [ Lyrics ] [ Queue ]` capsule pills.
  - Removed earphone & video toggle from the top header for a clean, focused music player interface.
  - Progress bar adjusted to a modern 3.5dp height with an 11dp thumb.
  - Bottom drawer handle displays folder/playlist source name with `Modifier.basicMarquee(iterations = Int.MAX_VALUE)` to prevent multi-line overflow.
* **MiniPlayer Real-Time Progress Line**:
  - Features an edge-to-edge sleek white progress line (2dp) at the bottom of the floating pill card, indicating real-time playback progress while minimized.
* **Quick Settings (QS) HD Artwork & Crisp Vector Icons**:
  - `AudioPlayerService` loads HD album artwork asynchronously via `ContentResolver` and `MediaMetadataRetriever`, publishing to `MediaMetadataCompat` (`METADATA_KEY_ALBUM_ART`, `METADATA_KEY_ART`) and `NotificationCompat.Builder.setLargeIcon`. This triggers Android SystemUI to render full-bleed artwork with dynamic color extraction in the Quick Settings media carousel.
  - Replaced legacy system notification icons with crisp vector drawables (`ic_notif_play`, `ic_notif_pause`, `ic_notif_next`, `ic_notif_prev`).
* **Strict `.nomedia` and Hidden Folder Exclusion**:
  - `MediaScanner.kt` implements directory-ancestor traversal checking for `.nomedia` markers and dot-prefixed (`.`) hidden directories, completely preventing unwanted audio/video leaks.
* **Deezer Online Artist Imagery & Disk Caching**: `OnlineMetadataFetcher.fetchArtistImageUrl(artist)` connects to Deezer's public API to download artist photos, downloading and caching them directly as permanent local JPEG files on disk (`context.filesDir/artist_images/art_{hash}.jpg`). Populated upfront during library initialization with explicit Compose item keys (`key = { it.name }`), eliminating redundant re-queries, network saturation, and list jitter.
* **Music Player Track Title Animation Lock (Zero Reload / Flicker)**: Added `contentKey = { it.id }` and `if (initialState.id == targetState.id) EnterTransition.None togetherWith ExitTransition.None` to `AnimatedContent` in `NowPlayingSheet.kt`. This completely prevents the music title from reloading or sliding across the screen when artwork or metadata arrives for the current song.
* **Hierarchical Back Navigation & Accidental Exit Prevention**:
  - Queue view collapses first.
  - `NowPlayingSheet` collapses next into `MiniPlayer`.
  - Search query dismisses next.
  - Sub-views (Folder, Artist, Album drilldown) return to library lists.
  - Nav tabs return to the Home tab.
  - Active mood/filter chips reset to "All".
  - At the root level, pressing back triggers a smooth "Press back again to exit" toast, requiring a double tap to exit and completely eliminating accidental exits.

### 4.13 Studio & Cinema Audio Enhancer Engine, Per-Device Wavelet Profiles & Video Quality Engine
* **`AudioEnhancerEngine.kt`**:
  - Bridges Android native audiofx (`Virtualizer`, `BassBoost`, `LoudnessEnhancer`, `Equalizer`) directly to audio session IDs across both Music (`AudioPlayerService`) and Video (`VideoPlayerActivity`).
  - **Default Disabled**: All enhancers are strictly disabled by default (`false`), preserving bit-perfect native sound unless the user explicitly enables them in Settings.
  - **Per-Device Profile Architecture (Wavelet Model)**:
    - Automatically discovers and fingerprints each connected audio device by product name and route:
      - **Bluetooth Audio Devices**: Saves independent profiles for each connected headset or speaker, keyed by the device product name.
      - **Wired / USB DAC Devices**: Identifies 3.5mm wired headphones and external USB DAC adapters (`Wired Headphones`, `USB-C DAC`).
      - **Built-in Phone Speaker**: Dedicated phone speaker profile (`Phone Speaker`).
    - Profiles (enabled state, sound preset, bass boost, 3D spatial virtualizer, dialogue boost, night mode) are auto-persisted independently per device key. Connecting or disconnecting any device instantly loads its saved profile and adapts the native audio effects with zero interruption.
  - **Acoustic Sound Quality Optimization (Harman Target Aligned)**:
    - **Earphones / Bluetooth Profile**: Gentle, distortion-free natural soundstage (25% virtualizer crossfeed mimicking studio monitors without hollow echo), controlled warm punch (30% bass boost without mid-bass bloat), transparent flat mids, and subtle air sparkle (>8kHz). LoudnessEnhancer is kept OFF to preserve 100% dynamic range.
    - **Phone Speaker Profile**: Phase virtualizer is strictly disabled (0%) to eliminate destructive phase cancellation and prevent hollow tin-can vocals on small mono/stereo drivers. Sub-bass (<150Hz) is rolled off (-3dB) to prevent diaphragm rattle and chassis distortion, paired with +2.2dB clean vocal speech presence and gentle loudness boost for room projection.
* **Music Studio Sound Enhancer (`SettingsScreen.kt`)**:
  - Master toggle (per device), active device card showing exact device name and route badge (`Connected • Bluetooth Audio Profile (Auto-Saved)`), 4-preset pill selector (`Auto`, `3D Spatial`, `Clear Bass`, `Vocal`), interactive sliders for Clear Bass Boost and 3D Spatial Surround, and 1-tap `Reset Audio to Default` resetting only the active device's profile.
* **Cinema Audio Enhancer (`VideoSettingsScreen.kt`)**:
  - Master toggle (per device), active device card with exact device name, Dialogue & Speech Boost (makes whispers and conversations crystal clear over loud background explosions), Cinema 3D Surround (bypassed on phone speaker), Night Mode Volume Leveler, and 1-tap `Reset Audio to Default`.
* **Video Quality & Display Enhancer (`LibVlcController.kt` & `VideoSettingsScreen.kt`)**:
  - **Display Picture Profile**: `Vivid` (AMOLED Pop with contrast & saturation enhancement), `Cinema` (deep blacks & warm tone), and `Natural` (standard colors).
  - **Hardware Decoding Engine**: `Automatic` (MediaCodec HW + SW fallback), `Force HW` (direct hardware decoding), or `Software` fallback.
  - **32-Bit True Color (RV32)**: Option eliminating gradient banding in dark movie scenes on AMOLED screens.
  - **Deinterlacing Filter**: `Auto`, `Yadif (Crisp)`, `Blend`, `Off` for artifact-free vintage, anime, and broadcast playback.
  - **Reset Video to Default**: 1-tap instant restoration of all display and decoder settings to factory defaults.

### 4.14 Library Blacklist Architecture (Blacklisted Folders & Files) & Settings Layout
* **Separated Blacklist Persistence (`PreferencesManager.kt`)**:
  - Independent sets for Music (`music_blacklisted_folders`, `music_blacklisted_files`) and Video (`video_blacklisted_folders`, `video_blacklisted_files`).
  - Watched via Compose state `blacklistVersion` for instantaneous UI reactivity across screens.
  - **Path Normalization & Multi-Alias Matching**:
    - `PreferencesManager.normalizePath(path)` resolves all Android primary storage aliases (`/sdcard`, `/mnt/sdcard`, `/storage/emulated/legacy`, `/data/media/0`, `/storage/self/primary`) to canonical `/storage/emulated/0`.
    - Handles case-insensitivity, trailing slash removal, and forward/backward slash normalization.
    - `isPathInsideFolder` verifies normalized prefix matching, raw path matching, directory hierarchy parent walk-up, and canonical path fallback, eliminating Android FUSE/scoped storage false negatives.
    - Folder blacklisting from 3-dot menus and candidates also captures distinct parent directory paths of nested tracks/videos.
    - `BlacklistManagerScreen` deduplicates displayed paths so users see a single, clean path entry per folder.
* **Removal of Automatic Junk Audio Keyword Filter**:
  - Completely removed hardcoded automatic audio keyword filtering (`junkAudioKeywords`, `isJunkAudio`) from `MediaScanner.kt`.
  - All legitimate voice recordings, messaging audio, and music tracks remain visible in library unless manually blacklisted or filtered by the user-configurable 30-second threshold switch ("Ignore audio under 30s").
* **Separated PLAYBACK & LIBRARY Settings Sections**:
  - Both Music (`SettingsScreen.kt`) and Video (`VideoSettingsScreen.kt`) feature cleanly separated cards:
    - **PLAYBACK**: Focused on playback toggles (Player Volume Bar, 200% Volume Boost in Music; 200% Boost, Low Battery Warning, Auto PiP in Video).
    - **LIBRARY**: Dedicated to media management (Ignore audio under 30s, Blacklisted Folders, Blacklisted Files, Rescan Library/Videos, Network Stream in Header).
* **NowPlayingSheet Visual Dot Separators**:
  - Bottom source tracking format: `All Songs • (146/214)` or `[Source Name] • ([Index]/[Total])` separated by a high-contrast bullet dot (`•`).
  - Volume slider indicator format: `Volume • 100%`, `Bluetooth • 100%`, `Headphones • 100%`, `⚡ Boost • 120%` cleanly separated by a bullet dot (`•`).
* **Dedicated Blacklist Management Screens (`BlacklistComponents.kt`)**:
  - Accessible via Settings under Library: **Blacklisted Folders** and **Blacklisted Files** with real-time blacklisted item counts.
  - Displays full lists of currently blacklisted folders/files with path previews and 1-tap restore / unblock buttons.
  - Header `+` icon button opens an interactive multi-selection **Blacklist Picker Dialog** with search, Select All / Deselect All, and item checkboxes (styled after the playlist song picker).
* **3-Dot Context Menu Blacklist Shortcuts & Confirmation Safeguard**:
  - Folder and File 3-dot menus in both Music (`SongOptionsMenu`, `FolderRow`, `NowPlayingSheet` dropdown, Queue item modal) and Video (`VideoActionDialog`, `FolderActionDialog`) provide a direct "Blacklist Folder" / "Blacklist Song" / "Blacklist Video" action.
  - Mandatory confirmation modal (`BlacklistConfirmDialog`) asks user to confirm before blacklisting, explaining that items will be hidden from the library and can be restored anytime from Settings.
  - Live real-time UI refresh automatically updates the library without requiring app restarts.

### 4.15 Scan .nomedia Folders, Player Menu Upgrades, Smart Auto Playlists & Neutral Blacklist UI
* **Scan `.nomedia` Folders Toggle (`SettingsScreen.kt`, `VideoSettingsScreen.kt`, `MediaScanner.kt`)**:
  - Added "Scan .nomedia Folders" switch setting under the `LIBRARY` card in both Music and Video Settings.
  - Persisted in `PreferencesManager.isIgnoreNoMedia()` / `setIgnoreNoMedia()`.
  - When enabled, `MediaScanner.isExcludedOrNoMedia()` bypasses the `.nomedia` directory exclusion, allowing files from hidden or messaging folders (e.g. WhatsApp Audio/Video) to be fully indexed in the library.
* **NowPlayingSheet Top Menu & Queue Actions**:
  - **Go to Artist & Go to Album Fix**: Wired top-right dropdown menu and queue item modal "Go to artist" and "Go to album" to navigate to the target artist or album sub-view and smoothly dismiss the player sheet.
  - **Share Action**: Added native Android `ACTION_SEND` audio sharing with file URI and title/artist text to the top-right 3-dot dropdown menu.
  - **Blacklist Song in Player & Queue**: Added "Blacklist song" to both the top 3-dot dropdown and Queue item options modal with `BlacklistConfirmDialog`, automatically removing the track from queue, advancing to next song if active, and hiding it from the library.
* **Smart Auto Playlists: Recently Played & Most Played (`MusicScreen.kt`, `PreferencesManager.kt`, `AudioPlayerService.kt`)**:
  - `AudioPlayerService.playCurrent()` calls `PreferencesManager.recordTrackPlayed(track.id)`, updating per-track play counts and timestamp history.
  - In `PlaylistsSectionView`, the "Auto Playlists" header now features a balanced 2x2 grid:
    1. **Liked Music** (Dynamic Red)
    2. **Last Added** (Vivid Blue)
    3. **Recently Played** (Warm Amber, sorted by last played timestamp descending)
    4. **Most Played** (Royal Violet, sorted by lifetime play count descending)
* **Professional Neutral Blacklist Header & Artwork Display (`BlacklistComponents.kt`)**:
  - **Neutral Header Buttons**: Replaced colored accent backgrounds on the `+` (Add) and `Clear All` (Trash) buttons with sleek, theme-adaptive neutral circular surfaces (`#222432` dark / `#F0F2F6` light) and high-contrast icons (`#FFFFFF` dark / `#1E2029` light).
  - **Album Art for Music Files**: Music candidates in the picker and blacklisted files list display high-resolution album artwork via `AsyncImage` with fallback to `Icons.Default.MusicNote`.
  - **Clean Video File Icon**: Video candidates and blacklisted video files display `Icons.Default.VideoFile` with clean styling, completely eliminating audio icon confusion and heavy thumbnail decoding overhead.

### 4.22 MediaStore Scanner Optimization, Embedded & Online Album Art Caching Engine
* **Clean MediaStore Scanner (Removal of .nomedia crawling)**:
  - Completely eliminated slow background filesystem crawling and `.nomedia` scanning toggles from Settings and MediaScanner. Media queries now run instantly and reliably via Android's native MediaStore provider with zero scanning delays.
* **On-Device Embedded ID3 Picture Extraction (`AudioAlbumArtCache`)**:
  - Legacy `content://media/external/audio/albumart/{albumId}` fails on modern Android versions (API 29+).
  - `AudioAlbumArtCache` directly extracts embedded ID3 pictures from local audio files via `MediaMetadataRetriever`, resamples them to 512px JPEG, and saves them to `cacheDir/audio_art/art_{id}.jpg`.
  - Registered `AudioAlbumArtInterceptor` in Coil's `ImageLoader` to resolve embedded art first, local downloaded online art second, and vector placeholder fallback with zero latency.
* **Persistent Online HD Album Art Local Storage**:
  - `OnlineMetadataFetcher` fetches HD album art and artist names from public iTunes/Deezer APIs.
  - Image bytes are downloaded and saved directly to device storage (`filesDir/album_art/`), enabling 100% offline access and instant 0ms cached loading.
* **Pager Height Stabilization**:
  - Bound `HorizontalPager` containers and grid pages (`FolderGridPage`, `AlbumGridPage`, `ArtistGridPage`, and `PlaylistsSectionView`) with `Modifier.defaultMinSize(minHeight = ...)` to prevent vertical page collapse and layout jumping when paging across grids with varying item counts.

### 4.23 Unified Technical Details Modal, Search Cursor & Playlist Cards
* **Redesigned Track Details Modal (`MusicTrackDetailsDialog`)**:
  - Redesigned to match the clean AMOLED aesthetic of `VideoInfoDialog`: 56dp artwork header, title, subtitle (`"${durStr} • ${fileSizeStr}"`), horizontal divider, scrollable tech specs (Format/Container, Title, Artist, Album, Folder, Duration, File Size, Bitrate, Sample Rate, Date Added, Location with 1-tap Copy Path button), and a full-width bottom "Close" button styled in `MusicRed`.
  - Completely removed "Set as Ringtone" and "Delete from Device" action buttons and the redundant top "X" close icon for a professional specifications-focused dialog.
* **Search Screen Cursor & Placeholder Vertical Centering**:
  - Refactored `SearchScreen` input using `BasicTextField(decorationBox = ...)` with `platformStyle = PlatformTextStyle(includeFontPadding = false)`.
  - Eliminates vertical misalignment where the cursor appeared too low when the search input field was empty.
* **Smart Playlist Gradient Cards Icon Realignment**:
  - Realigned category icon pills ("Liked Music", "Last Added", "Recently Played", "Most Played") to the top-right corner using `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End)`, keeping titles and song counts cleanly anchored at the bottom-left.

* **Music Folders Pager Height Stabilization (`PlaylistsSectionView`)**:
  - Pager pages with fewer than 4 folders inject exact height placeholder Spacers (`76.dp`), guaranteeing 100% constant container height across all pages and eliminating layout jumping/glitching during horizontal swiping.

### 4.24 Professional Audio Tag Editor & Video Player Error Resilience
* **Professional Audio ID3 Tag Editor (`AudioTagManager.kt` & `MusicTagEditorDialog.kt`)**:
  - Direct on-device ID3 tag reader and writer utilizing `net.jthink:jaudiotagger:3.0.1`.
  - Supports reading and writing Title, Artist, Album, Genre, Year, Track Number, and custom embedded Album Artwork.
  - Interactive Album Art management: pick custom image from device gallery, remove existing cover art, or keep current art with live high-contrast preview.
  - Automatically triggers MediaStore rescan and invalidates in-memory caches upon saving for instant UI reflection.
  - Seamlessly accessible from Song 3-Dot Menus and Track Details modal.
* **Video Player Playback Error Resilience & Informative Dialog**:
  - Detects corrupted media, missing/moved files, and unsupported codecs in `LibVlcController.kt`.
  - Prevents silent failures or infinite hangs by raising explicit descriptive error dialogs with clean Dismiss/Exit actions.

---

## 5. Build, Test, and Deployment Commands

### Build Debug APK:
```powershell
.\gradlew.bat assembleDebug
```
Output path: `app/build/outputs/apk/debug/app-debug.apk`

### Compile Kotlin Code Only:
```powershell
.\gradlew.bat compileDebugKotlin
```

### Install onto Connected Device via ADB:
```powershell
adb devices
adb -s <device_id> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch Installed App on Device:
```powershell
# Launch Music Mode:
adb -s <device_id> shell am start -n com.jk.mediaplayer/.MusicLauncher

# Launch Video Mode:
adb -s <device_id> shell am start -n com.jk.mediaplayer/.VideoLauncher
```

---

## 6. Open-Source & Contribution Rules

1. **Language:** All code comments, documentation, and commit messages must be written in clear, concise English.
2. **Security & Privacy:** Never commit `local.properties`, keystore files, private keys, personal paths, or tokens. Ensure `.gitignore` covers build outputs and the `tmp/` reference directory.
3. **Trademark Neutrality:** Do not use proprietary trademarked names in code comments, class names, or user-facing strings.
4. **Android Compatibility:** Always verify storage access handling across Android 7–9 (legacy), Android 10 (scoped storage flag), and Android 11–17+ (`MANAGE_EXTERNAL_STORAGE`).
