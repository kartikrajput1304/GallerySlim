package com.galleryslim.shrinkflow

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.galleryslim.diag.GsLog
import com.galleryslim.work.ShrinkNotifications

/**
 * Foreground service held for the duration of a manual (in-app) batch. It does
 * no work itself — the batch runs in [ShrinkViewModel] in this same process —
 * but its presence keeps the process at foreground priority, so leaving the app
 * mid-batch doesn't get it frozen or its hardware codecs starved (seen on a
 * Galaxy S24: Samsung's freezer froze a backgrounded transcode).
 *
 * Foreground type: `mediaProcessing` on Android 15+ (the type made for transcoding),
 * `dataSync` below — see [ShrinkNotifications.transcodeServiceType]. Both types have a
 * daily time limit on Android 15+, handled in [onTimeout].
 */
class BatchKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+: dataSync and mediaProcessing services share a 6-hour-a-day limit. The
     * system calls this when it is used up and crashes the app unless the service leaves
     * the foreground within seconds, so drop foreground priority and stop at once.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val type = when (fgsType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING -> "mediaProcessing"
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
            else -> "type $fgsType"
        }
        GsLog.w("batch", "foreground time limit reached ($type) — keep-alive stopped; the batch continues while the app is open")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ShrinkNotifications.progressNotification(this, 0, 0, null)
        try {
            val type = ShrinkNotifications.transcodeServiceType(
                this, ComponentName(this, BatchKeepAliveService::class.java),
            )
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            GsLog.i("batch", "keep-alive foreground service started (type $type)")
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if started from the background.
            GsLog.e("batch", "keep-alive could not go foreground — batch may be frozen if the app is left", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        GsLog.i("batch", "keep-alive foreground service stopped")
        super.onDestroy()
    }

    companion object {
        /** Own id: 41–45 are taken by worker progress, results, trip, trash watch and sharing. */
        private const val NOTIFICATION_ID = 46

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, BatchKeepAliveService::class.java))
            } catch (e: Exception) {
                GsLog.e("batch", "keep-alive start refused", e)
            }
        }

        fun update(context: Context, p: BatchProgress) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, ShrinkNotifications.progressNotification(context, p.done, p.total, p.currentName))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatchKeepAliveService::class.java))
        }
    }
}
