package com.laundryhub.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class KioskAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchTime = 0L
    private val launchCooldown = 3000L // 3s cooldown to avoid loops

    // Known launcher packages - ONLY relaunch when these appear
    private val launcherKeywords = listOf(
        "launcher",
        "zeasn",
        "toptech",
        "tvlauncher",
        "home",
        "homescreen"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("KioskA11y", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        try {
            val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("launcher_enabled", false)) return
            if (prefs.getBoolean("exit_requested", false)) return

            val pausedUntil = prefs.getLong("paused_until", 0L)
            if (System.currentTimeMillis() < pausedUntil) return

            val pkg = event.packageName?.toString()?.lowercase() ?: return

            // Never react to our own app
            if (pkg == packageName.lowercase()) return

            // Only relaunch if detected package LOOKS like a launcher
            val isLauncher = launcherKeywords.any { pkg.contains(it) }
            if (!isLauncher) return

            val now = System.currentTimeMillis()
            if (now - lastLaunchTime < launchCooldown) return
            lastLaunchTime = now

            Log.d("KioskA11y", "Launcher detected: $pkg, relaunching app")
            handler.postDelayed({ launchMainActivity() }, 500)
        } catch (e: Exception) {
            Log.e("KioskA11y", "Error in event handler", e)
        }
    }

    override fun onInterrupt() {}

    private fun launchMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("KioskA11y", "Launch failed", e)
        }
    }
}
