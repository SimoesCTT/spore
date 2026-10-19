package com.spore.torrent.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spore.torrent.data.TorrentRepository
import com.spore.torrent.data.TorrentSnapshot
import com.spore.torrent.engine.SporeEngine
import com.spore.torrent.service.SporeService
import com.spore.torrent.stream.StreamServer
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIncomingMagnet(intent)
        
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        setContent {
            val context = LocalContext.current
            val prefs by com.spore.torrent.data.SettingsRepository
                .settingsFlow(context)
                .collectAsStateWithLifecycle(initialValue = com.spore.torrent.data.SporeSettings())
            SporeTheme(themeMode = prefs.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!prefs.onboarded) {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        OnboardingScreen(onFinish = {
                            scope.launch {
                                com.spore.torrent.data.SettingsRepository.setOnboarded(context)
                            }
                        })
                    } else {
                        TorrentListScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingMagnet(intent)
    }

    private fun handleIncomingMagnet(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!uri.scheme.equals("magnet", ignoreCase = true)) return
        val magnet = uri.toString()
        if (magnet.isBlank()) return
        val serviceIntent = Intent(this, SporeService::class.java)
            .putExtra(SporeService.EXTRA_MAGNET, magnet)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentListScreen() {
    val context = LocalContext.current
    val torrents by TorrentRepository.torrents.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var streamDialogInfoHash by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spore") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add torrent")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (torrents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No torrents. Tap + to add one.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(torrents.entries.toList(), key = { it.key }) { entry ->
                        TorrentRow(
                            key = entry.key,
                            torrent = entry.value,
                            onStream = { streamDialogInfoHash = entry.key }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTorrentDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { magnet ->
                showAddDialog = false
                val intent = Intent(context, SporeService::class.java)
                    .putExtra(SporeService.EXTRA_MAGNET, magnet)
                ContextCompat.startForegroundService(context, intent)
            }
        )
    }

    streamDialogInfoHash?.let { infoHash ->
        StreamFileDialog(
            infoHash = infoHash,
            onDismiss = { streamDialogInfoHash = null }
        )
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    }
}

@Composable
fun TorrentRow(key: String, torrent: TorrentSnapshot, onStream: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(torrent.name.ifEmpty { key.take(8) + "…" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { torrent.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${(torrent.progress * 100).toInt()}%  ↓ ${formatSpeed(torrent.downloadRate)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(torrent.stateName, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val isPaused = torrent.stateName == "PAUSED" ||
                        torrent.stateName == "FINISHED" ||
                        torrent.stateName == "SEEDING"
                if (!isPaused) {
                    IconButton(onClick = { SporeEngine.pause(key) }) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                    }
                } else {
                    IconButton(onClick = { SporeEngine.resume(key) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                    }
                }
                IconButton(onClick = onStream) {
                    Icon(Icons.Default.PlayCircle, contentDescription = "Stream")
                }
                IconButton(onClick = { SporeEngine.remove(key) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                }
            }
        }
    }
}

@Composable
fun StreamFileDialog(infoHash: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val files = remember { SporeEngine.listFiles(infoHash) }
    val videoExts = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "flv", "wmv")
    val videos = files.filter { f ->
        f.name.substringAfterLast('.', "").lowercase() in videoExts
    }
    val toShow = if (videos.isNotEmpty()) videos else files

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stream file") },
        text = {
            if (toShow.isEmpty()) {
                Text("No files available. Wait for metadata.")
            } else {
                Column {
                    toShow.forEach { file ->
                        TextButton(
                            onClick = {
                                val url = StreamServer.urlFor(infoHash, file.index)
                                clipboard.setText(AnnotatedString(url))
                                val mime = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
                                    ?: "video/*"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(url), mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    setPackage("com.cobplayer.app")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    val fallback = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(url), mime)
                                    }
                                    try {
                                        context.startActivity(fallback)
                                    } catch (_: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "URL copied. Install Cob Player to stream.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    file.name.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AddTorrentDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var magnet by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add magnet") },
        text = {
            OutlinedTextField(
                value = magnet,
                onValueChange = { magnet = it },
                label = { Text("magnet:?xt=urn:btih:…") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (magnet.startsWith("magnet:?")) onAdd(magnet) },
                enabled = magnet.startsWith("magnet:?")
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun formatSpeed(bytesPerSec: Int): String = when {
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
    else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
