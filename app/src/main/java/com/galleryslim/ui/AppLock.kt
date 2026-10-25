package com.galleryslim.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Optional lock for the vault and review screens (research log round 5),
 * using the platform BiometricPrompt — fingerprint/face or the phone's own
 * PIN/pattern — so no extra library is needed.
 */
object AppLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Unlocked once per app process; re-locks when the process dies. */
    @Volatile var unlocked = false
        private set

    /** True only when the phone has no screen lock or biometrics at all (not for temporary failures). */
    fun hasNoLock(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS).let {
            it == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || it == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        }

    /** The lock can only be enabled when the phone has a screen lock or biometrics. */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Explicit confirmation for releasing photos to a laptop: ALWAYS prompts
     * (ignores the session's unlocked memo). Phones with no screen lock at all
     * cannot prompt, so they fall through — there is nothing to verify against.
     */
    fun confirm(
        activity: Activity, subtitle: String, onConfirmed: () -> Unit, onCancelled: () -> Unit,
        title: String = "Send these files to your computer?",
    ) {
        val status = activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS)
        when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            // No lock set up at all: whoever holds the phone already has everything; nothing to verify against.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> { onConfirmed(); return }
            // Temporarily unavailable / needs a security update: refuse rather than release silently.
            else -> { onCancelled(); return }
        }
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onConfirmed()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onCancelled()
            })
    }

    fun requireUnlock(activity: Activity, enabled: Boolean, onUnlocked: () -> Unit) {
        if (!enabled || unlocked) { onUnlocked(); return }
        BiometricPrompt.Builder(activity)
            .setTitle("Unlock GallerySlim")
            .setSubtitle("Confirm it's you to see what's ready to clear and the vault")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    unlocked = true
                    onUnlocked()
                }
            })
    }
}
