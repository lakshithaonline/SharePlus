package com.example.shareplus.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

// advertises _raop._tcp / _airplay._tcp over mDNS. UxPlay's dnssd.c only builds the TXT
// records on Android (android_dnssd_shim.c); the actual announcement goes through NsdManager
class NsdPublisher(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    // Android drops inbound multicast (mDNS queries from the iPhone) without this
    private val multicastLock = wifi.createMulticastLock("shareplus_mdns").apply {
        setReferenceCounted(false)
        acquire()
    }

    fun register(type: String, name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered $type '${info.serviceName}' on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "register $type failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "unregistered $type")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "unregister $type failed: $code")
            }
        }
        listeners += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun release() {
        listeners.forEach { runCatching { nsd.unregisterService(it) } }
        listeners.clear()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private companion object {
        const val TAG = "SharePlus"
    }
}
