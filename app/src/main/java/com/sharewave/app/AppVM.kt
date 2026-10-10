package com.sharewave.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

class AppVM(app: Application) : AndroidViewModel(app) {
    val screen = MutableStateFlow(Screen.HOME)
    val items = MutableStateFlow<List<TItem>>(emptyList())
    val status = MutableStateFlow("")
    val qr = MutableStateFlow<Bitmap?>(null)
    val files = MutableStateFlow<List<SendFile>>(emptyList())
    val busy = MutableStateFlow(false)

    private var server: ReceiverServer? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var sendJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun upsert(i: TItem) = items.update { l ->
        if (l.any { it.id == i.id }) l.map { if (it.id == i.id) i else it } else l + i
    }

    // ---------- RECEIVE ----------
    fun startReceive() {
        stopAll()
        screen.value = Screen.RECEIVE
        items.value = emptyList()
        qr.value = null
        status.value = "Preparing…"
        val token = UUID.randomUUID().toString().take(8)
        val srv = ReceiverServer(getApplication(), token) { upsert(it) }
        try { srv.start() } catch (e: Exception) { status.value = "Server error: ${e.message}"; return }
        server = srv
        var decided = false

        fun decide(p: Pairing, msg: String) {
            if (decided && p.ssid == null) return
            decided = true
            qr.value = makeQr(p.encode())
            status.value = msg
        }

        Hotspot.start(getApplication(), { ssid, pw, res ->
            reservation = res
            decide(Pairing(ssid, pw, token, srv.port, localIps()), "Ready. Ask the sender to scan this code.")
        }, { reason ->
            decide(
                Pairing(null, null, token, srv.port, localIps()),
                "Hotspot unavailable ($reason).\nConnect both phones to the same Wi-Fi, then scan.",
            )
        })
        handler.postDelayed({
            if (!decided && server === srv) {
                decide(
                    Pairing(null, null, token, srv.port, localIps()),
                    "Hotspot is slow to start.\nIf both phones are on the same Wi-Fi, scan now.",
                )
            }
        }, 12000)
    }

    // ---------- SEND ----------
    fun openSend() {
        stopAll()
        files.value = emptyList()
        items.value = emptyList()
        status.value = "Pick files, then scan the receiver's QR code"
        screen.value = Screen.SEND
    }

    fun addFiles(uris: List<Uri>) {
        val r = getApplication<Application>().contentResolver
        val add = uris.map { u ->
            var name = "file"
            var size = -1L
            try {
                r.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {}
            SendFile(u, name, size)
        }
        files.update { l -> l + add.filter { a -> l.none { it.uri == a.uri } } }
        items.value = emptyList()
    }

    fun startSend(qrText: String) {
        val p = Pairing.parse(qrText)
        if (p == null) { status.value = "This is not a ShareWave QR code"; return }
        val list = files.value
        if (list.isEmpty() || busy.value) return
        items.value = emptyList()
        busy.value = true
        status.value = "Connecting…"
        val ctx = getApplication<Application>()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            var target: Target? = null
            try {
                target = Sender.connect(ctx, p) { status.value = it }
                status.value = "Sending…"
                val sem = Semaphore(3)
                val t = target
                coroutineScope {
                    list.mapIndexed { idx, f ->
                        async { sem.withPermit { Sender.upload(ctx, t, p, f, idx.toLong()) { upsert(it) } } }
                    }.awaitAll()
                }
                status.value = if (items.value.all { it.state == TState.DONE }) "All files sent ✔" else "Some files failed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                target?.close()
                busy.value = false
            }
        }
    }

    // ---------- common ----------
    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        sendJob?.cancel()
        sendJob = null
        busy.value = false
    }

    fun goHome() {
        stopAll()
        files.value = emptyList()
        items.value = emptyList()
        qr.value = null
        screen.value = Screen.HOME
    }

    override fun onCleared() {
        stopAll()
    }
}
