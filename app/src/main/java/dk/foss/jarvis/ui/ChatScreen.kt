package dk.foss.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.foss.jarvis.data.UiMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val messages = vm.messages
    val streaming by vm.isStreaming

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            JarvisMark()
                            Text(
                                "Jarvis",
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = JarvisColors.TextPrimary,
                        actionIconContentColor = JarvisColors.Cyan,
                        navigationIconContentColor = JarvisColors.Cyan,
                    ),
                    actions = {
                        IconButton(onClick = onOpenVoice) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice conversation")
                        }
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                        IconButton(onClick = { vm.newConversation() }) {
                            Icon(Icons.Default.Add, contentDescription = "New conversation")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                if (messages.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Ask Jarvis anything",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages) { msg -> MessageBubble(msg) }
                    }
                }

                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    streaming = streaming,
                    onSend = {
                        vm.send(input)
                        input = ""
                    },
                    onStop = { vm.cancel() },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"
    val isError = msg.isError

    val bubbleColor = when {
        isError -> JarvisColors.ErrorOrange.copy(alpha = 0.16f)
        isUser -> JarvisColors.Cyan.copy(alpha = 0.16f)
        else -> JarvisColors.GlassBg
    }
    val borderColor = when {
        isError -> JarvisColors.ErrorOrange.copy(alpha = 0.25f)
        isUser -> JarvisColors.Cyan.copy(alpha = 0.25f)
        else -> JarvisColors.GlassBorder
    }
    val textColor = when {
        isError -> JarvisColors.ErrorOrange
        isUser -> JarvisColors.TextPrimaryAlpha
        else -> JarvisColors.TextPrimary.copy(alpha = 0.9f)
    }
    val shape = when {
        isUser -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
        else -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleColor, shape)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = msg.text.ifEmpty { "\u2026" },
                color = textColor,
                fontFamily = DmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(99.dp)
    Surface(
        color = JarvisColors.GlassBg,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.2f), shape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Message Jarvis",
                        fontFamily = DmSans,
                        color = JarvisColors.Muted,
                    )
                },
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = JarvisColors.Cyan,
                    focusedTextColor = JarvisColors.TextPrimary,
                    unfocusedTextColor = JarvisColors.TextPrimary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (!streaming) onSend() }),
            )
            if (streaming) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = JarvisColors.Cyan,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) JarvisColors.Cyan else JarvisColors.Muted,
                    )
                }
            }
            if (streaming) {
                CircularProgressIndicator(
                    Modifier.padding(start = 4.dp).size(20.dp),
                    strokeWidth = 2.dp,
                    color = JarvisColors.Cyan,
                )
            }
        }
    }
}
