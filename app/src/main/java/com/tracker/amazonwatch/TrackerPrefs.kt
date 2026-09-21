package com.tracker.amazonwatch

import android.content.Context

/** Thin wrapper around SharedPreferences for the tracker's config. */
class TrackerPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)

    var productUrl: String
        get() = sp.getString("product_url", "") ?: ""
        set(value) = sp.edit().putString("product_url", value).apply()

    var targetPrice: Double?
        get() = if (sp.contains("target_price")) sp.getFloat("target_price", 0f).toDouble() else null
        set(value) {
            if (value == null) sp.edit().remove("target_price").apply()
            else sp.edit().putFloat("target_price", value.toFloat()).apply()
        }

    var intervalSeconds: Int
        get() = sp.getInt("interval_seconds", 15)
        set(value) = sp.edit().putInt("interval_seconds", value).apply()

    var lastKnownInStock: Boolean
        get() = sp.getBoolean("last_in_stock", false)
        set(value) = sp.edit().putBoolean("last_in_stock", value).apply()
}
