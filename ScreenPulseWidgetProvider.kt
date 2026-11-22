package com.screenpulse.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ScreenPulseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_RESET -> {
                val store = SotStore(context)
                store.resetCycle("Widget quick reset")
                updateAllWidgets(context)
                // Also ensure service notification updates if running
                if (store.getSnapshot().serviceRunning) {
                    ScreenTimeService.start(context)
                }
            }
            ACTION_WIDGET_REFRESH -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_RESET = "com.screenpulse.app.ACTION_WIDGET_RESET"
        const val ACTION_WIDGET_REFRESH = "com.screenpulse.app.ACTION_WIDGET_REFRESH"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ScreenPulseWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                renderWidget(context, manager, id)
            }
        }

        private fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val store = SotStore(context)
            val snap = store.getSnapshot()

            val views = RemoteViews(context.packageName, R.layout.widget_screen_pulse)
            views.setTextViewText(R.id.widget_sot_value, SotStore.formatDurationCompact(snap.screenOnMs))

            val chargeText = if (snap.isCharging) "Charging · ${snap.batteryPct}%" else "${snap.batteryPct}% battery"
            views.setTextViewText(R.id.widget_meta_line, "$chargeText · ${snap.pickups} pickups")

            val ruleText = when (snap.resetMode) {
                ResetMode.DEFAULT_CHARGE_STOP -> "Auto-reset on unplug"
                ResetMode.MANUAL_ONLY -> "Manual reset"
                ResetMode.CUSTOM -> if (snap.autoResetOnChargeStop) "Unplug ≥ ${snap.minChargeThresholdPct}%" else "Custom manual"
            }
            views.setTextViewText(R.id.widget_rule_line, ruleText)

            // Tap widget body -> open app
            val openIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)

            // Tap Reset button on widget -> instant reset without opening app
            val resetIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 10000,
                Intent(context, ScreenPulseWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_RESET
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_reset_btn, resetIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
