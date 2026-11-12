package com.eyes.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.eyes.app.protocol.PROTOCOL_VERSION

/** Annonce `_eyes._tcp` sur le Wi-Fi (TXT : id, v). */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, phoneId: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_eyes._tcp"
            setPort(port)
            setAttribute("id", phoneId)
            setAttribute("v", PROTOCOL_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i("EyesNsd", "annoncé : ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w("EyesNsd", "échec annonce $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}
