package com.tracker.amazonwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Without this, a phone restart would silently stop tracking until you
 * manually reopened the app. This restarts the service automatically,
 * but only if a product URL was already configured.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = TrackerPrefs(context)
        if (prefs.productUrl.isBlank()) return

        val serviceIntent = Intent(context, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
