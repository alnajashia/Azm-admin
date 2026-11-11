package io.github.alnajashia.azmadmin.manager

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Builds and (for root) runs the server start command.
 *
 * Three start paths run the same [serverShellCommand]: the manager shows
 * it as an ADB command for a connected computer; [WirelessStarter]
 * executes it in-app through the on-device wireless-debugging ADB
 * connection after the user pairs; and the root path runs it through
 * `su` when a root manager is installed and grants it.
 */
object ServerStarter {

    const val ENTRY_CLASS = "io.github.alnajashia.azmadmin.server.AzmAdminServerMain"
    const val CONFIG_PATH = "/data/local/tmp/azmadmin/grants.json"

    data class RootStartResult(val started: Boolean, val message: String)

    fun serverShellCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        return "CLASSPATH=$apkPath app_process /system/bin " +
            "--nice-name=azm_admin_server $ENTRY_CLASS --config $CONFIG_PATH"
    }

    /** The full command to paste into a host terminal with the phone connected. */
    fun adbCommand(context: Context): String =
        "adb shell \"${serverShellCommand(context)} >/dev/null 2>&1 &\""

    fun startViaRoot(context: Context): RootStartResult {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", serverShellCommand(context) + " >/dev/null 2>&1 &"),
            )
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return RootStartResult(false, "su did not answer within 15 seconds")
            }
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }.trim()
            val exit = process.exitValue()
            if (exit == 0) {
                RootStartResult(true, "root start command accepted")
            } else {
                RootStartResult(false, "su exited with $exit${if (error.isNotBlank()) ": $error" else ""}")
            }
        } catch (e: Exception) {
            RootStartResult(false, "root is not available: ${e.message}")
        }
    }
}
