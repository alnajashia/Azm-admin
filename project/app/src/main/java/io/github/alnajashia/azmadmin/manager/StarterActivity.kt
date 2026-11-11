package io.github.alnajashia.azmadmin.manager

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.alnajashia.azmadmin.R
import io.github.alnajashia.azmadmin.client.AzmAdmin
import java.util.concurrent.Executors

/**
 * The starter screen, mirroring Shizuku's StarterActivity: a terminal
 * style log of the wireless start as it happens — discover the connect
 * port over mDNS (ports change every session, so nothing is typed),
 * connect, run the starter command, then wait for the server's binder
 * and report the real outcome. On success the screen closes itself
 * after a few seconds, like upstream.
 */
class StarterActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_starter)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        if (savedInstanceState == null && !started) {
            started = true
            io.execute { runWirelessStart() }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun tryStart(endpoint: Pair<String, Int>): WirelessStarter.WirelessResult {
        log(getString(R.string.starter_starting, endpoint.second))
        return WirelessStarter.startServerLogged(this, endpoint.first, endpoint.second) { line -> log(line) }
    }

    /**
     * Discovery on Ahmed's phone keeps serving the STALE announcement
     * first (a port from an earlier wireless-debugging session; the
     * connect is refused on it while the live port differs). So no
     * single resolve is trusted: collect EVERY distinct announced
     * endpoint for a few seconds, then try them newest-first; if all
     * fail, restart discovery fresh and try whatever new endpoints the
     * second round surfaces.
     */
    private fun runWirelessStart() {
        log(getString(R.string.starter_searching))
        val endpoints = mutableListOf<Pair<String, Int>>()
        val tried = mutableSetOf<Pair<String, Int>>()
        var lastResult: WirelessStarter.WirelessResult? = null

        fun collect(discovery: PortDiscovery, seconds: Long) {
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end) {
                Thread.sleep(200)
            }
            discovery.stop()
        }

        fun tryUntried(): Boolean {
            val snapshot = synchronized(endpoints) { endpoints.toList() }
            for (endpoint in snapshot.asReversed()) {
                if (!tried.add(endpoint)) continue
                val result = tryStart(endpoint)
                if (result.ok) return true
                lastResult = result
                log("info: ${endpoint.first}:${endpoint.second} did not answer; trying the next announcement…")
            }
            return false
        }

        var discovery = PortDiscovery(this, PortDiscovery.TLS_CONNECT) { host, port ->
            synchronized(endpoints) {
                val endpoint = host to port
                endpoints.remove(endpoint)
                endpoints.add(endpoint)
            }
        }
        discovery.start()
        collect(discovery, 6)
        var startedOk = tryUntried()
        if (!startedOk) {
            log("info: searching again for a fresher announcement…")
            discovery = PortDiscovery(this, PortDiscovery.TLS_CONNECT) { host, port ->
                synchronized(endpoints) {
                    val endpoint = host to port
                    endpoints.remove(endpoint)
                    endpoints.add(endpoint)
                }
            }
            discovery.start()
            collect(discovery, 10)
            startedOk = tryUntried()
        }
        if (!startedOk) {
            val failure = lastResult
            if (failure == null) {
                log(getString(R.string.starter_no_service))
            } else {
                log("error: ${failure.message}")
            }
            return
        }

        log(getString(R.string.starter_waiting))
        repeat(15) {
            val up = runCatching {
                AzmAdmin.requestBinder(this)
                AzmAdmin.pingBinder()
            }.getOrDefault(false)
            if (up) {
                log(getString(R.string.starter_started))
                Thread.sleep(3000)
                runOnUiThread { finish() }
                return
            }
            Thread.sleep(1000)
        }
        log(getString(R.string.starter_not_started))
    }
}
