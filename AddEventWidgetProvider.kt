package com.magic3d.gcalsearchadd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * ווידג'ט "+" קטן למסך הבית - לחיצה עליו פותחת ישירות את מסך הוספת האירוע,
 * עם תאריך ברירת המחדל של היום.
 */
class AddEventWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_add_event)

            val intent = Intent(context, AddEventActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AddEventActivity.EXTRA_DATE_MILLIS, MainActivity.startOfDay(System.currentTimeMillis()))
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widgetAddButton, pendingIntent)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
