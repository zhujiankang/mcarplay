package com.mcarplay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.carlink.CarlinkActivity
import com.mcarplay.app.carlink.CarlinkRuntime
import java.security.SecureRandom

class CarlinkReceiverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (!CarlinkRuntime.status().ready) stopSelf() }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("carlink-input", getString(R.string.carlink_experiment_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarlinkActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(
            3101,
            Notification
                .Builder(this, "carlink-input")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.carlink_experiment_title))
                .setContentText(getString(R.string.carlink_notification))
                .setContentIntent(open)
                .build(),
        )
        CarlinkRuntime.start((SecureRandom().nextInt(900000) + 100000).toString(), SessionController::appendLog)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 120000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        CarlinkRuntime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
