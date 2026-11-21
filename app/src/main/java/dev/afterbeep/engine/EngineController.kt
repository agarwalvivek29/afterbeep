package dev.afterbeep.engine

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.afterbeep.AfterbeepApp
import dev.afterbeep.BuildConfig
import dev.afterbeep.R
import dev.afterbeep.adb.AdbManager
import dev.afterbeep.adb.PairingReplyReceiver
import dev.afterbeep.data.VoicemailRepository
import dev.afterbeep.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class EngineStatus(
    val running: Boolean = false,
    val state: String = "off",
    val pid: Int = 0,
    val version: Int = 0,
    val heartbeatAgeMs: Long = Long.MAX_VALUE,
)

sealed interface PairingState {
    data object Idle : PairingState
    data object Searching : PairingState          // waiting for "Pair device with pairing code" dialog
    data class AwaitingCode(val port: Int) : PairingState
    data object Pairing : PairingState
    data object Paired : PairingState
    data class Failed(val reason: String) : PairingState
}

/**
 * App-side control of the shell-uid engine:
 *  - pair with the phone's own Wireless debugging (once)
 *  - connect over TLS and launch Engine via app_process (detached, survives adb disconnect)
 *  - stop via a flag file, watch status.json heartbeat
 */
class EngineController(private val app: AfterbeepApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo: VoicemailRepository get() = app.repository
    private val adbLock = Mutex()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** True when the one-time WRITE_SECURE_SETTINGS grant was refused by OEM permission monitoring. */
    private val _grantBlocked = MutableStateFlow(false)
    val grantBlocked: StateFlow<Boolean> = _grantBlocked.asStateFlow()

    /** USB debugging keeps adbd alive when Wireless debugging turns off (e.g. leaving Wi-Fi) — and the engine with it. */
    private val _usbDebug = MutableStateFlow(true)
    val usbDebug: StateFlow<Boolean> = _usbDebug.asStateFlow()
    val usbDebugOn: Boolean get() = Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private var discovery: NsdManager.DiscoveryListener? = null

    init {
        scope.launch {
            var wasRunning = readStatus().running
            var downFor = 0
            while (isActive) {
                val s = readStatus()
                _status.value = s
                _usbDebug.value = usbDebugOn
                usbPortOpen()
                if (s.running) {
                    downFor = 0
                    // Running again (however it got started): any "stopped/paused" alert is stale.
                    app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED)
                } else downFor++
                // Only alert after ~6 s of consistently "not running", never on a single blip.
                if (wasRunning && downFor == 3) checkUnexpectedStop()
                if (s.running) wasRunning = true else if (downFor >= 3) wasRunning = false
                delay(2000)
            }
        }
    }

    /** Engine went away without the user tapping Stop: say so, and why if we can tell. */
    fun checkUnexpectedStop() {
        val st = readStatus()
        if (!repo.settings.state.value.engineWanted || st.running || _busy.value != null) return
        if (st.heartbeatAgeMs < 15_000) return // heartbeat still recent: a hiccup, not a stop
        if (!usbDebugOn) notifyPaused(
            "Voicemail stopped",
            "USB debugging is off, so leaving Wi-Fi stopped Afterbeep. Turn USB debugging on, then tap to restart.",
        ) else notifyPaused(
            "Voicemail stopped",
            "Afterbeep's engine stopped, so calls ring normally. Tap, then turn on Wireless debugging (on Wi-Fi) to resume.",
        )
    }

    val hasSecureSettings: Boolean
        get() = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ config for the engine

    fun syncConfig() {
        scope.launch {
            val s = repo.settings.state.value
            val o = JSONObject()
                .put("enabled", s.enabled)
                .put("ringSeconds", s.ringSeconds)
                .put("maxSeconds", s.maxSeconds)
                .put("silenceLocal", s.silenceLocal)
                .put("lockDebugging", s.lockDebugging)
                .put("repeatRingThrough", s.repeatRingThrough)
                .put("ringThrough", JSONArray(if (s.starredRingThrough) repo.starredNumbers() else emptyList()))
            val f = File(repo.dataDir, "config.json")
            val tmp = File(repo.dataDir, "config.json.tmp")
            tmp.writeText(o.toString(2)); tmp.renameTo(f)
        }
    }

    @Volatile private var lastGoodStatus = EngineStatus()
    @Volatile private var lastGoodHeartbeatAt = 0L // absolute time of the engine's last heartbeat we saw

    /**
     * The engine rewrites status.json every 3 s (write temp + rename). A read can land mid-swap and fail — that
     * must NOT count as "engine stopped", so retry briefly and otherwise fall back to the last good read, aged by
     * the heartbeat so a genuinely dead engine still shows as stopped within ~10 s.
     */
    private fun readStatus(): EngineStatus {
        repeat(3) { attempt ->
            val r = runCatching {
                val o = JSONObject(File(repo.dataDir, "status.json").readText())
                val age = System.currentTimeMillis() - o.optLong("heartbeat")
                val running = o.optBoolean("running") && age < 10_000
                EngineStatus(running, if (running) o.optString("state", "idle") else "off", o.optInt("pid"), o.optInt("version"), age)
            }
            r.getOrNull()?.let {
                lastGoodStatus = it
                lastGoodHeartbeatAt = System.currentTimeMillis() - it.heartbeatAgeMs
                return it
            }
            if (attempt < 2) Thread.sleep(60)
        }
        val last = lastGoodStatus
        val age = System.currentTimeMillis() - lastGoodHeartbeatAt
        return if (last.running && age < 10_000) last.copy(heartbeatAgeMs = age)
        else last.copy(running = false, state = "off", heartbeatAgeMs = age)
    }

    // ------------------------------------------------------------------ pairing

    /** Watches for the "Pair device with pairing code" dialog (mDNS) and asks for the code in a notification. */
    fun startPairing() {
        stopDiscovery()
        _pairing.value = PairingState.Searching
        val nsd = app.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { _pairing.value = PairingState.Failed("Discovery failed ($e)") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "resolve failed $e") }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        if (_pairing.value is PairingState.Searching || _pairing.value is PairingState.AwaitingCode) {
                            _pairing.value = PairingState.AwaitingCode(i.port)
                            postPairingNotification(i.port)
                        }
                    }
                })
            }
        }
        discovery = listener
        nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { app.getSystemService(NsdManager::class.java).stopServiceDiscovery(it) } }
        discovery = null
    }

    fun cancelPairing() {
        stopDiscovery()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAIR)
        _pairing.value = PairingState.Idle
    }

    private fun postPairingNotification(port: Int) {
        val input = RemoteInput.Builder(PairingReplyReceiver.KEY_CODE).setLabel("6-digit pairing code").build()
        val reply = PendingIntent.getBroadcast(
            app, 1,
            Intent(app, PairingReplyReceiver::class.java).putExtra(PairingReplyReceiver.EXTRA_PORT, port),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(R.drawable.ic_stat_beep, "Enter code", reply)
            .addRemoteInput(input).build()
        val n = NotificationCompat.Builder(app, AfterbeepApp.CH_SETUP)
            .setSmallIcon(R.drawable.ic_stat_beep)
            .setContentTitle("Pair Afterbeep")
            .setContentText("Type the code shown in the Wireless debugging dialog")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_PAIR, n)
    }

    fun submitPairingCode(port: Int, code: String) {
        scope.launch {
            _pairing.value = PairingState.Pairing
            val nm = app.getSystemService(NotificationManager::class.java)
            val ok = adbLock.withLock {
                runCatching { AdbManager.get(app).pair("127.0.0.1", port, code.trim()) }
                    .onFailure { Log.w(TAG, "pair failed", it); _lastError.value = it.message }
                    .getOrDefault(false)
            }
            if (ok) {
                stopDiscovery()
                repo.settings.update { it.copy(paired = true) }
                _pairing.value = PairingState.Paired
                nm.notify(NOTIF_PAIR, simpleNotif("Paired ✓", "Afterbeep can now start its engine."))
                startEngine()
            } else {
                _pairing.value = PairingState.Failed("Pairing failed — check the code and try again")
                postPairingNotification(port)
            }
        }
    }

    // ------------------------------------------------------------------ engine lifecycle

    /** Connects to our own adbd over TLS and launches the engine. Wireless debugging must be on. */
    fun startEngine(onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = launchEngine()
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** [onlyIfStopped]: background callers pass true so two near-simultaneous checks can't both relaunch. */
    suspend fun launchEngine(onlyIfStopped: Boolean = false): Boolean = adbLock.withLock {
        if (onlyIfStopped && readStatus().running) { slog("skip launch: engine already running"); return@withLock true }
        _busy.value = "Connecting to Wireless debugging…"
        _lastError.value = null
        val adb = AdbManager.get(app)
        try {
            diagnose()?.let { _lastError.value = it; return@withLock false }
            val old = readStatus()
            val oldBusy = old.running && old.state != "idle"

            // All adb work happens under a watchdog: libadb blocks forever on a dead connection (e.g. after
            // Wireless debugging restarted), so on timeout we tear the connection down to unblock it.
            val launched = withAdbWatchdog(adb, 45_000) {
                // Always start from a fresh connection — a stale one looks "connected" but never answers.
                runCatching { adb.disconnect() }
                if (!connectAdb(adb)) {
                    _lastError.value = "Couldn't find Wireless debugging. Turn it off and on again, then tap Start."
                    return@withAdbWatchdog false
                }
                if (!oldBusy) {
                    // Fresh random port on every start. Skipped mid-call: moving the port restarts adbd, which
                    // would end the running engine (and the call) abruptly.
                    _busy.value = "Securing connection…"
                    if (!rotatePort(adb)) { _lastError.value = "Couldn't move debugging to a private port. Try again."; return@withAdbWatchdog false }
                } else slog("old engine busy -> keeping port $USB_PORT")
                _busy.value = if (oldBusy) "Waiting for the current call to finish…" else "Starting engine…"
                val apk = app.applicationInfo.sourceDir
                val dir = repo.dataDir.absolutePath
                // Never kill the running engine: the new one asks it to hand over and it finishes any call first.
                // adbd's legacy "shell:" runs under a pty and kills the group the moment the shell exits —
                // linger so setsid has detached the engine before that happens.
                shell(
                    adb,
                    "CLASSPATH=$apk setsid nohup app_process /system/bin --nice-name=afterbeep-engine " +
                        "dev.afterbeep.engine.Engine $dir ${BuildConfig.VERSION_CODE} >/dev/null 2>&1 </dev/null & sleep 2; pidof afterbeep-engine"
                )
                if (!hasSecureSettings) {
                    // One-time: lets us flip Wireless debugging on at boot to restart the engine automatically.
                    // OxygenOS refuses this unless Developer options → "Disable permission monitoring" is on.
                    val out = shell(adb, "pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS 2>&1")
                    _grantBlocked.value = !hasSecureSettings && out.contains("SecurityException")
                }
                true
            }
            runCatching { adb.disconnect() } // engine is detached; don't hold adb open while we wait
            slog("launch step -> ${when (launched) { null -> "TIMED OUT"; true -> "engine launched"; false -> "not launched: ${_lastError.value}" }}")
            if (launched == null) {
                _lastError.value = "Wireless debugging stopped responding. Turn it off and on again, then tap Start."
                return@withLock false
            }
            if (!launched) return@withLock false

            if (oldBusy) _busy.value = "Waiting for the current call to finish…"
            // Normal hand-over takes a second or two; mid-call it can take up to the longest message.
            val waitMs = if (oldBusy) (repo.settings.state.value.maxSeconds + 90) * 1000L else 15_000L
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                val s = readStatus()
                if (s.running && s.pid != old.pid) {
                    _status.value = s; _lastError.value = null; disarm()
                    repo.settings.update { it.copy(engineWanted = true) }
                    slog("SUCCESS engine pid=${s.pid} v${s.version} reported in")
                    app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED)
                    return@withLock true
                }
            }
            _lastError.value = "Engine didn't report in. See engine.log."
            slog("engine never reported in")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "launch failed", t)
            _lastError.value = t.message ?: t.javaClass.simpleName
            false
        } finally {
            _busy.value = null
            runCatching { adb.disconnect() }
        }
    }

    /**
     * Runs blocking adb work on its own thread; if it takes longer than [ms], closes the connection (which
     * makes the blocked read/write throw) and returns null.
     */
    private suspend fun <T> withAdbWatchdog(adb: AdbManager, ms: Long, block: () -> T): T? {
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>?>(null)
        val worker = Thread({ result.set(runCatching(block)) }, "adb-work").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + ms
        while (worker.isAlive && System.currentTimeMillis() < deadline) delay(100)
        if (worker.isAlive) {
            Log.w(TAG, "adb watchdog fired after ${ms}ms — closing connection")
            runCatching { adb.close() }
            runCatching { adb.disconnect() }
            AdbManager.reset()
            return null
        }
        return result.get()?.getOrThrow()
    }

    fun stopEngine() {
        repo.settings.update { it.copy(engineWanted = false) }
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED)
        scope.launch {
            File(repo.dataDir, "engine.stop").writeText("stop")
            delay(1500)
            _status.value = readStatus()
        }
    }

    /**
     * Boot / periodic path: if the engine isn't running and we hold WRITE_SECURE_SETTINGS, turn Wireless
     * debugging on for a few seconds, start the engine, then turn it back off.
     */
    suspend fun autoStartIfNeeded(): Boolean {
        if (readStatus().running) { app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED); return true }
        val s = repo.settings.state.value
        if (!s.paired) return false
        slogState("autostart")
        if (usbPortOpen()) return launchEngine(onlyIfStopped = true).also { slog("autostart via USB route -> $it"); if (it) notifyStarted() }
        // Wireless debugging already on (user flipped it): no special permission needed.
        if (wifiDebugOn && onWifi) return launchEngine(onlyIfStopped = true).also { if (it) notifyStarted() }
        if (!s.autoStart || !hasSecureSettings) return false
        val cr = app.contentResolver
        val wasOn = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        return try {
            if (!wasOn) { Settings.Global.putInt(cr, "adb_wifi_enabled", 1); delay(3000) }
            launchEngine(onlyIfStopped = true).also { if (it) app.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED) }
        } finally {
            if (!wasOn) Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
        }
    }

    // ------------------------------------------------------------------ USB-only experiment (alpha)

    /** Plain-text trail of every start attempt, so a test done away from the Mac can be read back later. */
    fun slog(msg: String) {
        Log.i(TAG, "startup: $msg")
        runCatching {
            val f = File(repo.dataDir, "startup.log")
            if (f.length() > 512_000) f.renameTo(File(repo.dataDir, "startup.log.1"))
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText("$ts  $msg\n")
        }
    }

    /** Snapshot of everything that decides whether a start can work. */
    fun slogState(why: String) = slog(
        "[$why] usbDebug=${usbDebugOn} wifiDebug=${wifiDebugOn} onWifi=${onWifi} paired=${repo.settings.state.value.paired} " +
            "port$USB_PORT=${if (usbPortOpen()) "OPEN" else "closed"} engineRunning=${readStatus().running}"
    )

    /** adbd listens on a plain TCP port (adb tcpip / persist.adb.tcp.port) — reachable on loopback without Wi-Fi. */
    fun usbPortOpen(): Boolean {
        // Network I/O is forbidden on the main thread (it silently failed there in alpha2) — use the value the
        // background status loop refreshes every 2 s instead.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return usbPortCached
        val open = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", USB_PORT), 400) }; true
        }.getOrDefault(false)
        usbPortCached = open
        return open
    }
    @Volatile private var usbPortCached = false

    /** adbd's plain-TCP port: random per start (never 5555, which ADB worms scan for). Private to the app. */
    private val portPrefs by lazy { app.getSharedPreferences("adb_port", Context.MODE_PRIVATE) }
    private val USB_PORT: Int get() = portPrefs.getInt("port", LEGACY_PORT)
    private fun newRandomPort(): Int {
        val r = java.security.SecureRandom()
        var p: Int
        do { p = 20000 + r.nextInt(40000) } while (p == USB_PORT || p == LEGACY_PORT)
        return p
    }

    /**
     * Moves adbd's TCP listener to a fresh random port. This restarts adbd, which also ends any engine it had
     * launched — so callers only do it when no call is in progress. Returns true when reconnected on the new port.
     */
    private fun rotatePort(adb: AdbManager): Boolean {
        val port = newRandomPort()
        val reply = runCatching {
            val st = adb.openStream("tcpip:$port")
            val buf = java.io.ByteArrayOutputStream(); val ch = ByteArray(256); val input = st.openInputStream()
            runCatching { while (true) { val n = input.read(ch); if (n < 0) break; buf.write(ch, 0, n) } }
            runCatching { st.close() }
            buf.toString().trim()
        }.getOrElse { "error ${it.message}" }
        slog("rotate port -> $port : $reply")
        runCatching { adb.disconnect() }
        portPrefs.edit().putInt("port", port).commit()
        // adbd takes a moment to come back on the new port
        repeat(20) {
            Thread.sleep(500)
            val open = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }; true }.getOrDefault(false)
            if (open) {
                val ok = runCatching { adb.connect("127.0.0.1", port) }.getOrDefault(false)
                slog("reconnect on new port $port -> $ok")
                if (ok) return true
            }
        }
        slog("adbd never came back on $port")
        return false
    }

    private val wifiDebugOn: Boolean
        get() = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1

    private val onWifi: Boolean
        get() {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            return cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }

    /** Plain-language reason the engine can't be started right now, or null if it's worth trying. */
    fun diagnose(): String? = when {
        !repo.settings.state.value.paired -> "Not paired yet. Tap “Pair with Wireless debugging” below first."
        usbPortOpen() -> null // USB-only route available: no Wi-Fi needed
        !onWifi -> "Connect to Wi-Fi first. Wireless debugging only works on Wi-Fi (any network is fine). Afterbeep starts by itself once it's on."
        !wifiDebugOn -> "Turn on Wireless debugging. Afterbeep starts by itself the moment it's on."
        else -> null
    }

    /** mDNS discovery first; if the phone's discovery is flaky, fall back to the port adbd publishes. */
    private fun connectAdb(adb: AdbManager): Boolean {
        slogState("connect")
        if (!usbPortOpen() && USB_PORT != LEGACY_PORT && runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", LEGACY_PORT), 300) }; true
            }.getOrDefault(false)) {
            slog("stored port $USB_PORT closed but $LEGACY_PORT open -> using it once")
            portPrefs.edit().putInt("port", LEGACY_PORT).commit(); usbPortOpen()
        }
        if (usbPortOpen()) {
            val t0 = System.currentTimeMillis()
            val f = java.util.concurrent.Executors.newSingleThreadExecutor().submit<Boolean> { adb.connect("127.0.0.1", USB_PORT) }
            val r = runCatching { f.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            slog("USB route 127.0.0.1:$USB_PORT -> ${r.getOrNull() ?: ("FAILED " + r.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message })} in ${System.currentTimeMillis() - t0}ms")
            if (r.getOrDefault(false)) return true
            f.cancel(true); runCatching { adb.disconnect() }
        }
        if (!wifiDebugOn) { slog("wireless debugging off -> no other route"); return false }
        val viaMdns = runCatching { adb.connectTls(app, 12_000) }
            .onFailure { Log.w(TAG, "mDNS connect failed: ${it.message}"); slog("mDNS route FAILED ${it.message}") }.getOrDefault(false)
        slog("mDNS route -> $viaMdns")
        if (viaMdns) return true
        // Fallback: Wireless debugging listens on a random port (30000-50000 on Android 11+). Scanning our own
        // loopback is near-instant (closed ports refuse immediately), then try the adb handshake on each hit.
        val open = java.util.concurrent.Executors.newFixedThreadPool(64).let { pool ->
            val hits = java.util.Collections.synchronizedList(mutableListOf<Int>())
            (30000..50000).chunked(320).map { chunk ->
                pool.submit {
                    for (p in chunk) runCatching {
                        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", p), 150); hits += p }
                    }
                }
            }.forEach { runCatching { it.get() } }
            pool.shutdown(); hits.sorted()
        }
        Log.i(TAG, "loopback scan found $open"); slog("loopback scan found $open")
        return open.any { p ->
            val f = java.util.concurrent.Executors.newSingleThreadExecutor().submit<Boolean> { adb.connect("127.0.0.1", p) }
            runCatching { f.get(6, java.util.concurrent.TimeUnit.SECONDS) }
                .onFailure { Log.i(TAG, "port $p: ${it.message}"); f.cancel(true); runCatching { adb.disconnect() } }
                .getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------ start as soon as Wireless debugging comes on

    private var adbObserver: android.database.ContentObserver? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * "Tap Start, flip the switch, done": watches Wi-Fi and the Wireless debugging switch, and launches the
     * engine the moment both are ready. Used by the Start button and the after-reboot notification.
     */
    fun startWhenReady() {
        val reason = diagnose()
        scope.launch { slogState("start tapped -> ${reason ?: "starting"}") }
        if (reason == null) { startEngine(); return }
        _lastError.value = diagnose()
        if (adbObserver != null) return
        val tryNow = {
            if (adbObserver != null && !readStatus().running && diagnose() == null) {
                disarm()
                scope.launch { delay(1500); if (launchEngine(onlyIfStopped = true)) notifyStarted() }
            } else if (adbObserver != null) _lastError.value = diagnose()
        }
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { tryNow() }
        }
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, obs)
        adbObserver = obs
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tryNow() }, 1500) }
        }
        app.getSystemService(android.net.ConnectivityManager::class.java).registerNetworkCallback(
            android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
        )
        netCallback = cb
    }

    private fun disarm() {
        adbObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        netCallback?.let { runCatching { app.getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        adbObserver = null; netCallback = null
    }

    private fun notifyStarted() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_PAUSED)
        nm.notify(NOTIF_PAIR, simpleNotif("Afterbeep is answering calls", "You can turn Wireless debugging off now."))
    }

    /** After a reboot the engine is gone; tell the user voicemail is paused and make restarting one tap. */
    fun notifyPaused(
        title: String = "Voicemail is paused",
        text: String = "Your phone restarted, so calls ring normally for now. On any Wi-Fi, tap the Wireless debugging tile in your pull-down (or tap here) — Afterbeep resumes by itself, then you can switch it off.",
    ) {
        if (!repo.settings.state.value.paired || readStatus().running) return
        val open = PendingIntent.getActivity(
            app, 7, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, AfterbeepApp.CH_SETUP)
            .setSmallIcon(R.drawable.ic_stat_beep)
            .setColor(0xFFFF8A4C.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_PAUSED, n)
    }

    private fun shell(adb: AdbManager, cmd: String): String {
        val stream = adb.openStream("shell:$cmd")
        // libadb throws "Stream closed" (instead of returning EOF) once the remote side closes,
        // which is how every command ends — so keep whatever was read before that.
        val buf = java.io.ByteArrayOutputStream()
        val input = stream.openInputStream()
        val chunk = ByteArray(4096)
        try {
            while (true) { val n = input.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
        } catch (_: java.io.IOException) { }
        val out = buf.toString(Charsets.UTF_8.name())
        runCatching { stream.close() }
        Log.i(TAG, "shell[$cmd] -> $out")
        return out
    }

    private fun simpleNotif(title: String, text: String) =
        NotificationCompat.Builder(app, AfterbeepApp.CH_SETUP)
            .setSmallIcon(R.drawable.ic_stat_beep)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        const val TAG = "Afterbeep"
        const val NOTIF_PAIR = 10
        const val NOTIF_PAUSED = 11
        const val LEGACY_PORT = 5555
    }
}
