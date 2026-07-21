package com.crunchbarcode.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.crunchbarcode.app.MainActivity
import com.crunchbarcode.app.R

class BarcodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val barcode = prefs(context).getString(PREF_BARCODE, null)
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, barcode)
        }
    }

    override fun onEnabled(context: Context) {
        val barcode = prefs(context).getString(PREF_BARCODE, null)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, BarcodeWidgetProvider::class.java)
        )
        for (id in ids) {
            updateWidget(context, manager, id, barcode)
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        barcode: String?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_barcode)
        if (barcode != null) {
            views.setTextViewText(R.id.widget_barcode_value, barcode)
            views.setViewVisibility(R.id.widget_barcode_value, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_placeholder, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_barcode_value, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_placeholder, android.view.View.VISIBLE)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, pi)

        manager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val PREF_BARCODE = "widget_barcode"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun pushBarcodeUpdate(context: Context, barcode: String) {
            prefs(context).edit().putString(PREF_BARCODE, barcode).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BarcodeWidgetProvider::class.java)
            )
            val provider = BarcodeWidgetProvider()
            for (id in ids) {
                provider.updateWidget(context, manager, id, barcode)
            }
        }
    }
}
