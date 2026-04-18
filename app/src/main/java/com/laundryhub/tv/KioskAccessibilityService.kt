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
    private val launchCooldown = 1500L // Avoid rapid relaunches

    // Packages we allow to stay on top (system UI, our own app, settings when user is configuring)
    private val allowedPackages = setOf(
        "com.laundryhub.tv",
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("KioskA11y", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        try {
            // Check if kiosk mode is currently enabled
            val prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("launcher_enabled", false)) return

            // Check if user explicitly requested exit
            if (prefs.getBoolean("exit_requested", false)) return

            // Check if watchdog is paused
            val pausedUntil = prefs.getLong("paused_until", 0L)
            if (System.currentTimeMillis() < pausedUntil) return

            val pkg = event.packageName?.toString() ?: return

            // If a foreign package is foreground, bring our app back
            if (pkg !in allowedPackages) {
                val now = System.currentTimeMillis()
                if (now - lastLaunchTime < launchCooldown) return
                lastLaunchTime = now

                Log.d("KioskA11y", "Foreign pkg detected: $pkg, relaunching app")
                handler.postDelayed({
                    launchMainActivity()
                }, 300)
            }
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
