# Jarvis — Open-source Android Assistant for Hermes

**Date:** 2026-06-17
**Status:** Approved (design); implementation plan pending
**Owner:** nila@foss.dk
**License intent:** Apache-2.0 (permissive, contributor-friendly, matches reused libs)

## 1. Goal & scope

A native Android app, **"Jarvis"**, that anyone running a **Hermes** agent can install and point at their own instance. It:

1. **Replaces Google Gemini as the default digital assistant** — registers for `RoleManager.ROLE_ASSISTANT`, launched by the long-press / assist gesture.
2. Has **"Hey Jarvis" always-on wake word** (openWakeWord, on-device).
3. Provides a **dedicated chat UI** with the Hermes agent.
4. Provides a **Gemini-style conversation mode** — voice in / voice out.
5. Supports **both text and voice** for input and output, interchangeably.

**Design principle — app-only.** The app talks to **exactly one required dependency: the user's Hermes `api_server`.** No sidecar, no extra container, no server changes. Configure a base URL + API key and it works against *any* Hermes instance — which is what makes it cleanly open-sourceable.

**The brain is always Hermes.** Every message and utterance routes through Hermes's `/v1/chat/completions`, inheriting Hermes's memory, personalities, tools, sessions, and model (`kimi-for-coding`). The app handles only the **ears, mouth, face, and OS integration** — on-device.

Out of scope: iOS, multi-user, any Hermes-side changes.

## 2. Background — verified Hermes findings

Confirmed live against the running container (`nousresearch/hermes-agent:v2026.5.16`) on the owner's Unraid box (`100.102.44.121`); applies to any Hermes:

- **`api_server` on `:8642`** — Python aiohttp, OpenAI-compatible, `cors_origins:'*'`, **Bearer auth on every route** (`API_SERVER_KEY`). A live `POST /v1/chat/completions` returned a reply through the full agent (~13.6k prompt tokens = Hermes injecting personality + memory + tools).
  - Routes: `GET /v1/models`, `/v1/capabilities`, `/v1/health`; `POST /v1/chat/completions` (SSE when `stream:true`, tools, **images** via `image_url`/data-URL, returns **`X-Hermes-Session-Id`** for continuity); `POST /v1/responses`; `POST /v1/runs` + `/events` + `/approval` + `/stop`.
  - **Audio is rejected** (`{"type":"audio"}` → `unsupported_content_type`). The api_server is **text + images only** — this is *why* voice is on-device.
- **Dashboard on `:9119`** — FastAPI management plane (sessions, `/api/model/options|set`, `/api/profiles`, skills, cron). Session-token auth, distinct from the bearer key. Optional, Phase 4 only.
- Hermes's native voice mode is **channel-bound** (CLI / Telegram / Discord), not exposed over the api_server.

## 3. Prior art & what we reuse

Researched 2026-06-17. Nothing existing *is* an open-source Hermes assistant app, but two scary parts are already solved:

- **Home Assistant companion app** — the closest turnkey solution: can be the default assistant, does on-device wake word (microWakeWord, ships "Hey Jarvis"), and supports LLM conversation agents. Rejected as our base: requires running Home Assistant, routes through HA's Assist pipeline, and its stock OpenAI integration won't talk to a custom backend without a community add-on. It's "an HA feature," not a shareable Hermes app.
- **`openwakeword-android-kt`** (Apache-2.0) — Kotlin + ONNX Runtime port of openWakeWord; multiple custom keywords incl. pretrained **"hey jarvis"**. **We use this directly** for the wake word.
- **Dicio** (`org.stypox.dicio`, GPLv3) — mature Android assistant that registers as default digital assistant with a Compose UI + hotword. **Reference only** (not forked — we stay Apache-2.0); its `VoiceInteractionService`/manifest wiring is the proven pattern to mirror.
- **SEPIA** — full self-hosted assistant framework; different ecosystem, not relevant to a thin client.

**Hard platform constraint (confirmed):** third-party apps **cannot** use Android's privileged always-on hotword API (reserved for preinstalled apps like Gemini). Even the ChatGPT app can be set as default assistant but can't register a custom hotword. **Therefore every wake-word solution — ours included — runs its own foreground mic service.** Accepted.

## 4. Architecture

```
┌── Android phone (Kotlin / Jetpack Compose) ─────────────────┐
│  "Hey Jarvis"  → WakeWordService (openWakeWord, ONNX)        │      ┌── user's Hermes ────────────┐
│  long-press    → HermesVoiceInteractionSession               │ HTTPS│  api_server :8642  (BRAIN)   │
│                        │                                      │Bearer│  POST /v1/chat/completions   │
│                        ▼                                      │◄────►│   (SSE stream)               │
│  ConversationController ── text ──────────────────────────── │      │   ← reply + X-Hermes-Session │
│   • STT  : Android SpeechRecognizer (on-device preferred)     │      └──────────────────────────────┘
│   • brain: Hermes /v1/chat/completions (stream)              │
│   • TTS  : TtsEngine ─┬─ AndroidTextToSpeech (free default)   │      ┌── ElevenLabs (OPTIONAL) ─────┐
│                       └─ ElevenLabs (if user key set) ───────│─HTTPS►│  /v1/text-to-speech/{id}/    │
│  Chat UI · Conversation UI · Settings                        │  key  │     stream                   │
└───────────────────────────────────────────────────────────────┘      └──────────────────────────────┘
```

One **required** network dependency (Hermes). One **optional** one (ElevenLabs, only if the user wants premium voice and supplies a key). Everything else is on-device.

## 5. Components (each: one purpose, one interface)

**Stack:** Kotlin + Jetpack Compose, `minSdk 29` (clean `RoleManager.ROLE_ASSISTANT`), `targetSdk 34`. Built locally on Windows (JDK 17 + SDK + Gradle already installed). Exact lib versions/APIs verified via context7 at implementation time.

| Unit | Responsibility | Depends on |
|---|---|---|
| `HermesClient` | The **only** Hermes coupling: OkHttp SSE chat stream, models, capabilities. Bearer auth, base-URL config, `X-Hermes-Session-Id` continuity. | settings |
| `HermesVoiceInteractionService` + `…Session` | Registers as default assistant; `onShow()` (gesture) opens conversation/chat. Mirrors Dicio's manifest wiring. | ConversationController |
| `WakeWordService` (foreground) | openWakeWord "hey jarvis" via `openwakeword-android-kt` (ONNX); on detect → launch conversation. Persistent notification. | mic |
| `SpeechInput` | Wraps Android `SpeechRecognizer` (prefer `createOnDeviceSpeechRecognizer` on 13+); partial + final transcripts; VAD end-of-turn. | mic |
| `TtsEngine` (interface) | Pluggable speech out: **`AndroidTextToSpeech`** (free default) and **`ElevenLabsTts`** (streamed mp3, used only when a key is set). | network/audio |
| `ConversationController` | The loop: STT → stream chat → sentence-chunk → TTS → play → barge-in → next turn. | HermesClient, SpeechInput, TtsEngine |
| Chat UI (Compose) | Text in/out, streaming tokens, markdown, scrollback, tap-to-talk. | HermesClient |
| Conversation UI (Compose) | Full-screen voice overlay: live transcript, reply text, listen/think/speak states, tap-to-type. | ConversationController |
| `Settings` (DataStore) | Hermes base URL + API key; **optional** ElevenLabs key + voice id; wake-word/voice toggles; model selection. Keys in EncryptedSharedPreferences/Keystore. | — |

**Permissions / manifest:** `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (API 34), `POST_NOTIFICATIONS`; `VoiceInteractionService` declared with `BIND_VOICE_INTERACTION` + `voice_interaction_service.xml` (recognition + session services).

## 6. Voice design

- **STT (on-device):** Android `SpeechRecognizer`, preferring the on-device recognizer (API 31+) for privacy/offline; falls back to the default (may be Google cloud on some devices). Danish depends on the device's installed language pack — surfaced in settings. (A bundled Whisper/whisper.cpp option is a possible later upgrade, not v1.)
- **TTS (pluggable):** default **Android `TextToSpeech`** (free, offline, decent Danish neural voices on modern phones). If the user sets an **ElevenLabs** key, `ElevenLabsTts` streams from `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream` (`xi-api-key` header) for premium voice. Per-user key + cost; stored encrypted; never logged.
- **Conversation latency (critical):** stream chat tokens, **split into sentences, pipeline each sentence to the TTS engine** so playback starts before the full reply lands. Capture 16 kHz mono; play via ExoPlayer/MediaPlayer.
- **Barge-in:** mic stays half-active during playback; detected speech stops playback and starts a new capture.

## 7. Chat design

Messenger UI. `POST /v1/chat/completions stream:true`; render SSE deltas; persist `X-Hermes-Session-Id` + history locally (DataStore/Room). Markdown rendering. Inline "speak this" button (uses `TtsEngine`). Long-press jumps into conversation mode.

## 8. Networking & security

The app needs to reach the user's Hermes — that's their choice (LAN, **Tailscale**, or public reverse proxy). For the owner: Tailscale (`http://100.102.44.121:8642`). The app only needs base URL + bearer key; CORS is already `*`. API keys stored with EncryptedSharedPreferences / Android Keystore.

## 9. Phasing

No sidecar phase. Each phase independently shippable & testable.

- **Phase 1 — Core app.** `HermesClient` + **text chat** end-to-end (streaming) + register as **default assistant** (gesture opens app) + Settings (URL/key). Proves the brain + the "replace Gemini" claim.
- **Phase 2 — Conversation mode.** On-device STT → chat stream → `TtsEngine` (Android default **and** ElevenLabs option) → playback loop, barge-in, full-screen UI.
- **Phase 3 — "Hey Jarvis" wake word.** openWakeWord foreground service launching conversation mode; persistent notification; battery/privacy handling.
- **Phase 4 — Polish & release.** Model picker (`/api/model/*`), history, theming, error/retry/offline states, and **open-source packaging** (Apache-2.0 LICENSE, README, build/F-Droid notes, CI).

Each phase gets its own implementation plan (writing-plans) before coding.

## 10. Testing strategy (pragmatic)

Test the fiddly bits once, then move on (no repeated verification runs):
- `HermesClient`: focused unit tests for **SSE delta parsing** and **session-id continuity**.
- `TtsEngine` sentence-chunking: unit test the chunker.
- Assistant role, wake word, conversation loop: manual on-device verification (system-assistant instrumented tests are high-effort, low-value).

## 11. Risks & caveats

- **Privileged hotword unavailable** to third-party apps → wake word needs a foreground mic service (battery + a mandatory persistent notification + Android 14 `FOREGROUND_SERVICE_MICROPHONE`). The gesture path (Phase 1) avoids all of this — wake word is genuinely optional.
- **STT privacy/quality varies:** Android `SpeechRecognizer` may use Google cloud on some devices; Danish on-device support depends on installed packs.
- **ElevenLabs** = per-user key + usage cost; on-device TTS is the always-available free fallback.
- **openWakeWord** tuning: false-accepts / battery; pick a sensible detection threshold.
- **Conversation latency:** Hermes's large per-turn prompt (~13.6k tokens) + agent reasoning + STT/TTS stack; mitigated by streaming + sentence-chunked TTS.
- **Session-id replay mechanism** (header vs. body) to be confirmed from `api_server.py` in Phase 1.

## 12. Deferred / open

- `/v1/runs` agentic surface (tool-progress UI, approvals).
- Dashboard-driven model/personality pickers (session-token auth) — Phase 4.
- Bundled on-device Whisper STT as an alternative to `SpeechRecognizer`.

## Appendix — endpoint reference

| Surface | Method · Path | Auth | Notes |
|---|---|---|---|
| Hermes (required) | `POST /v1/chat/completions` | Bearer | SSE if `stream:true`; images ok; **audio rejected**; returns `X-Hermes-Session-Id` |
| Hermes (required) | `GET /v1/models`, `/v1/capabilities` | Bearer | discovery |
| Hermes (optional) | `POST /v1/runs` (+ `/events`, `/approval`, `/stop`) | Bearer | deferred agentic surface |
| Dashboard (optional) | `/api/model/options`, `/api/model/set` | session token | Phase 4 picker |
| ElevenLabs (optional) | `POST /v1/text-to-speech/{voice_id}/stream` | `xi-api-key` | premium TTS, only if user sets a key |
