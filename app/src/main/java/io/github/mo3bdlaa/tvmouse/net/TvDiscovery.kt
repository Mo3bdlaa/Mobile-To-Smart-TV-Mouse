package io.github.mo3bdlaa.tvmouse.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** Finds TVs running the TV Mouse receiver on the local network (mDNS / DNS-SD). */
class TvDiscovery(context: Context) {

    data class Found(val id: String?, val name: String, val host: String, val port: Int)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    fun start() {
        if (listener != null || nsd == null) return
        multicastLock = appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("tvmouse-discovery")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
                main.post { listener = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queueResolve(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { _found.value = _found.value.filter { it.name != info.serviceName } }
            }
        }
        listener = discoveryListener
        runCatching { nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        multicastLock?.release()
        multicastLock = null
    }

    private fun queueResolve(info: NsdServiceInfo) {
        pending.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService/host still work and cover Android 9-13 too.
    private fun resolveNext() {
        if (resolving || nsd == null) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post { onResolved(info) }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun onResolved(info: NsdServiceInfo) {
        resolving = false
        val address = info.host
        if (address != null && listener != null) {
            val host = if (address is Inet4Address) address.hostAddress else address.hostAddress?.substringBefore('%')
            val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) }
            if (host != null) {
                val entry = Found(id, info.serviceName, host, info.port)
                _found.value = _found.value.filter { it.name != entry.name && (id == null || it.id != id) } + entry
            }
        }
        resolveNext()
    }

    private companion object {
        const val TAG = "TvDiscovery"
    }
}
