package com.galleryslim.storage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.galleryslim.MainActivity
import com.galleryslim.R
import java.util.concurrent.TimeUnit

/** Home-screen widget: free space, "full in about N days", and a Shrink now button. */
class StorageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        StorageSamples.record(context)
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    override fun onEnabled(context: Context) = StorageSampleWorker.schedule(context)

    companion object {
        fun forecastText(days: Int?): String = when {
            days == null -> "Tracking how fast it fills up"
            days == 0 -> "Almost full"
            days > 365 -> "Plenty of room at this rate"
            else -> "Full in about $days days at this rate"
        }

        fun views(context: Context): RemoteViews {
            val free = StorageForecast.freeBytes()
            val total = StorageForecast.totalBytes().coerceAtLeast(1)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return RemoteViews(context.packageName, R.layout.widget_storage).apply {
                setTextViewText(R.id.widget_free, "%.1f GB free".format(free / 1e9))
                setTextViewText(R.id.widget_forecast, forecastText(StorageSamples.daysUntilFull(context)))
                setProgressBar(R.id.widget_bar, 100, (100 - free * 100 / total).toInt(), false)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_button, open)
            }
        }

        /** Refreshes every placed widget (after a batch or cleanup). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StorageWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }
    }
}

/** Twice a day: record a free-space sample and refresh the widget. */
class StorageSampleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        StorageSamples.record(applicationContext)
        StorageWidget.refresh(applicationContext)
        LowSpaceAlert.checkAndNotify(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "storage-samples", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StorageSampleWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}

/** "Running low. Make room now?" at most once a day when free space drops under 2 GB. */
object LowSpaceAlert {
    const val THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024
    private const val DAY_MS = 86_400_000L

    /** Pure: alert when under the threshold and the last alert is a day old. */
    fun shouldAlert(freeBytes: Long, lastAlertMs: Long, nowMs: Long): Boolean =
        freeBytes < THRESHOLD_BYTES && nowMs - lastAlertMs >= DAY_MS

    fun checkAndNotify(context: Context) {
        val prefs = context.getSharedPreferences("galleryslim_low_space", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val free = StorageForecast.freeBytes()
        if (!shouldAlert(free, prefs.getLong("last", 0L), now)) return
        prefs.edit().putLong("last", now).apply()
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("low_space", "Low space", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 9,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_MAKE_ROOM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(53, androidx.core.app.NotificationCompat.Builder(context, "low_space")
                .setSmallIcon(R.drawable.ic_stat_galleryslim)
                .setContentTitle("Running low: %.1f GB free".format(free / 1e9))
                .setContentText("Make room now, before the camera says it's full?")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
