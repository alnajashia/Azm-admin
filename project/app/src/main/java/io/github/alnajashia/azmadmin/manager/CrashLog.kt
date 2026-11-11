package io.github.alnajashia.azmadmin.manager

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Records the stack trace of an uncaught crash to a private file so the
 * next launch can show it to the user (and, through him, to us) instead
 * of dying silently behind Android's generic "keeps stopping" dialog.
 * The default handler still runs afterwards, so system behaviour —
 * including the crash dialog itself — is unchanged.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(Log.getStackTraceString(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the recorded crash (if any) and clears it, so it shows once. */
    fun readAndClear(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}
