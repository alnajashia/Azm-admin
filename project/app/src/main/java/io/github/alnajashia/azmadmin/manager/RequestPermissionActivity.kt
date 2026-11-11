package io.github.alnajashia.azmadmin.manager

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.alnajashia.azmadmin.R
import io.github.alnajashia.azmadmin.client.AzmAdmin
import java.util.concurrent.Executors

/**
 * The approval screen the privileged server opens (via `am start`) when a
 * client calls requestAuthorization. Approving here does not itself grant
 * anything in this process: it asks the server, through its manager-only
 * approveClient call, to record the (package, UID, signing certificate)
 * triple it resolves itself. Denying simply closes the screen — a denial is
 * one-time and records nothing, matching Shizuku's model.
 */
class RequestPermissionActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_permission)

        val body = findViewById<TextView>(R.id.requestBody)
        val resultText = findViewById<TextView>(R.id.requestResult)
        val approveButton = findViewById<MaterialButton>(R.id.approveButton)
        val denyButton = findViewById<MaterialButton>(R.id.denyButton)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (packageName.isNullOrBlank()) {
            body.text = getString(R.string.request_missing_package)
            approveButton.isEnabled = false
            denyButton.setOnClickListener { finish() }
            return
        }
        body.text = getString(R.string.request_body, packageName)

        denyButton.setOnClickListener { finish() }
        approveButton.setOnClickListener {
            approveButton.isEnabled = false
            io.execute {
                val message = try {
                    if (!AzmAdmin.requestBinder(this)) {
                        getString(R.string.request_server_unavailable)
                    } else {
                        AzmAdmin.requireService(this).approveClient(packageName).message
                    }
                } catch (e: Exception) {
                    e.message ?: "error"
                }
                runOnUiThread {
                    resultText.text = getString(R.string.request_result, message)
                    denyButton.text = getString(R.string.close)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "packageName"
    }
}
