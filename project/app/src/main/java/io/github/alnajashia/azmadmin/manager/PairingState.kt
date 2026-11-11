package io.github.alnajashia.azmadmin.manager

import android.content.Context

/**
 * The app's own record of whether THIS phone has accepted our ADB key.
 *
 * Android gives apps no API to enumerate adbd's paired keys, so the
 * record is built from proofs the app itself observes: a successful
 * SPAKE2 pairing, or a successful authenticated connect (which adbd
 * only allows for a key it already trusts). It is cleared the moment a
 * connect fails authentication, which is exactly what happens after
 * the user forgets this app in the wireless-debugging paired list.
 * The UI presents it as what it is — "paired, last verified …" — and
 * always leaves a deliberate re-pair path.
 */
object PairingState {

    private const val PREFS = "pairing_state"
    private const val KEY_VERIFIED_AT = "verified_at"

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_VERIFIED_AT, 0L) > 0L

    fun lastVerifiedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_VERIFIED_AT, 0L)

    fun markPaired(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPaired(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERIFIED_AT)
            .apply()
    }
}
