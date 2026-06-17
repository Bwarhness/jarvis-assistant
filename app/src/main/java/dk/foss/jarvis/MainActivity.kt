package dk.foss.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen

private enum class Screen { Chat, Settings, Conversation }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromAssist = intent?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true ||
            intent?.action == android.content.Intent.ACTION_ASSIST

        setContent {
            JarvisTheme {
                var screen by remember {
                    mutableStateOf(if (fromAssist) Screen.Conversation else Screen.Chat)
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

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
