package com.spore.torrent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spore.torrent.data.SettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsRepository.settingsFlow(context)
        .collectAsStateWithLifecycle(initialValue = com.spore.torrent.data.SporeSettings())

    var dlText by remember(settings.downloadLimitKb) { mutableStateOf(settings.downloadLimitKb.toString()) }
    var ulText by remember(settings.uploadLimitKb) { mutableStateOf(settings.uploadLimitKb.toString()) }
    var portText by remember(settings.listenPort) { mutableStateOf(settings.listenPort.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text("Theme", style = MaterialTheme.typography.titleSmall)

            val themes = listOf("SYSTEM", "LIGHT", "DARK", "AMOLED")
            var currentTheme by remember(settings.themeMode) { mutableStateOf(settings.themeMode) }

            themes.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = currentTheme == mode,
                        onClick = {
                            currentTheme = mode
                            scope.launch { SettingsRepository.updateThemeMode(context, mode) }
                        }
                    )
                    Text(mode.lowercase().replaceFirstChar { it.uppercase() })
                }
            }


            Text("Speed limits (0 = unlimited)", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = dlText,
                onValueChange = { dlText = it.filter { c -> c.isDigit() } },
                label = { Text("Download limit (KB/s)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ulText,
                onValueChange = { ulText = it.filter { c -> c.isDigit() } },
                label = { Text("Upload limit (KB/s)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Network", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() } },
                label = { Text("Listen port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SettingSwitch(
                label = "DHT",
                checked = settings.dhtEnabled,
                onCheckedChange = { scope.launch { SettingsRepository.updateDht(context, it) } }
            )
            SettingSwitch(
                label = "Encryption",
                checked = settings.encryptionEnabled,
                onCheckedChange = { scope.launch { SettingsRepository.updateEncryption(context, it) } }
            )

            Text("Behaviour", style = MaterialTheme.typography.titleSmall)

            SettingSwitch(
                label = "WiFi only",
                checked = settings.wifiOnly,
                onCheckedChange = { scope.launch { SettingsRepository.updateWifiOnly(context, it) } }
            )
            SettingSwitch(
                label = "Pause on screen off",
                checked = settings.pauseOnScreenOff,
                onCheckedChange = { scope.launch { SettingsRepository.updatePauseOnScreenOff(context, it) } }
            )


            Text("RSS feeds", style = MaterialTheme.typography.titleSmall)

            var rssUrl by remember { mutableStateOf("") }
            var rssName by remember { mutableStateOf("") }
            var rssFilter by remember { mutableStateOf("") }
            var rssFeeds by remember { mutableStateOf(listOf<com.spore.torrent.data.RssFeed>()) }
            var feedRefresh by remember { mutableStateOf(0) }

            androidx.compose.runtime.LaunchedEffect(feedRefresh) {
                rssFeeds = com.spore.torrent.data.RssDb.getAllFeeds()
            }

            OutlinedTextField(
                value = rssUrl,
                onValueChange = { rssUrl = it },
                label = { Text("Feed URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rssName,
                onValueChange = { rssName = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rssFilter,
                onValueChange = { rssFilter = it },
                label = { Text("Filter (regex, optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.Button(
                onClick = {
                    if (rssUrl.isNotBlank()) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.spore.torrent.data.RssDb.saveFeed(
                                com.spore.torrent.data.RssFeed(
                                    url = rssUrl.trim(),
                                    name = rssName.trim().ifEmpty { rssUrl.trim() },
                                    filter = rssFilter.trim(),
                                    lastChecked = 0L
                                )
                            )
                            rssUrl = ""
                            rssName = ""
                            rssFilter = ""
                            feedRefresh++
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add feed") }

            rssFeeds.forEach { feed ->
                androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(feed.name, style = MaterialTheme.typography.bodyMedium)
                        Text(feed.url, style = MaterialTheme.typography.bodySmall)
                        if (feed.filter.isNotBlank()) {
                            Text("Filter: ${feed.filter}", style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.spore.torrent.data.RssDb.deleteFeed(feed.url)
                                    feedRefresh++
                                }
                            }
                        ) { Text("Delete") }
                    }
                }
            }

            Text("Apply limits", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Button(
                onClick = {
                    val dl = dlText.toIntOrNull() ?: 0
                    val ul = ulText.toIntOrNull() ?: 0
                    val port = portText.toIntOrNull() ?: 6881
                    scope.launch {
                        SettingsRepository.updateDownloadLimit(context, dl)
                        SettingsRepository.updateUploadLimit(context, ul)
                        SettingsRepository.updatePort(context, port)
                        com.spore.torrent.engine.SporeEngine.applySettings(dl, ul, port)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
