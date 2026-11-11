package io.github.alnajashia.azmadmin.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.alnajashia.azmadmin.R
import java.util.concurrent.Executors

/**
 * The pairing flow exactly as Shizuku's AdbPairingService runs it:
 *
 * - Started from the pairing tutorial screen; posts a foreground
 *   "searching for pairing service" notification and listens over mDNS
 *   for Android's `_adb-tls-pairing` announcement.
 * - When the user opens "Pair device with pairing code" in Developer
 *   options, the announcement carries the pairing port; the service
 *   re-posts the notification with a reply action, so the user types
 *   the 6-digit code straight into the notification (RemoteInput) —
 *   the port travels inside the reply intent, so it stays correct even
 *   if the service was restarted.
 * - The code runs the SPAKE2 pairing through the manager's ADB identity
 *   ([AzmAdbManager]); a result notification reports success or the
 *   real failure, and the service stops.
 */
class PairingService : Service() {

    private val io = Executors.newSingleThreadExecutor()
    private var discovery: PortDiscovery? = null
    @Volatile
    private var searching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_pairing),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> postForeground(searchingNotification())
            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                if (port > 0 && code.isNotBlank()) {
                    postForeground(workingNotification())
                    io.execute { doPair(code, host, port) }
                } else {
                    postForeground(searchingNotification())
                }
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopSearch()
        io.shutdown()
        super.onDestroy()
    }

    private fun postForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Foreground start can be refused from the background on
            // newer Android; the notification itself still reaches the
            // user, which is what the flow depends on.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        startSearch()
    }

    private fun startSearch() {
        if (searching) return
        searching = true
        discovery = PortDiscovery(this, PortDiscovery.TLS_PAIRING) { host, port ->
            // A pairing announcement appeared (or moved): re-post the
            // input notification addressed at the current endpoint.
            val manager = getSystemService(NotificationManager::class.java)
            if (searching) manager.notify(NOTIFICATION_ID, inputNotification(host, port))
        }.also { it.start() }
    }

    private fun stopSearch() {
        searching = false
        discovery?.stop()
        discovery = null
    }

    private fun doPair(code: String, host: String, port: Int) {
        val result = WirelessStarter.pair(this, host, port, code)
        stopSearch()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(
                getString(
                    if (result.ok) R.string.pairing_succeeded_title else R.string.pairing_failed_title,
                ),
            )
            .setContentText(result.message)
            .setStyle(Notification.BigTextStyle().bigText(result.message))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun baseNotification(title: Int, text: Int): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setOngoing(true)

    private fun searchingNotification(): Notification =
        baseNotification(R.string.pairing_searching_title, R.string.pairing_searching_text)
            .addAction(stopAction())
            .build()

    private fun workingNotification(): Notification =
        baseNotification(R.string.pairing_working_title, R.string.pairing_working_text)
            .build()

    private fun inputNotification(host: String, port: Int): Notification {
        val replyIntent = Intent(this, PairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPending = PendingIntent.getService(this, REQUEST_REPLY, replyIntent, flags)
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.pairing_code_hint))
            .build()
        val replyAction = Notification.Action.Builder(
            null,
            getString(R.string.pairing_enter_code),
            replyPending,
        ).addRemoteInput(remoteInput).build()
        return baseNotification(R.string.pairing_input_title, R.string.pairing_input_text)
            .addAction(replyAction)
            .addAction(stopAction())
            .build()
    }

    private fun stopAction(): Notification.Action {
        val stopIntent = Intent(this, PairingService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getService(this, REQUEST_STOP, stopIntent, flags)
        return Notification.Action.Builder(null, getString(R.string.pairing_stop_searching), pending).build()
    }

    companion object {
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2
        private const val ACTION_START = "io.github.alnajashia.azmadmin.pairing.START"
        private const val ACTION_STOP = "io.github.alnajashia.azmadmin.pairing.STOP"
        private const val ACTION_REPLY = "io.github.alnajashia.azmadmin.pairing.REPLY"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val REMOTE_INPUT_KEY = "pairing_code"

        fun startIntent(context: Context): Intent =
            Intent(context, PairingService::class.java).setAction(ACTION_START)
    }
}
