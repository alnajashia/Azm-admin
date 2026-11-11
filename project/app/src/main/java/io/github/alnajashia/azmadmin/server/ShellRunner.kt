package io.github.alnajashia.azmadmin.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs commands inside the privileged server process (shell UID under ADB
 * start, root under root start). The discipline mirrors Azm's ShizukuRunner
 * fixes: the exit code is always captured and is the caller's success
 * criterion, stdout and stderr are drained concurrently (no pipe deadlock),
 * and a timeout destroys the process instead of hanging the binder thread.
 */
object ShellRunner {

    private const val TAG = "AzmAdminShell"
    const val TIMEOUT_EXIT_CODE = -2
    const val SPAWN_FAILED_EXIT_CODE = -3

    data class Result(val exitCode: Int, val output: String, val error: String)

    private val streamExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "azmadmin-stream").apply { isDaemon = true }
    }

    fun run(argv: List<String>, timeoutMs: Long = 30_000L): Result {
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start: ${argv.joinToString(" ")}", e)
            return Result(SPAWN_FAILED_EXIT_CODE, "", e.message ?: "spawn failed")
        }
        val stdoutFuture = streamExecutor.submit(Callable {
            readFully(process.inputStream)
        })
        val stderrFuture = streamExecutor.submit(Callable {
            readFully(process.errorStream)
        })
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            try {
                process.destroy()
            } catch (_: Exception) {
                // Already gone.
            }
            return Result(
                TIMEOUT_EXIT_CODE,
                awaitStream(stdoutFuture),
                "timed out after ${timeoutMs}ms: ${argv.joinToString(" ")}",
            )
        }
        return Result(process.exitValue(), awaitStream(stdoutFuture), awaitStream(stderrFuture))
    }

    fun runShell(command: String, timeoutMs: Long = 30_000L): Result =
        run(listOf("sh", "-c", command), timeoutMs)

    private fun readFully(stream: java.io.InputStream): String =
        try {
            BufferedReader(InputStreamReader(stream)).use { it.readText() }.trim()
        } catch (e: Exception) {
            Log.w(TAG, "stream read failed", e)
            ""
        }

    private fun awaitStream(future: java.util.concurrent.Future<String>): String =
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            ""
        } catch (_: Exception) {
            ""
        }
}
