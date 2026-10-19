Spore

A free, ad-free, no-tracking Android torrent client. Built for 2026. Open source, no accounts, no telemetry.

Features

Multi-torrent downloads — add as many as you want, each with independent progress, peers, and controls

Magnet and .torrent support — paste a magnet, open a .torrent file, or tap any magnet in your browser and Spore handles it

Streaming — serve torrent bytes over a local HTTP server to any player that supports HTTP, with Range request support for seeking

Persistence — torrents survive app restart, downloads resume where they left off

WiFi-only mode — pause on mobile data, resume on WiFi automatically

Pause on screen off — battery-friendly option for background downloads

RSS auto-download — subscribe to feeds, auto-add matching torrents with regex filters

Speed limits — global download and upload caps

Dark / AMOLED theme — Material 3 with pure-black option

Saves to public Downloads — files visible in any file manager, gallery, or video player (requires MANAGE_EXTERNAL_STORAGE)

Platform

Min SDK: 26 (Android 8.0)

Target SDK: 37 (Android 17)

ABI: arm64-v8a (primary); add armeabi-v7a or x86_64 if needed

Patches and known limitations

Spore ships a locally-built libtorrent4j native library with a one-line patch that is not in the upstream Maven Central artifact. This is required for Android 11+ arm64 to work at all.

The enum_routes patch

What: src/enum_net.cpp in libtorrent, Android branch of enum_routes.

Change:

#elif defined TORRENT_ANDROID && ANDROID_API >= 24

ec = boost::asio::error::operation_not_supported;

ec.clear();

Why: Android 11+ blocks NETLINK_ROUTE sockets via SELinux. enum_routes can't enumerate routes. Upstream sets operation_not_supported as the "signal" that routes aren't available. That error code corrupts session initialization: torrent_impl ends up partially constructed, and the first call to torrent_handle::status() dereferences a null pointer at offset 0xff0, causing a SIGSEGV on arm64.

Clearing the error and returning an empty vector keeps the session healthy. Routes aren't available — but the session doesn't crash because of it.

Upstream status: PR arvidn/libtorrent#9013 is open. The libtorrent4j maintainer (aldenml) has indicated the patch is not the "correct" fix location (he believes the downstream session code should handle the error gracefully). Until that downstream fix exists, Spore ships the locally-patched binary. The PR remains open, with the full crash evidence in the discussion.

Source: Fork at SimoesCTT/libtorrent4j, branch fix/android-enum-routes.

License: libtorrent is BSD-3-Clause. The patched binary is redistributed under the same license. Copyright belongs to Arvid Norberg and contributors.

slf4j-nop requirement

Ktor's CIO engine depends on SLF4J. On Android, the standard SLF4J providers (slf4j-simple, logback-android) fail to register via ServiceLoader — the META-INF/services descriptor is stripped during APK packaging, and SLF4J falls back to NOP with a warning. The server starts, but its application module (routing setup) dies silently before executing.

Spore uses org.slf4j:slf4j-nop:2.0.13 — a no-op binding that satisfies LoggerFactory.getLogger() without needing a real provider. The server module runs, routes register, HTTP works.

SQLite persistence (no Room)

Room requires KSP, and KSP is incompatible with AGP 9's built-in Kotlin (kotlin.sourceSets error). Spore uses plain SQLiteOpenHelper instead — no annotation processors, no build flags, works on any AGP version forever.

Multi-path streaming fix

StreamCoordinator.readFromDisk resolves the on-disk file path for a given torrent file. libtorrent's files().filePath(index) may or may not include the torrent's top-level folder name, depending on how the torrent was created and how libtorrent stores it. Spore tries three possible layouts:

File(File(saveDir, torrentName), filePath) // nested
File(saveDir, filePath) // direct
File(File(saveDir, torrentName), fileNameOnly) // flattened

Whichever exists first is used. This makes streaming work regardless of the torrent's internal folder structure. Without this, streaming a torrent with a top-level folder produced "file not on disk" errors and the server returned 504.

10-bit HEVC playback

Spore serves raw bytes over HTTP. The player decodes them. Playback of 10-bit HEVC (x265 Main 10) depends entirely on the player's decoder.

Tested working configuration: Cob Player with media3 1.11.1 and NextLib media3ext 1.11.1-0.16.0. NextLib's FFmpeg-based software decoder handles HEVC Main 10 when the device's hardware MediaCodec rejects the profile. On an OPPO CPH2269 (Android 11, arm64, midrange 2021 hardware), 10-bit HEVC plays via software decode.

The media3-exoplayer-ffmpeg Maven artifact does not exist on Maven Central — Media3's FFmpeg extension must be built from source with the NDK. NextLib ships a prebuilt FFmpeg extension that covers this use case without the custom build pipeline.

On players without software HEVC Main 10 support (the stock Android player, some third-party players), 10-bit HEVC files may show a black screen with 0:00 duration. That's the player's decoder rejecting the profile — the file itself is intact and the HTTP stream is serving valid bytes.

Building

Requirements

JDK 17

Android SDK Platform 37 + Build-Tools 36.0.0

Docker (for rebuilding the patched libtorrent4j native library — optional, the prebuilt jar is in app/libs/)

Build the APK

git clone https://github.com/SimoesCTT/spore.git
cd Spore
./gradlew :app:assembleDebug

The APK is at app/build/outputs/apk/debug/app-debug.apk.

Rebuilding the patched libtorrent4j (optional)

If you need to update libtorrent4j or rebuild for a different ABI:

git clone https://github.com/SimoesCTT/libtorrent4j.git
cd libtorrent4j
git checkout fix/android-enum-routes
git submodule update --init --recursive

cd swig/android-build
docker build -t lt4j:latest .
./build-arm64.sh

The built jar is at build/libs/libtorrent4j-android-arm64-2.1.0-39.jar (or similar). Copy it to Spore's app/libs/ and rebuild.

License

Spore is licensed under the MIT License. See LICENSE.

Bundled libtorrent4j native library is BSD-3-Clause, copyright Arvid Norberg and contributors.

Status

Phase 0-6: Complete. Engine, multi-torrent, streaming, persistence, settings, WiFi-only, screen-off pause, RSS.

Phase 7: In progress. Icon, themes, onboarding, README complete. Signed release and F-Droid metadata pending.

Streaming: end-to-end verified on OPPO CPH2269 (Android 11, arm64). 2.86 GB H.264 file streamed from a local Ktor server to Cob Player, with seeking. 10-bit HEVC playback verified via NextLib 0.16.0 software decoding.

Credits

libtorrent by Arvid Norberg

libtorrent4j by Alden Torres

Ktor by JetBrains

Cob Player by SimoesCTT

rssparser by Marco Gomiero
