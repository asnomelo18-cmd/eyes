package com.eyes.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Empêche le téléphone de couper le processeur, le Wi-Fi et le multicast (mDNS) pendant « Prêt à partager ». */
class KeepAwake(context: Context) {
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eyes:share")
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private val wifi = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "eyes:wifi")
    private val multicast = wifiManager.createMulticastLock("eyes:mdns")

    @SuppressLint("WakelockTimeout") // tenu volontairement tant que le service tourne
    fun acquire() {
        if (!wake.isHeld) wake.acquire()
        if (!wifi.isHeld) wifi.acquire()
        if (!multicast.isHeld) multicast.acquire()
    }

    fun release() {
        if (multicast.isHeld) multicast.release()
        if (wifi.isHeld) wifi.release()
        if (wake.isHeld) wake.release()
    }
}
