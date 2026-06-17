package dk.foss.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import dk.foss.jarvis.wake.WakeWordService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(SettingsStore.DEFAULT_MODEL) }
    var elevenKey by remember { mutableStateOf("") }
    var elevenVoice by remember { mutableStateOf(SettingsStore.DEFAULT_ELEVEN_VOICE) }
    fun isBatteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var wakeEnabled by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = AndroidSettings.canDrawOverlays(context) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryExempt = isBatteryExempt() }

    fun requestOverlay() {
        overlayLauncher.launch(
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    fun requestBattery() {
        batteryLauncher.launch(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    suspend fun persist() {
        store.updateConnection(baseUrl, apiKey, model)
        store.updateVoice(elevenKey, elevenVoice)
    }

    fun enableWake() {
        scope.launch { store.updateWake(true) }
        WakeWordService.start(context)
        wakeEnabled = true
        // For reliable always-on: launch-from-background + survive battery optimization.
        if (!overlayGranted) requestOverlay() else if (!batteryExempt) requestBattery()
    }

    val wakePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) enableWake()
    }

    fun toggleWake(on: Boolean) {
        if (on) {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) enableWake() else wakePermLauncher.launch(needed.toTypedArray())
        } else {
            scope.launch { store.updateWake(false) }
            WakeWordService.stop(context)
            wakeEnabled = false
        }
    }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        model = s.model
        elevenKey = s.elevenKey
        elevenVoice = s.elevenVoiceId
        wakeEnabled = s.wakeEnabled
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { persist() }
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hermes connection", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; status = null },
                label = { Text("Base URL") },
                placeholder = { Text("http://100.x.x.x:8642") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; status = null },
                label = { Text("API key (Bearer)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        persist()
                        testing = true
                        status = "Testing…"
                        val s = store.settings.first()
                        val result = HermesClient(s.baseUrl, s.apiKey).testConnection()
                        testing = false
                        status = result.fold(
                            onSuccess = { ids ->
                                "✓ Connected. ${ids.size} model(s)" +
                                    if (ids.isNotEmpty()) ": ${ids.take(5).joinToString()}" else ""
                            },
                            onFailure = { "✗ ${it.message}" },
                        )
                    }
                },
                enabled = loaded && !testing && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                Text("Save & test connection")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Divider(Modifier.padding(vertical = 8.dp))
            Text("Voice (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Leave blank to use your phone's built-in voice. Set an ElevenLabs key " +
                    "for premium speech.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = elevenKey,
                onValueChange = { elevenKey = it },
                label = { Text("ElevenLabs API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = elevenVoice,
                onValueChange = { elevenVoice = it },
                label = { Text("ElevenLabs voice ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Divider(Modifier.padding(vertical = 8.dp))
            Text("System integration", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("“Hey Jarvis” wake word", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Always-on listening (foreground service). Uses battery + a " +
                            "persistent notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wakeEnabled, onCheckedChange = { toggleWake(it) })
            }

            if (wakeEnabled && !overlayGranted) {
                OutlinedButton(onClick = { requestOverlay() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow “display over other apps” (needed to open on wake)")
                }
            }
            if (wakeEnabled && !batteryExempt) {
                OutlinedButton(onClick = { requestBattery() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow background activity (keep listening always-on)")
                }
            }

            Text(
                "Set Jarvis as your device's digital assistant to launch it with the " +
                    "assist gesture (long-press the power/home button).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { openAssistantSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set as default assistant")
            }
        }
    }
}

private fun openAssistantSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
