package com.galleryslim.share

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import com.galleryslim.work.ShrinkNotifications
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows: the link to open on the laptop and live transfer progress. */
data class ShareState(
    val url: String? = null,
    /** Friendly address (mDNS), shown first; the IP [url] is the fallback. */
    val friendlyUrl: String? = null,
    val error: String? = null,
    val queued: List<MediaItem> = emptyList(),
    val approvedSessions: Int = 0,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** MediaStore ids of queued items whose bytes were fully sent to an approved computer. */
    val deliveredIds: Set<Long> = emptySet(),
    /** Which computer each delivered item went to ("Windows PC (192.168.1.19)"), for the archive map. */
    val deliveredTo: Map<Long, String> = emptyMap(),
    /** The queue came from the trash (files GallerySlim cleared); step 4 then offers deleting them for good. */
    val fromTrash: Boolean = false,
    /** Ids whose copy on the computer was read back and matched byte for byte (GallerySlim Receiver only). */
    val verifiedIds: Set<Long> = emptySet(),
    /** Verified in an earlier session: shown as a label only. Deleting for good needs a fresh check in this run. */
    val previouslyVerified: Set<Long> = emptySet(),
    /** Short code shown here and on the receiver, so two phones sharing at once can't be confused. */
    val code: String = "",
    /** Bring back to phone / Receive from computer (§10); null while sending. */
    val receive: ReceiveState? = null,
)

/** §10 progress: what the owner asked for ([free] = the computer chooses), and what arrived, checked. */
data class ReceiveState(
    val free: Boolean,
    val wanted: List<Inbox.Target> = emptyList(),
    /** Target ids saved on the phone (complete and matching), with their names, in arrival order. */
    val saved: Map<String, String> = emptyMap(),
    val receivedBytes: Long = 0L,
) {
    val totalBytes get() = wanted.sumOf { it.size }
}

/** Process-wide hub: the service writes, screens read; the scan screen approves through it. */
object ShareHub {
    val state = MutableStateFlow(ShareState())
    @Volatile internal var sessions: ShareSessions? = null
    @Volatile internal var receivers: ReceiverSessions? = null

    /** True while the Bring back / Receive screen is on screen: only then are uploads accepted (§10). */
    @Volatile var receiveScreenOpen = false

    /** Start "Bring back to phone" for these files (picked in Moved to computer). */
    fun startBringBack(wanted: List<Inbox.Target>) = state.update { it.copy(receive = ReceiveState(free = false, wanted = wanted)) }

    /** Start "Receive from computer": the computer offers files, the phone accepts photos and videos that fit. */
    fun startReceive() = state.update { it.copy(receive = ReceiveState(free = true)) }

    /** Back to sending: approvals carry the send queue again. */
    fun endReceive() = state.update { if (it.receive == null) it else it.copy(receive = null) }

    /** What the inbox accepts right now; null unless a receive mode is set AND its screen is open. */
    fun receivePlan(): Inbox.Plan? {
        val r = state.value.receive ?: return null
        return if (receiveScreenOpen) Inbox.Plan(r.free, r.wanted) else null
    }

    internal fun onReceived(t: Inbox.Target) =
        state.update { s -> s.receive?.let { s.copy(receive = it.copy(saved = it.saved + (t.id to t.name))) } ?: s }

    internal fun onReceivedBytes(n: Long) =
        state.update { s -> s.receive?.let { s.copy(receive = it.copy(receivedBytes = it.receivedBytes + n)) } ?: s }

    fun setQueue(items: List<MediaItem>, fromTrash: Boolean = false) {
        state.update {
            it.copy(
                queued = items, totalBytes = items.sumOf { i -> i.sizeBytes }, sentBytes = 0L,
                deliveredIds = emptySet(), deliveredTo = emptyMap(), fromTrash = fromTrash, verifiedIds = emptySet(),
                previouslyVerified = emptySet(),
            )
        }
    }

    /**
     * Items verified in an earlier session (from history): a label only. The laptop folder may have changed since,
     * so "delete for good" still needs the receiver to re-check them in this run (it re-hashes what it already has).
     */
    fun restoreVerified(ids: Set<Long>, to: Map<Long, String>) {
        if (ids.isEmpty()) return
        state.update { it.copy(previouslyVerified = it.previouslyVerified + ids, deliveredTo = it.deliveredTo + to) }
    }

    /** Who a scanned receiver code belongs to ("Kartik's PC"), for the confirmation prompt; null for browser codes. */
    fun receiverFor(qrPayload: String): String? = receivers?.pending(qrPayload)?.receiver

    /** Remembered computers (§9) that are running the receiver and proved who they are: rid → session's sid. */
    fun readyReceivers(): Map<String, String> = receivers?.ready()?.mapValues { it.value.sid }.orEmpty()

    /** §9: send to a remembered computer, called only after the owner confirmed with biometrics. */
    fun approveKnown(rid: String): Boolean {
        val ok = receivers?.approveKnown(rid, queuedFiles()) != null
        if (ok) state.update { it.copy(approvedSessions = it.approvedSessions + 1, sentBytes = 0L) }
        GsLog.i("share", if (ok) "remembered computer approved for ${state.value.queued.size} files" else "remembered computer is no longer ready")
        return ok
    }

    /** In a receive mode a computer is approved for receiving only: it gets no files from this phone. */
    private fun queuedFiles() = if (state.value.receive != null) emptyList() else state.value.queued.map {
        ShareSessions.ShareFile(it.id.toString(), it.displayName, it.sizeBytes, it.mimeType, it.contentUri, it.dateTakenMs)
    }

    /** Called after the phone scanned a QR AND the owner confirmed with biometrics. */
    fun approve(qrPayload: String): Boolean {
        val files = queuedFiles()
        if (qrPayload.startsWith(Gs2.QR_PREFIX)) {
            val ok = receivers?.approve(qrPayload, files) != null
            if (ok) state.update { it.copy(approvedSessions = it.approvedSessions + 1, sentBytes = 0L) }
            GsLog.i("share", if (ok) "receiver approved for ${files.size} files" else "scanned receiver code did not match")
            return ok
        }
        val s = sessions ?: return false
        val ok = s.approve(qrPayload, files)
        if (ok) state.update { it.copy(approvedSessions = s.approvedSessions().size, sentBytes = 0L) }
        GsLog.i("share", if (ok) "session approved for ${files.size} files" else "scanned code did not match any open page")
        return ok
    }
}

/**
 * Foreground service that keeps the phone's page reachable while sharing.
 * Binds only to the Wi-Fi interface; stops when the user ends sharing.
 */
class ShareService : Service() {
    private var server: LanHttpServer? = null
    private var mdns: MdnsResponder? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+: dataSync services have a daily time limit; stop cleanly when it's reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        com.galleryslim.diag.GsLog.w("share", "foreground time limit reached — stopping sharing")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY

        val link = wifiLink()
        val wifi = link?.first
        if (wifi == null) {
            // Started with startForegroundService: Android requires startForeground before stopping, or the app crashes.
            ShrinkNotifications.ensureChannels(this)
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    NotificationCompat.Builder(this, ShrinkNotifications.PROGRESS_CHANNEL)
                        .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
                        .setContentTitle("Not sharing: no Wi-Fi").build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
            ShareHub.state.update { it.copy(url = null, error = "Connect the phone to Wi-Fi first") }
            keepErrorOnDestroy = true
            stopSelf(); return START_NOT_STICKY
        }
        ShrinkNotifications.ensureChannels(this)
        val openApp = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, com.galleryslim.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = android.app.PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ShrinkNotifications.PROGRESS_CHANNEL)
            .setSmallIcon(com.galleryslim.R.drawable.ic_stat_galleryslim)
            .setContentTitle("Ready to send to your computer")
            .setContentText("Open the link on the computer and scan its code · stops by itself after 30 min")
            .setContentIntent(openApp)
            .addAction(0, "Stop sharing", stop)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Never run an invisible listener.
            GsLog.e("share", "could not start in foreground — not sharing", e)
            ShareHub.state.update { it.copy(url = null, error = "Couldn't start sharing (notification blocked)") }
            stopSelf(); return START_NOT_STICKY
        }

        val sessions = ShareSessions()
        ShareHub.sessions = sessions
        val receivers = ReceiverSessions(pairs = PairedReceiverStore.get(this))
        ShareHub.receivers = receivers
        val phoneName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val code = shortCode()
        ShareHub.state.update { it.copy(code = code) }
        val hasher = FileHasher(this)
        val ctx = this
        val receiverApi = object : LanHttpServer.ReceiverApi {
            override val sessions = receivers
            override val phoneName = phoneName
            override val shortCode = code
            override val phoneId = PhoneId.get(ctx)
            override fun open(uri: String) = contentResolver.openInputStream(android.net.Uri.parse(uri))
            override fun realLength(uri: String) = hasher.realLength(uri)
            override fun hash(uri: String, onProgress: () -> Unit) = hasher.hash(uri, onProgress)
            override fun onBytes(bytes: Long) {
                lastActivityAt = System.currentTimeMillis()
                ShareHub.state.update { it.copy(sentBytes = (it.sentBytes + bytes).coerceAtMost(it.totalBytes)) }
            }
            override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) {
                val id = file.id.toLongOrNull() ?: return
                ShareHub.state.update {
                    it.copy(verifiedIds = it.verifiedIds + id, deliveredIds = it.deliveredIds + id, deliveredTo = it.deliveredTo + (id to computer))
                }
                // Saved at once: survives the service stopping, the app closing, or a new session.
                com.galleryslim.history.History.recordVerified(ctx, id, file.name, file.uri, bytes, file.takenMs, computer, sha256)
            }
            override fun onStreaming(active: Boolean) = this@ShareService.onStreaming(active)
            override val inbox = Inbox(
                writer = MediaStoreWriter(ctx),
                freeBytes = { com.galleryslim.storage.StorageForecast.freeBytes() },
                plan = { ShareHub.receivePlan() },
                onSaved = { t, uri ->
                    ShareHub.onReceived(t)
                    com.galleryslim.history.History.recordRestored(ctx, t.name, uri, t.size, t.takenMs, t.sha256, t.originalId)
                },
                onProgress = { n -> lastActivityAt = System.currentTimeMillis(); ShareHub.onReceivedBytes(n) },
            )
        }
        val prefix = link.second
        val downloads = AssetReceiverDownloads(assets)
        val srv = LanHttpServer(
            bindAddress = wifi,
            sessions = sessions,
            resolver = contentResolver,
            pageHtml = { created, svg -> SharePage.html(created.id, created.secret, svg, phoneName, downloads.available()) },
            qrSvg = { QrCodes.svg(it) },
            // Same Wi-Fi segment only (falls back to the private-range check if the prefix is unknown).
            peerAllowed = { peer -> if (prefix in 1..32) ShareSessions.sameSubnet(peer, wifi.hostAddress ?: "", prefix) else ShareSessions.isPrivateAddress(peer) },
            onBytesSent = { _, bytes ->
                lastActivityAt = System.currentTimeMillis()
                ShareHub.state.update { it.copy(sentBytes = (it.sentBytes + bytes).coerceAtMost(it.totalBytes)) }
            },
            receiver = receiverApi,
            downloads = downloads,
            onFileDelivered = { id, computer ->
                id.toLongOrNull()?.let { mid ->
                    ShareHub.state.update {
                        it.copy(deliveredIds = it.deliveredIds + mid, deliveredTo = it.deliveredTo + (mid to computer))
                    }
                }
            },
        )
        val port = srv.start()
        server = srv
        // Friendly name: galleryslim.local (answered by our own mDNS responder while sharing).
        runCatching {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            multicastLock = wm?.createMulticastLock("galleryslim-share")?.apply { setReferenceCounted(false); acquire() }
            val iface = NetworkInterface.getByInetAddress(wifi)
            mdns = MdnsResponder(wifi as Inet4Address, iface).also { it.start() }
        }.onFailure { GsLog.w("share", "mDNS name unavailable: ${it.message}") }
        ShareHub.state.update { it.copy(
            url = "http://${wifi.hostAddress}:$port",
            friendlyUrl = if (mdns != null) "http://${MdnsResponder.HOSTNAME}:$port" else null,
            error = null,
        ) }
        watchNetworkAndIdle(cm = getSystemService(ConnectivityManager::class.java), sessions = sessions)
        return START_NOT_STICKY
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivityAt = System.currentTimeMillis()
    private var lastApprovals = 0
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val activeStreams = java.util.concurrent.atomic.AtomicInteger(0)

    private var keepErrorOnDestroy = false
    private val lockGuard = Any()
    @Volatile private var destroyed = false

    /**
     * Hold the CPU and Wi-Fi awake only while a file is streaming or being checked (screen-off would otherwise slow
     * or stop it). Both locks are created once, not reference-counted, and changed only under [lockGuard].
     */
    private fun onStreaming(active: Boolean) = synchronized(lockGuard) {
        lastActivityAt = System.currentTimeMillis()
        val n = if (active) activeStreams.incrementAndGet() else activeStreams.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (destroyed) return@synchronized
        if (active) {
            runCatching {
                val wl = wakeLock ?: getSystemService(android.os.PowerManager::class.java)
                    ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GallerySlim:share")?.apply { setReferenceCounted(false) }
                wakeLock = wl
                wl?.acquire(WAKE_RENEW_MS) // re-acquiring renews the timeout for long transfers
                if (wifiLock == null) {
                    @Suppress("DEPRECATION")
                    wifiLock = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GallerySlim:share")
                        ?.apply { setReferenceCounted(false) }
                }
                wifiLock?.takeIf { !it.isHeld }?.acquire()
            }
        } else if (n == 0) {
            releaseLocksLocked()
        }
    }

    private fun releaseLocks() = synchronized(lockGuard) { releaseLocksLocked() }

    private fun releaseLocksLocked() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    /** Stop when the Wi-Fi drops, and after [AUTO_STOP_MS] without an approved transfer. */
    private fun watchNetworkAndIdle(cm: ConnectivityManager?, sessions: ShareSessions) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                GsLog.i("share", "Wi-Fi lost — stopping sharing")
                idleHandler.post { stopSelf() }
            }
        }
        netCallback = cb
        runCatching {
            cm?.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
            )
        }
        val tick = object : Runnable {
            override fun run() {
                // Active = an approved session still in use (sessions expire after 10 idle minutes) or a stream running.
                // It used to count "anything was ever sent", so sharing never stopped by itself after the first transfer.
                // Activity = a stream running or a new approval; bytes sent update lastActivityAt directly.
                // (Approved-but-idle sessions no longer count, so sharing really stops 30 min after the last transfer.)
                val approvals = ShareHub.state.value.approvedSessions
                if (activeStreams.get() > 0 || approvals != lastApprovals) lastActivityAt = System.currentTimeMillis()
                lastApprovals = approvals
                if (System.currentTimeMillis() - lastActivityAt > AUTO_STOP_MS) {
                    GsLog.i("share", "no transfer for 30 min — stopping sharing")
                    stopSelf()
                } else {
                    idleHandler.postDelayed(this, 60_000L)
                }
            }
        }
        idleHandler.postDelayed(tick, 60_000L)
    }

    /** Swiping the app away ends sharing too. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        server?.stop(); server = null
        mdns?.stop(); mdns = null
        runCatching { multicastLock?.release() }; multicastLock = null
        ShareHub.sessions?.revokeAll(); ShareHub.sessions = null
        ShareHub.receivers?.revokeAll(); ShareHub.receivers = null
        synchronized(lockGuard) { destroyed = true; releaseLocksLocked() }
        // Keep a "why sharing didn't start" message visible instead of wiping it the moment the service stops.
        val error = if (keepErrorOnDestroy) ShareHub.state.value.error else null
        ShareHub.state.value = ShareState(error = error)
        super.onDestroy()
    }

    /** "K7-42": two letters (no I/O) and two digits, new for every sharing run. */
    private fun shortCode(): String {
        val r = java.security.SecureRandom()
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        return "${letters[r.nextInt(letters.length)]}${letters[r.nextInt(letters.length)]}-${10 + r.nextInt(90)}"
    }

    /** The phone's IPv4 Wi-Fi address and prefix length, or null when not on plain Wi-Fi (VPN excluded). */
    private fun wifiLink(): Pair<InetAddress, Int>? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val props: LinkProperties = cm.getLinkProperties(net) ?: return null
        if (!props.interfaceName.orEmpty().startsWith("wlan")) return null
        val la = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return la.address to la.prefixLength
    }

    companion object {
        private const val NOTIFICATION_ID = 45
        private const val AUTO_STOP_MS = 30L * 60 * 1000
        private const val WAKE_RENEW_MS = 60L * 60 * 1000
        private const val ACTION_STOP = "com.galleryslim.share.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, ShareService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
    }
}
