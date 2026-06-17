# Jarvis — an Android assistant for Hermes

Jarvis is an open-source Android app that turns your phone into a voice + chat
client for a self-hosted [Hermes](https://hermes-agent.nousresearch.com) agent.
It can **replace Gemini as your device's digital assistant**, listens for
**"Hey Jarvis"**, and gives you a **Gemini-style voice conversation** plus a
normal **chat** — all powered by *your* Hermes instance (its memory,
personalities, tools, and model).

The app talks to **one thing only: your Hermes `api_server`** (the
OpenAI-compatible `/v1/chat/completions` endpoint). No companion server, no
sidecar — point it at your Hermes URL + API key and go.

## Features

- 💬 **Streaming chat** with your Hermes agent (continuous sessions via `X-Hermes-Session-Id`).
- 🎙️ **Voice conversation mode** — speak, Jarvis thinks and replies aloud, then listens again.
- 🗣️ **"Hey Jarvis" wake word** — fully on-device ([openWakeWord](https://github.com/dscripka/openWakeWord)), no cloud, no account.
- 🤖 **Default digital assistant** — launch with the long-press / assist gesture, replacing Gemini.
- 🔊 **Pluggable voice** — your phone's built-in TTS by default; optional **ElevenLabs** for premium speech.
- 🔒 App-only: your Hermes key stays on your device; works over LAN, Tailscale, or a reverse proxy.

The **brain is always Hermes** — Jarvis only handles the ears, mouth, face, and OS integration.

## Install

A prebuilt debug APK is in [`dist/`](dist/). With the phone connected and USB
debugging on:

```bash
adb install -r dist/Jarvis-0.1.0-debug.apk
```

Or copy the APK to the phone and tap it (allow "install from unknown sources").

## First-run setup

1. **Open Jarvis → ⚙ Settings.**
2. **Base URL** — your Hermes `api_server`, e.g. `http://100.x.x.x:8642` (Tailscale) or `http://<lan-ip>:8642`.
3. **API key** — your Hermes `API_SERVER_KEY` (in Hermes' `.env`). Tap **Save & test connection** — you should see your models.
4. **Set as default assistant** (optional) — opens system settings; pick Jarvis so the assist gesture launches it.
5. **"Hey Jarvis" wake word** (optional) — toggle on and grant microphone + notification permissions. A persistent notification shows while it listens.
6. **ElevenLabs** (optional) — paste an ElevenLabs API key + voice ID for premium speech; otherwise the phone's built-in voice is used.

Tap the 🎤 in the chat top bar (or use the assist gesture / wake word) to enter
voice conversation.

## Build from source

Requires JDK 17 and the Android SDK (platform 34, build-tools 34). Set
`local.properties` with `sdk.dir=...`, then:

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

The openWakeWord model files (`melspectrogram.onnx`, `embedding_model.onnx`,
`hey_jarvis_v0.1.onnx`) live in `app/src/main/assets/` and are included.

### Signed release

The shipped APK is debug-signed (fine for sideloading). For a release build, add
a `signingConfig` with your keystore and run `./gradlew :app:assembleRelease`.

## Architecture

| Layer | What |
|---|---|
| `hermes/HermesClient` | OkHttp SSE streaming to `/v1/chat/completions`; the only Hermes coupling. |
| `voice/SpeechInput` | On-device STT via Android `SpeechRecognizer`. |
| `voice/TtsEngine` | `AndroidTts` (free) / `ElevenLabsTts` (premium). |
| `ui/ConversationViewModel` | The listen → think → speak → listen loop. |
| `wake/WakeWordService` | openWakeWord foreground service for "Hey Jarvis". |
| `assist/*` | `VoiceInteractionService` so Jarvis can be the default assistant. |

Stack: Kotlin + Jetpack Compose, minSdk 29 / target 34. Design notes in
[`docs/superpowers/specs/`](docs/superpowers/specs/).

## Caveats

- **Wake word costs battery** and requires an always-on mic foreground service (Android restricts the privileged hotword API to preinstalled apps, so this is the only option for third-party apps).
- **Background launch on wake** uses a full-screen-intent notification; reliability varies by OEM/Android version (Android 14 restricts full-screen intents). The assist gesture is the most reliable trigger.
- **On-device STT** quality/language support depends on the phone (Danish needs the language pack installed).
- **ElevenLabs** is per-user (your key, your usage cost).

## Credits & license

Licensed under [Apache-2.0](LICENSE). Wake word by
[openWakeWord](https://github.com/dscripka/openWakeWord) (models) and
[openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt)
(`xyz.rementia:openwakeword`).
