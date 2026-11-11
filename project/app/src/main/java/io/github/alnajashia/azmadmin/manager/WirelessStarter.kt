package io.github.alnajashia.azmadmin.manager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Starts the privileged server through wireless debugging, entirely on
 * the device — the same mechanism Shizuku uses, so no computer is
 * involved:
 *
 * 1. The user enables Wireless debugging in Developer options and opens
 *    "Pair device with pairing code"; Android shows a 6-digit code and a
 *    pairing port (and both ports are also announced over mDNS, which
 *    this class listens for to pre-fill the fields).
 * 2. [pair] runs the ADB SPAKE2 pairing against 127.0.0.1 on the pairing
 *    port; adbd now trusts this app's ADB key (see [AzmAdbManager]).
 *    Pairing is needed once — later starts skip straight to connect.
 * 3. [startServer] connects to adbd's connect port and runs the exact
 *    same app_process starter command the ADB path uses.
 *
 * All calls are blocking and must run off the main thread.
 */
object WirelessStarter {

    private const val HOST = "127.0.0.1"

    data class WirelessResult(val ok: Boolean, val message: String)

    fun pair(context: Context, port: Int, code: String): WirelessResult {
        if (port !in 1..65535) return WirelessResult(false, "invalid pairing port")
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return WirelessResult(false, "pairing code must be 6 digits")
        }
        return try {
            val manager = AzmAdbManager.getInstance(context)
            val paired = manager.pair(HOST, port, code)
            if (paired) {
                WirelessResult(true, "paired")
            } else {
                WirelessResult(false, "pairing rejected — check the code and that the pairing screen is still open")
            }
        } catch (e: Exception) {
            WirelessResult(false, "pairing failed: ${e.message}")
        }
    }

    fun startServer(context: Context, port: Int): WirelessResult {
        if (port !in 1..65535) return WirelessResult(false, "invalid connect port")
        return try {
            val manager = AzmAdbManager.getInstance(context)
            if (!manager.isConnected) {
                val connected = manager.connect(HOST, port)
                if (!connected) {
                    return WirelessResult(false, "connect failed — pair first and check the connect port")
                }
            }
            val command = "mkdir -p /data/local/tmp/azmadmin; " +
                ServerStarter.serverShellCommand(context) + " >/dev/null 2>&1 &"
            manager.openStream("shell:$command").use { stream ->
                // Give app_process a moment to detach and push its binder,
                // then close; the server lives on independently.
                Thread.sleep(1500)
                stream.openOutputStream().flush()
            }
            runCatching { manager.disconnect() }
            WirelessResult(true, "start command sent")
        } catch (e: Exception) {
            WirelessResult(false, "wireless start failed: ${e.message}")
        }
    }

    /**
     * mDNS listener for the two wireless-debugging services. Android
     * announces `_adb-tls-pairing._tcp` while the pairing dialog is open
     * and `_adb-tls-connect._tcp` whenever wireless debugging is on.
     * Discovered ports are reported to the callbacks; manual entry stays
     * available whenever discovery finds nothing.
     */
    class Discovery(
        context: Context,
        private val onPairingPort: (Int) -> Unit,
        private val onConnectPort: (Int) -> Unit,
    ) {
        private val nsd = context.getSystemService(NsdManager::class.java)
        private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        private val multicastLock: WifiManager.MulticastLock? =
            wifi?.createMulticastLock("azmadmin-mdns")?.apply { setReferenceCounted(true) }
        private val resolveExecutor = Executors.newSingleThreadExecutor()
        private var running = false

        private fun listener(expectedType: String, callback: (Int) -> Unit) =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, resolveExecutor,
                        object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                if (resolved.serviceType.contains("_adb-tls-")) {
                                    callback(resolved.port)
                                }
                            }

                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                        })
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, error: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
            }

        private var pairingListener: NsdManager.DiscoveryListener? = null
        private var connectListener: NsdManager.DiscoveryListener? = null

        fun start() {
            if (running) return
            running = true
            runCatching { multicastLock?.acquire() }
            pairingListener = listener(PAIRING_TYPE, onPairingPort)
            connectListener = listener(CONNECT_TYPE, onConnectPort)
            runCatching { nsd.discoverServices(PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairingListener) }
            runCatching { nsd.discoverServices(CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, connectListener) }
        }

        fun stop() {
            if (!running) return
            running = false
            pairingListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            connectListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            pairingListener = null
            connectListener = null
            runCatching { multicastLock?.release() }
            resolveExecutor.shutdown()
            runCatching { resolveExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        }

        companion object {
            const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
            const val CONNECT_TYPE = "_adb-tls-connect._tcp"
        }
    }
}
