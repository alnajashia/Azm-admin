package io.github.alnajashia.azmadmin.manager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.Executors

/**
 * mDNS discovery for one wireless-debugging service type, in the role of
 * Shizuku's AdbMdns: Android announces `_adb-tls-pairing._tcp` while the
 * pairing dialog is open and `_adb-tls-connect._tcp` whenever wireless
 * debugging is on; each announcement carries the current endpoint —
 * the device's network address AND its port — reported to [onEndpoint].
 * Both halves matter: adbd's TLS listener is bound to the Wi-Fi
 * interface on many ROMs, so connecting to 127.0.0.1 can fail even with
 * the right port, and ports change between sessions, so discovery —
 * not any stored value — is the source of truth.
 */
class PortDiscovery(
    context: Context,
    private val serviceType: String,
    private val onEndpoint: (host: String, port: Int) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicastLock: WifiManager.MulticastLock? =
        wifi?.createMulticastLock("azmadmin-mdns")?.apply { setReferenceCounted(true) }
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private var running = false

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (resolved.serviceType.contains("_adb-tls-") && resolved.port > 0) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: return
                    onEndpoint(host, resolved.port)
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        // The Executor-based overload exists only on API 34+; calling it
        // on Android 11–13 throws NoSuchMethodError the moment a service
        // is found and kills the caller. Use the legacy overload there.
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                nsd.resolveService(serviceInfo, resolveExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, listener)
            }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolve(serviceInfo)
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, error: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
    }

    fun start() {
        if (running) return
        running = true
        runCatching { multicastLock?.acquire() }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        runCatching { multicastLock?.release() }
        resolveExecutor.shutdown()
    }

    companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
    }
}
