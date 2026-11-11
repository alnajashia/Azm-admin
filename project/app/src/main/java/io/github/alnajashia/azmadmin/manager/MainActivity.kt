package io.github.alnajashia.azmadmin.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import io.github.alnajashia.azmadmin.R
import io.github.alnajashia.azmadmin.client.AzmAdmin
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Azm Admin manager dashboard. Every state shown here comes from a live
 * binder call to the privileged server (state JSON, pending list, grant
 * list) — never from an assumed flag. When no binder answers, the UI says
 * the server is stopped and offers the real start paths (ADB command copy,
 * root start) instead of pretending otherwise.
 */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var stopButton: MaterialButton
    private lateinit var startSection: View
    private lateinit var adbCommandText: TextView
    private lateinit var rootResultText: TextView
    private lateinit var pendingList: LinearLayout
    private lateinit var clientsList: LinearLayout
    private lateinit var authorizeAzmButton: MaterialButton
    private lateinit var diagnosticsButton: MaterialButton
    private lateinit var pairingPortInput: TextInputEditText
    private lateinit var pairingCodeInput: TextInputEditText
    private lateinit var connectPortInput: TextInputEditText
    private lateinit var wirelessStatusText: TextView
    private var discovery: WirelessStarter.Discovery? = null

    @Volatile
    private var serverRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        stopButton = findViewById(R.id.stopButton)
        startSection = findViewById(R.id.startSection)
        adbCommandText = findViewById(R.id.adbCommandText)
        rootResultText = findViewById(R.id.rootResultText)
        pendingList = findViewById(R.id.pendingList)
        clientsList = findViewById(R.id.clientsList)
        authorizeAzmButton = findViewById(R.id.authorizeAzmButton)
        diagnosticsButton = findViewById(R.id.diagnosticsButton)
        pairingPortInput = findViewById(R.id.pairingPortInput)
        pairingCodeInput = findViewById(R.id.pairingCodeInput)
        connectPortInput = findViewById(R.id.connectPortInput)
        wirelessStatusText = findViewById(R.id.wirelessStatusText)

        adbCommandText.text = ServerStarter.adbCommand(this)

        CrashLog.readAndClear(this)?.let { crash ->
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(crash.take(3000))
                .setPositiveButton(R.string.close, null)
                .show()
        }

        discovery = WirelessStarter.Discovery(
            this,
            onPairingPort = { port ->
                runOnUiThread {
                    if (pairingPortInput.text.isNullOrBlank()) {
                        pairingPortInput.setText(port.toString())
                    }
                }
            },
            onConnectPort = { port ->
                runOnUiThread {
                    if (connectPortInput.text.isNullOrBlank()) {
                        connectPortInput.setText(port.toString())
                    }
                }
            },
        )

        findViewById<MaterialButton>(R.id.pairFlowButton).setOnClickListener {
            startActivity(android.content.Intent(this, PairingTutorialActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.startFlowButton).setOnClickListener {
            startActivity(android.content.Intent(this, StarterActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.pairButton).setOnClickListener {
            val port = pairingPortInput.text?.toString()?.toIntOrNull() ?: -1
            val code = pairingCodeInput.text?.toString()?.trim().orEmpty()
            wirelessStatusText.text = getString(R.string.wireless_working)
            io.execute {
                val result = WirelessStarter.pair(this, "127.0.0.1", port, code)
                runOnUiThread {
                    wirelessStatusText.text = getString(R.string.wireless_result, result.message)
                }
            }
        }
        findViewById<MaterialButton>(R.id.wirelessStartButton).setOnClickListener {
            val port = connectPortInput.text?.toString()?.toIntOrNull() ?: -1
            wirelessStatusText.text = getString(R.string.wireless_working)
            io.execute {
                val result = WirelessStarter.startServer(this, "127.0.0.1", port)
                runOnUiThread {
                    wirelessStatusText.text = getString(R.string.wireless_result, result.message)
                }
                // Give the server a moment to come up and push its binder.
                Thread.sleep(2000)
                refresh()
            }
        }

        findViewById<MaterialButton>(R.id.refreshButton).setOnClickListener { refresh() }
        findViewById<MaterialButton>(R.id.copyCommandButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Azm Admin ADB command", ServerStarter.adbCommand(this)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.rootStartButton).setOnClickListener {
            io.execute {
                val result = ServerStarter.startViaRoot(this)
                runOnUiThread {
                    rootResultText.text = getString(R.string.root_start_result, result.message)
                }
                // Give the server a moment to come up and push its binder.
                Thread.sleep(1500)
                refresh()
            }
        }
        stopButton.setOnClickListener {
            io.execute {
                try {
                    AzmAdmin.requireService(this).exitServer()
                } catch (_: Exception) {
                    // The binder is expected to die with the server.
                }
                Thread.sleep(600)
                refresh()
            }
        }
        authorizeAzmButton.setOnClickListener {
            io.execute {
                val message = try {
                    val result = AzmAdmin.requireService(this).approveClient(AZM_PACKAGE)
                    result.message
                } catch (e: Exception) {
                    e.message ?: "error"
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.request_result, message), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), 41)
        }
        diagnosticsButton.setOnClickListener {
            io.execute {
                val text = try {
                    AzmAdmin.requireService(this).diagnosticsJson
                } catch (e: Exception) {
                    e.message ?: "error"
                }
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.diagnostics_title)
                        .setMessage(text)
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        discovery?.start()
    }

    override fun onStop() {
        discovery?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        io.execute {
            val running = AzmAdmin.requestBinder(this)
            if (!running) {
                runOnUiThread { renderStopped() }
                return@execute
            }
            try {
                val service = AzmAdmin.requireService(this)
                val state = JSONObject(service.serverStateJson)
                val pending = JSONArray(service.pendingRequestsJson)
                val clients = JSONArray(service.authorizedClientsJson)
                runOnUiThread { renderRunning(state, pending, clients) }
            } catch (e: Exception) {
                runOnUiThread { renderStopped() }
            }
        }
    }

    private fun renderStopped() {
        serverRunning = false
        statusText.text = getString(R.string.status_stopped)
        stopButton.visibility = View.GONE
        startSection.visibility = View.VISIBLE
        diagnosticsButton.visibility = View.GONE
        authorizeAzmButton.visibility = View.GONE
        pendingList.removeAllViews()
        pendingList.addView(bodyText(getString(R.string.pending_none)))
        clientsList.removeAllViews()
        clientsList.addView(bodyText(getString(R.string.clients_none)))
    }

    private fun renderRunning(state: JSONObject, pending: JSONArray, clients: JSONArray) {
        serverRunning = true
        val uid = state.optInt("serverUid", -1)
        val modeText = when (state.optString("mode")) {
            "shell" -> getString(R.string.status_mode_shell)
            "root" -> getString(R.string.status_mode_root)
            else -> getString(R.string.status_mode_other, uid)
        }
        statusText.text = listOf(
            getString(R.string.status_running),
            modeText,
            getString(R.string.status_api_version, state.optInt("apiVersion", 0)),
        ).joinToString("\n")
        stopButton.visibility = View.VISIBLE
        startSection.visibility = View.GONE
        diagnosticsButton.visibility = View.VISIBLE

        pendingList.removeAllViews()
        if (pending.length() == 0) {
            pendingList.addView(bodyText(getString(R.string.pending_none)))
        } else {
            for (i in 0 until pending.length()) {
                val item = pending.getJSONObject(i)
                val pkg = item.getString("packageName")
                pendingList.addView(clientRow(pkg, item.optInt("uid", -1), R.string.approve) {
                    decide(pkg, approve = true)
                })
            }
        }

        clientsList.removeAllViews()
        var azmAuthorized = false
        if (clients.length() == 0) {
            clientsList.addView(bodyText(getString(R.string.clients_none)))
        } else {
            for (i in 0 until clients.length()) {
                val item = clients.getJSONObject(i)
                val pkg = item.getString("packageName")
                if (pkg == AZM_PACKAGE) azmAuthorized = true
                clientsList.addView(clientRow(pkg, item.optInt("uid", -1), R.string.revoke) {
                    decide(pkg, approve = false)
                })
            }
        }

        val azmInstalled = AzmAdmin.isManagerInstalled(this) &&
            try {
                packageManager.getPackageInfo(AZM_PACKAGE, 0) != null
            } catch (_: Exception) {
                false
            }
        authorizeAzmButton.visibility = if (azmInstalled && !azmAuthorized) View.VISIBLE else View.GONE
    }

    private fun decide(packageName: String, approve: Boolean) {
        io.execute {
            val message = try {
                val service = AzmAdmin.requireService(this)
                val result = if (approve) {
                    service.approveClient(packageName)
                } else {
                    service.revokeClient(packageName)
                }
                result.message
            } catch (e: Exception) {
                e.message ?: "error"
            }
            runOnUiThread {
                Toast.makeText(this, getString(R.string.request_result, message), Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, 8, 0, 8)
        }

    private fun clientRow(
        packageName: String,
        uid: Int,
        actionLabel: Int,
        onAction: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val label = TextView(this).apply {
            text = getString(R.string.client_line, packageName, uid)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = MaterialButton(this).apply {
            text = getString(actionLabel)
            setOnClickListener { onAction() }
        }
        row.addView(label)
        row.addView(button)
        return row
    }

    companion object {
        private const val AZM_PACKAGE = "io.github.alnajashia.dominanter"
    }
}
