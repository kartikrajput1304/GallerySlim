package com.galleryslim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.ScanUiState
import com.galleryslim.scan.ScanViewModel
import com.galleryslim.shrinkflow.CleanupViewModel
import com.galleryslim.shrinkflow.ShrinkViewModel
import com.galleryslim.ui.AppRoot
import com.galleryslim.ui.Routes
import com.galleryslim.ui.theme.GallerySlimTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()
    private val shrinkViewModel: ShrinkViewModel by viewModels()
    private val cleanupViewModel: CleanupViewModel by viewModels()

    /** Route requested from outside (overnight-worker notification tap). */
    private val deepLink = MutableStateFlow<String?>(null)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it } || hasAnyMediaAccess()) {
                viewModel.partialAccess = hasPartialAccessOnly()
                viewModel.onPermissionGranted()
            } else {
                val fallbackToSettings = mediaPermissions().none { shouldShowRequestPermissionRationale(it) }
                viewModel.onPermissionDenied(fallbackToSettings)
            }
        }

    /** POST_NOTIFICATIONS (13+): best-effort — overnight work runs either way. */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyMediaAccess(): Boolean =
        mediaPermissions().any { granted(it) } || hasPartialAccessOnly()

    /** Android 14 "Select photos": user-selected grant without the full permission. */
    private fun hasPartialAccessOnly(): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !granted(Manifest.permission.READ_MEDIA_IMAGES)

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun consumeDeepLink(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_ROUTE)) {
            ROUTE_PENDING -> deepLink.value = Routes.PENDING
            ROUTE_REPORT -> deepLink.value = Routes.REPORT
            ROUTE_QUICK_REVIEW -> deepLink.value = Routes.QUICK_REVIEW
            ROUTE_MAKE_ROOM -> deepLink.value = Routes.MAKE_ROOM
        }
        intent?.removeExtra(EXTRA_ROUTE)
    }

    /**
     * H2: every start/resume resolves trash / restore / delete dialogs whose result never reached the app
     * (process death while the dialog was up) against MediaStore truth, and runs the MediaStore-version
     * quarantine. Application-scoped so it finishes even if this activity goes away.
     */
    override fun onResume() {
        super.onResume()
        val app = applicationContext
        com.galleryslim.safety.SafetyScope.launch {
            try {
                com.galleryslim.safety.Operations.reconcile(app)
            } catch (e: Exception) {
                GsLog.w("ops", "reconcile on resume failed", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GsLog.init(this)
        com.galleryslim.work.TrashWatchWorker.schedule(this)
        com.galleryslim.storage.StorageSamples.record(this)
        com.galleryslim.storage.StorageSampleWorker.schedule(this)
        com.galleryslim.report.MonthlyReportWorker.schedule(this)
        com.galleryslim.cleanup.WeeklyTidyWorker.schedule(this)
        enableEdgeToEdge()
        consumeDeepLink(intent)

        if (hasAnyMediaAccess()) {
            viewModel.partialAccess = hasPartialAccessOnly()
            viewModel.onPermissionGranted()
        }

        setContent {
            GallerySlimTheme {
                val state by viewModel.state.collectAsState()

                // Spec §7: keep the screen on while scanning.
                LaunchedEffect(state is ScanUiState.Scanning) {
                    if (state is ScanUiState.Scanning) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                AppRoot(
                    scanVm = viewModel,
                    shrinkVm = shrinkViewModel,
                    cleanupVm = cleanupViewModel,
                    deepLink = deepLink,
                    onConsumeDeepLink = { deepLink.value = null },
                    onRequestPermission = { requestPermissions.launch(mediaPermissions()) },
                    onOpenSettings = ::openAppSettings,
                    onEnsureNotificationPermission = ::ensureNotificationPermission,
                )
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "com.galleryslim.ROUTE"
        const val ROUTE_PENDING = "pending"
        const val ROUTE_REPORT = "report"
        const val ROUTE_QUICK_REVIEW = "quick-review"
        const val ROUTE_MAKE_ROOM = "make-room"
    }
}
