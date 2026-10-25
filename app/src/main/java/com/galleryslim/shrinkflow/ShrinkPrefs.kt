package com.galleryslim.shrinkflow

import android.content.Context
import android.net.Uri
import com.galleryslim.core.Preset

/** Small settings store: global preset, vault/overnight toggles, one-shot flags. */
class ShrinkPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("galleryslim_settings", Context.MODE_PRIVATE)

    var preset: Preset
        get() = runCatching { Preset.valueOf(prefs.getString(KEY_PRESET, null) ?: "") }
            .getOrDefault(Preset.GENTLE)
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    var vaultEnabled: Boolean
        get() = prefs.getBoolean(KEY_VAULT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VAULT_ENABLED, value).apply()

    var overnightEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERNIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERNIGHT, value).apply()

    /** One-shot Google-Photos re-upload warning (design §6 rule 6). */
    var photosWarningShown: Boolean
        get() = prefs.getBoolean(KEY_PHOTOS_WARNED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTOS_WARNED, value).apply()

    /** SAF tree the motion-photo clips are exported into before stripping. */
    var motionExportDirUri: Uri?
        get() = prefs.getString(KEY_MOTION_EXPORT, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_MOTION_EXPORT, value?.toString()).apply()

    /** Require device biometrics / screen lock for the vault and review screens. */
    var appLock: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** Days new photos stay protected from shrinking (7 / 30 / 90). */
    var protectDays: Int
        get() = prefs.getInt(KEY_PROTECT_DAYS, ExclusionRules.RECENT_WINDOW_DAYS)
        set(value) = prefs.edit().putInt(KEY_PROTECT_DAYS, value).apply()

    private companion object {
        const val KEY_APP_LOCK = "app_lock"
        const val KEY_PROTECT_DAYS = "protect_days"
        const val KEY_PRESET = "global_preset"
        const val KEY_VAULT_ENABLED = "vault_enabled"
        const val KEY_OVERNIGHT = "overnight_enabled"
        const val KEY_PHOTOS_WARNED = "photos_warning_shown"
        const val KEY_MOTION_EXPORT = "motion_export_dir"
    }
}
