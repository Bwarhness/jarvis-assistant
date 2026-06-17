package dk.foss.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Conversation }

class MainActivity : ComponentActivity() {

    // Incremented each time the assistant is triggered; observed by Compose to
    // jump into conversation mode (works for both cold start and onNewIntent).
    private var assistEpoch by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
        rearmWakeWord()

        setContent {
            JarvisTheme {
                var screen by remember {
                    mutableStateOf(if (assistEpoch > 0) Screen.Conversation else Screen.Chat)
                }
                LaunchedEffect(assistEpoch) {
                    if (assistEpoch > 0) screen = Screen.Conversation
                }
                when (screen) {
                    Screen.Chat -> {
                        val vm: ChatViewModel = viewModel()
                        ChatScreen(
                            vm = vm,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenVoice = { screen = Screen.Conversation },
                        )
                    }
                    Screen.Settings -> {
                        BackHandler { screen = Screen.Chat }
                        SettingsScreen(onBack = { screen = Screen.Chat })
                    }
                    Screen.Conversation -> {
                        BackHandler { screen = Screen.Chat }
                        val cvm: ConversationViewModel = viewModel()
                        ConversationScreen(vm = cvm, onExit = { screen = Screen.Chat })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
    }

    /** Appear over the lock screen and turn the display on (wake-word / assist launch). */
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    /** If the wake word is enabled, make sure the always-on listener is running. */
    private fun rearmWakeWord() {
        lifecycleScope.launch {
            val s = SettingsStore(this@MainActivity).settings.first()
            val micOk = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (s.wakeEnabled && micOk) runCatching { WakeWordService.start(this@MainActivity) }
        }
    }

    private fun isAssistIntent(i: Intent?): Boolean =
        i?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true ||
            i?.action == Intent.ACTION_ASSIST

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
