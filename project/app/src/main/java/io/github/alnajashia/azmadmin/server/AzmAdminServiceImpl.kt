package io.github.alnajashia.azmadmin.server

import android.content.ComponentName
import android.os.Binder
import android.os.Process
import android.util.Log
import io.github.alnajashia.azmadmin.api.AzmAdminResult
import io.github.alnajashia.azmadmin.api.IAzmAdminService
import io.github.alnajashia.azmadmin.client.AzmAdmin
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The privileged server: a typed, allow-listed implementation of
 * [IAzmAdminService]. It runs as shell (2000) when started through ADB or
 * root (0) when started through su, inside the app_process started by
 * [AzmAdminServerMain].
 *
 * Security model (approved Strategy B):
 *  - Identity comes only from the Binder calling UID. The server resolves
 *    UID -> packages and package -> signing certificate itself, through
 *    IPackageManager; nothing a caller claims is trusted.
 *  - A grant exists only because the user approved the client in the
 *    manager UI; recording it is a manager-only call and stores
 *    (package, UID, cert SHA-256) resolved server-side at approval time.
 *  - Every privileged call re-verifies all three values and fails closed.
 *  - A client may only act on its OWN package (provision itself, grant
 *    itself, toggle its own accessibility service).
 *  - Every operation post-verifies its result before reporting success,
 *    and decisive shell steps always carry their exit code.
 */
class AzmAdminServiceImpl(private val authStore: AuthStore) : IAzmAdminService.Stub() {

    private data class PendingRequest(val packageName: String, val uid: Int, val requestedAtMillis: Long)

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    // ---- identity ---------------------------------------------------------

    private fun callerUid(): Int = Binder.getCallingUid()

    private fun callerUserId(): Int = callerUid() / 100000

    private fun callerPackages(): List<String> {
        val pm = HiddenApi.packageManager() ?: return emptyList()
        return HiddenApi.getPackagesForUid(pm, callerUid()).toList()
    }

    private fun isManagerCaller(): Boolean = callerPackages().contains(AzmAdmin.MANAGER_PACKAGE)

    /**
     * The grant check every privileged operation runs: a recorded grant for
     * one of the caller's packages whose stored UID equals the calling UID
     * and whose stored certificate equals the package's CURRENT signing
     * certificate (so a reinstall under a different key loses the grant).
     */
    private fun authorizedCallerPackage(): String? {
        val pm = HiddenApi.packageManager() ?: return null
        val uid = callerUid()
        val userId = callerUserId()
        for (pkg in callerPackages()) {
            val grant = authStore.find(pkg) ?: continue
            if (grant.uid != uid) continue
            val currentCert = HiddenApi.getSigningCertSha256(pm, pkg, userId) ?: continue
            if (currentCert.equals(grant.certSha256, ignoreCase = true)) return pkg
        }
        return null
    }

    private fun requireAuthorized(): String =
        authorizedCallerPackage()
            ?: throw SecurityException("Azm Admin: caller is not authorized")

    private fun requireManager() {
        if (!isManagerCaller()) throw SecurityException("Azm Admin: manager-only operation")
    }

    private fun notAuthorizedResult(): AzmAdminResult =
        AzmAdminResult.fail(
            "NOT_AUTHORIZED",
            "The calling app is not authorized in Azm Admin",
        )

    // ---- state / authorization ---------------------------------------------

    override fun getApiVersion(): Int = AzmAdmin.API_VERSION

    override fun getServerUid(): Int = Process.myUid()

    override fun getServerStateJson(): String {
        val uid = Process.myUid()
        val mode = when (uid) {
            0 -> "root"
            2000 -> "shell"
            else -> "other"
        }
        return JSONObject()
            .put("apiVersion", AzmAdmin.API_VERSION)
            .put("serverUid", uid)
            .put("mode", mode)
            .put("callerPackage", callerPackages().firstOrNull() ?: JSONObject.NULL)
            .put("callerAuthorized", authorizedCallerPackage() != null)
            .put("callerIsManager", isManagerCaller())
            .toString()
    }

    override fun checkAuthorization(): Int =
        if (authorizedCallerPackage() != null) 1 else 0

    override fun requestAuthorization() {
        val pkg = callerPackages().firstOrNull()
            ?: throw SecurityException("Azm Admin: caller package could not be resolved")
        if (authorizedCallerPackage() != null) return
        pending[pkg] = PendingRequest(pkg, callerUid(), System.currentTimeMillis())
        // Ask the manager app to show its approval screen. `am` runs with
        // the server's own (shell/root) identity, so this reaches the
        // manager even when the requesting app is in the background.
        val component = ServerLogic.flattenComponent(
            AzmAdmin.MANAGER_PACKAGE,
            "io.github.alnajashia.azmadmin.manager.RequestPermissionActivity",
        )
        val result = ShellRunner.run(
            listOf(
                "am", "start",
                "-n", component,
                "--es", "packageName", pkg,
            ),
        )
        if (result.exitCode != 0) {
            Log.w(TAG, "could not open approval screen for $pkg: exit ${result.exitCode}: ${result.error}")
        }
    }

    // ---- privileged operations ----------------------------------------------

    override fun provisionDeviceOwner(
        targetPackage: String,
        adminReceiverClassName: String,
    ): AzmAdminResult {
        val callerPackage = try {
            requireAuthorized()
        } catch (_: SecurityException) {
            return notAuthorizedResult()
        }
        if (targetPackage != callerPackage) {
            return AzmAdminResult.fail(
                "TARGET_MISMATCH",
                "A client may only provision its own package",
                "caller=$callerPackage target=$targetPackage",
            )
        }
        val userId = callerUserId()
        val adminComponent = ComponentName(targetPackage, adminReceiverClassName)

        currentOwnerPackage()?.let { owner ->
            if (owner == targetPackage) {
                return AzmAdminResult.ok("ALREADY_OWNER", "Target package is already Device Owner")
            }
            return AzmAdminResult.fail(
                "OTHER_OWNER",
                "Another package is already Device Owner",
                "owner=$owner",
            )
        }

        var bypassed = false
        var pathUsed = "binderDpm"
        var decisiveExitCode = -1
        val detail = StringBuilder()
        try {
            // user_setup_complete bypass (Azm commands #1/#2): provisioning a
            // device owner is refused on an already-set-up device unless
            // this reads 0 during the attempt; it is restored in finally.
            val current = settingsGet(userId, "secure", "user_setup_complete")
            if (current == "1") {
                val put = settingsPut(userId, "secure", "user_setup_complete", "0")
                decisiveExitCode = put.exitCode
                if (put.exitCode != 0) {
                    return AzmAdminResult.fail(
                        "SHELL_FAILED",
                        "Could not bypass user_setup_complete",
                        put.error,
                        put.exitCode,
                    )
                }
                bypassed = true
                refreshUserSetupComplete(userId)
            }

            var success = false
            val dpm = HiddenApi.devicePolicyManager()
            if (dpm != null) {
                HiddenApi.dpmSetActiveAdmin(dpm, adminComponent, true, userId)
                success = HiddenApi.dpmSetDeviceOwner(dpm, adminComponent, null, userId)
            } else {
                detail.append("IDevicePolicyManager unavailable; ")
            }

            if (!success && currentOwnerPackage() != targetPackage) {
                pathUsed = "shellFallback"
                val flattened = ServerLogic.flattenComponent(targetPackage, adminReceiverClassName)
                val result = ShellRunner.runShell("dpm set-device-owner $flattened")
                decisiveExitCode = result.exitCode
                detail.append(result.output)
                if (result.error.isNotBlank()) detail.append("\n").append(result.error)
            }
        } finally {
            if (bypassed) {
                val restore = settingsPut(userId, "secure", "user_setup_complete", "1")
                if (restore.exitCode != 0) {
                    Log.e(TAG, "restore user_setup_complete failed: exit ${restore.exitCode}: ${restore.error}")
                    detail.append("\nWARNING: user_setup_complete restore failed (exit ${restore.exitCode})")
                }
                refreshUserSetupComplete(userId)
            }
        }

        // Post-verification: success is claimed only from a fresh owner read.
        if (currentOwnerPackage() != targetPackage) {
            return AzmAdminResult.fail(
                "PROVISIONING_FAILED",
                "Device Owner provisioning did not take effect",
                detail.toString().trim(),
                decisiveExitCode,
            )
        }
        // As Azm's original flow did, also grant WRITE_SECURE_SETTINGS;
        // its failure is reported in the detail, not treated as un-provisioning.
        val grant = grantWriteSecureSettingsInternal(targetPackage, userId)
        if (!grant.success) {
            detail.append("\nWRITE_SECURE_SETTINGS grant failed: ${grant.message}")
        }
        return AzmAdminResult.ok(
            "PROVISIONED",
            "Device Owner provisioned and verified",
            "path=$pathUsed ${detail.toString().trim()}",
            decisiveExitCode,
        )
    }

    override fun grantWriteSecureSettings(targetPackage: String): AzmAdminResult {
        val callerPackage = try {
            requireAuthorized()
        } catch (_: SecurityException) {
            return notAuthorizedResult()
        }
        if (targetPackage != callerPackage) {
            return AzmAdminResult.fail(
                "TARGET_MISMATCH",
                "A client may only grant WRITE_SECURE_SETTINGS to its own package",
                "caller=$callerPackage target=$targetPackage",
            )
        }
        return grantWriteSecureSettingsInternal(targetPackage, callerUserId())
    }

    private fun grantWriteSecureSettingsInternal(targetPackage: String, userId: Int): AzmAdminResult {
        val permission = "android.permission.WRITE_SECURE_SETTINGS"
        if (hasPermission(targetPackage, permission, userId)) {
            return AzmAdminResult.ok("ALREADY_GRANTED", "WRITE_SECURE_SETTINGS already granted")
        }
        val result = ShellRunner.run(listOf("pm", "grant", targetPackage, permission))
        if (result.exitCode != 0) {
            return AzmAdminResult.fail(
                "SHELL_FAILED",
                "pm grant failed",
                result.error.ifBlank { result.output },
                result.exitCode,
            )
        }
        return if (hasPermission(targetPackage, permission, userId)) {
            AzmAdminResult.ok("GRANTED", "WRITE_SECURE_SETTINGS granted and verified", "", result.exitCode)
        } else {
            AzmAdminResult.fail(
                "GRANT_NOT_VERIFIED",
                "pm grant reported success but the permission does not read back as granted",
                result.output,
                result.exitCode,
            )
        }
    }

    private fun hasPermission(packageName: String, permission: String, userId: Int): Boolean {
        val pm = HiddenApi.packageManager() ?: return false
        return HiddenApi.checkPermission(pm, permission, packageName, userId) == 0
    }

    override fun setAccessibilityEnabled(
        targetPackage: String,
        serviceClassName: String,
        enabled: Boolean,
    ): AzmAdminResult {
        val callerPackage = try {
            requireAuthorized()
        } catch (_: SecurityException) {
            return notAuthorizedResult()
        }
        if (targetPackage != callerPackage) {
            return AzmAdminResult.fail(
                "TARGET_MISMATCH",
                "A client may only toggle its own accessibility service",
                "caller=$callerPackage target=$targetPackage",
            )
        }
        val userId = callerUserId()
        val component = ServerLogic.flattenComponent(targetPackage, serviceClassName)

        val currentRead = settingsGetResult(userId, "secure", "enabled_accessibility_services")
        if (currentRead.exitCode != 0) {
            return AzmAdminResult.fail(
                "SHELL_FAILED",
                "Could not read enabled_accessibility_services",
                currentRead.error,
                currentRead.exitCode,
            )
        }
        val newValue = ServerLogic.updatedAccessibilityList(currentRead.output, component, enabled)
        val putValue = newValue ?: "'null'"
        val put = ShellRunner.run(
            listOf(
                "settings", "--user", "$userId", "put", "secure",
                "enabled_accessibility_services", putValue,
            ),
        )
        if (put.exitCode != 0) {
            return AzmAdminResult.fail(
                "SHELL_FAILED",
                "Could not write enabled_accessibility_services",
                put.error,
                put.exitCode,
            )
        }
        val flagPut = settingsPut(userId, "secure", "accessibility_enabled", "1")
        if (flagPut.exitCode != 0) {
            return AzmAdminResult.fail(
                "SHELL_FAILED",
                "Could not set accessibility_enabled",
                flagPut.error,
                flagPut.exitCode,
            )
        }
        // Verified by strict re-read: if the read itself fails, the toggle
        // is reported failed, never assumed (Azm audit A-M6 discipline).
        val verifyRead = settingsGetResult(userId, "secure", "enabled_accessibility_services")
        if (verifyRead.exitCode != 0) {
            return AzmAdminResult.fail(
                "VERIFY_FAILED",
                "Could not verify the accessibility toggle state",
                verifyRead.error,
                verifyRead.exitCode,
            )
        }
        val verified = ServerLogic.accessibilityListContains(verifyRead.output, component)
        if (verified != enabled) {
            return AzmAdminResult.fail(
                "TOGGLE_NOT_VERIFIED",
                "Accessibility toggle did not take effect",
                "expected enabled=$enabled, read back $verified",
                put.exitCode,
            )
        }
        return AzmAdminResult.ok(
            if (enabled) "ACCESSIBILITY_ENABLED" else "ACCESSIBILITY_DISABLED",
            "Accessibility service state verified",
            "",
            put.exitCode,
        )
    }

    override fun getDiagnosticsJson(): String {
        if (authorizedCallerPackage() == null && !isManagerCaller()) {
            throw SecurityException("Azm Admin: caller is not authorized")
        }
        val accountsResult = ShellRunner.runShell("dumpsys account")
        val usersResult = ShellRunner.run(listOf("pm", "list", "users"))
        val ownersResult = ShellRunner.runShell("dpm list-owners")

        val accounts = JSONArray()
        ServerLogic.parseAccounts(accountsResult.output).forEach { (name, type) ->
            accounts.put(JSONObject().put("name", name).put("type", type))
        }
        val extraUsers = JSONArray()
        ServerLogic.parseExtraUsers(usersResult.output).forEach { (id, name) ->
            extraUsers.put(JSONObject().put("id", id).put("name", name))
        }
        val owner = ServerLogic.parseCurrentOwner(ownersResult.output)
        val currentOwner = if (owner == null) {
            JSONObject.NULL
        } else {
            JSONObject().put("packageName", owner.first).put("receiver", owner.second)
        }
        return JSONObject()
            .put("accounts", accounts)
            .put("extraUsers", extraUsers)
            .put("currentOwner", currentOwner)
            .toString()
    }

    // ---- manager-only ---------------------------------------------------------

    override fun getPendingRequestsJson(): String {
        requireManager()
        val array = JSONArray()
        pending.values.sortedBy { it.requestedAtMillis }.forEach { request ->
            array.put(
                JSONObject()
                    .put("packageName", request.packageName)
                    .put("uid", request.uid)
                    .put("requestedAtMillis", request.requestedAtMillis),
            )
        }
        return array.toString()
    }

    override fun getAuthorizedClientsJson(): String {
        requireManager()
        val array = JSONArray()
        authStore.all().sortedBy { it.grantedAtMillis }.forEach { grant ->
            array.put(
                JSONObject()
                    .put("packageName", grant.packageName)
                    .put("uid", grant.uid)
                    .put("certSha256", grant.certSha256)
                    .put("grantedAtMillis", grant.grantedAtMillis),
            )
        }
        return array.toString()
    }

    override fun approveClient(packageName: String): AzmAdminResult {
        try {
            requireManager()
        } catch (_: SecurityException) {
            return AzmAdminResult.fail("NOT_MANAGER", "Only the Azm Admin manager app can approve clients")
        }
        val pm = HiddenApi.packageManager()
            ?: return AzmAdminResult.fail("NO_PACKAGE_MANAGER", "IPackageManager unavailable")
        val userId = callerUserId()
        val uid = HiddenApi.getPackageUid(pm, packageName, userId)
        if (uid <= 0) {
            return AzmAdminResult.fail("PACKAGE_NOT_FOUND", "Package is not installed: $packageName")
        }
        val cert = HiddenApi.getSigningCertSha256(pm, packageName, userId)
            ?: return AzmAdminResult.fail("NO_SIGNING_CERT", "Could not read the signing certificate of $packageName")
        authStore.recordGrant(
            AuthStore.Grant(packageName, uid, cert, System.currentTimeMillis()),
        )
        pending.remove(packageName)
        return AzmAdminResult.ok("APPROVED", "Client approved and recorded", "package=$packageName uid=$uid")
    }

    override fun revokeClient(packageName: String): AzmAdminResult {
        try {
            requireManager()
        } catch (_: SecurityException) {
            return AzmAdminResult.fail("NOT_MANAGER", "Only the Azm Admin manager app can revoke clients")
        }
        val removed = authStore.revoke(packageName)
        return if (removed) {
            AzmAdminResult.ok("REVOKED", "Authorization revoked for $packageName")
        } else {
            AzmAdminResult.fail("NOT_FOUND", "No recorded grant for $packageName")
        }
    }

    override fun exitServer() {
        requireManager()
        Thread {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            System.exit(0)
        }.apply { isDaemon = false }.start()
    }

    // ---- shared helpers ----------------------------------------------------------

    private fun settingsGetResult(userId: Int, namespace: String, key: String): ShellRunner.Result =
        ShellRunner.run(listOf("settings", "--user", "$userId", "get", namespace, key))

    private fun settingsGet(userId: Int, namespace: String, key: String): String? {
        val result = settingsGetResult(userId, namespace, key)
        return if (result.exitCode == 0) result.output.trim() else null
    }

    private fun settingsPut(userId: Int, namespace: String, key: String, value: String): ShellRunner.Result =
        ShellRunner.run(listOf("settings", "--user", "$userId", "put", namespace, key, value))

    private fun refreshUserSetupComplete(userId: Int) {
        val dpm = HiddenApi.devicePolicyManager() ?: return
        HiddenApi.dpmForceUpdateUserSetupComplete(dpm, userId)
    }

    private fun currentOwnerPackage(): String? {
        val result = ShellRunner.runShell("dpm list-owners")
        if (result.exitCode != 0) return null
        return ServerLogic.parseCurrentOwner(result.output)?.first
    }

    companion object {
        private const val TAG = "AzmAdminService"
    }
}
