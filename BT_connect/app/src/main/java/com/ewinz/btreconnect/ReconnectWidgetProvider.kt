package com.ewinz.btreconnect

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class ReconnectWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_CLICK = "com.ewinz.btreconnect.ACTION_WIDGET_CLICK"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)


            // Klik tombol → kirim broadcast ke provider sendiri
            val clickIntent = Intent(context, ReconnectWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetBtn, pi)


            val name = PrefsHelper.getName(context) ?: "Belum ada nama"
            views.setTextViewText(R.id.widgetMac, name)

    
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_CLICK) {
            val svc = Intent(context, ReconnectService::class.java).apply {
                action = ReconnectService.ACTION_RECONNECT
            }
            ContextCompat.startForegroundService(context, svc)
        }
    }
}
