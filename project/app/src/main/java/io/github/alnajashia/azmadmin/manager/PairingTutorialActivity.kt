package io.github.alnajashia.azmadmin.manager

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.alnajashia.azmadmin.R

/**
 * The pairing guide screen, mirroring Shizuku's AdbPairingTutorialActivity:
 * it starts the pairing service (which posts the notification the user
 * types the code into), explains the three steps, warns about local
 * network restrictions on MIUI-style ROMs, and — when notifications are
 * disabled — replaces the steps with a path to the notification settings,
 * because without notifications the flow cannot work.
 */
class PairingTutorialActivity : AppCompatActivity() {

    private var notificationEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing_tutorial)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            findViewById<View>(R.id.miuiNote).visibility = View.VISIBLE
        }

        findViewById<MaterialButton>(R.id.developerOptionsButton).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        findViewById<MaterialButton>(R.id.notificationOptionsButton).setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationEnabled = getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled()
        syncVisibility()
        if (notificationEnabled) {
            ContextCompat.startForegroundService(this, PairingService.startIntent(this))
        }
    }

    private fun syncVisibility() {
        val stepsVisibility = if (notificationEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step1).visibility = stepsVisibility
        findViewById<View>(R.id.step2).visibility = stepsVisibility
        findViewById<View>(R.id.step3).visibility = stepsVisibility
        findViewById<View>(R.id.developerOptionsButton).visibility = stepsVisibility
        findViewById<View>(R.id.networkNote).visibility = stepsVisibility
        findViewById<View>(R.id.notificationDisabled).visibility =
            if (notificationEnabled) View.GONE else View.VISIBLE
    }
}
