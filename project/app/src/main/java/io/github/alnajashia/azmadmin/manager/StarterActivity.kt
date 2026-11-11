package io.github.alnajashia.azmadmin.manager

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.alnajashia.azmadmin.R
import io.github.alnajashia.azmadmin.client.AzmAdmin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private fun runWirelessStart() {
        log(getString(R.string.starter_searching))
        val portRef = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val discovery = PortDiscovery(this, PortDiscovery.TLS_CONNECT) { port ->
            if (portRef.compareAndSet(-1, port)) latch.countDown()
        }
        discovery.start()
        val found = latch.await(30, TimeUnit.SECONDS)
        discovery.stop()
        if (!found) {
            log(getString(R.string.starter_no_service))
            return
        }
        val port = portRef.get()
        log(getString(R.string.starter_starting, port))

        val result = WirelessStarter.startServerLogged(this, port) { line -> log(line) }
        if (!result.ok) {
            log("error: ${result.message}")
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
