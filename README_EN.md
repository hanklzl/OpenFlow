# OpenFlow

**On-device voice input for Android** · whisper.cpp ASR + llama.cpp polish · writes straight into any text field

[![License: GPL v3](https://img.shields.io/github/license/hanklzl/OpenFlow.svg?color=blue)](LICENSE)
[![Release](https://img.shields.io/github/v/release/hanklzl/OpenFlow?include_prereleases&sort=semver)](https://github.com/hanklzl/OpenFlow/releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/12)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-blue)](https://github.com/hanklzl/OpenFlow/releases/latest)

[中文](README.md) · Docs: <https://hanklzl.github.io/OpenFlow/>

---

> **Long-press the floating ball and talk** → whisper.cpp transcribes on-device → optionally Qwen polishes the text → an Accessibility Service writes it at the cursor.
> Everything runs on your phone — **no server, no audio upload**.

## Features

- **Fully offline**: recording, ASR and LLM polish all run locally. No telemetry, no audio leaves the device.
- **Works in any app's text field**: built on Android's Accessibility Service + overlay window — not an IME, not app-specific.
- **Two-stage pipeline, toggleable**: transcription only (whisper), or transcription + polish (whisper + Qwen).
- **Swappable models**: download and switch whisper / Qwen GGUF models from the *Models* tab. Pick by device capability and use case.
- **Swipe up to cancel**: while recording, sliding off the ball discards the result — handy for misfires.
- **Open source under GPL-3.0**: the app code is GPL-3.0; vendored whisper.cpp / llama.cpp keep their original MIT licenses (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

## Quick start

1. Grab the matching APK from [Releases](https://github.com/hanklzl/OpenFlow/releases):
   - Real devices: `arm64-v8a`
   - Emulators: `x86_64`
2. Install and follow the in-app guide to grant **Accessibility Service**, **Display over other apps**, and **Microphone**.
3. Open the *Models* tab and download at least one ASR model (start with the smallest whisper model to validate the pipeline).
4. Focus any text field → the floating ball appears → **long-press** to record → release to stop → text is inserted at the cursor.

> Full permission walkthrough, model selection guide, and FAQ live on the [docs site](https://hanklzl.github.io/OpenFlow/).

## System requirements

| Item | Requirement |
|---|---|
| OS | Android 12 (API 31) or newer |
| ABI | arm64-v8a (recommended) / x86_64 (emulator) |
| Storage | Depends on chosen models — typically 100 MB – 2 GB |
| Permissions | Accessibility Service, SYSTEM_ALERT_WINDOW, RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE |

## Project status

Current release: **v0.1.0** (first public preview). See [CHANGELOG.md](CHANGELOG.md).

## Build from source

```bash
git clone --recurse-submodules https://github.com/hanklzl/OpenFlow.git
cd OpenFlow
./gradlew :app:assembleDebug
```

The first build compiles whisper.cpp + llama.cpp natively (5–15 minutes). For full build / signing / release flow, see [`AGENTS.md`](AGENTS.md) (developer-oriented).

## License

- App code: **GPL-3.0-or-later**, see [LICENSE](LICENSE).
- Third-party attributions: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Model weights are not in this repo and are not bundled in the APK; they are downloaded at runtime and remain subject to their own upstream licenses.

## Acknowledgements

OpenFlow stands on the shoulders of:

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) · on-device ASR inference
- [llama.cpp](https://github.com/ggml-org/llama.cpp) · on-device LLM inference
- [Qwen](https://github.com/QwenLM) team · high-quality small Chinese models for polishing

Design inspiration: Typeless Flow and [MusicFreeAndroid](https://github.com/maotoumao/MusicFreeAndroid).
