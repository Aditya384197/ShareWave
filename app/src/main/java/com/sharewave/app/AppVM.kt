package com.sharewave.app

import android.app.Application
import android.content.ContentUris
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import java.io.File
import java.util.UUID

class AppVM(app: Application) : AndroidViewModel(app) {
    val screen = MutableStateFlow(Screen.HOME)
    val items = MutableStateFlow<List<TItem>>(emptyList())
    val status = MutableStateFlow("")
    val qr = MutableStateFlow<Bitmap?>(null)
    val busy = MutableStateFlow(false)

    // picker state
    val cat = MutableStateFlow(Cat.PHOTOS)
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val loading = MutableStateFlow(false)
    val dir = MutableStateFlow<File>(Environment.getExternalStorageDirectory())
    val selected = MutableStateFlow<Map<String, SendFile>>(emptyMap())

    private var server: ReceiverServer? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var sendJob: Job? = null
    private var loadJob: Job? = null
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

    // ---------- SEND: picker ----------
    fun openSend() {
        stopAll()
        selected.value = emptyMap()
        items.value = emptyList()
        status.value = ""
        dir.value = Environment.getExternalStorageDirectory()
        cat.value = Cat.PHOTOS
        screen.value = Screen.SEND
        load()
    }

    fun setCat(c: Cat) {
        cat.value = c
        load()
    }

    fun load() {
        loadJob?.cancel()
        entries.value = emptyList()
        loading.value = true
        val c = cat.value
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val list = try {
                when (c) {
                    Cat.PHOTOS -> query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
                    Cat.VIDEOS -> query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)
                    Cat.MUSIC -> query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null)
                    Cat.DOCS -> {
                        val exts = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "zip", "rar", "7z", "epub", "apk")
                        val sel = exts.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
                        query(MediaStore.Files.getContentUri("external"), sel, exts.map { "%.$it" }.toTypedArray())
                    }
                    Cat.APPS -> apps()
                    Cat.FILES -> files()
                }
            } catch (e: Exception) {
                emptyList()
            }
            entries.value = list
            loading.value = false
        }
    }

    private fun query(uri: Uri, sel: String?, args: Array<String>?): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC",
            )?.use { c ->
                val ii = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val ni = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val si = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext() && out.size < 8000) {
                    val id = c.getLong(ii)
                    out.add(Entry("m:$uri:$id", c.getString(ni) ?: "file", ContentUris.withAppendedId(uri, id), c.getLong(si)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun apps(): List<Entry> {
        val pm = getApplication<Application>().packageManager
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .map {
                val f = File(it.sourceDir)
                Entry("a:${it.packageName}", pm.getApplicationLabel(it).toString() + ".apk", Uri.fromFile(f), f.length(), sub = it.packageName)
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun files(): List<Entry> {
        val d = dir.value
        return (d.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map {
                if (it.isDirectory) Entry("d:${it.path}", it.name, Uri.fromFile(it), -1, isDir = true)
                else Entry("f:${it.path}", it.name, Uri.fromFile(it), it.length())
            }
    }

    fun openDir(e: Entry) {
        dir.value = File(e.key.removePrefix("d:"))
        load()
    }

    /** @return true if it navigated up */
    fun fileUp(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        val d = dir.value
        if (cat.value != Cat.FILES || d.absolutePath == root.absolutePath) return false
        dir.value = d.parentFile ?: root
        load()
        return true
    }

    fun toggle(e: Entry) {
        selected.update { m ->
            if (m.containsKey(e.key)) m - e.key else m + (e.key to SendFile(e.uri, e.name, e.size))
        }
    }

    fun addFolder(e: Entry) {
        viewModelScope.launch(Dispatchers.IO) {
            val add = File(e.key.removePrefix("d:")).walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .take(5000)
                .associate { "f:${it.path}" to SendFile(Uri.fromFile(it), it.name, it.length()) }
            selected.update { it + add }
        }
    }

    fun clearSelection() {
        selected.value = emptyMap()
    }

    fun backToPick() {
        items.value = emptyList()
        selected.value = emptyMap()
        status.value = ""
    }

    // ---------- SEND: transfer ----------
    fun startSend(qrText: String) {
        val p = Pairing.parse(qrText)
        if (p == null) { status.value = "This is not a ShareWave QR code"; return }
        val list = selected.value.values.toList()
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
        loadJob?.cancel()
        busy.value = false
    }

    fun goHome() {
        stopAll()
        selected.value = emptyMap()
        items.value = emptyList()
        qr.value = null
        screen.value = Screen.HOME
    }

    override fun onCleared() {
        stopAll()
    }
}
