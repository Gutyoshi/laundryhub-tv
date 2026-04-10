package com.laundryhub.tv

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    companion object {
        private const val CHECK_INTERVAL = 10_000L
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "laundryhub_watchdog"

        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("Watchdog", "Failed to start service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "LaundryHub Display",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("Watchdog", "Failed to start foreground", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startWatchdog()
        }
        return START_STICKY
    }

    private fun startWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val exitRequested = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                        .getBoolean("exit_requested", false)

                    if (exitRequested) {
                        Log.d("Watchdog", "Exit requested, stopping")
                        stopSelf()
                        return
                    }

                    if (!isAppInForeground()) {
                        Log.d("Watchdog", "App not in foreground, relaunching")
                        launchApp()
                    }
                } catch (e: Exception) {
                    Log.e("Watchdog", "Watchdog check failed", e)
                }
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun isAppInForeground(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNullOrEmpty()) false
            else tasks[0].topActivity?.packageName == packageName
        } catch (e: Exception) {
            false
        }
    }

    private fun launchApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("Watchdog", "Failed to relaunch", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val exitRequested = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                .getBoolean("exit_requested", false)

            if (!exitRequested) {
                val restartIntent = Intent(applicationContext, WatchdogService::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
                val pendingIntent = PendingIntent.getService(
                    applicationContext, 1, restartIntent, flags
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e("Watchdog", "onTaskRemoved failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            val exitRequested = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
                .getBoolean("exit_requested", false)
            if (!exitRequested) {
                start(applicationContext)
            }
        } catch (e: Exception) {
            Log.e("Watchdog", "onDestroy restart failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("LaundryHub Display Ativo")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
