package com.screenpulse.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

enum class ResetMode(val id: String) {
    DEFAULT_CHARGE_STOP("default_charge_stop"),
    MANUAL_ONLY("manual_only"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String?): ResetMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT_CHARGE_STOP
    }
}

data class CycleHistoryEntry(
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val pickups: Int,
    val batteryStartPct: Int,
    val batteryEndPct: Int,
    val reason: String
)

data class SotSnapshot(
    val screenOnMs: Long,
    val screenOffMs: Long,
    val pickups: Int,
    val isScreenOn: Boolean,
    val isCharging: Boolean,
    val isManuallyPaused: Boolean,
    val batteryPct: Int,
    val batteryStartPct: Int,
    val cycleStartedWallMs: Long,
    val resetMode: ResetMode,
    val autoResetOnChargeStop: Boolean,
    val minChargeThresholdPct: Int,
    val pauseWhileCharging: Boolean,
    val dailyGoalMinutes: Int,
    val serviceRunning: Boolean,
    val lastResetReason: String
)

class SotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureInitialized(initialScreenOn: Boolean, initialCharging: Boolean, initialBatteryPct: Int) {
        if (!prefs.contains(KEY_CYCLE_STARTED_WALL)) {
            val nowWall = System.currentTimeMillis()
            val nowMono = SystemClock.elapsedRealtime()
            prefs.edit()
                .putLong(KEY_CYCLE_STARTED_WALL, nowWall)
                .putLong(KEY_ACCUMULATED_ON_MS, 0L)
                .putLong(KEY_ACCUMULATED_OFF_MS, 0L)
                .putLong(KEY_LAST_TRANSITION_MONO, nowMono)
                .putInt(KEY_PICKUPS, if (initialScreenOn) 1 else 0)
                .putBoolean(KEY_IS_SCREEN_ON, initialScreenOn)
                .putBoolean(KEY_IS_CHARGING, initialCharging)
                .putBoolean(KEY_IS_PAUSED, false)
                .putInt(KEY_BATTERY_PCT, initialBatteryPct)
                .putInt(KEY_BATTERY_START_PCT, initialBatteryPct)
                .putString(KEY_RESET_MODE, ResetMode.DEFAULT_CHARGE_STOP.id)
                .putBoolean(KEY_AUTO_RESET_CHARGE_STOP, true)
                .putInt(KEY_MIN_CHARGE_THRESHOLD, 0)
                .putBoolean(KEY_PAUSE_WHILE_CHARGING, true)
                .putInt(KEY_DAILY_GOAL_MINUTES, 240)
                .putString(KEY_LAST_RESET_REASON, "Active cycle")
                .apply()
        }
    }

    @Synchronized
    fun flushElapsed(nowMono: Long = SystemClock.elapsedRealtime()) {
        val lastMono = prefs.getLong(KEY_LAST_TRANSITION_MONO, nowMono)
        val delta = if (nowMono >= lastMono) nowMono - lastMono else 0L
        if (delta <= 0L) return

        val isScreenOn = prefs.getBoolean(KEY_IS_SCREEN_ON, true)
        val isCharging = prefs.getBoolean(KEY_IS_CHARGING, false)
        val isPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
        val pauseWhileCharging = prefs.getBoolean(KEY_PAUSE_WHILE_CHARGING, true)

        val editor = prefs.edit().putLong(KEY_LAST_TRANSITION_MONO, nowMono)
        if (!isPaused) {
            if (isScreenOn && !(isCharging && pauseWhileCharging)) {
                val accOn = prefs.getLong(KEY_ACCUMULATED_ON_MS, 0L)
                editor.putLong(KEY_ACCUMULATED_ON_MS, accOn + delta)
            } else if (!isScreenOn) {
                val accOff = prefs.getLong(KEY_ACCUMULATED_OFF_MS, 0L)
                editor.putLong(KEY_ACCUMULATED_OFF_MS, accOff + delta)
            }
        }
        editor.apply()
    }

    @Synchronized
    fun onScreenStateChanged(screenOn: Boolean) {
        flushElapsed()
        val wasOn = prefs.getBoolean(KEY_IS_SCREEN_ON, true)
        val pickups = prefs.getInt(KEY_PICKUPS, 1)
        val nextPickups = if (!wasOn && screenOn) pickups + 1 else pickups
        prefs.edit()
            .putBoolean(KEY_IS_SCREEN_ON, screenOn)
            .putInt(KEY_PICKUPS, nextPickups)
            .putLong(KEY_LAST_TRANSITION_MONO, SystemClock.elapsedRealtime())
            .apply()
    }

    @Synchronized
    fun updateBattery(levelPct: Int, isCharging: Boolean): Boolean {
        flushElapsed()
        val wasCharging = prefs.getBoolean(KEY_IS_CHARGING, false)
        prefs.edit()
            .putInt(KEY_BATTERY_PCT, levelPct.coerceIn(0, 100))
            .putBoolean(KEY_IS_CHARGING, isCharging)
            .putLong(KEY_LAST_TRANSITION_MONO, SystemClock.elapsedRealtime())
            .apply()

        // Transition: Charging -> Stopped Charging (Unplugged)
        if (wasCharging && !isCharging) {
            val mode = ResetMode.fromId(prefs.getString(KEY_RESET_MODE, ResetMode.DEFAULT_CHARGE_STOP.id))
            val autoEnabled = when (mode) {
                ResetMode.DEFAULT_CHARGE_STOP -> true
                ResetMode.MANUAL_ONLY -> false
                ResetMode.CUSTOM -> prefs.getBoolean(KEY_AUTO_RESET_CHARGE_STOP, true)
            }
            val threshold = if (mode == ResetMode.DEFAULT_CHARGE_STOP) {
                0
            } else {
                prefs.getInt(KEY_MIN_CHARGE_THRESHOLD, 0)
            }

            if (autoEnabled && levelPct >= threshold) {
                resetCycle("Charging stopped")
                return true
            }
        }
        return false
    }

    @Synchronized
    fun resetCycle(reason: String) {
        flushElapsed()
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        val onMs = prefs.getLong(KEY_ACCUMULATED_ON_MS, 0L)
        val offMs = prefs.getLong(KEY_ACCUMULATED_OFF_MS, 0L)
        val pickups = prefs.getInt(KEY_PICKUPS, 1)
        val batteryStart = prefs.getInt(KEY_BATTERY_START_PCT, 100)
        val batteryEnd = prefs.getInt(KEY_BATTERY_PCT, 100)
        val startedWall = prefs.getLong(KEY_CYCLE_STARTED_WALL, nowWall)
        val screenOn = prefs.getBoolean(KEY_IS_SCREEN_ON, true)

        if (onMs >= 1000L || offMs >= 1000L) {
            appendHistory(
                CycleHistoryEntry(
                    startedAtWallMs = startedWall,
                    endedAtWallMs = nowWall,
                    screenOnMs = onMs,
                    screenOffMs = offMs,
                    pickups = pickups,
                    batteryStartPct = batteryStart,
                    batteryEndPct = batteryEnd,
                    reason = reason
                )
            )
        }

        prefs.edit()
            .putLong(KEY_CYCLE_STARTED_WALL, nowWall)
            .putLong(KEY_ACCUMULATED_ON_MS, 0L)
            .putLong(KEY_ACCUMULATED_OFF_MS, 0L)
            .putLong(KEY_LAST_TRANSITION_MONO, nowMono)
            .putInt(KEY_PICKUPS, if (screenOn) 1 else 0)
            .putInt(KEY_BATTERY_START_PCT, batteryEnd)
            .putString(KEY_LAST_RESET_REASON, reason)
            .apply()
    }

    @Synchronized
    fun toggleManualPause() {
        flushElapsed()
        val paused = prefs.getBoolean(KEY_IS_PAUSED, false)
        prefs.edit()
            .putBoolean(KEY_IS_PAUSED, !paused)
            .putLong(KEY_LAST_TRANSITION_MONO, SystemClock.elapsedRealtime())
            .apply()
    }

    @Synchronized
    fun setResetMode(mode: ResetMode) {
        val editor = prefs.edit().putString(KEY_RESET_MODE, mode.id)
        when (mode) {
            ResetMode.DEFAULT_CHARGE_STOP -> {
                editor.putBoolean(KEY_AUTO_RESET_CHARGE_STOP, true)
                editor.putInt(KEY_MIN_CHARGE_THRESHOLD, 0)
                editor.putBoolean(KEY_PAUSE_WHILE_CHARGING, true)
            }
            ResetMode.MANUAL_ONLY -> {
                editor.putBoolean(KEY_AUTO_RESET_CHARGE_STOP, false)
            }
            ResetMode.CUSTOM -> Unit
        }
        editor.apply()
    }

    @Synchronized
    fun updateCustomRules(
        autoResetOnChargeStop: Boolean? = null,
        minChargeThresholdPct: Int? = null,
        pauseWhileCharging: Boolean? = null,
        dailyGoalMinutes: Int? = null
    ) {
        flushElapsed()
        val editor = prefs.edit()
        autoResetOnChargeStop?.let {
            editor.putBoolean(KEY_AUTO_RESET_CHARGE_STOP, it)
            editor.putString(KEY_RESET_MODE, ResetMode.CUSTOM.id)
        }
        minChargeThresholdPct?.let {
            editor.putInt(KEY_MIN_CHARGE_THRESHOLD, it)
            editor.putString(KEY_RESET_MODE, ResetMode.CUSTOM.id)
        }
        pauseWhileCharging?.let {
            editor.putBoolean(KEY_PAUSE_WHILE_CHARGING, it)
            editor.putString(KEY_RESET_MODE, ResetMode.CUSTOM.id)
        }
        dailyGoalMinutes?.let {
            editor.putInt(KEY_DAILY_GOAL_MINUTES, it.coerceIn(60, 600))
        }
        editor.apply()
    }

    @Synchronized
    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    @Synchronized
    fun getSnapshot(): SotSnapshot {
        val nowMono = SystemClock.elapsedRealtime()
        val lastMono = prefs.getLong(KEY_LAST_TRANSITION_MONO, nowMono)
        val delta = if (nowMono >= lastMono) nowMono - lastMono else 0L

        val isScreenOn = prefs.getBoolean(KEY_IS_SCREEN_ON, true)
        val isCharging = prefs.getBoolean(KEY_IS_CHARGING, false)
        val isPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
        val pauseWhileCharging = prefs.getBoolean(KEY_PAUSE_WHILE_CHARGING, true)

        var onMs = prefs.getLong(KEY_ACCUMULATED_ON_MS, 0L)
        var offMs = prefs.getLong(KEY_ACCUMULATED_OFF_MS, 0L)

        if (!isPaused) {
            if (isScreenOn && !(isCharging && pauseWhileCharging)) {
                onMs += delta
            } else if (!isScreenOn) {
                offMs += delta
            }
        }

        return SotSnapshot(
            screenOnMs = onMs,
            screenOffMs = offMs,
            pickups = prefs.getInt(KEY_PICKUPS, 1),
            isScreenOn = isScreenOn,
            isCharging = isCharging,
            isManuallyPaused = isPaused,
            batteryPct = prefs.getInt(KEY_BATTERY_PCT, 100),
            batteryStartPct = prefs.getInt(KEY_BATTERY_START_PCT, 100),
            cycleStartedWallMs = prefs.getLong(KEY_CYCLE_STARTED_WALL, System.currentTimeMillis()),
            resetMode = ResetMode.fromId(prefs.getString(KEY_RESET_MODE, ResetMode.DEFAULT_CHARGE_STOP.id)),
            autoResetOnChargeStop = prefs.getBoolean(KEY_AUTO_RESET_CHARGE_STOP, true),
            minChargeThresholdPct = prefs.getInt(KEY_MIN_CHARGE_THRESHOLD, 0),
            pauseWhileCharging = pauseWhileCharging,
            dailyGoalMinutes = prefs.getInt(KEY_DAILY_GOAL_MINUTES, 240),
            serviceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
            lastResetReason = prefs.getString(KEY_LAST_RESET_REASON, "Active cycle") ?: "Active cycle"
        )
    }

    @Synchronized
    fun getHistory(): List<CycleHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
        val list = mutableListOf<CycleHistoryEntry>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CycleHistoryEntry(
                        startedAtWallMs = obj.optLong("startedAt"),
                        endedAtWallMs = obj.optLong("endedAt"),
                        screenOnMs = obj.optLong("screenOnMs"),
                        screenOffMs = obj.optLong("screenOffMs"),
                        pickups = obj.optInt("pickups"),
                        batteryStartPct = obj.optInt("batteryStartPct"),
                        batteryEndPct = obj.optInt("batteryEndPct"),
                        reason = obj.optString("reason", "Manual reset")
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().putString(KEY_HISTORY_JSON, "[]").apply()
    }

    private fun appendHistory(entry: CycleHistoryEntry) {
        val current = getHistory().toMutableList()
        current.add(0, entry)
        val trimmed = current.take(100)
        val arr = JSONArray()
        trimmed.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("startedAt", item.startedAtWallMs)
                    put("endedAt", item.endedAtWallMs)
                    put("screenOnMs", item.screenOnMs)
                    put("screenOffMs", item.screenOffMs)
                    put("pickups", item.pickups)
                    put("batteryStartPct", item.batteryStartPct)
                    put("batteryEndPct", item.batteryEndPct)
                    put("reason", item.reason)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "screenpulse_native_prefs"
        private const val KEY_CYCLE_STARTED_WALL = "cycle_started_wall"
        private const val KEY_ACCUMULATED_ON_MS = "accumulated_on_ms"
        private const val KEY_ACCUMULATED_OFF_MS = "accumulated_off_ms"
        private const val KEY_LAST_TRANSITION_MONO = "last_transition_mono"
        private const val KEY_PICKUPS = "pickups"
        private const val KEY_IS_SCREEN_ON = "is_screen_on"
        private const val KEY_IS_CHARGING = "is_charging"
        private const val KEY_IS_PAUSED = "is_paused"
        private const val KEY_BATTERY_PCT = "battery_pct"
        private const val KEY_BATTERY_START_PCT = "battery_start_pct"
        private const val KEY_RESET_MODE = "reset_mode"
        private const val KEY_AUTO_RESET_CHARGE_STOP = "auto_reset_charge_stop"
        private const val KEY_MIN_CHARGE_THRESHOLD = "min_charge_threshold"
        private const val KEY_PAUSE_WHILE_CHARGING = "pause_while_charging"
        private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_LAST_RESET_REASON = "last_reset_reason"
        private const val KEY_HISTORY_JSON = "history_json"

        fun formatDurationClock(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return String.format("%02d:%02d:%02d", h, m, s)
        }

        fun formatDurationCompact(ms: Long): String {
            val totalMinutes = (ms.coerceAtLeast(0L)) / 60000L
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            return if (h > 0) String.format("%dh %02dm", h, m) else String.format("%dm", m)
        }
    }
}
