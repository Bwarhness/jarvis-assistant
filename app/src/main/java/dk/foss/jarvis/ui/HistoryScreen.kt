package dk.foss.jarvis.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpen: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val items by vm.items

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "History",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.startNew(onOpen) }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New conversation",
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
            if (items.isEmpty()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No conversations yet",
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        color = JarvisColors.Muted,
                    )
                }
            } else {
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    items(items, key = { it.id }) { meta ->
                        Row2(
                            title = meta.title,
                            subtitle = "${DateUtils.getRelativeTimeSpanString(meta.updatedAt)} \u00b7 ${meta.messageCount} msgs",
                            onClick = { vm.open(meta.id) { onOpen() } },
                            onDelete = { vm.delete(meta.id) },
                        )
                        HorizontalDivider(color = JarvisColors.Cyan.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(title: String, subtitle: String, onClick: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Conversation" },
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = JarvisColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontFamily = DmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = JarvisColors.Muted,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = "Delete",
                tint = JarvisColors.Muted,
            )
        }
    }
}
