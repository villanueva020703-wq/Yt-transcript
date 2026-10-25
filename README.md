# YT Transcribe

Paste a YouTube link, get the transcript. Nothing else.

Pipeline: NewPipeExtractor resolves the smallest audio stream → resumable 4 MB range download → `MediaCodec` decodes to 16 kHz mono PCM, streamed → whisper.cpp (base, q5_1) in 30 s windows cut at the quietest point → timestamped text. Runs in a foreground service with a wake lock, so long videos survive the screen being off.

## Build the APK (no local setup)
1. Push this folder to a GitHub repo (branch `main`).
2. Actions tab → **Build APK** → download `yt-transcribe-apk` → install `app-release.apk`.

## Build locally
Android Studio (Ladybug+), NDK + CMake from SDK Manager, internet for the first build (whisper.cpp is fetched by CMake). Then `Build > Build APK`, or `gradle :app:assembleRelease`.

## Notes
- arm64 phones with ARMv8.2 (roughly 2019 and newer). Older CPUs will crash in native code.
- First run downloads the ~60 MB speech model, then transcription of downloaded audio is offline. Fetching a YouTube link needs internet.
- Speed on mid-range phones is about 0.3–1x realtime: a 1 h video takes roughly 1–3 h. Keep it charging.
- Interrupted audio downloads resume. Transcription restarts from the beginning if the app is killed.
- Cancel takes effect after the current 30 s window finishes.
- Downloading YouTube streams breaks YouTube's Terms of Service. Personal sideload use only; not Play Store material. If YouTube changes something and extraction fails, bump `NewPipeExtractor` in `app/build.gradle.kts`.
