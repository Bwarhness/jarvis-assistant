package dk.foss.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun ConversationScreen(vm: ConversationViewModel, assistTrigger: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state
    val transcript by vm.transcript
    val reply by vm.reply
    val error by vm.error
    val working by vm.working
    val stalled by vm.stalled

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) vm.startListening()
    }

    LaunchedEffect(Unit) {
        vm.resetView() // clear any stale exchange from a previous screen visit
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.startListening()
        }
    }
    // "Hey Jarvis" while already on this screen (idle) re-activates the mic.
    var lastTrigger by remember { mutableStateOf(assistTrigger) }
    LaunchedEffect(assistTrigger) {
        if (assistTrigger != lastTrigger) {
            lastTrigger = assistTrigger
            // Only (re)start from an assist trigger when not already mid-turn.
            if (hasPermission && state == ConvState.Idle) {
                android.util.Log.i("JarvisConv", "assistTrigger fired ($assistTrigger) -> startListening")
                vm.startListening()
            }
        }
    }
    // Stop the conversation (and hand the mic back to "Hey Jarvis") when the app is
    // backgrounded/minimized, not just when navigating away or closing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.stopAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopAll()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {

            IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }

            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (stalled) "Working…" else stateLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (working) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(140.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                if (transcript.isNotEmpty()) {
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (reply.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }

            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state == ConvState.Thinking) {
                    CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 4.dp)
                }
                FloatingActionButton(
                    onClick = { if (hasPermission) vm.onMicTap() else permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                    containerColor = if (state == ConvState.Listening)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Microphone", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

private fun stateLabel(state: ConvState): String = when (state) {
    ConvState.Idle -> "Tap the mic to talk"
    ConvState.Listening -> "Listening…"
    ConvState.Thinking -> "Thinking…"
    ConvState.Speaking -> "Speaking…"
}
