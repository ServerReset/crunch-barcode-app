package com.crunchbarcode.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.crunchbarcode.app.MainActivity
import com.crunchbarcode.app.R

class BarcodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onEnabled(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, BarcodeWidgetProvider::class.java))
        for (id in ids) updateWidget(context, manager, id)
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_barcode)
        val barcodeFile = BarcodeImageProvider.getBarcodeFile(context)

        if (barcodeFile.exists()) {
            val uri = BarcodeImageProvider.getBarcodeUri(context)
            views.setImageViewUri(R.id.widget_barcode_image, uri)
            views.setViewVisibility(R.id.widget_barcode_image, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_placeholder, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_barcode_image, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_placeholder, android.view.View.VISIBLE)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_container, pi)

        manager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        fun pushBarcodeUpdate(context: Context, bitmap: Bitmap) {
            BarcodeImageProvider.saveBarcodeBitmap(context, bitmap)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BarcodeWidgetProvider::class.java))
            val provider = BarcodeWidgetProvider()
            for (id in ids) provider.updateWidget(context, manager, id)
        }
    }
}
