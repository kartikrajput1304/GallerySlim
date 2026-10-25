package com.galleryslim.diag

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics log: every line goes to logcat under [TAG] (for live `adb logcat`
 * sessions) and to a size-capped file in app-private storage (for the in-app
 * "Share diagnostic log" button, which works without a computer).
 *
 * Never log file contents or EXIF/GPS values — names, sizes, flags and
 * decisions only. Android-only: keep it out of the JVM-pure classes that unit
 * tests load (ShrinkController, estimators, splitters).
 */
object GsLog {
    const val TAG = "GallerySlim"
    private const val MAX_BYTES = 1L shl 20 // 1 MiB, then rotate to .1

    @Volatile private var file: File? = null
    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
            file = File(dir, "galleryslim.log")
            installCrashHandler()
            i("app", "start · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }

    fun logFile(): File? = file

    fun i(area: String, msg: String) {
        Log.i(TAG, "[$area] $msg")
        append("I", area, msg, null)
    }

    fun w(area: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$area] $msg", t)
        append("W", area, msg, t)
    }

    fun e(area: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$area] $msg", t)
        append("E", area, msg, t)
    }

    private fun append(level: String, area: String, msg: String, t: Throwable?) {
        val f = file ?: return
        val line = buildString {
            append(stamp.format(Date())).append(' ').append(level).append(" [").append(area).append("] ").append(msg)
            if (t != null) append('\n').append(stackOf(t))
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            }
        }
    }

    private fun stackOf(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { e("crash", "uncaught on thread ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
