package com.galleryslim.report

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.galleryslim.MainActivity
import com.galleryslim.R
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.shrinkflow.ShrinkController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A month of GallerySlim, in plain numbers. */
data class MonthReport(
    val month: YearMonth,
    val shrunkCount: Int,
    val shrinkSavedBytes: Long,
    val clearedCount: Int,
    val clearedBytes: Long,
) {
    val totalFreedBytes: Long get() = shrinkSavedBytes + clearedBytes
    /** "Room for about N more photos", at a typical 3 MB camera photo. */
    val roomForPhotos: Long get() = totalFreedBytes / TYPICAL_PHOTO_BYTES
    val title: String get() = "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"

    companion object {
        const val TYPICAL_PHOTO_BYTES = 3_000_000L

        /** Pure: builds the report from confirmed shrink records and the Clean up tally. */
        fun of(month: YearMonth, confirmed: List<ShrinkRecord>, cleared: Pair<Int, Long>, zone: ZoneId = ZoneId.systemDefault()): MonthReport {
            val inMonth = confirmed.filter {
                (it.status == ShrinkController.STATUS_CONFIRMED || it.status == ShrinkController.STATUS_FREED) &&
                    YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(zone)) == month
            }
            return MonthReport(
                month, inMonth.size, inMonth.sumOf { (it.originalBytes - it.newBytes).coerceAtLeast(0) },
                cleared.first, cleared.second,
            )
        }
    }
}

/** Running tally of items deleted through Clean up, Quick review, Duplicates and Move to computer, per month. */
object CleanupTally {
    private const val PREFS = "galleryslim_cleanup_tally"

    fun record(context: Context, count: Int, bytes: Long, month: YearMonth = YearMonth.now()) {
        if (count <= 0) return
        val p = prefs(context)
        val (c, b) = get(context, month)
        p.edit().putString(month.toString(), "${c + count},${b + bytes}").apply()
    }

    fun get(context: Context, month: YearMonth): Pair<Int, Long> =
        prefs(context).getString(month.toString(), null)?.split(',')?.let { p ->
            (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
        } ?: (0 to 0L)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object ReportCard {
    /** Draws a 1080×1080 card with the month's numbers (no personal data, no photos). */
    fun render(r: MonthReport): Bitmap {
        val size = 1080
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF1F3A5F.toInt(), 0xFF2E7D6B.toInt(), Shader.TileMode.CLAMP)
        })
        fun text(s: String, y: Float, sizePx: Float, bold: Boolean = false, alpha: Int = 255) =
            c.drawText(s, 90f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); this.alpha = alpha; textSize = sizePx
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            })
        text("My ${r.title} with GallerySlim", 190f, 52f, alpha = 210)
        text("Freed ${gb(r.totalFreedBytes)}", 400f, 120f, bold = true)
        text("${r.shrunkCount} photos & videos made smaller", 540f, 50f)
        text("${r.clearedCount} unwanted items cleared", 620f, 50f)
        text("Room for about ${r.roomForPhotos} more photos", 760f, 56f, bold = true)
        text("Done on my phone. Nothing uploaded.", 960f, 40f, alpha = 190)
        return bmp
    }

    fun share(context: Context, r: MonthReport) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "gallery-slim-${r.month}.png")
        file.outputStream().use { render(r).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diag", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "I freed ${gb(r.totalFreedBytes)} on my phone this month with GallerySlim")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share your month").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun gb(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format(bytes / 1_000_000L)
}

/** Daily check; on the first days of a month, posts last month's report once (only if something was freed). */
class MonthlyReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val now = YearMonth.now()
        val last = now.minusMonths(1)
        val prefs = ctx.getSharedPreferences("galleryslim_report", Context.MODE_PRIVATE)
        if (prefs.getString("posted", null) == last.toString()) return Result.success()
        val confirmed = runBlocking { GsDatabase.get(ctx).shrinkLookupDao().confirmedOrFreed().first() }
        val r = MonthReport.of(last, confirmed, CleanupTally.get(ctx, last))
        prefs.edit().putString("posted", last.toString()).apply()
        if (r.totalFreedBytes <= 0) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return Result.success()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monthly report", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, 7,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_REPORT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_galleryslim)
                    .setContentTitle("Your ${r.title}: ${ReportCard.gb(r.totalFreedBytes)} freed")
                    .setContentText("Room for about ${r.roomForPhotos} more photos. Tap to see and share.")
                    .setContentIntent(open).setAutoCancel(true).build(),
            )
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "monthly_report"
        private const val NOTIFICATION_ID = 51
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "monthly-report", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MonthlyReportWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
