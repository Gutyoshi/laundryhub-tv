package com.laundryhub.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private val bootActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        "android.intent.action.REBOOT"
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in bootActions) {
            Log.d("BootReceiver", "Boot signal received: ${intent.action}")

            // Delay launch 3s - TVs need time to finish booting
            Handler(Looper.getMainLooper()).postDelayed({
                launchApp(context)
            }, 3000)
        }
    }

    private fun launchApp(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to launch, watchdog will retry", e)
        }
    }
}
