package io.github.alnajashia.azmadmin.server

import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflective access to the hidden framework services the privileged server
 * needs (IPackageManager, IDevicePolicyManager, IContentManager,
 * ServiceManager). The server runs as shell/root through app_process, where
 * these are reachable — the same mechanism Shizuku's server uses — while
 * the code still compiles against the public SDK. Every lookup is attempted
 * across the signature variants seen on Android 11–16 and fails soft with a
 * log line instead of crashing the server.
 */
object HiddenApi {

    private const val TAG = "AzmAdminHiddenApi"

    private fun unwrap(e: Throwable): Throwable =
        if (e is InvocationTargetException) e.targetException ?: e else e

    private fun findMethod(target: Any, name: String, predicate: (Method) -> Boolean): Method? {
        val classes = mutableListOf<Class<*>>()
        var type: Class<*>? = target.javaClass
        while (type != null) {
            classes.add(type)
            type = type.superclass
        }
        classes.addAll(target.javaClass.interfaces)
        target.javaClass.interfaces.forEach { iface ->
            var superIface: Class<*>? = iface
            while (superIface != null) {
                classes.add(superIface)
                superIface = superIface.interfaces.firstOrNull()
            }
        }
        for (clazz in classes) {
            val found = clazz.declaredMethods.firstOrNull { it.name == name && predicate(it) }
            if (found != null) {
                found.isAccessible = true
                return found
            }
        }
        // Public inherited/interface methods not covered above.
        return target.javaClass.methods.firstOrNull { it.name == name && predicate(it) }?.apply {
            isAccessible = true
        }
    }

    private fun invoke(target: Any, name: String, predicate: (Method) -> Boolean, vararg args: Any?): Any? {
        val method = findMethod(target, name, predicate)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name matching variant not found")
        return try {
            method.invoke(target, *args)
        } catch (e: Throwable) {
            throw unwrap(e)
        }
    }

    // ---- ServiceManager -------------------------------------------------

    fun getServiceBinder(name: String): IBinder? =
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val method = serviceManager.getMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (e: Throwable) {
            Log.w(TAG, "ServiceManager.getService($name) failed", e)
            null
        }

    fun addService(name: String, binder: IBinder): Boolean =
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val method = serviceManager.getMethod("addService", String::class.java, IBinder::class.java)
            method.invoke(null, name, binder)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "ServiceManager.addService($name) failed (expected on many builds)", e)
            false
        }

    private fun asInterface(stubClassName: String, binder: IBinder): Any? =
        try {
            val stub = Class.forName(stubClassName)
            val method = stub.getMethod("asInterface", IBinder::class.java)
            method.invoke(null, binder)
        } catch (e: Throwable) {
            Log.w(TAG, "asInterface($stubClassName) failed", e)
            null
        }

    // ---- IPackageManager --------------------------------------------------

    fun packageManager(): Any? {
        val binder = getServiceBinder("package") ?: return null
        return asInterface("android.content.pm.IPackageManager\$Stub", binder)
    }

    fun getPackagesForUid(pm: Any, uid: Int): Array<String> =
        try {
            @Suppress("UNCHECKED_CAST")
            (invoke(pm, "getPackagesForUid", { it.parameterCount == 1 }, uid) as? Array<String>)
                ?: emptyArray()
        } catch (e: Throwable) {
            Log.w(TAG, "getPackagesForUid($uid) failed", e)
            emptyArray()
        }

    fun getPackageInfo(pm: Any, packageName: String, userId: Int): PackageInfo? {
        // GET_SIGNING_CERTIFICATES = 0x08000000. Flags are int before
        // Android 14's long-flags AIDL variant and long after; try both.
        val flags = 0x08000000
        val attempts: List<Pair<(Method) -> Boolean, Array<Any?>>> = listOf(
            Pair(
                { m: Method -> m.parameterCount == 3 && m.parameterTypes[1] == java.lang.Long.TYPE },
                arrayOf(packageName, flags.toLong(), userId),
            ),
            Pair(
                { m: Method -> m.parameterCount == 3 && m.parameterTypes[1] == Integer.TYPE },
                arrayOf(packageName, flags, userId),
            ),
        )
        for ((predicate, args) in attempts) {
            try {
                return invoke(pm, "getPackageInfo", predicate, *args) as? PackageInfo
            } catch (_: Throwable) {
                // Try the next signature variant.
            }
        }
        Log.w(TAG, "getPackageInfo($packageName) failed on all signature variants")
        return null
    }

    /** Current signing-certificate SHA-256 (hex) of an installed package, or null. */
    fun getSigningCertSha256(pm: Any, packageName: String, userId: Int): String? {
        val info = getPackageInfo(pm, packageName, userId) ?: return null
        val signatures: Array<Signature>? = try {
            info.signingInfo?.apkContentsSigners
        } catch (_: Throwable) {
            null
        } ?: @Suppress("DEPRECATION") info.signatures
        val first = signatures?.firstOrNull() ?: return null
        return ServerLogic.sha256Hex(first.toByteArray())
    }

    fun getPackageUid(pm: Any, packageName: String, userId: Int): Int =
        getPackageInfo(pm, packageName, userId)?.applicationInfo?.uid ?: -1

    /** PackageManager.PERMISSION_GRANTED = 0 when granted, -1 otherwise / on failure. */
    fun checkPermission(pm: Any, permission: String, packageName: String, userId: Int): Int =
        try {
            (invoke(
                pm,
                "checkPermission",
                { it.parameterCount == 3 },
                permission,
                packageName,
                userId,
            ) as? Int) ?: -1
        } catch (e: Throwable) {
            Log.w(TAG, "checkPermission($permission, $packageName) failed", e)
            -1
        }

    // ---- IDevicePolicyManager ----------------------------------------------

    fun devicePolicyManager(): Any? {
        val binder = getServiceBinder("device_policy") ?: return null
        return asInterface("android.app.admin.IDevicePolicyManager\$Stub", binder)
    }

    fun dpmSetActiveAdmin(dpm: Any, admin: ComponentName, refreshing: Boolean, userId: Int): Boolean =
        try {
            val threeArg = findMethod(dpm, "setActiveAdmin") {
                it.parameterCount == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java
            }
            if (threeArg != null) {
                threeArg.invoke(dpm, admin, refreshing, userId)
                true
            } else {
                val twoArg = findMethod(dpm, "setActiveAdmin") { it.parameterCount == 2 }
                if (twoArg != null) {
                    twoArg.invoke(dpm, admin, refreshing)
                    true
                } else {
                    false
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "setActiveAdmin failed", unwrap(e))
            false
        }

    fun dpmSetDeviceOwner(dpm: Any, admin: ComponentName, ownerName: String?, userId: Int): Boolean {
        return try {
            val twoArg = findMethod(dpm, "setDeviceOwner") {
                it.parameterCount == 2 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.returnType == java.lang.Boolean.TYPE
            }
            if (twoArg != null) {
                return (twoArg.invoke(dpm, admin, userId) as? Boolean) ?: false
            }
            val threeArg = findMethod(dpm, "setDeviceOwner") {
                it.parameterCount == 3 &&
                    it.parameterTypes[0] == ComponentName::class.java &&
                    it.returnType == java.lang.Boolean.TYPE
            }
            if (threeArg != null) {
                return (threeArg.invoke(dpm, admin, ownerName, userId) as? Boolean) ?: false
            }
            false
        } catch (e: Throwable) {
            Log.w(TAG, "setDeviceOwner failed", unwrap(e))
            false
        }
    }

    fun dpmForceUpdateUserSetupComplete(dpm: Any, userId: Int) {
        try {
            val withUser = findMethod(dpm, "forceUpdateUserSetupComplete") { it.parameterCount == 1 }
            if (withUser != null) {
                withUser.invoke(dpm, userId)
                return
            }
            val noArg = findMethod(dpm, "forceUpdateUserSetupComplete") { it.parameterCount == 0 }
            noArg?.invoke(dpm)
        } catch (e: Throwable) {
            Log.w(TAG, "forceUpdateUserSetupComplete failed (may be unsupported)", unwrap(e))
        }
    }
}
