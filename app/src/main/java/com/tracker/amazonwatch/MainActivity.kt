package com.tracker.amazonwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tracker.amazonwatch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: TrackerPrefs

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications are required for alerts to show", Toast.LENGTH_LONG).show()
            }
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = TrackerPrefs(this)

        binding.productUrlInput.setText(prefs.productUrl)
        prefs.targetPrice?.let { binding.targetPriceInput.setText(it.toString()) }
        binding.intervalInput.setText(prefs.intervalSeconds.toString())

        binding.btnNotificationPerm.setOnClickListener { requestNotificationPermission() }
        binding.btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        binding.btnBatteryPerm.setOnClickListener { requestBatteryExemption() }

        binding.btnStart.setOnClickListener { startTracking() }
        binding.btnStop.setOnClickListener { stopTracking() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        binding.statusNotification.text = "Notifications: ${if (hasNotificationPermission()) "✅ granted" else "❌ required"}"
        binding.statusOverlay.text = "Draw over other apps: ${if (hasOverlayPermission()) "✅ granted" else "❌ required"}"
        binding.statusBattery.text = "Battery optimization ignored: ${if (hasBatteryExemption()) "✅ granted" else "❌ required"}"

        val allGranted = hasNotificationPermission() && hasBatteryExemption()
        binding.btnStart.isEnabled = allGranted
        binding.hintText.visibility = if (allGranted) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissionStatus()
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Enable \"Allow display over other apps\" for this app, then come back", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasBatteryExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (!hasBatteryExemption()) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Toast.makeText(
                    this,
                    "Find \"Amazon Tracker\" in the list and set it to unrestricted / not optimized",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startTracking() {
        val url = binding.productUrlInput.text.toString().trim()
        if (url.isBlank() || !url.contains("amazon.")) {
            Toast.makeText(this, "Enter a valid Amazon product URL", Toast.LENGTH_SHORT).show()
            return
        }
        val priceText = binding.targetPriceInput.text.toString().trim()
        val targetPrice = priceText.toDoubleOrNull()
        val interval = binding.intervalInput.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: 15

        prefs.productUrl = url
        prefs.targetPrice = targetPrice
        prefs.intervalSeconds = interval

        val intent = Intent(this, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Tracking started - check the persistent notification", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        stopService(Intent(this, TrackerService::class.java))
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }
}
