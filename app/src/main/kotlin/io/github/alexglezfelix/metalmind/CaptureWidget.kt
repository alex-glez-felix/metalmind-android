package io.github.alexglezfelix.metalmind

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Una barra en la pantalla de inicio que abre la captura. Con `RemoteViews` y
 * no con Glance: no tiene estado, y así no suma dependencias ni arranque.
 */
class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_capture).apply {
            setOnClickPendingIntent(R.id.widget_root, CaptureLaunch.pendingIntent(context))
        }
        manager.updateAppWidget(ids, views)
    }
}
