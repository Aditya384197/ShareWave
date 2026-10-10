package com.sharewave.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object Hotspot {
    /** Starts a local-only Wi-Fi hotspot (no internet needed). Returns the reservation via onOk. */
    @SuppressLint("MissingPermission")
    fun start(
        ctx: Context,
        onOk: (ssid: String, pw: String, res: WifiManager.LocalOnlyHotspotReservation) -> Unit,
        onFail: (String) -> Unit,
    ) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @Suppress("DEPRECATION")
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    val ssid: String?
                    val pw: String?
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = res.softApConfiguration
                        ssid = c.ssid
                        pw = c.passphrase
                    } else {
                        val c = res.wifiConfiguration
                        ssid = c?.SSID
                        pw = c?.preSharedKey
                    }
                    val s = ssid?.trim('"')
                    val p = pw?.trim('"')
                    if (s.isNullOrEmpty() || p.isNullOrEmpty()) {
                        res.close()
                        onFail("no hotspot credentials")
                    } else onOk(s, p, res)
                }

                override fun onFailed(reason: Int) = onFail("error $reason (turn ON Location and turn OFF Hotspot/Tethering)")
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onFail(e.message ?: "hotspot error")
        }
    }
}
