package com.tracker.amazonwatch

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Draws a small floating alert window ON TOP of whatever app is currently
 * open (this is what SYSTEM_ALERT_WINDOW / "draw over other apps" enables).
 * This is what makes the alert genuinely impossible to miss, rather than
 * just a notification in the shade.
 */
class OverlayPopupService : Service() {

    private var windowManager: WindowManager? = null
    private var popupView: View? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            // Permission not granted - can't draw the overlay, bail out.
            // The regular notification from TrackerService still fires.
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra("title") ?: "Product"
        val price = intent?.getDoubleExtra("price", -1.0) ?: -1.0
        val url = intent?.getStringExtra("url") ?: ""

        showOverlay(title, price, url)
        startLoopingAlarm()
        startLoopingVibration()

        return START_NOT_STICKY
    }

    private fun showOverlay(title: String, price: Double, url: String) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        popupView = inflater.inflate(R.layout.overlay_popup, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP

        popupView?.findViewById<TextView>(R.id.overlayTitle)?.text = title
        popupView?.findViewById<TextView>(R.id.overlayPrice)?.text =
            if (price > 0) "₹%.0f - In Stock!".format(price) else "In Stock!"

        popupView?.findViewById<View>(R.id.overlayOpenButton)?.setOnClickListener {
            val openIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(openIntent)
            dismiss()
        }
        popupView?.findViewById<View>(R.id.overlayDismissButton)?.setOnClickListener { dismiss() }

        windowManager?.addView(popupView, params)

        // Auto-dismiss after 20 seconds if the user doesn't interact
        popupView?.postDelayed({ dismiss() }, 20_000L)
    }

    /**
     * Loops the device's ALARM sound (same stream as a wake-up alarm) for as
     * long as the popup is showing, instead of a single one-shot ping.
     * Alarm-stream audio also ignores the media volume slider, so it plays
     * even if the phone was in normal/silent-for-media mode - though a true
     * Do Not Disturb / silent (ringer) mode can still suppress it depending
     * on the phone's settings.
     */
    private fun startLoopingAlarm() {
        try {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@OverlayPopupService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { /* best-effort - popup still shows visually if audio fails */ }
    }

    private fun startLoopingVibration() {
        try {
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // repeat index 0 = loop the whole pattern indefinitely
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 600), 0)
                )
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun stopAudioAndVibration() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
    }

    private fun dismiss() {
        stopAudioAndVibration()
        popupView?.let { windowManager?.removeView(it) }
        popupView = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioAndVibration()
        popupView?.let { runCatching { windowManager?.removeView(it) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
