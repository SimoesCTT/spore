package com.spore.torrent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.spore.torrent.data.SettingsRepository
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.spore.torrent.engine.SporeEngine
import java.io.File

class SporeService : Service() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var isOnWifi = true
    private var pausedBySystem = false
    private var wakeLock: PowerManager.WakeLock? = null
    companion object {
        const val EXTRA_MAGNET = "magnet"
    }

    override fun onCreate() {
        super.onCreate()
        com.spore.torrent.data.TorrentDb.init(this)
        try {
            val rssRequest = androidx.work.PeriodicWorkRequestBuilder<RssWorker>(
                30, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "spore_rss",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                rssRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("SporeService", "RSS schedule failed", e)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "spore")
            .setContentTitle("Spore")
            .setContentText("Torrent engine active")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spore::TorrentEngine").apply {
                setReferenceCounted(false)
                acquire()
            }
        // Setup network + screen listeners
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network) ?: return
                    isOnWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    applyWifiPolicy()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    isOnWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    applyWifiPolicy()
                }
                override fun onLost(network: Network) {}
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            networkCallback = callback
            
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> applyScreenPolicy(true)
                        Intent.ACTION_SCREEN_ON -> applyScreenPolicy(false)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(receiver, filter)
            screenReceiver = receiver
        } catch (e: Exception) {
            android.util.Log.e("SporeService", "Listener setup failed", e)
        }

        }

        val saveDir = if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Spore")
        } else {
            File(getExternalFilesDir(null), "Downloads")
        }
        SporeEngine.start(saveDir)

        intent?.getStringExtra(EXTRA_MAGNET)?.let { magnet ->
            SporeEngine.addMagnet(magnet, saveDir)
        }

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("spore", "Spore", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    
    private fun applyWifiPolicy() {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val settings = com.spore.torrent.data.SettingsRepository.settingsFlow(this@SporeService).first()
                if (!settings.wifiOnly) return@launch
                if (isOnWifi) {
                    if (pausedBySystem) {
                        com.spore.torrent.engine.SporeEngine.resumeAll()
                        pausedBySystem = false
                    }
                } else {
                    if (!pausedBySystem) {
                        com.spore.torrent.engine.SporeEngine.pauseAll()
                        pausedBySystem = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SporeService", "wifi policy failed", e)
            }
        }
    }

    private fun applyScreenPolicy(screenOff: Boolean) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val settings = com.spore.torrent.data.SettingsRepository.settingsFlow(this@SporeService).first()
                if (!settings.pauseOnScreenOff) return@launch
                if (screenOff) {
                    com.spore.torrent.engine.SporeEngine.pauseAll()
                } else {
                    com.spore.torrent.engine.SporeEngine.resumeAll()
                }
            } catch (e: Exception) {
                android.util.Log.e("SporeService", "screen policy failed", e)
            }
        }
    }

override fun onDestroy() {
        try {
            networkCallback?.let { (getSystemService(ConnectivityManager::class.java)).unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
        SporeEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
