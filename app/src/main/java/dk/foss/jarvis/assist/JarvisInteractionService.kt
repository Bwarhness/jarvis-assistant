package dk.foss.jarvis.assist

import android.service.voice.VoiceInteractionService

/**
 * Marks Jarvis as an available "digital assistant" the user can select in
 * Settings → Default apps. The real work happens in the session below.
 */
class JarvisInteractionService : VoiceInteractionService()
