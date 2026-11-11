package io.github.alnajashia.azmadmin.manager

import android.content.Context

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

    data class WirelessResult(val ok: Boolean, val message: String)

    /** Full exception description — class, message and stack — so a
     *  failure names itself instead of surfacing as "failed: null". */
    fun describe(e: Throwable): String =
        "${e.javaClass.name}: ${e.message}\n${android.util.Log.getStackTraceString(e)}".trim()

    fun pair(context: Context, host: String, port: Int, code: String): WirelessResult {
        if (port !in 1..65535) return WirelessResult(false, "invalid pairing port")
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return WirelessResult(false, "pairing code must be 6 digits")
        }
        return try {
            val manager = AzmAdbManager.getInstance(context)
            val paired = manager.pair(host, port, code)
            if (paired) {
                WirelessResult(true, "paired")
            } else {
                WirelessResult(false, "pairing rejected — check the code and that the pairing screen is still open")
            }
        } catch (e: Exception) {
            WirelessResult(false, "pairing failed: ${describe(e)}")
        }
    }

    fun startServer(context: Context, host: String, port: Int): WirelessResult =
        startServerLogged(context, host, port) {}

    /**
     * Connects to adbd at [host]:[port] and runs the starter command,
     * reporting each real step to [log] (the Starter screen shows them).
     */
    fun startServerLogged(
        context: Context,
        host: String,
        port: Int,
        log: (String) -> Unit,
    ): WirelessResult {
        if (port !in 1..65535) return WirelessResult(false, "invalid connect port")
        return try {
            val manager = AzmAdbManager.getInstance(context)
            if (!manager.isConnected) {
                log("info: connecting to $host:$port")
                val connected = manager.connect(host, port)
                if (!connected) {
                    return WirelessResult(false, "connect failed — pair first and check the connect port")
                }
                log("info: connected")
            }
            val command = "mkdir -p /data/local/tmp/azmadmin; " +
                ServerStarter.serverShellCommand(context) + " >/dev/null 2>&1 &"
            log("info: running starter via adb shell")
            manager.openStream("shell:$command").use { stream ->
                // Give app_process a moment to detach and push its binder,
                // then close; the server lives on independently.
                Thread.sleep(1500)
                stream.openOutputStream().flush()
            }
            runCatching { manager.disconnect() }
            log("info: starter command sent")
            WirelessResult(true, "start command sent")
        } catch (e: Exception) {
            WirelessResult(false, "wireless start failed: ${describe(e)}")
        }
    }

    /**
     * mDNS listener for the two wireless-debugging services, used by the
     * dashboard to pre-fill the manual port fields. Discovered ports are
     * reported to the callbacks; manual entry stays available whenever
     * discovery finds nothing.
     */
    class Discovery(
        context: Context,
        onPairingPort: (Int) -> Unit,
        onConnectPort: (Int) -> Unit,
    ) {
        private val pairing = PortDiscovery(context, PortDiscovery.TLS_PAIRING) { _, port -> onPairingPort(port) }
        private val connect = PortDiscovery(context, PortDiscovery.TLS_CONNECT) { _, port -> onConnectPort(port) }

        fun start() {
            pairing.start()
            connect.start()
        }

        fun stop() {
            pairing.stop()
            connect.stop()
        }
    }
}
