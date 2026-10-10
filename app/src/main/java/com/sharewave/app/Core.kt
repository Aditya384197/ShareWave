package com.sharewave.app

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

enum class Screen { HOME, RECEIVE, SEND }
enum class TState { ACTIVE, DONE, FAILED }

data class TItem(val id: Long, val name: String, val total: Long, val done: Long, val state: TState)
data class SendFile(val uri: Uri, val name: String, val size: Long)

/** Data that is encoded inside the QR code. */
data class Pairing(
    val ssid: String?,
    val pw: String?,
    val token: String,
    val port: Int,
    val ips: List<String>,
) {
    fun encode(): String = Uri.Builder().scheme("sharewave").authority("c").apply {
        ssid?.let { appendQueryParameter("s", it) }
        pw?.let { appendQueryParameter("p", it) }
        appendQueryParameter("t", token)
        appendQueryParameter("o", port.toString())
        appendQueryParameter("i", ips.joinToString(","))
    }.build().toString()

    companion object {
        fun parse(text: String): Pairing? = try {
            val u = Uri.parse(text.trim())
            if (u.scheme != "sharewave") null
            else Pairing(
                ssid = u.getQueryParameter("s"),
                pw = u.getQueryParameter("p"),
                token = u.getQueryParameter("t") ?: "",
                port = u.getQueryParameter("o")?.toInt() ?: 0,
                ips = (u.getQueryParameter("i") ?: "").split(",").filter { it.isNotBlank() },
            ).takeIf { it.token.isNotEmpty() && it.port > 0 }
        } catch (e: Exception) {
            null
        }
    }
}

fun makeQr(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

fun localIps(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .filterNot { ni -> listOf("rmnet", "ccmni", "v4-", "dummy", "tun", "lo").any { ni.name.startsWith(it) } }
        .flatMap { ni ->
            ni.inetAddresses.toList()
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }
} catch (e: Exception) {
    emptyList()
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024)
}
