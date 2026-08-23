package com.chronicle.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Discover a Mac running `chronicle serve` via Bonjour/NSD
 * (`_chronicle._tcp.local.`, CONTRACT v1.11).
 *
 * The PC advertises TXT `v` (connect version) and `fp` (TLS cert fingerprint)
 * when the optional zeroconf extra is installed; discovery is convenience —
 * QR pairing remains canonical when mDNS is unavailable (Windows, firewalls).
 */
object NsdHelper {
    const val SERVICE_TYPE = "_chronicle._tcp."

    data class DiscoveredServe(
        val name: String,
        val host: String,
        val port: Int,
        /** TLS cert fingerprint from TXT records, when advertised. */
        val tlsFp: String?,
        /** Whether the Mac serves https (`tls=1` TXT, default true). */
        val tls: Boolean,
    )

    /**
     * Emits each resolved serve as it appears. Flow completes on [dispose];
     * callers should collect with a timeout — discovery runs until cancelled.
     */
    fun discover(context: Context): Flow<DiscoveredServe> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val disposed = AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                // Unresolvable instances are skipped, not fatal.
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (disposed.get()) return
                val host = info.host?.hostAddress ?: return
                val fp = info.attributes["fp"]?.let { String(it) }
                // Older PC builds omit the flag; they are https-by-default too.
                val tls = info.attributes["tls"]?.let { String(it) != "0" } ?: true
                trySend(
                    DiscoveredServe(
                        name = info.serviceName ?: "Chronicle",
                        host = host,
                        port = info.port,
                        tlsFp = fp,
                        tls = tls,
                    ),
                )
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD start failed ($errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (disposed.get()) return
                if (serviceInfo.serviceType?.startsWith("_chronicle") == true ||
                    serviceInfo.serviceName?.contains("chronicle", ignoreCase = true) == true
                ) {
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            disposed.set(true)
            runCatching {
                nsdManager.stopServiceDiscovery(discoveryListener)
            }
        }
    }
}
