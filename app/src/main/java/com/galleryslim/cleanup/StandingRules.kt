package com.galleryslim.cleanup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.galleryslim.MainActivity
import com.galleryslim.R
import java.util.concurrent.TimeUnit

/**
 * Five questions instead of five thousand swipes: answers become standing rules. A rule never deletes
 * anything by itself; it pre-selects matching items in Quick review, where one confirmation clears them.
 */
object StandingRules {
    enum class Rule(val question: String, val detail: String, val lane: CleanupLanes.Lane?) {
        SCREENSHOTS("Clear screenshots after a month?", "Old screenshots are pre-selected in Quick review.", CleanupLanes.Lane.OLD_SCREENSHOTS),
        GREETINGS("Let good-morning cards and forwards go after a week?", "Greeting and quote images from chats are pre-selected.", CleanupLanes.Lane.GREETINGS),
        BLURRY("Clear blurry and too-dark shots?", "Low-quality photos are pre-selected.", CleanupLanes.Lane.LOW_QUALITY),
        FLOOR("Always keep 10 GB free?", "Overnight shrinking works toward this goal.", null),
        COOL("Lighter copies of photos older than 2 years?", "Favourites and albums you protect are never touched.", null),
    }

    private const val PREFS = "galleryslim_rules"
    private const val DONE = "setup_done"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isOn(c: Context, r: Rule): Boolean = prefs(c).getBoolean(r.name, false)
    fun set(c: Context, r: Rule, on: Boolean) = prefs(c).edit().putBoolean(r.name, on).apply()
    fun setupDone(c: Context): Boolean = prefs(c).getBoolean(DONE, false)
    fun markSetupDone(c: Context) = prefs(c).edit().putBoolean(DONE, true).apply()

    /** Pure: ids pre-selected in Quick review = items in the lanes whose rule is on, plus expired items. */
    fun preselect(lanes: Map<CleanupLanes.Lane, List<com.galleryslim.scan.MediaItem>>, on: Set<Rule>): Set<Long> {
        val laneSet = on.mapNotNull { it.lane }.toSet() + CleanupLanes.Lane.EXPIRED
        return laneSet.flatMap { lanes[it].orEmpty() }.map { it.id }.toSet()
    }

    fun enabled(c: Context): Set<Rule> = Rule.entries.filter { isOn(c, it) }.toSet()
}

/** Weekly nudge when any clean-up rule is on: "Your weekly tidy-up is ready". */
class WeeklyTidyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val rules = StandingRules.enabled(ctx).filter { it.lane != null }
        if (rules.isEmpty()) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return Result.success()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly tidy-up", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, 8,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_QUICK_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(52, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_galleryslim)
                .setContentTitle("Your weekly tidy-up is ready")
                .setContentText("Your rules picked what to clear. Take a look, then confirm once.")
                .setContentIntent(open).setAutoCancel(true).build())
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "weekly_tidy"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "weekly-tidy", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeeklyTidyWorker>(7, TimeUnit.DAYS).setInitialDelay(3, TimeUnit.DAYS).build(),
            )
        }
    }
}
