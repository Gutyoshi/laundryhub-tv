package com.laundryhub.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.Context
import android.util.Log

class LaundryHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            requestBatteryOptimizationExemption()
        } catch (e: Exception) {
            Log.e("LaundryHubApp", "Battery exemption failed", e)
        }
        try {
            WatchdogService.start(this)
        } catch (e: Exception) {
            Log.e("LaundryHubApp", "Watchdog start failed", e)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some TVs don't support this intent - that's OK
                    Log.e("LaundryHubApp", "Battery optimization not supported", e)
                }
            }
        }
    }
}
