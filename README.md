# DASH for Android TV

DASH is a native Android TV and Google TV app for Android 8.0 or later. It brings Stremio add-ons and a user-supplied IPTV playlist into one remote-controlled library. Supply the streaming sources you want to use.

## Install the APK

[Browse published DASH releases](https://github.com/oussamalahmidiDev/dash-android-tv/releases).

**With a USB drive.** Copy `DASH-2.0.1.apk` to a USB drive. Open it from a file manager on the TV, allow that file manager to install apps if Android prompts you, and select **Install**. DASH appears in the TV app launcher.

**With ADB.** Enable debugging on the TV and connect it to your computer with the option your TV offers. For a TV with **Wireless debugging**, pair and connect using the addresses shown on the TV. The pairing and connection ports can be different. [Android Debug Bridge setup](https://developer.android.com/tools/adb)

```sh
adb devices
adb install -r DASH-2.0.1.apk
```

To upgrade from an earlier DASH or Afterglow version, install the signed DASH APK over the existing installation. The package ID and signing key carry over, so your playlists, add-ons, favorites, and playback progress stay in place.

## Add sources

On a fresh install, DASH opens a welcome screen with Cinemeta and optional IPTV.org and Torrentio presets. Choose the presets you want to install. Torrent playback has a separate opt-in under **Settings → Playback**. Existing DASH installations keep their sources and skip the welcome screen.

DASH 1.4.0 adds Cinemeta automatically on first launch, including after an upgrade. Cinemeta provides movie and series catalogs, search, and title information. Playback requires a separate streaming source. You can remove Cinemeta in **Settings → Stremio add-ons**, and DASH will remember that choice. [Cinemeta manifest](https://v3-cinemeta.strem.io/manifest.json)

1. In **Settings → IPTV**, enter your **M3U playlist URL** and optionally your **XMLTV guide URL**. A gzip-compressed guide is accepted. Select **Import playlist**. Choose **HTTP allowed** first if your provider’s feed or stream uses HTTP.
2. Classify each imported group as **Live TV**, **Movies**, **Series**, or **Hidden**. Groups start as Live TV. DASH groups titles such as `Harbor S01E02` into seasons and leaves other entries in a Series group playable on their own. The guide matches the playlist’s `tvg-id` to the XMLTV channel ID.
3. In **Settings → Stremio add-ons**, enter a compatible add-on’s `manifest.json` URL and select **Install**. Add-on catalog support varies. **Search** includes installed catalogs that advertise search and titles already in the local library.
4. If you want torrent playback, enable it under **Settings → Playback**. The TV can upload pieces to peers during playback. Torrent playback uses temporary internal storage and ends when you close the mini-player or leave DASH.

To play a Cinemeta movie or episode, open its source picker and add a compatible Stremio stream add-on using its manifest URL. Return to the source picker to see what the add-on offers. If you select a torrent with peer-to-peer playback disabled, DASH opens the relevant setting and returns to the same title when you go back.

For an imported playlist, switching **HTTP allowed** or **HTTPS only** takes effect immediately. The source status panel shows progress and the result of add-on installs, playlist imports, and refreshes. Use **Refresh** under IPTV to update the playlist and guide. If either feed fails, DASH keeps the last successfully imported lineup and schedule. Live TV can tune a channel with no guide match. On-demand progress and favorites stay on the TV.

To start over, open **Settings → Playback → Reset DASH** and confirm. Android clears DASH’s app data and cache. Open the app again to run setup.

## Build from source

Use JDK 21, Android SDK Platform 36, and Android SDK Build-Tools 35.0.0 or the version requested by Gradle. Set `ANDROID_HOME` to your SDK or configure it in Android Studio. The Gradle wrapper retrieves the pinned dependencies, including FrostWire’s four Android JNI binaries.

```sh
./gradlew :app:testReleaseUnitTest :app:lintRelease
./gradlew :app:assembleDebug
```

For a signed release, supply the path and password to a keystore with the alias `afterglow` using the `AFTERGLOW_KEYSTORE` and `AFTERGLOW_STORE_PASSWORD` environment variables. The release build expects the key and keystore passwords to match.

```sh
./gradlew :app:assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/`. Android requires the same signing key to install an update over an existing copy and retain its local data. Third-party license texts are bundled under `app/src/main/assets/notices/`.

## Dashboard and player

Home, Movies, and Series open with a featured carousel. Use the previous and next buttons to browse it, or select View details. Home also groups Continue Watching, Recently Watched Channels, ranked shelves, and source catalogs. Shelf numbers follow the imported catalog order. Press Down on a main tab or a Settings section to enter it. A live channel appears after playback starts, and the history stays on the device. In Search, select the search bar and type. Matches appear as you type in grids for movies, series, and live channels. Press Back to dismiss the keyboard, then use the D-pad to move through the grid. Press Select to open a title or tune a channel. Hold Select on a result to add it to favorites or remove it again.

Movie and series details open on a large backdrop with viewing and favorite actions. Scroll down on a series page to select a season or episode.

In the player, the arrow icons seek and the center icon plays or pauses. Select the speaker icon to adjust DASH’s playback volume. Audio tracks and subtitles have separate controls. Press Back to dismiss an open panel.

Press Back during playback to return to browsing. Live TV keeps playing in the mini-player, while a movie or episode pauses and keeps its buffer. Select **Return to playback** to reopen it, or close the mini-player to stop playback.

## Release verification

DASH 2.0.0 was checked on the Android TV 12 ARM64 emulator. The welcome screen installed both optional presets. Volume Down changed Android’s media volume during live HLS, and Volume Up did the same during a torrent movie. A controlled TCP seeder delivered the first decoded MP4 frame before the torrent had fully downloaded. Playback continued after a 30-second skip and after canceling the stop prompt. Confirming Reset DASH cleared app data and returned to the welcome screen on relaunch. The direct UDP test stalled. The Player Pop remote, HDMI audio routing, and real Torrentio startup times require testing on the device.

DASH 1.6.0 was checked on the Android TV 12 ARM64 emulator with live Cinemeta artwork. The D-pad advanced the featured carousel, Back restored the selected slide, and movie details opened the source picker. The D-pad switched to Season 2 of Silo, opened the first episode’s sources, and restored focus to the episode on return.

DASH 1.5.0 was tested on the Android TV 12 ARM64 emulator. A Cinemeta-only movie stayed on the source picker and opened the add-on setup. A test provider returned playable magnet and info-hash torrent streams. An HLS channel kept the same session when minimized, browsed alongside Movies, and restored to full screen. Closing the mini-player stopped playback.

DASH 1.4.0 loaded live Cinemeta catalogs on an Android TV 12 emulator. A fresh install registered Cinemeta automatically. An upgrade from 1.3.0 added it alongside the existing add-on, and Cinemeta stayed removed after relaunch.

DASH 1.3.0 was tested on the Android TV 12 ARM64 emulator (`sdk_google_atv64_arm64`). The D-pad entered all six main tabs, reached three rows of search results, and enabled torrent playback from Settings. Search results appeared as text was entered. Channel, movie, and series favorites survived relaunch. A controlled HTTP HLS channel responded immediately to changes in the saved IPTV HTTP setting. Stremio installs and IPTV imports displayed loading, success, and failure messages. A malformed playlist left the saved lineup available.

DASH 1.2.0 was tested on Android TV 12. The D-pad reached search results after keyboard dismissal. Both demo channels played, appeared in the correct order on Home, and stayed in history after relaunch. Replaying a channel moved it to the front.

The release passed five parser and protocol tests, Android lint with zero errors, APK signature verification, and 16 KB APK and 64-bit native-library alignment checks. It includes ARM and x86 libraries in 32-bit and 64-bit variants.

On the 16 KB Google TV emulator running Android 16, controlled feeds verified M3U and compressed XMLTV import, guide matches and missing-guide fallback, catalog search, Stremio season and episode playback, and preservation of the last good lineup when a refresh failed. HLS, MPEG-DASH, raw HTTP MPEG-TS, and H.264/AAC MP4 produced video. A throttled BitTorrent test produced a first frame and sought while the download was incomplete. The peer session stopped on exit and on backgrounding. Playback also resumed after a force-stop and relaunch.

The signed APK also installed and launched on the Android TV 12 ARM64 emulator. Remote navigation reached Settings and opened the TV keyboard. A source returning HTTP 404 produced a playback error with a retry action.

Android 8.0 is the minimum declared SDK. Verification used emulators and controlled feeds. A physical TV, Android 8 itself, and individual providers' feeds remain unverified.
