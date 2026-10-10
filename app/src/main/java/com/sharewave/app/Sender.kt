package com.sharewave.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder

class Target(
    val host: String,
    val network: Network?,
    private val cb: ConnectivityManager.NetworkCallback?,
    private val cm: ConnectivityManager?,
) {
    fun close() {
        try { if (cb != null) cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }
}

object Sender {
    private fun ping(n: Network?, host: String, p: Pairing): Boolean = try {
        val url = URL("http://$host:${p.port}/ping")
        val c = (n?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 1500
        c.setRequestProperty("X-Token", p.token)
        val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText() == "sharewave"
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    suspend fun connect(ctx: Context, p: Pairing, status: (String) -> Unit): Target {
        // 1) Same network already? (home Wi-Fi / same hotspot)
        for (ip in p.ips) if (ping(null, ip, p)) return Target(ip, null, null, null)

        // 2) Join the receiver's hotspot
        val ssid = p.ssid ?: throw IOException("Receiver not reachable. Put both phones on the same Wi-Fi.")
        status("Joining receiver Wi-Fi… tap CONNECT in the system popup")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(p.pw ?: "").build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val handler = Handler(Looper.getMainLooper())
        val found = CompletableDeferred<Pair<Network, String>>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (found.isCompleted) return
                val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
                val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                if (gw != null) {
                    found.complete(n to gw)
                } else {
                    handler.postDelayed({
                        if (!found.isCompleted) {
                            val parts = (v4.address.hostAddress ?: "").split(".")
                            if (parts.size == 4) found.complete(n to (parts.take(3) + "1").joinToString("."))
                        }
                    }, 700)
                }
            }

            override fun onUnavailable() {
                found.completeExceptionally(IOException("Could not join receiver's Wi-Fi"))
            }
        }
        cm.requestNetwork(req, cb, 40000)
        try {
            val (net, host) = withTimeout(45000) { found.await() }
            status("Connected, starting transfer…")
            repeat(8) {
                if (ping(net, host, p)) return Target(host, net, cb, cm)
                delay(500)
            }
            throw IOException("Receiver app not responding")
        } catch (e: Exception) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            throw e
        }
    }

    fun upload(ctx: Context, t: Target, p: Pairing, f: SendFile, id: Long, batch: String, report: (TItem) -> Unit) {
        var size = f.size
        var temp: File? = null
        try {
            val stream: InputStream
            if (size <= 0) {
                val tf = File.createTempFile("sw", null, ctx.cacheDir)
                temp = tf
                ctx.contentResolver.openInputStream(f.uri)!!.use { i -> tf.outputStream().use { o -> i.copyTo(o, 1024 * 1024) } }
                size = tf.length()
                stream = tf.inputStream()
            } else {
                stream = ctx.contentResolver.openInputStream(f.uri)!!
            }
            report(TItem(id, f.name, size, 0, TState.ACTIVE))
            val url = URL("http://${t.host}:${p.port}/upload")
            val c = (t.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = 120000
            c.setFixedLengthStreamingMode(size)
            c.setRequestProperty("X-Token", p.token)
            c.setRequestProperty("X-Batch", batch)
            c.setRequestProperty("X-Name", URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
            c.setRequestProperty("Content-Type", "application/octet-stream")
            var done = 0L
            var last = 0L
            stream.use { inp ->
                c.outputStream.use { out ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 200) {
                            last = now
                            report(TItem(id, f.name, size, done, TState.ACTIVE))
                        }
                    }
                }
            }
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.disconnect()
            report(TItem(id, f.name, size, size, TState.DONE))
        } catch (e: Exception) {
            report(TItem(id, f.name, maxOf(size, 0), 0, TState.FAILED))
        } finally {
            temp?.delete()
        }
    }
}
