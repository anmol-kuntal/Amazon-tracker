package com.tracker.amazonwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Runs continuously as a foreground service (Android requires the
 * persistent notification below in exchange for letting a service run
 * indefinitely - you can't hide it, that's the trade-off for reliability).
 *
 * Loop: fetch product page -> parse stock/price -> if it now matches your
 * criteria and didn't before, fire an overlay popup + high-priority
 * notification immediately.
 */
class TrackerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: TrackerPrefs
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val userAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    )

    override fun onCreate() {
        super.onCreate()
        prefs = TrackerPrefs(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification("Starting…"))

        // Partial wake lock keeps the CPU alive for the fetch even if the
        // screen is off; released between cycles so it doesn't drain battery.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmazonTracker::poll")

        serviceScope.launch { pollLoop() }

        // START_STICKY: if the OS kills this service under memory pressure,
        // it will attempt to recreate it automatically.
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val url = prefs.productUrl
        val targetPrice = prefs.targetPrice
        var consecutiveErrors = 0

        while (isActive) {
            try {
                wakeLock?.acquire(20_000L) // safety timeout, auto-releases
                val html = fetchPage(url)
                val result = parseProduct(html)
                consecutiveErrors = 0

                updatePersistentNotification(result)

                if (result.blocked) {
                    updatePersistentNotification(result, extra = "Blocked by Amazon - backing off 5 min")
                    delay(5 * 60_000L)
                } else {
                    val priceOk = targetPrice == null || (result.price != null && result.price <= targetPrice)
                    val hit = result.inStock && priceOk

                    if (hit && !prefs.lastKnownInStock) {
                        triggerAlert(result, url)
                        prefs.lastKnownInStock = true
                    } else if (!hit) {
                        prefs.lastKnownInStock = false
                    }
                }
            } catch (e: Exception) {
                consecutiveErrors++
                updatePersistentNotification(
                    ParseResult(title = "Error", inStock = false, price = null, blocked = false),
                    extra = "Fetch error ($consecutiveErrors): ${e.message}"
                )
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
            }

            // Jittered delay so requests aren't perfectly periodic
            val base = prefs.intervalSeconds.coerceAtLeast(10) * 1000L
            val jitter = (-2000..3000).random()
            delay((base + jitter).coerceAtLeast(5000L))
        }
    }

    private fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgents.random())
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("Empty body")
        }
    }

    private fun parseProduct(html: String): ParseResult {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("#productTitle")?.text()?.trim() ?: "Unknown product"

        val availabilityText = doc.selectFirst("#availability")?.text()?.trim() ?: ""
        val hasAddToCart = doc.selectFirst("#add-to-cart-button, #buy-now-button") != null
        val outOfStockPhrases = listOf("currently unavailable", "out of stock", "temporarily out of stock")
        val looksOut = outOfStockPhrases.any { availabilityText.lowercase().contains(it) }
        val inStock = hasAddToCart && !looksOut

        var price: Double? = null
        val priceSelectors = listOf(
            "span.a-price span.a-offscreen",
            "#priceblock_ourprice",
            "#priceblock_dealprice",
            "#corePrice_feature_div span.a-offscreen"
        )
        for (sel in priceSelectors) {
            val raw = doc.selectFirst(sel)?.text()
            if (!raw.isNullOrBlank()) {
                val digits = raw.replace(Regex("[^0-9.]"), "")
                price = digits.toDoubleOrNull()
                if (price != null) break
            }
        }

        val lowerHtml = html.lowercase()
        val blocked = lowerHtml.contains("captcha") || lowerHtml.contains("sorry, we just need to make sure")

        return ParseResult(title, inStock, price, blocked, availabilityText)
    }

    // ---------------- Alerting ----------------

    private fun triggerAlert(result: ParseResult, productUrl: String) {
        // 1. High-priority notification (works even if overlay permission
        //    somehow isn't granted)
        showAlertNotification(result, productUrl)

        // 2. Full-screen-ish overlay popup drawn over whatever app is open
        val overlayIntent = Intent(this, OverlayPopupService::class.java).apply {
            putExtra("title", result.title)
            putExtra("price", result.price ?: -1.0)
            putExtra("url", productUrl)
        }
        startService(overlayIntent)
    }

    // ---------------- Notifications ----------------

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT, "Tracker status",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows the tracker is running" }

        val alert = NotificationChannel(
            CHANNEL_ALERT, "Stock alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires the instant the product is in stock"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setBypassDnd(true)
        }

        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(alert)
    }

    private fun buildPersistentNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle("Amazon Tracker running")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updatePersistentNotification(result: ParseResult, extra: String? = null) {
        val status = extra ?: buildString {
            append(if (result.inStock) "In stock" else "Out of stock")
            result.price?.let { append(" · ₹%.0f".format(it)) }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_PERSISTENT, buildPersistentNotification(status))
    }

    private fun showAlertNotification(result: ParseResult, productUrl: String) {
        val openIntent = Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(productUrl))
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val priceStr = result.price?.let { "₹%.0f".format(it) } ?: ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("🎉 Back in stock!")
            .setContentText("${result.title} $priceStr")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_PERSISTENT = "tracker_persistent"
        const val CHANNEL_ALERT = "tracker_alert"
        const val NOTIF_ID_PERSISTENT = 1001
        const val NOTIF_ID_ALERT = 1002
    }
}

data class ParseResult(
    val title: String,
    val inStock: Boolean,
    val price: Double?,
    val blocked: Boolean,
    val availabilityText: String = ""
)
