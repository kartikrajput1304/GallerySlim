package com.galleryslim.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * "Full in about N days": a least-squares line through recent free-space samples.
 * Pure part is [daysUntilFull]; [StorageSamples] keeps the samples in app-private prefs.
 */
object StorageForecast {
    data class Sample(val timeMs: Long, val freeBytes: Long)

    const val WINDOW_DAYS = 30
    private const val DAY_MS = 86_400_000.0
    /** The phone is effectively "full" below this (Android starts failing writes around here). */
    const val FULL_BELOW_BYTES = 500L * 1024 * 1024

    /**
     * Days until free space falls under [FULL_BELOW_BYTES] at the recent trend, or null when there
     * isn't enough history (under 2 days or 3 samples) or free space isn't shrinking.
     */
    fun daysUntilFull(samples: List<Sample>, nowMs: Long): Int? {
        val recent = samples.filter { it.timeMs >= nowMs - WINDOW_DAYS * DAY_MS.toLong() && it.timeMs <= nowMs }
        if (recent.size < 3) return null
        val span = recent.maxOf { it.timeMs } - recent.minOf { it.timeMs }
        if (span < 2 * DAY_MS) return null
        val xs = recent.map { (it.timeMs - nowMs) / DAY_MS }
        val ys = recent.map { it.freeBytes.toDouble() }
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in xs.indices) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        if (sxx == 0.0) return null
        val slope = sxy / sxx // bytes per day
        if (slope >= 0) return null
        val freeNow = my + slope * (0 - mx)
        val days = (freeNow - FULL_BELOW_BYTES) / -slope
        return days.coerceAtLeast(0.0).toInt()
    }

    fun freeBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes
    fun totalBytes(): Long = StatFs(Environment.getDataDirectory().path).totalBytes
}

object StorageSamples {
    private const val PREFS = "galleryslim_storage_samples"
    private const val KEY = "samples"
    private const val MIN_GAP_MS = 6 * 3_600_000L
    private const val MAX_SAMPLES = 120

    fun all(context: Context): List<StorageForecast.Sample> =
        prefs(context).getString(KEY, "")!!.split(';').mapNotNull { s ->
            val p = s.split(',')
            if (p.size != 2) null else runCatching { StorageForecast.Sample(p[0].toLong(), p[1].toLong()) }.getOrNull()
        }

    /** Records the current free space, at most once every 6 hours. */
    fun record(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val list = all(context)
        if (list.isNotEmpty() && nowMs - list.last().timeMs < MIN_GAP_MS) return
        val next = (list + StorageForecast.Sample(nowMs, StorageForecast.freeBytes())).takeLast(MAX_SAMPLES)
        prefs(context).edit().putString(KEY, next.joinToString(";") { "${it.timeMs},${it.freeBytes}" }).apply()
    }

    fun daysUntilFull(context: Context): Int? = StorageForecast.daysUntilFull(all(context), System.currentTimeMillis())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
