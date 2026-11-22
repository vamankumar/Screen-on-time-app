package com.screenpulse.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ScreenTimeService : Service() {

    private lateinit var store: SotStore

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    store.onScreenStateChanged(true)
                    refreshUiAndWidgets()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    store.onScreenStateChanged(false)
                    refreshUiAndWidgets()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    val (pct, _) = readBatteryStatus()
                    store.updateBattery(levelPct = pct, isCharging = true)
                    refreshUiAndWidgets()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val (pct, _) = readBatteryStatus()
                    store.updateBattery(levelPct = pct, isCharging = false)
                    refreshUiAndWidgets()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val pct = if (level >= 0) (level * 100) / scale else store.getSnapshot().batteryPct
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    store.updateBattery(levelPct = pct, isCharging = charging)
                    refreshUiAndWidgets()
                }
                Intent.ACTION_TIME_TICK -> {
                    store.flushElapsed()
                    refreshUiAndWidgets()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SotStore(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive
        val (pct, charging) = readBatteryStatus()

        store.ensureInitialized(isScreenOn, charging, pct)
        store.onScreenStateChanged(isScreenOn)
        store.updateBattery(pct, charging)
        store.setServiceRunning(true)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(
            this,
            systemEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ScreenPulseWidgetProvider.updateAllWidgets(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_NOW -> {
                store.resetCycle("Notification reset")
                refreshUiAndWidgets()
            }
            ACTION_STOP_SERVICE -> {
                store.flushElapsed()
                store.setServiceRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                refreshUiAndWidgets()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(systemEventReceiver) }
        store.flushElapsed()
        store.setServiceRunning(false)
        ScreenPulseWidgetProvider.updateAllWidgets(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readBatteryStatus(): Pair<Int, Boolean> {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Pair((level * 100) / scale, charging)
    }

    private fun refreshUiAndWidgets() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        ScreenPulseWidgetProvider.updateAllWidgets(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen-On Time Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent live Screen-On Time tracker in background"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val snap = store.getSnapshot()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenTimeService::class.java).apply { action = ACTION_RESET_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ruleLabel = when (snap.resetMode) {
            ResetMode.DEFAULT_CHARGE_STOP -> "Auto-reset on unplug"
            ResetMode.MANUAL_ONLY -> "Manual reset only"
            ResetMode.CUSTOM -> if (snap.autoResetOnChargeStop) "Unplug ≥ ${snap.minChargeThresholdPct}%" else "Custom manual"
        }

        val stateText = when {
            snap.isCharging && snap.pauseWhileCharging -> "Charging · Paused"
            snap.isManuallyPaused -> "Paused"
            else -> "Active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screen-On Time: ${SotStore.formatDurationCompact(snap.screenOnMs)}")
            .setContentText("$stateText · ${snap.batteryPct}% · $ruleLabel")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Reset Now", resetIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "screenpulse_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_RESET_NOW = "com.screenpulse.app.ACTION_NOTIFICATION_RESET"
        const val ACTION_STOP_SERVICE = "com.screenpulse.app.ACTION_STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ScreenTimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenTimeService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
