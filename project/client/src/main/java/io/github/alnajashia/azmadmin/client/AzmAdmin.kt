package io.github.alnajashia.azmadmin.client

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import io.github.alnajashia.azmadmin.api.AzmAdminResult
import io.github.alnajashia.azmadmin.api.BinderContainer
import io.github.alnajashia.azmadmin.api.IAzmAdminService

/**
 * Client entry point for Azm Admin, the Strategy-B replacement for the
 * Shizuku API surface Azm used (ping / version / uid / permission / typed
 * provisioning). The server binder reaches the client two ways, both ending
 * in [attachBinder]:
 *
 *  1. The privileged server pushes it into this app's own
 *     [AzmAdminProvider] (authority "<applicationId>.azmadmin") on startup.
 *  2. The client pulls it from the manager app's provider
 *     (authority [MANAGER_PROVIDER_AUTHORITY]) via [requestBinder] — the
 *     manager redistributes the binder it received from the server. Holding
 *     the binder is not itself a privilege: the server authorizes every
 *     privileged call from the calling UID/package/signature record.
 */
object AzmAdmin {

    const val MANAGER_PACKAGE = "io.github.alnajashia.azmadmin"
    const val MANAGER_PROVIDER_AUTHORITY = "io.github.alnajashia.azmadmin.binder"
    const val API_VERSION = 1

    const val METHOD_SEND_BINDER = "sendBinder"
    const val METHOD_GET_BINDER = "getBinder"
    const val EXTRA_BINDER = "io.github.alnajashia.azmadmin.extra.BINDER"

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var service: IAzmAdminService? = null

    private val binderDeathRecipient = IBinder.DeathRecipient {
        synchronized(this@AzmAdmin) {
            binder = null
            service = null
        }
        binderDeadListeners.forEach { it() }
    }

    private val binderReceivedListeners = mutableListOf<() -> Unit>()
    private val binderDeadListeners = mutableListOf<() -> Unit>()

    fun addBinderReceivedListener(listener: () -> Unit) {
        synchronized(this) { binderReceivedListeners.add(listener) }
        if (pingBinder()) listener()
    }

    fun addBinderDeadListener(listener: () -> Unit) {
        synchronized(this) { binderDeadListeners.add(listener) }
    }

    fun removeBinderReceivedListener(listener: () -> Unit) {
        synchronized(this) { binderReceivedListeners.remove(listener) }
    }

    fun removeBinderDeadListener(listener: () -> Unit) {
        synchronized(this) { binderDeadListeners.remove(listener) }
    }

    /** Called by [AzmAdminProvider] when the server pushes its binder. */
    fun attachBinder(newBinder: IBinder) {
        if (!newBinder.pingBinder()) return
        synchronized(this) {
            if (binder === newBinder && service != null) return
            try {
                binder?.unlinkToDeath(binderDeathRecipient, 0)
            } catch (_: Exception) {
                // Old binder already dead; nothing to unlink.
            }
            binder = newBinder
            service = IAzmAdminService.Stub.asInterface(newBinder)
            try {
                newBinder.linkToDeath(binderDeathRecipient, 0)
            } catch (_: Exception) {
                binder = null
                service = null
                return
            }
        }
        binderReceivedListeners.forEach { it() }
    }

    fun getBinder(): IBinder? = synchronized(this) { binder }

    fun pingBinder(): Boolean {
        val current = synchronized(this) { binder } ?: return false
        return current.pingBinder()
    }

    fun isManagerInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) != null
        } catch (_: Exception) {
            false
        }

    /**
     * Pulls the server binder from the manager app's provider. Returns true
     * when a live binder is held afterwards. Never throws.
     */
    fun requestBinder(context: Context): Boolean {
        if (pingBinder()) return true
        val uri = Uri.parse("content://$MANAGER_PROVIDER_AUTHORITY")
        val reply: Bundle? = try {
            context.contentResolver.call(uri, METHOD_GET_BINDER, null, null)
        } catch (_: Exception) {
            null
        }
        val container = reply?.let { extractBinderContainer(it) }
        val pulled = container?.binder
        if (pulled != null && pulled.pingBinder()) {
            attachBinder(pulled)
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    internal fun extractBinderContainer(bundle: Bundle): BinderContainer? {
        bundle.classLoader = BinderContainer::class.java.classLoader
        return try {
            bundle.getParcelable(EXTRA_BINDER)
        } catch (_: Exception) {
            null
        }
    }

    internal fun binderBundle(binder: IBinder): Bundle =
        Bundle().apply { putParcelable(EXTRA_BINDER, BinderContainer(binder)) }

    /** The typed service, or throws [AzmAdminException] when no live binder is held. */
    fun requireService(context: Context): IAzmAdminService {
        synchronized(this) { service }?.let {
            if (pingBinder()) return it
        }
        if (requestBinder(context)) {
            synchronized(this) { service }?.let { return it }
        }
        throw AzmAdminException("Azm Admin server is not running (no live binder)")
    }

    // ---- Convenience wrappers mirroring the state Azm's gateway reported.

    fun getApiVersion(context: Context): Int = requireService(context).apiVersion

    fun getServerUid(context: Context): Int = requireService(context).serverUid

    fun getServerStateJson(context: Context): String = requireService(context).serverStateJson

    fun isAuthorized(context: Context): Boolean =
        try {
            requireService(context).checkAuthorization() == 1
        } catch (_: Exception) {
            false
        }

    fun requestAuthorization(context: Context) {
        requireService(context).requestAuthorization()
    }

    fun provisionDeviceOwner(
        context: Context,
        targetPackage: String,
        adminReceiverClassName: String,
    ): AzmAdminResult = requireService(context).provisionDeviceOwner(targetPackage, adminReceiverClassName)

    fun grantWriteSecureSettings(context: Context, targetPackage: String): AzmAdminResult =
        requireService(context).grantWriteSecureSettings(targetPackage)

    fun setAccessibilityEnabled(
        context: Context,
        targetPackage: String,
        serviceClassName: String,
        enabled: Boolean,
    ): AzmAdminResult =
        requireService(context).setAccessibilityEnabled(targetPackage, serviceClassName, enabled)

    fun getDiagnosticsJson(context: Context): String = requireService(context).diagnosticsJson
}
