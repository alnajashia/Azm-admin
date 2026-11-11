package io.github.alnajashia.azmadmin.server

import android.os.Looper
import android.util.Log
import io.github.alnajashia.azmadmin.client.AzmAdmin
import java.io.File

/**
 * Privileged server entry point, launched through app_process exactly the
 * way Shizuku's starter launches its server:
 *
 *   CLASSPATH=<manager APK> app_process /system/bin \
 *     --nice-name=azm_admin_server \
 *     io.github.alnajashia.azmadmin.server.AzmAdminServerMain \
 *     --config /data/local/tmp/azmadmin/grants.json
 *
 * Started by ADB the process runs as shell (2000); started by su it runs as
 * root (0). It cannot start itself from the manager app — that boundary is
 * Android's, and the manager UI states it honestly and supplies the exact
 * ADB command plus a root start where a root manager is present.
 */
class AzmAdminServerMain {

    companion object {

        private const val TAG = "AzmAdminServer"
        private const val DEFAULT_CONFIG = "/data/local/tmp/azmadmin/grants.json"
        private const val SERVICE_NAME = "io.github.alnajashia.azmadmin"

        @JvmStatic
        fun main(args: Array<String>) {
            var configPath = DEFAULT_CONFIG
            var index = 0
            while (index < args.size) {
                if (args[index] == "--config" && index + 1 < args.size) {
                    configPath = args[index + 1]
                    index += 2
                } else {
                    index++
                }
            }

            Log.i(TAG, "starting Azm Admin server, uid=${android.os.Process.myUid()}, config=$configPath")
            val authStore = AuthStore(File(configPath))
            val service = AzmAdminServiceImpl(authStore)
            val binder = service.asBinder()

            // Best-effort system-service registration; servicemanager policy
            // refuses shell on many builds, which is fine — the provider
            // push below is the supported discovery path.
            HiddenApi.addService(SERVICE_NAME, binder)

            // Push the binder to the manager (which redistributes it to
            // clients) and directly to Azm's own provider. Retry for a
            // while: at device-boot-adjacent starts the manager process may
            // not be ready on the first attempt.
            val pushThread = Thread {
                val targets = listOf(
                    AzmAdmin.MANAGER_PROVIDER_AUTHORITY to 0,
                    "io.github.alnajashia.dominanter.azmadmin" to 0,
                )
                val delaysMs = longArrayOf(0, 2_000, 5_000, 10_000, 20_000)
                val delivered = mutableSetOf<String>()
                for (delay in delaysMs) {
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                    for ((authority, userId) in targets) {
                        if (authority in delivered) continue
                        if (BinderPush.push(binder, authority, userId)) {
                            delivered.add(authority)
                        }
                    }
                    if (delivered.size == targets.size) break
                }
                Log.i(TAG, "binder push finished, delivered=$delivered")
            }
            pushThread.isDaemon = true
            pushThread.name = "azmadmin-binder-push"
            pushThread.start()

            Looper.prepare()
            Looper.loop()
        }
    }
}
