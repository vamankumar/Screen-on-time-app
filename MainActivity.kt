package com.screenpulse.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        ScreenTimeService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+ so Foreground Service notification displays cleanly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ScreenTimeService.start(this)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3B82F6),
                    background = Color(0xFF090D16),
                    surface = Color(0xFF111827),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScreenPulseAppScreen()
                }
            }
        }
    }
}

@Composable
fun ScreenPulseAppScreen() {
    val context = LocalContext.current
    val store = remember { SotStore(context) }

    var snapshot by remember { mutableStateOf(store.getSnapshot()) }
    var history by remember { mutableStateOf(store.getHistory()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = store.getSnapshot()
            history = store.getHistory()
            delay(1000L)
        }
    }

    val isBatteryOptIgnored = remember(snapshot.serviceRunning) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 44.dp, bottom = 36.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ScreenPulse",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = if (snapshot.serviceRunning) "Background service active" else "Background service stopped",
                        fontSize = 12.sp,
                        color = if (snapshot.serviceRunning) Color(0xFF34D399) else Color(0xFFF59E0B)
                    )
                }

                Button(
                    onClick = {
                        store.resetCycle("Manual reset")
                        snapshot = store.getSnapshot()
                        history = store.getHistory()
                        ScreenPulseWidgetProvider.updateAllWidgets(context)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reset Now")
                }
            }
        }

        // Main Screen-On Time Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        snapshot.isCharging && snapshot.pauseWhileCharging -> "Charging · Paused"
                        snapshot.isManuallyPaused -> "Paused by user"
                        else -> "Screen-On Time"
                    },
                    fontSize = 13.sp,
                    color = if (snapshot.isCharging) Color(0xFF34D399) else Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = SotStore.formatDurationClock(snapshot.screenOnMs),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFF8FAFC)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Standby ${SotStore.formatDurationCompact(snapshot.screenOffMs)}  ·  ${snapshot.pickups} pickups  ·  ${snapshot.batteryPct}%",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            store.toggleManualPause()
                            snapshot = store.getSnapshot()
                            ScreenPulseWidgetProvider.updateAllWidgets(context)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (snapshot.isManuallyPaused) "Resume" else "Pause")
                    }

                    OutlinedButton(
                        onClick = {
                            if (snapshot.serviceRunning) {
                                ScreenTimeService.stop(context)
                            } else {
                                ScreenTimeService.start(context)
                            }
                            snapshot = store.getSnapshot()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (snapshot.serviceRunning) "Stop Service" else "Start Service")
                    }
                }
            }
        }

        // Background Reliability Card (Battery Optimization Exemption)
        if (!isBatteryOptIgnored) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Keep Background Tracking Reliable",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Exclude ScreenPulse from Android battery optimization so OEM task killers never pause screen-on tracking.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Allow Unrestricted Background")
                    }
                }
            }
        }

        // Reset Control Rules Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Reset Mode & Automation",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(
                        ResetMode.DEFAULT_CHARGE_STOP to "Default (Unplug)",
                        ResetMode.MANUAL_ONLY to "Manual Only",
                        ResetMode.CUSTOM to "Custom"
                    )
                    modes.forEach { (mode, label) ->
                        FilterChip(
                            selected = snapshot.resetMode == mode,
                            onClick = {
                                store.setResetMode(mode)
                                snapshot = store.getSnapshot()
                                ScreenPulseWidgetProvider.updateAllWidgets(context)
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-reset when charging stops", fontSize = 14.sp, color = Color(0xFFF8FAFC))
                        Text("Resets SOT immediately upon unplugging", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                    Switch(
                        checked = snapshot.autoResetOnChargeStop,
                        onCheckedChange = { enabled ->
                            store.updateCustomRules(autoResetOnChargeStop = enabled)
                            snapshot = store.getSnapshot()
                            ScreenPulseWidgetProvider.updateAllWidgets(context)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause while plugged in", fontSize = 14.sp, color = Color(0xFFF8FAFC))
                        Text("Only counts screen time on battery", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                    Switch(
                        checked = snapshot.pauseWhileCharging,
                        onCheckedChange = { enabled ->
                            store.updateCustomRules(pauseWhileCharging = enabled)
                            snapshot = store.getSnapshot()
                        }
                    )
                }

                if (snapshot.autoResetOnChargeStop) {
                    Text(
                        text = "Minimum battery to trigger unplug reset: ${if (snapshot.minChargeThresholdPct == 0) "Any unplug" else "≥ ${snapshot.minChargeThresholdPct}%"}",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 80, 90, 100).forEach { pct ->
                            FilterChip(
                                selected = snapshot.minChargeThresholdPct == pct,
                                onClick = {
                                    store.updateCustomRules(minChargeThresholdPct = pct)
                                    snapshot = store.getSnapshot()
                                    ScreenPulseWidgetProvider.updateAllWidgets(context)
                                },
                                label = { Text(if (pct == 0) "Any" else "$pct%", fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        // History Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cycle History (${history.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = {
                        store.clearHistory()
                        history = emptyList()
                    }) {
                        Text("Clear")
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    text = "Past Screen-On Time cycles will appear here after unplugging your charger or tapping Reset.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        } else {
            items(history) { item ->
                val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = fmt.format(Date(item.endedAtWallMs)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "${item.reason} · ${item.batteryStartPct}% → ${item.batteryEndPct}% · ${item.pickups} pickups",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Text(
                        text = SotStore.formatDurationCompact(item.screenOnMs),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF3B82F6)
                    )
                }
            }
        }
    }
}
