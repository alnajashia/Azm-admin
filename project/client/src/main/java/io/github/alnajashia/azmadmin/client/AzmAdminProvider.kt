package io.github.alnajashia.azmadmin.client

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Receives the privileged server's binder (server push, method
 * [AzmAdmin.METHOD_SEND_BINDER]) and serves it to other processes of the
 * same app (method [AzmAdmin.METHOD_GET_BINDER]).
 *
 * Each consuming app declares this provider itself with authority
 * "${applicationId}.azmadmin". The Azm Admin manager app declares its own
 * subclass with authority [AzmAdmin.MANAGER_PROVIDER_AUTHORITY]; because
 * that instance redistributes the binder to client apps that pull it, the
 * manager's provider answers getBinder with the same static holder.
 *
 * Possession of the binder grants nothing by itself: the server verifies
 * (calling UID, package, signing certificate) on every privileged call.
 */
open class AzmAdminProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            AzmAdmin.METHOD_SEND_BINDER -> {
                val container = extras?.let { AzmAdmin.extractBinderContainer(it) }
                val binder = container?.binder
                if (binder != null && binder.pingBinder()) {
                    AzmAdmin.attachBinder(binder)
                    Bundle.EMPTY
                } else {
                    null
                }
            }
            AzmAdmin.METHOD_GET_BINDER -> {
                val binder = AzmAdmin.getBinder()
                if (binder != null && binder.pingBinder()) {
                    AzmAdmin.binderBundle(binder)
                } else {
                    null
                }
            }
            else -> null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
