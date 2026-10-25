package com.galleryslim.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.galleryslim.data.GsDatabase
import com.galleryslim.diag.GsLog
import com.galleryslim.safety.MediaStoreGateways
import com.galleryslim.safety.Operations
import com.galleryslim.safety.SafetyGates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * "Last look before permanent purge" (research log round 2): once a day,
 * finds confirmed originals whose trash expiry is within [WARN_WINDOW_MS] and
 * posts one reminder per expiry day, so a regret can still be undone from the
 * gallery's trash. Never trashes, restores or deletes an original itself; it
 * only finishes the bookkeeping of a system dialog the user already answered
 * (Operations.reconcile — e.g. after process death), from MediaStore truth.
 */
class TrashWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        GsLog.init(applicationContext)
        // Resolve any trash/restore/delete dialog whose result was lost to process death (H2).
        try {
            Operations.reconcile(applicationContext)
        } catch (e: Exception) {
            GsLog.w("trash", "reconcile from trash watch failed", e)
        }
        val now = System.currentTimeMillis()
        // CONFIRMED only: FREED originals are gone for good and never probed again.
        val confirmed = GsDatabase.get(applicationContext).shrinkLookupDao().confirmed().first()
        if (confirmed.isEmpty()) return Result.success()
        // ONE batched probe for all originals (M1), and only originals that are verifiably the recorded file.
        val gateway = MediaStoreGateways.get(applicationContext)
        val probes = gateway.probe(confirmed.mapNotNull { SafetyGates.originalIdOf(it) })
        val msVersion = gateway.mediaStoreVersion()
        val expiries = confirmed.mapNotNull { r -> SafetyGates.trashedOriginalExpiry(r, probes, msVersion) }
            .filter { it in now..(now + WARN_WINDOW_MS) }
        if (expiries.isEmpty()) return Result.success()

        val first = expiries.min()
        val prefs = applicationContext.getSharedPreferences("galleryslim_trashwatch", Context.MODE_PRIVATE)
        val dayKey = first / TimeUnit.DAYS.toMillis(1)
        if (prefs.getLong(KEY_LAST_WARNED_DAY, -1) == dayKey) return Result.success()
        prefs.edit().putLong(KEY_LAST_WARNED_DAY, dayKey).apply()

        val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(first))
        ShrinkNotifications.ensureChannels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, ShrinkNotifications.DONE_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle("Last chance to restore ${expiries.size} originals")
            .setContentText("They leave your phone's trash on $date. Open your gallery's trash to restore any you want back.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${expiries.size} originals GallerySlim replaced with smaller copies leave your phone's trash on $date. " +
                    "Open your gallery's trash (Recycle bin) to restore any you want back.",
            ))
            .setAutoCancel(true)
            .build()
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
        GsLog.i("trash", "last-look reminder: ${expiries.size} originals expire from $date")
        return Result.success()
    }

    companion object {
        private const val NAME = "galleryslim-trash-watch"
        private const val NOTIFICATION_ID = 44
        private const val KEY_LAST_WARNED_DAY = "last_warned_day"
        val WARN_WINDOW_MS = TimeUnit.DAYS.toMillis(3)

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrashWatchWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
