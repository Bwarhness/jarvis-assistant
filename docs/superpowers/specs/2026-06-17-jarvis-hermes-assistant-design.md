# Jarvis — Android Assistant fronting Hermes

**Date:** 2026-06-17
**Status:** Approved (design); implementation plan pending
**Owner:** nila@foss.dk

## 1. Goal & scope

Build a native Android app, **"Jarvis"**, that:

1. **Replaces Google Gemini as the default digital assistant** — registered for `RoleManager.ROLE_ASSISTANT`, launched by the long-press / assist gesture.
2. Has **"Hey Jarvis" always-on wake word** (Picovoice Porcupine built-in keyword).
3. Provides a **dedicated chat UI** with the user's self-hosted **Hermes** agent (replacing the current Telegram workflow).
4. Provides a **Gemini-style conversation mode** — full-duplex voice in / voice out.
5. Supports **both text and voice** for input and output, interchangeably.

**The brain is always Hermes.** Every message and utterance is routed through Hermes's `api_server`, so the app inherits Hermes's memory, personalities, tools, sessions, and model (`kimi-for-coding`). The app is the ears, mouth, and face — not a second LLM.

Out of scope for now: iOS, multi-user, Hermes feature changes beyond a small voice sidecar.

## 2. Background — verified Hermes findings

All of the following was confirmed live against the running container (`nousresearch/hermes-agent:v2026.5.16`) on Unraid (`root@192.168.1.200`, Tailscale `100.102.44.121`):

- **`api_server` on `:8642`** — Python aiohttp, OpenAI-compatible, `cors_origins: '*'`, **Bearer auth on every route** via `API_SERVER_KEY` (set in `/opt/data/.env`). A live `POST /v1/chat/completions` succeeded and returned the reply through the full agent (~13.6k prompt tokens = Hermes injecting personality + memory + tools).
  - Routes: `GET /health`, `/v1/health`, `/v1/models`, `/v1/capabilities`; `POST /v1/chat/completions` (SSE streaming when `stream:true`, tools, **images via `image_url`/data-URL**, returns **`X-Hermes-Session-Id`** header for continuity); `POST /v1/responses` (+ GET/DELETE by id, stateful); `POST /v1/runs` + `GET /v1/runs/{id}/events` (SSE lifecycle/tool-progress) + `/approval` + `/stop`; `/api/jobs` CRUD (cron).
  - **Audio is rejected:** a `{"type":"audio"}` content part returns `unsupported_content_type`. The api_server is text + images only.
- **Dashboard on `:9119`** — FastAPI/uvicorn management plane (`/openapi.json`, `/docs`). Endpoints for sessions, config, **models (`/api/model/options`, `/api/model/set`)**, **profiles/personalities (`/api/profiles`)**, skills, cron, logs, env. Auth is a session token (`window.__HERMES_SESSION_TOKEN__`), distinct from the api_server bearer. Not on the app's critical path; candidate for personality/model pickers later.
- **Voice config (`/opt/data/config.yaml`):** `stt.provider: local` (faster-whisper `base`, CPU/int8, script `/opt/data/home/scripts/stt_transcribe.py`); `tts.provider: elevenlabs` (voice_id `JBFqnCBsd6RMkjVDRZzb`, `eleven_multilingual_v2`) with `tts.edge.voice: da-DK-ChristelNeural`; `voice.auto_tts: true`.
- **No `ELEVENLABS_API_KEY` is set** → effective working TTS today is **Edge `da-DK-ChristelNeural`** (free, keyless Danish neural voice). ElevenLabs activates the moment a key is added.
- **Hermes's native "voice mode" is channel-bound** (per official docs: CLI mic loop, Telegram/Discord spoken replies, Discord VC). It is **not exposed over the `api_server`**. Provider menu (to mirror): STT = `local`/`groq`/`openai`; TTS = `edge`/`neutts`/`elevenlabs`/`openai`/`mistral`.
- **Prior art:** two abandoned Android scaffolds inside the container (`/opt/data/android-project` Groovy, `/opt/data/hermes-assistant` Kotlin-DSL). The latter's `PLAN.md` routed through the **Telegram Bot API + direct ElevenLabs** — explicitly rejected. Reuse only the manifest/service skeleton.

## 3. Architecture

```
┌── Android phone (Kotlin/Compose) ─────────────┐        ┌── Unraid (reached via Tailscale) ──────────┐
│  "Hey Jarvis"  →  WakeWordService (Porcupine) │        │                                            │
│  long-press    →  VoiceInteractionSession      │        │  Hermes  api_server  :8642   (THE BRAIN)   │
│                         │                       │ HTTPS  │   POST /v1/chat/completions (SSE stream)   │
│                         ▼                       │ Bearer │     → kimi, memory, personality, tools     │
│  ConversationController / Chat screen          │◄──────►│     ← reply + X-Hermes-Session-Id          │
│   • mic → /v1/audio/transcriptions → text       │        │                                            │
│   • text → /v1/chat/completions → text          │        │  voice-gateway       :8643   (NEW sidecar) │
│   • text → /v1/audio/speech → 🔊                │◄──────►│   POST /v1/audio/transcriptions (Whisper)  │
│                                                 │  same  │   POST /v1/audio/speech (Edge/ElevenLabs)  │
└─────────────────────────────────────────────────┘ token  │   reads Hermes config.yaml + .env (RO)     │
                                                            └────────────────────────────────────────────┘
```

Three deployables: **(A) the Android app**, **(B) the `voice-gateway` sidecar container**, and **(C) Hermes itself, unchanged**.

## 4. Component A — Hermes (the brain), unchanged

The app uses Hermes only through `api_server`:

- **Chat:** `POST /v1/chat/completions` with `stream:true`, `Authorization: Bearer <API_SERVER_KEY>`, `model: "kimi-for-coding"` (or the user's selected model). Parse SSE deltas; capture `X-Hermes-Session-Id` from the first response and **send it back on subsequent turns** to keep one continuous Hermes session/memory thread. (Mechanism for replaying the session id — request header vs. body field — to be confirmed from `api_server.py` during Phase 1.)
- **Capabilities/models** discovered via `GET /v1/capabilities` and `GET /v1/models`.
- The richer `POST /v1/runs` + `/events` SSE surface (tool progress, approvals) is **deferred**; chat/completions streaming is sufficient for v1.

No Hermes code changes. Updates via the dashboard's `/api/hermes/update` remain safe.

## 5. Component B — `voice-gateway` sidecar (NEW)

A small FastAPI service in its own container that mirrors Hermes's own voice stack and exposes it over HTTP for the phone.

- **Endpoints (OpenAI-shaped):**
  - `POST /v1/audio/transcriptions` — multipart audio upload → `{ "text": "..." }`. Backed by **faster-whisper `base`** (same as Hermes); optional `groq` backend for speed.
  - `POST /v1/audio/speech` — `{ input, voice?, model?, format? }` → audio bytes (mp3/ogg). Default **Edge `da-DK-ChristelNeural`**; switches to **ElevenLabs** (voice_id `JBFqnCBsd6RMkjVDRZzb`) automatically when `ELEVENLABS_API_KEY` is present.
- **Auth:** same `Bearer API_SERVER_KEY` as Hermes → the app uses **one token**.
- **Config:** mounts Hermes's `/opt/data/config.yaml` + `.env` **read-only** and reads `stt.*` / `tts.*` so the phone's voice always matches Hermes's configured voice. No duplicated settings.
- **Deploy:** new image + Dockerfile (Python 3.13, faster-whisper, edge-tts, fastapi/uvicorn), `docker run` on the Unraid box, host port **`:8643`** (verify free before claiming it), `restart: unless-stopped`. Follows the user's manual-deploy norm (build local image, `docker run`); not compose.
- **Why a sidecar, not a Hermes patch:** the official Nous image is text-only over HTTP; patches die on every `/api/hermes/update`. The sidecar reuses Hermes's *engines and config* without forking it.

## 6. Component C — Android app

**Stack:** Kotlin + Jetpack Compose, `minSdk 29` (clean `RoleManager.ROLE_ASSISTANT`), `targetSdk 34`. Built locally on Windows (JDK 17 + SDK + Gradle already installed). Single-module to start; split modules only if it grows.

**Key dependencies** (exact versions/APIs to be confirmed via context7 at implementation time): OkHttp (SSE streaming), kotlinx-serialization, Coil (markdown/images), a Compose markdown renderer, `ai.picovoice:porcupine-android`, AndroidX `core`/`activity-compose`/`datastore`/`security-crypto`, ExoPlayer or MediaPlayer for audio playback.

**Modules / units (each one purpose, one interface):**

| Unit | Responsibility | Depends on |
|---|---|---|
| `HermesClient` | OkHttp client: SSE chat stream, models, capabilities; multipart STT + TTS to voice-gateway. Bearer auth, base-URL config, session-id continuity. | settings |
| `HermesVoiceInteractionService` + `…Session` | Registers as default assistant; `onShow()` (gesture) launches conversation/chat. | ConversationController |
| `WakeWordService` (foreground) | Porcupine "Jarvis" detection loop; on hit → launches conversation mode. Persistent notification. | Porcupine, mic |
| `ConversationController` | The voice loop: capture (VAD) → STT → stream chat → sentence-chunked TTS playback → barge-in → next turn. | HermesClient, audio I/O |
| Chat UI (Compose) | Text in/out, streaming tokens, markdown, scrollback, session continuity, tap-to-talk. | HermesClient |
| Conversation UI (Compose) | Full-screen voice overlay: live transcript, reply text, listening/speaking states, tap-to-type. | ConversationController |
| `Settings` (DataStore) | Base URL, API key (EncryptedSharedPreferences/Keystore), wake-word on/off, voice on/off, voice/model/personality selection. | — |

**Permissions / manifest:** `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (API 34), `POST_NOTIFICATIONS`; `VoiceInteractionService` declared with `BIND_VOICE_INTERACTION` + `voice_interaction_service.xml` (recognition + session services); `RECOGNITION_SERVICE` if needed.

## 7. Conversation mode (the Gemini feel)

Full-screen overlay, entered from the wake word **or** the assist gesture. Loop: **listen → think → speak → listen**.

- **End-of-turn:** voice-activity detection (silence threshold + duration, mirroring Hermes's `silence_threshold`/`silence_duration`) decides when the user stopped talking; then send to STT.
- **Latency mitigation (critical):** stream chat tokens, **split the stream into sentences, and pipeline each sentence to `/v1/audio/speech`** so playback starts before the full reply is generated. Keeps it feeling live despite Hermes's ~13.6k-token baseline prompt.
- **Barge-in:** mic stays half-active during playback; detected speech stops playback and starts a new capture.
- **Display:** live partial transcript + streaming reply text; clear listening/thinking/speaking states; tap-to-type fallback always present.
- **Audio formats:** capture 16 kHz mono WAV/PCM for Whisper; TTS returns mp3, played via ExoPlayer/MediaPlayer.

## 8. Chat mode

Standard messenger UI. `POST /v1/chat/completions stream:true`; render SSE deltas incrementally; persist `X-Hermes-Session-Id` and message history locally (DataStore or Room). Markdown rendering. Optional inline "speak this" button (calls `/v1/audio/speech`). A long-press from chat can jump into conversation mode.

## 9. Networking & security

- **Tailscale (recommended):** phone joins the tailnet; base URLs `http://100.102.44.121:8642` (Hermes) and `:8643` (voice-gateway). No public exposure; bearer key never crosses the open internet.
- **Alternative:** expose both behind NginxProxyManager on one subdomain + TLS (e.g. `hermes.<domain>` with path routing), if off-tailnet access is wanted later.
- API key stored with EncryptedSharedPreferences / Android Keystore; never logged.

## 10. Settings

Base URL(s), API key, wake-word toggle, voice toggle, default mode (chat vs voice). Stretch: **model picker** (`GET/POST /api/model/*` on `:9119`) and **personality picker** (`GET /api/profiles`) — these mutate Hermes config for *new* sessions; auth/UX TBD, Phase 4.

## 11. Key data flows

- **Text turn:** user text → `POST /v1/chat/completions stream` (with prior session id) → render deltas → store reply + session id.
- **Voice turn:** capture WAV → `POST /v1/audio/transcriptions` → text (shown) → `POST /v1/chat/completions stream` → sentence chunks → `POST /v1/audio/speech` per chunk → enqueue + play → on playback end, resume listening.
- **Wake word:** `WakeWordService` detects "Jarvis" → starts ConversationController in conversation mode.
- **Assist gesture:** long-press → `HermesVoiceInteractionSession.onShow()` → opens conversation mode (or chat, per user default).

## 12. Phasing

Each phase is independently shippable and testable.

- **Phase 0 — `voice-gateway` sidecar.** STT + TTS endpoints, container, deployed on Unraid. Verifiable with `curl` (round-trip a WAV → text → mp3). Foundation for all voice.
- **Phase 1 — Core app.** `HermesClient`, **text chat** end-to-end through Hermes (streaming), and registration as **default assistant** (gesture opens the app). No voice, no wake word. Proves the brain + the "replace Gemini" claim.
- **Phase 2 — Conversation mode.** The voice loop (Phase 0 + Phase 1 combined), barge-in, full-screen UI.
- **Phase 3 — "Hey Jarvis" wake word.** Porcupine foreground service launching conversation mode; persistent notification; battery/privacy handling.
- **Phase 4 — Polish.** Personality/model pickers, history/scrollback, theming, notification UX, error/retry/offline states.

Each phase will get its own implementation plan (writing-plans) before coding.

## 13. Testing strategy (pragmatic)

Per the user's preference to avoid over-testing: **test the parts that are easy to get wrong, verify once, move on** — no repeated verification runs.

- `voice-gateway`: a couple of `curl` smoke checks (transcribe a sample WAV; synthesize a phrase). Done once at deploy.
- `HermesClient`: focused unit tests for **SSE delta parsing** and **session-id continuity** (the genuinely fiddly bits).
- Assistant role + wake word: manual on-device verification (instrumented tests for system-assistant registration are high-effort, low-value here).
- Conversation loop: manual end-to-end on device.

## 14. Risks & caveats

- **Always-on mic (Phase 3):** battery + privacy cost; mandatory foreground-service notification; Android 14 `FOREGROUND_SERVICE_MICROPHONE`. The gesture path (Phase 1) has none of this — wake word is genuinely optional.
- **Conversation latency:** Hermes carries a large per-turn system prompt (~13.6k tokens) plus agent reasoning, then STT + TTS stack on top. Mitigated by streaming + sentence-chunked TTS and the option of `groq` STT; still expect a perceptible think pause. Hermes's `compression` helps over long sessions.
- **"ElevenLabs" = Edge Danish voice** until a key is added (then a config flip; no app change).
- **Picovoice** free tier requires a free AccessKey; "Jarvis" is a built-in keyword (no custom-model training).
- **Session-id replay mechanism** for chat/completions must be confirmed from `api_server.py` (header vs. body) in Phase 1.
- **Port `:8643`** for the sidecar must be confirmed free on the Unraid host before use.

## 15. Deferred / open

- `/v1/runs` agentic surface (tool-progress UI, approvals) — later, if wanted.
- Dashboard-driven personality/model pickers — Phase 4 (auth via session token TBD).
- Off-tailnet access via NginxProxyManager — only if needed.

## Appendix — verified endpoint reference

| Surface | Method · Path | Auth | Notes |
|---|---|---|---|
| Hermes | `POST /v1/chat/completions` | Bearer | SSE if `stream:true`; images ok; audio rejected; returns `X-Hermes-Session-Id` |
| Hermes | `GET /v1/models`, `/v1/capabilities`, `/v1/health` | Bearer | discovery |
| Hermes | `POST /v1/responses`, `/v1/runs` (+ `/events`, `/approval`, `/stop`) | Bearer | deferred |
| Dashboard | `/api/model/options`, `/api/model/set`, `/api/profiles`, `/api/sessions` | session token | Phase 4 pickers |
| voice-gateway (new) | `POST /v1/audio/transcriptions` | Bearer | multipart → `{text}` |
| voice-gateway (new) | `POST /v1/audio/speech` | Bearer | text → audio (Edge default) |
