package de.hamzabistro.printstation

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/**
 * The last crash, kept in a file: an app that closes itself says nothing on
 * the tablet, and nobody there reads logcat. The next start shows it, to
 * copy and send, before anything that could crash again.
 *
 * Plain files and no Android beyond the context, so it works from the
 * moment the process starts.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    /** Longest trace kept: enough for any cause chain, short enough to paste. */
    private const val MAX_CHARS = 32_000

    /**
     * Saves every uncaught throwable before handing it on to the handler
     * that was there — Android's, which closes the app as before.
     */
    fun install(context: Context) {
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(file, thread.name, error) }
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    /** The saved crash, or null when the app ended well last time. */
    fun pending(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    /** "Weiter": seen, so not shown again. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    internal fun record(file: File, thread: String, error: Throwable, at: Instant = Instant.now()) {
        val text =
            buildString {
                append("Hamza Team ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                append("Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MODEL).append('\n')
                append(at).append(", thread ").append(thread).append("\n\n")
                append(error.stackTraceToString())
            }
        file.writeText(text.take(MAX_CHARS))
    }

    internal fun file(context: Context) = File(context.noBackupFilesDir, FILE)
}
