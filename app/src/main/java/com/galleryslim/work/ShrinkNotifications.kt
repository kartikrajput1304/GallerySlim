package com.galleryslim.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.galleryslim.MainActivity
import com.galleryslim.diag.GsLog

/** Channels + builders for the overnight worker's progress and completion notifications. */
object ShrinkNotifications {

    const val PROGRESS_CHANNEL = "gs_overnight_progress"
    const val DONE_CHANNEL = "gs_overnight_done"
    const val PROGRESS_ID = 41
    const val DONE_ID = 42

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL, "Overnight shrink progress", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows while GallerySlim shrinks media overnight" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL, "Overnight shrink results", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Morning summary: originals ready to clear" },
        )
    }

    fun progressNotification(context: Context, done: Int, total: Int, currentName: String?): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle("Shrinking your gallery")
            .setContentText(
                if (total > 0) "$done of $total" + (currentName?.let { " · $it" } ?: "") else "Scanning…",
            )
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Overnight run skipped because the vault is enabled but has no folder (review finding 8). */
    fun postVaultMissing(context: Context) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle("Overnight shrink skipped")
            .setContentText("The vault is on but no vault folder is set. Pick one to resume overnight shrinking.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(DONE_ID, notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — settings UI still shows the unset vault.
        }
    }

    /** Morning summary; tapping deep-links to the pending-confirm screen. */
    fun postCompletion(context: Context, queuedCount: Int, savedBytesLabel: String) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PENDING)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle(
                if (queuedCount > 0) "Overnight shrink finished" else "Overnight shrink — nothing to do",
            )
            .setContentText(
                if (queuedCount > 0) {
                    "Ready to clear: $queuedCount originals · $savedBytesLabel. Tap to clear them."
                } else {
                    "Your gallery is already slim."
                },
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(DONE_ID, notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the pending banner on the dashboard still shows the queue.
        }
    }

    /**
     * Morning summary when a storage floor is set (round 3 item 1). [text]
     * comes from [com.galleryslim.shrinkflow.RoomPlanner.floorMessage]; it
     * never claims space is free before the trash empties. Same channel, id
     * and Ready-to-clear deep link as [postCompletion].
     */
    fun postFloorCompletion(context: Context, belowGoal: Boolean, text: String) {
        postToReadyToClear(
            context,
            id = DONE_ID,
            title = if (belowGoal) "Below your free-space goal" else "Overnight shrink finished",
            text = text,
        )
    }

    /** Trip mode (round 3 item 3): the evening-before run finished. */
    fun postTripDone(context: Context, title: String, text: String) {
        postToReadyToClear(context, id = TRIP_ID, title = title, text = text)
    }

    const val TRIP_ID = 43

    /**
     * A worker was stopped at Android's foreground-service time limit (Android 15+:
     * 6 h a day for dataSync and for mediaProcessing). Finished items are already
     * queued; the rest are picked up on the next run.
     */
    fun postTimeLimit(context: Context, id: Int, title: String) {
        postToReadyToClear(
            context, id = id, title = title,
            text = "Android's daily time limit for background work was reached. What finished is in Ready to clear; " +
                "the rest continues next time.",
        )
    }

    // ------------------------------------------------------------ foreground service type

    /** WorkManager's own foreground service, which hosts promoted workers. */
    const val WORK_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"

    private const val MEDIA_PROCESSING_PERMISSION = "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"

    /**
     * The foreground-service type for transcoding: `mediaProcessing` (added in Android 15
     * for exactly this work) on API 35+, `dataSync` below. Falls back to `dataSync` when the
     * manifest does not (yet) declare `mediaProcessing` for [service] or the matching
     * permission, because starting with an undeclared type throws.
     */
    fun transcodeServiceType(context: Context, service: ComponentName): Int =
        serviceTypes.getOrPut(service.className) { readServiceType(context, service) }

    /** The manifest cannot change while the process lives, so one PackageManager read per service. */
    private val serviceTypes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun readServiceType(context: Context, service: ComponentName): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val declared = try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getServiceInfo(service, PackageManager.ComponentInfoFlags.of(0L)).foregroundServiceType
            } else {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(service, 0).foregroundServiceType
            }
        } catch (e: Exception) {
            GsLog.w("fgs", "service info unreadable for ${service.className}", e)
            0
        }
        val permitted = context.checkSelfPermission(MEDIA_PROCESSING_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return chooseServiceType(Build.VERSION.SDK_INT, declared, permitted)
    }

    /** Pure choice behind [transcodeServiceType] (unit-tested). */
    fun chooseServiceType(sdkInt: Int, declaredTypes: Int, mediaProcessingPermitted: Boolean): Int {
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        return if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM && mediaProcessingPermitted && declaredTypes and media != 0) {
            media
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    /** Progress notification as [ForegroundInfo] for a transcoding worker (overnight, trip). */
    fun workerForegroundInfo(context: Context, done: Int, total: Int, currentName: String?): ForegroundInfo =
        ForegroundInfo(
            PROGRESS_ID,
            progressNotification(context, done, total, currentName),
            transcodeServiceType(context, ComponentName(context, WORK_FOREGROUND_SERVICE)),
        )

    private fun postToReadyToClear(context: Context, id: Int, title: String, text: String) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_PENDING)
        }
        val pi = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the dashboard's Ready to clear banner still shows the queue.
        }
    }
}
