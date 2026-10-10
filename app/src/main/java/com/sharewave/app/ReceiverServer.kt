package com.sharewave.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Tiny streaming HTTP server: POST /upload (raw body) and GET /ping. */
class ReceiverServer(
    private val ctx: Context,
    private val token: String,
    private val onUpdate: (TItem) -> Unit,
) {
    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val ids = AtomicLong(0)

    val port: Int get() = ss?.localPort ?: 0

    fun start() {
        val s = ServerSocket(0, 64)
        ss = s
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            s.use {
                s.soTimeout = 30000
                s.tcpNoDelay = true
                val inp = BufferedInputStream(s.getInputStream(), 1 shl 16)
                val out = s.getOutputStream()
                val head = readHead(inp) ?: return
                val lines = head.split("\r\n")
                val rl = lines[0].split(" ")
                if (rl.size < 2) return
                val h = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val k = lines[i].indexOf(':')
                    if (k > 0) h[lines[i].substring(0, k).trim().lowercase()] = lines[i].substring(k + 1).trim()
                }
                if (h["x-token"] != token) { reply(out, 403, "forbidden"); return }
                if (rl[1].startsWith("/ping")) { reply(out, 200, "sharewave"); return }
                if (rl[0] == "POST" && rl[1] == "/upload") receive(inp, out, h) else reply(out, 404, "not found")
            }
        } catch (_: Exception) {
        }
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16384) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == 13 && (state == 0 || state == 2) -> state + 1
                b == 10 && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toString("UTF-8")
        }
        return null
    }

    private fun receive(inp: InputStream, out: OutputStream, h: Map<String, String>) {
        val name = sanitize(URLDecoder.decode(h["x-name"] ?: "file", "UTF-8"))
        val total = h["content-length"]?.toLongOrNull() ?: run { reply(out, 411, "length required"); return }
        val id = ids.incrementAndGet()
        val r = ctx.contentResolver
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShareWave")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { reply(out, 500, "storage error"); return }
        var done = 0L
        try {
            onUpdate(TItem(id, name, total, 0, TState.ACTIVE))
            r.openOutputStream(uri)!!.use { o ->
                val buf = ByteArray(256 * 1024)
                var last = 0L
                while (done < total) {
                    val want = minOf(buf.size.toLong(), total - done).toInt()
                    val n = inp.read(buf, 0, want)
                    if (n < 0) throw IOException("connection closed")
                    o.write(buf, 0, n)
                    done += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 200) {
                        last = now
                        onUpdate(TItem(id, name, total, done, TState.ACTIVE))
                    }
                }
            }
            r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            onUpdate(TItem(id, name, total, total, TState.DONE))
            reply(out, 200, "ok")
        } catch (e: Exception) {
            try { r.delete(uri, null, null) } catch (_: Exception) {}
            onUpdate(TItem(id, name, total, done, TState.FAILED))
        }
    }

    private fun sanitize(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }

    private fun reply(out: OutputStream, code: Int, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code X\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
        out.flush()
    }
}
