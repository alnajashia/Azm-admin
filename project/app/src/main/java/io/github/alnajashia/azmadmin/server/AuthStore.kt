package io.github.alnajashia.azmadmin.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The server's authorization record — the heart of Strategy B's
 * trust-on-first-approval design. When the user approves a client in the
 * manager UI, the server itself (never the caller) resolves and stores the
 * triple (package, UID, signing-certificate SHA-256). Every later privileged
 * call must match all three again. Denial records nothing: a denied request
 * is one-time, exactly like Shizuku's model.
 *
 * The file lives in the directory passed to the server at startup
 * (default /data/local/tmp/azmadmin/grants.json, writable by the shell/root
 * identity the server runs as, not by ordinary apps).
 */
class AuthStore(private val file: File) {

    data class Grant(
        val packageName: String,
        val uid: Int,
        val certSha256: String,
        val grantedAtMillis: Long,
    )

    private val lock = Any()
    private val grants = mutableMapOf<String, Grant>()

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            grants.clear()
            if (!file.exists()) return
            try {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("grants") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val grant = Grant(
                        packageName = item.getString("packageName"),
                        uid = item.getInt("uid"),
                        certSha256 = item.getString("certSha256"),
                        grantedAtMillis = item.optLong("grantedAtMillis", 0L),
                    )
                    grants[grant.packageName] = grant
                }
            } catch (e: Exception) {
                // A corrupt record must fail closed, never open: start empty.
                Log.w(TAG, "grant store unreadable, starting empty (fail closed)", e)
                grants.clear()
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            grants.values.forEach { grant ->
                array.put(
                    JSONObject()
                        .put("packageName", grant.packageName)
                        .put("uid", grant.uid)
                        .put("certSha256", grant.certSha256)
                        .put("grantedAtMillis", grant.grantedAtMillis),
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("grants", array).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(JSONObject().put("grants", array).toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not persist grant store", e)
        }
    }

    fun recordGrant(grant: Grant) {
        synchronized(lock) {
            grants[grant.packageName] = grant
            persistLocked()
        }
    }

    fun revoke(packageName: String): Boolean =
        synchronized(lock) {
            val removed = grants.remove(packageName) != null
            if (removed) persistLocked()
            removed
        }

    fun find(packageName: String): Grant? = synchronized(lock) { grants[packageName] }

    fun all(): List<Grant> = synchronized(lock) { grants.values.toList() }

    companion object {
        private const val TAG = "AzmAdminAuthStore"
    }
}
