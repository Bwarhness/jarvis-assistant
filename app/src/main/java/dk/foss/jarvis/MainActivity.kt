package dk.foss.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                val vm: ChatViewModel = viewModel()
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
