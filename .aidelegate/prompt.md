You are doing a CORRECTNESS code review of the code in this repository. Read the source and find
CONCRETE bugs that would actually misbehave at runtime: race conditions, re-entrancy / feedback
loops, null / object-lifetime errors, resource leaks (memory, handles, native allocations),
off-by-one, incorrect logic, wrong field/enum usage, unsafe concurrency, and unvalidated input.

Think carefully and VERIFY each candidate against the actual code before reporting it — reason about
real execution paths, not surface pattern-matching. Prefer a few high-confidence real bugs over many
speculative ones.

DO NOT modify, create, or delete any files. This is a read-only review.
FINISH your response with a single fenced ```json block: a JSON array of findings
[{"severity":"critical|high|medium|low","location":"file:line","issue":"concrete runtime symptom","fix":"specific fix"}]
Output [] if you find no real bugs. The JSON block is machine-parsed — keep it valid.

ADDITIONAL CONTEXT:
Focus ONLY on the voice conversation listening loop. Files: app/src/main/java/dk/foss/jarvis/ui/ConversationViewModel.kt, ui/ConversationScreen.kt, voice/ScribeRecognizer.kt, voice/AudioCapture.kt, voice/SpeechInput.kt, wake/WakeWordService.kt. BUG: in voice conversation mode it loops — it starts listening, says 'no speech heard', then quickly starts listening again, repeatedly, without the user doing anything. Find every code path that can cause startListening()/startRecognition() to be re-invoked in a loop. Consider: continuous auto-listen after speaking (finishTurn), the wake word re-trigger (WakeWordService.resumeListening + assistTrigger in ConversationScreen + MainActivity onNewIntent bumping assistEpoch), the retry-once logic, and any interaction where goIdle() resuming the wake word could lead back into startListening. Identify the exact loop cycle.
