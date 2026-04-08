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
        private const val CHECK_INTERVAL = 10_000L // 10 seconds
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "laundryhub_watchdog"

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startWatchdog()
        }
        return START_STICKY // Android restarts this if killed
    }

    private fun startWatchdog() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isAppInForeground()) {
                    Log.d("Watchdog", "App not in foreground, relaunching")
                    launchApp()
                }
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = am.getRunningTasks(1)
        if (tasks.isNullOrEmpty()) return false
        return tasks[0].topActivity?.packageName == packageName
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
        // If app is swiped away, schedule restart
        val restartIntent = Intent(applicationContext, WatchdogService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        start(applicationContext) // Restart yourself
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
