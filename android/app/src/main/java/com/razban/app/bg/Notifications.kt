package com.razban.app.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.razban.app.R
import com.razban.app.ui.MainActivity

object Notifications {

    private const val CHANNEL = "razban-vpn"
    private const val ID = 1

    fun startForeground(service: Service) {
        ensureChannel(service)
        val notification = build(service, service.getString(R.string.notif_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            service.startForeground(ID, notification)
        }
    }

    fun update(service: Service, text: String) {
        val nm = service.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(ID, build(service, text))
    }

    fun stopForeground(service: Service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    /** Notification requested by the core itself (e.g. a deprecation note). */
    fun fromCore(context: Context, n: io.nekohasekai.libbox.Notification) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            n.identifier.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun build(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_disconnect), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
