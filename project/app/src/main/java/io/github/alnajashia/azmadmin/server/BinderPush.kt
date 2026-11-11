package io.github.alnajashia.azmadmin.server

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.github.alnajashia.azmadmin.api.BinderContainer
import io.github.alnajashia.azmadmin.client.AzmAdmin
import java.lang.reflect.Method

/**
 * Delivers the server binder into an app through its ContentProvider — the
 * discovery mechanism Shizuku's BinderSender uses, reimplemented for Azm
 * Admin's own provider authorities. The server asks IContentManager for the
 * target provider, then calls its "sendBinder" method with a
 * [BinderContainer]. All hidden-API access is reflective (see [HiddenApi]).
 */
object BinderPush {

    private const val TAG = "AzmAdminBinderPush"

    fun push(serverBinder: IBinder, authority: String, userId: Int): Boolean {
        var contentManager: Any? = null
        return try {
            val cmBinder = HiddenApi.getServiceBinder("content") ?: return false
            contentManager = asContentManager(cmBinder) ?: return false
            val holder = invokeGetProvider(contentManager, authority, userId) ?: return false
            val provider = extractProvider(holder) ?: return false
            val extras = Bundle().apply {
                putParcelable(AzmAdmin.EXTRA_BINDER, BinderContainer(serverBinder))
            }
            val reply = invokeProviderCall(provider, authority, extras)
            Log.i(TAG, "push to $authority (user $userId): reply=${reply != null}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "push to $authority failed", e)
            false
        } finally {
            contentManager?.let { releaseProvider(it, authority) }
        }
    }

    private fun asContentManager(binder: IBinder): Any? {
        for (stubName in listOf(
            "android.content.IContentManager\$Stub",
            "android.app.IContentManager\$Stub",
        )) {
            try {
                val stub = Class.forName(stubName)
                val method = stub.getMethod("asInterface", IBinder::class.java)
                return method.invoke(null, binder)
            } catch (_: Throwable) {
                // Try the next candidate.
            }
        }
        Log.w(TAG, "IContentManager stub not found")
        return null
    }

    private fun invokeGetProvider(contentManager: Any, authority: String, userId: Int): Any? {
        val candidates = contentManager.javaClass.methods.filter {
            it.name == "getContentProviderExternal"
        }
        for (method in candidates) {
            try {
                method.isAccessible = true
                val args: Array<Any?> = when (method.parameterCount) {
                    4 -> arrayOf(authority, userId, null, "azmadmin-server")
                    3 -> arrayOf(authority, userId, null)
                    else -> continue
                }
                val holder = method.invoke(contentManager, *args)
                if (holder != null) return holder
            } catch (_: Throwable) {
                // Try the next overload.
            }
        }
        Log.w(TAG, "getContentProviderExternal($authority) returned no holder")
        return null
    }

    private fun extractProvider(holder: Any): Any? =
        try {
            val field = holder.javaClass.getField("provider")
            field.get(holder)
        } catch (e: Throwable) {
            Log.w(TAG, "provider holder has no usable provider field", e)
            null
        }

    private fun buildAttributionSource(): Any? =
        try {
            val builderClass = Class.forName("android.content.AttributionSource\$Builder")
            val constructor = builderClass.getConstructor(Int::class.javaPrimitiveType)
            val builder = constructor.newInstance(android.os.Process.myUid())
            builderClass.getMethod("setPackageName", String::class.java)
                .invoke(builder, "com.android.shell")
            builderClass.getMethod("build").invoke(builder)
        } catch (e: Throwable) {
            Log.w(TAG, "AttributionSource build failed", e)
            null
        }

    private fun invokeProviderCall(provider: Any, authority: String, extras: Bundle): Bundle? {
        val callMethods = mutableListOf<Method>()
        var type: Class<*>? = provider.javaClass
        while (type != null) {
            callMethods.addAll(type.declaredMethods.filter { it.name == "call" })
            type = type.superclass
        }
        callMethods.addAll(provider.javaClass.methods.filter { it.name == "call" })
        for (method in callMethods.distinct()) {
            if (method.parameterCount != 5) continue
            try {
                method.isAccessible = true
                val params = method.parameterTypes
                val args = arrayOfNulls<Any?>(5)
                var stringQueue: List<String?> = listOf(authority, AzmAdmin.METHOD_SEND_BINDER, null)
                if (params[0] == String::class.java) {
                    stringQueue = listOf("com.android.shell", authority, AzmAdmin.METHOD_SEND_BINDER, null)
                }
                var stringIndex = 0
                for (i in params.indices) {
                    args[i] = when {
                        params[i] == Bundle::class.java -> extras
                        params[i] == String::class.java -> stringQueue.getOrNull(stringIndex++)
                        params[i].name == "android.content.AttributionSource" -> buildAttributionSource()
                        else -> null
                    }
                }
                return method.invoke(provider, *args) as? Bundle
            } catch (_: Throwable) {
                // Try the next overload.
            }
        }
        Log.w(TAG, "no compatible IContentProvider.call overload for $authority")
        return null
    }

    private fun releaseProvider(contentManager: Any, authority: String) {
        try {
            val method = contentManager.javaClass.methods.firstOrNull {
                it.name == "removeContentProviderExternal"
            } ?: return
            method.isAccessible = true
            when (method.parameterCount) {
                2 -> method.invoke(contentManager, authority, null)
                1 -> method.invoke(contentManager, authority)
            }
        } catch (_: Throwable) {
            // Releasing is best-effort.
        }
    }
}
