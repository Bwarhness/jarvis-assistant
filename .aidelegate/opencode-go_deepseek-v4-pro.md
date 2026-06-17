
> build · deepseek-v4-pro

→ Read app/src/main/java/dk/foss/jarvis/ui/ConversationViewModel.kt
→ Read app/src/main/java/dk/foss/jarvis/ui/ConversationScreen.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/ScribeRecognizer.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/AudioCapture.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/SpeechInput.kt
→ Read app/src/main/java/dk/foss/jarvis/wake/WakeWordService.kt
✱ Grep "assistEpoch|assistTrigger|EXTRA_FROM_ASSIST|onNewIntent" 20 matches
Let me also check the MainActivity for the `onNewIntent` / `assistEpoch` logic that triggers the `assistTrigger` in ConversationScreen:
→ Read app/src/main/java/dk/foss/jarvis/MainActivity.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/VoiceRecognizer.kt
Now let me read the remaining referenced file:
Now I have a complete picture. Let me trace the exact loop cycle and identify all contributing bugs.

Here is my analysis of the re-entry loop:

**The exact cycle:**

1. `startListening()` (ConversationViewModel:87) — sets continuous=true, pauses wake word, starts AudioCapture
2. No speech → `ScribeRecognizer.onResult(null)` → `onError("No speech heard", transient=false)` (ScribeRecognizer:36)
3. Non-transient error → `goIdle()` (ConversationViewModel:153)
4. `goIdle()` → **`WakeWordService.resumeListening()`** (ConversationViewModel:298) — restarts wake word engine
5. Wake word engine fires a detection (false positive, or after internal cooldown expires) → `onWake()` (WakeWordService:77) → `assistEpoch++` (MainActivity:98)
6. `LaunchedEffect(assistTrigger)` in ConversationScreen:79-83 — calls `startListening()` without user action
7. Return to step 2

Below are the concrete bugs that enable this loop plus additional correctness issues found during analysis.

---

```json
[
  {
    "severity": "critical",
    "location": "ConversationViewModel.kt:295-298",
    "issue": "goIdle() unconditionally calls WakeWordService.resumeListening() even when the conversation ended due to an error or 'no speech heard'. This immediately re-arms the wake word engine. Upon restart, the engine may fire a detection (false positive or restart transient), which bumps assistEpoch in MainActivity:98 and triggers ConversationScreen's LaunchedEffect(assistTrigger) at line 82 to call startListening() again — creating an indefinite listen→no speech→goIdle→wake→listen loop without the user doing anything.",
    "fix": "Do not resume the wake word when transitioning to Idle from a 'no speech' or error path. Either keep the wake word paused and show an explicit 'tap to retry' UI state, or add a debounce/cooldown guard in the LaunchedEffect(assistTrigger) in ConversationScreen that prevents startListening() from being called within a minimum interval (e.g. 5-10 seconds) of the previous goIdle()."
  },
  {
    "severity": "high",
    "location": "ConversationViewModel.kt:108-122",
    "issue": "beginTurn() stops TTS (line 115) but does NOT stop the current recognizer before starting a new recognition. If startListening() is called while a previous recognition is still in progress (e.g. finishTurn calling startListening and then a wake-word assistTrigger calling it again in rapid succession), the old ScribeRecognizer and AudioCapture thread keep running. AudioCapture.start() guards with 'if (active) return' (AudioCapture.kt:33), so the new capture.start() silently returns without starting, and the new recognition session never actually records audio. The UI shows 'Listening' but no recording is happening, and the old recognizer's callback has a stale turn number so its results are ignored.",
    "fix": "Add recognizer?.stop() (or recognizer?.cancel() for ScribeRecognizer) at the top of beginTurn() alongside the existing TTS stop, ensuring the previous capture thread is terminated before beginning a new turn."
  },
  {
    "severity": "high",
    "location": "ConversationViewModel.kt:87-105",
    "issue": "startListening() has no state guard — it always begins a new turn and starts recognition regardless of the current ConvState. When called re-entrantly (e.g. from both finishTurn() and a near-simultaneous assistTrigger LaunchedEffect), it bumps the turn via beginTurn(), invalidates in-flight callbacks, but the new AudioCapture.start() silently fails (active guard) leaving the system in a 'Listening' state with no actual recording.",
    "fix": "Add a state check at the top of startListening(): if state is already Listening or Thinking, either return early or first call recognizer?.stop() to cleanly abort the current recognition before starting a new one."
  },
  {
    "severity": "medium",
    "location": "ScribeRecognizer.kt:35-48",
    "issue": "When AudioCapture produces a non-null file but the STT API call fails (onFailure at line 47), the error is reported as transient=true, triggering the retry-once logic in ConversationViewModel:147-149. The retry restarts AudioCapture for another 450ms-delayed recording attempt. If this second attempt also produces a file that fails transcription, it falls through to goIdle(), which resumes the wake word, feeding the main loop. The retry buys nothing for API failures (the API won't recover in 450ms) and just extends the loop's period.",
    "fix": "Distinguish between retryable microphone-level errors (AudioCapture start failures) and non-retryable API-level errors (network/transcription failures). API failures should go directly to goIdle() or show an error without retrying."
  },
  {
    "severity": "medium",
    "location": "WakeWordService.kt:129-134",
    "issue": "resumeEngine() calls engine?.start() which restarts the wake word model. The engine's internal cooldown (1500ms, configured at line 65) is almost certainly reset when the engine is stopped and restarted, bypassing the cooldown. Combined with the external cooling flag (4000ms delay in onWake at line 90-93) potentially being stale, the engine can produce a detection immediately after resumeEngine() once any running cooling delay expires. This false-positive-on-restart scenario directly feeds step 5 of the main re-entry loop.",
    "fix": "When resumeEngine() restarts the engine, reset or extend the cooling flag to enforce a minimum quiet period (e.g. delay cooling=false by an additional cooldown window from the resume time, or call onWake-like cooling logic after resume)."
  },
  {
    "severity": "medium",
    "location": "AudioCapture.kt:33,116-122",
    "issue": "AudioCapture.cancel() sets thread = null (line 121) without waiting for the recording thread to exit. The stop() method only sets active = false (line 116). In both cases, the underlying AudioRecord is released only in the thread's finally block (line 110). If start() is called immediately after cancel()/stop(), it creates a new AudioRecord while the old thread's finally block may not yet have released the old AudioRecord, risking two simultaneous AudioRecord instances competing for the microphone.",
    "fix": "In cancel(), wait for the recording thread to finish (thread?.join()) before nulling it. Alternatively, explicitly release the AudioRecord in cancel()/stop() before allowing a new start()."
  },
  {
    "severity": "low",
    "location": "ConversationViewModel.kt:290-292",
    "issue": "finishTurn() calls startListening() if continuous is true, but startListening() itself sets continuous = true (line 90). If continuous were ever set to false (e.g. via setContinuous()), finishTurn() would call goIdle() instead, which resumes the wake word. But setContinuous() is only called from UI, so this path is unlikely to cause the loop. However, it is semantically confusing that startListening() overrides the continuous flag — a non-continuous conversation that receives an assistTrigger would become continuous after the next turn.",
    "fix": "Remove the continuous=true assignment from startListening(); it should respect the existing continuous flag. Let the caller (UI or finishTurn) decide whether to go continuous."
  }
]
```
