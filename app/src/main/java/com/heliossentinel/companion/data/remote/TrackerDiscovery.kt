package com.heliossentinel.companion.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.Collections
import kotlin.coroutines.resume

data class DiscoveredTracker(val name: String, val host: String, val port: Int, val source: String)

/**
 * Finds Helios Sentinel trackers on the current network two ways at once:
 *  1. mDNS / DNS-SD — the firmware advertises a "_helios._tcp" service.
 *  2. Probing well-known addresses (the ESP32 SoftAP gateway) with GET /api/status.
 */
class TrackerDiscovery(context: Context, private val api: TrackerApiService) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(timeoutMs: Long = 6000L): List<DiscoveredTracker> = coroutineScope {
        val mdns = async { discoverMdns(timeoutMs) }
        val probes = FALLBACK_HOSTS.map { host -> async { probe(host, 80) } }
        (mdns.await() + probes.awaitAll().filterNotNull()).distinctBy { it.host to it.port }
    }

    private suspend fun probe(host: String, port: Int): DiscoveredTracker? =
        api.getStatus(baseUrl = "http://$host:$port").getOrNull()
            ?.let { DiscoveredTracker("Helios Sentinel", host, port, "direct probe") }

    private suspend fun discoverMdns(timeoutMs: Long): List<DiscoveredTracker> {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val results = Collections.synchronizedList(mutableListOf<DiscoveredTracker>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.trySend(serviceInfo) }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { found.close() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        val started = runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
        if (!started) return emptyList()

        try {
            withTimeoutOrNull(timeoutMs) {
                // Resolve one at a time: older Android versions reject concurrent resolves.
                for (info in found) resolve(info)?.let { results += it }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return results.toList()
    }

    @Suppress("DEPRECATION") // resolveService/host are deprecated on API 34 but still work on every supported version.
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredTracker? = withTimeoutOrNull(3000L) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val address = serviceInfo.host as? Inet4Address
                    val tracker = address?.hostAddress?.let {
                        DiscoveredTracker(serviceInfo.serviceName, it, serviceInfo.port, "mDNS")
                    }
                    if (cont.isActive) cont.resume(tracker)
                }
            })
        }
    }

    companion object {
        const val SERVICE_TYPE = "_helios._tcp."
        private val FALLBACK_HOSTS = listOf("192.168.4.1", "helios-sentinel.local")
    }
}
