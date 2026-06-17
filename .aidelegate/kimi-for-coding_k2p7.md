
> build · k2p7

✱ Glob "**/ConversationViewModel.kt" 1 match
✱ Glob "**/ConversationScreen.kt" 1 match
✱ Glob "**/ScribeRecognizer.kt" 1 match
✱ Glob "**/AudioCapture.kt" 1 match
✱ Glob "**/SpeechInput.kt" 1 match
✱ Glob "**/WakeWordService.kt" 1 match
→ Read app/src/main/java/dk/foss/jarvis/ui/ConversationViewModel.kt
→ Read app/src/main/java/dk/foss/jarvis/ui/ConversationScreen.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/ScribeRecognizer.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/AudioCapture.kt
→ Read app/src/main/java/dk/foss/jarvis/voice/SpeechInput.kt
→ Read app/src/main/java/dk/foss/jarvis/wake/WakeWordService.kt
✱ Glob "**/MainActivity.kt" 1 match
→ Read app/src/main/java/dk/foss/jarvis/MainActivity.kt
✱ Glob "**/AndroidManifest.xml" 1 match
→ Read app/src/main/AndroidManifest.xml
The loop is a feedback cycle:

`startListening()` → no speech / error → `goIdle()` resumes the wake word → the wake word fires again → `MainActivity.onNewIntent()` bumps `assistEpoch` → `ConversationScreen`’s `LaunchedEffect(assistTrigger)` calls `startListening()` again. `finishTurn()`’s continuous auto-relisten feeds the same cycle, and the retry-once path can also land back in `goIdle()`.

```json
[
  {
    "severity": "critical",
    "location": "ConversationViewModel.kt:295",
    "issue": "goIdle() unconditionally calls WakeWordService.resumeListening() while the conversation screen is still active. After a no-speech/error turn this re-arms the wake word, which can immediately false-trigger and route back through MainActivity.onNewIntent / ConversationScreen.assistTrigger to startListening(), creating the observed infinite loop.",
    "fix": "Do not resume wake-word listening from goIdle(). Only resume it when the conversation truly stops (stopAll(), onCleared(), or onExit)."
  },
  {
    "severity": "high",
    "location": "ConversationViewModel.kt:291",
    "issue": "finishTurn() calls startListening() whenever continuous is true. If the user does not reply, the recognizer times out and the no-speech path returns via goIdle()->resumeListening, letting the wake word re-trigger and restart listening repeatedly.",
    "fix": "After finishTurn(), keep the wake word paused and schedule a bounded/delayed re-listen inside the ViewModel instead of relying on the wake word to re-enter."
  },
  {
    "severity": "medium",
    "location": "ConversationScreen.kt:77",
    "issue": "LaunchedEffect(assistTrigger) calls vm.startListening() on every assistEpoch bump without checking whether a turn is already in progress, so a re-entrant assist intent can abort the current turn and restart recognition, feeding the loop.",
    "fix": "Guard the effect so it only calls startListening() when vm.state.value == ConvState.Idle."
  },
  {
    "severity": "high",
    "location": "voice/AudioCapture.kt:33",
    "issue": "start() checks and sets the volatile `active` flag without synchronizing with the previous capture thread's finally block (line 109). A stale finally can reset `active` to false after a new capture has started, aborting it immediately and posting a spurious 'No speech heard' that drives the resume-wake/re-trigger loop.",
    "fix": "Join/wait for the previous capture thread before starting a new one, or use a per-capture generation token so a stale finally cannot affect the new thread."
  },
  {
    "severity": "medium",
    "location": "voice/ScribeRecognizer.kt:28",
    "issue": "ScribeRecognizer.start() does not cancel a previous AudioCapture before starting a new one. AudioCapture.start() returns early when `active` is true, so a new turn can be silently dropped or handed to a stale capture thread.",
    "fix": "Call capture.cancel() at the top of start() before invoking capture.start(), matching SpeechInput's behavior."
  }
]
```
