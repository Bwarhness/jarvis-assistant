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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

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

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisColors.Cyan.copy(alpha = 0.5f),
        unfocusedBorderColor = JarvisColors.CyanBorder,
        focusedLabelColor = JarvisColors.Cyan,
        unfocusedLabelColor = JarvisColors.Muted,
        cursorColor = JarvisColors.Cyan,
        focusedTextColor = JarvisColors.TextPrimary,
        unfocusedTextColor = JarvisColors.TextPrimary,
        focusedPlaceholderColor = JarvisColors.Muted,
        unfocusedPlaceholderColor = JarvisColors.Muted,
    )

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Settings",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { persist() }
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = JarvisColors.TextPrimary,
                    ),
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
                SectionHeader("Hermes connection")

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; status = null },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://100.x.x.x:8642") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; status = null },
                    label = { Text("API key (Bearer)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                PillButton(
                    text = "Save & test connection",
                    onClick = {
                        scope.launch {
                            persist()
                            testing = true
                            status = "Testing\u2026"
                            val s = store.settings.first()
                            val result = HermesClient(s.baseUrl, s.apiKey).testConnection()
                            testing = false
                            status = result.fold(
                                onSuccess = { ids ->
                                    "\u2713 Connected. ${ids.size} model(s)" +
                                        if (ids.isNotEmpty()) ": ${ids.take(5).joinToString()}" else ""
                                },
                                onFailure = { "\u2717 ${it.message}" },
                            )
                        }
                    },
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded && !testing && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                )
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                }
                status?.let {
                    Text(
                        it,
                        fontFamily = DmSans,
                        fontSize = 14.sp,
                        color = when {
                            it.startsWith("\u2713") -> JarvisColors.Cyan
                            it.startsWith("Testing") -> JarvisColors.TextSecondary
                            else -> JarvisColors.ErrorOrange
                        },
                    )
                }

                SettingsDivider()
                SectionHeader("Voice (optional)")
                Text(
                    "Leave blank to use your phone's built-in voice. Set an ElevenLabs key for premium speech.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                OutlinedTextField(
                    value = elevenKey,
                    onValueChange = { elevenKey = it },
                    label = { Text("ElevenLabs API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = elevenVoice,
                    onValueChange = { elevenVoice = it },
                    label = { Text("ElevenLabs voice ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                SettingsDivider()
                SectionHeader("System integration")

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "\u201CHey Jarvis\u201D wake word",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Always-on listening (foreground service). Uses battery + a persistent notification.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = wakeEnabled,
                        onCheckedChange = { toggleWake(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                if (wakeEnabled && !overlayGranted) {
                    NeutralButton("Allow \u201Cdisplay over other apps\u201D (needed to open on wake)") { requestOverlay() }
                }
                if (wakeEnabled && !batteryExempt) {
                    NeutralButton("Allow background activity (keep listening always-on)") { requestBattery() }
                }

                Text(
                    "Set Jarvis as your device's digital assistant to launch it with the assist gesture (long-press the power/home button).",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                NeutralButton("Set as default assistant") { openAssistantSettings(context) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = JarvisColors.CyanText,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = JarvisColors.Cyan.copy(alpha = 0.08f),
    )
}

@Composable
private fun NeutralButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.CyanBorder),
    ) {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = JarvisColors.TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
