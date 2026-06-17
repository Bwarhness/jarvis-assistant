package dk.foss.jarvis.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dk.foss.jarvis.MainActivity

/**
 * Invoked when the user triggers the assistant (assist gesture / long-press).
 * For now it brings Jarvis to the foreground; later phases open conversation mode.
 */
class JarvisInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        }
        context.startActivity(intent)
        hide()
    }
}
