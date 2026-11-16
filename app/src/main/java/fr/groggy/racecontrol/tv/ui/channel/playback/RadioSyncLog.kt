package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The radio sync telemetry, also kept in a small file that survives a reboot of the TV (logcat does
 * not), so what the sync did during a session can still be read afterwards:
 * `Android/data/<package>/files/radio-sync.log` (and `radio-sync.1.log`, the previous part).
 * Sync measurements only.
 */
internal object RadioSyncLog {
    private const val MAX_BYTES = 1_500_000L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private var file: File? = null

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "radio-sync-log").apply { isDaemon = true }
    }

    /** Queued: the file is written on a background thread, never on the main thread. */
    fun append(context: Context?, message: String, throwable: Throwable? = null) {
        val appContext = context?.applicationContext
        val stamped = time.format(Date())
        writer.execute { write(appContext, stamped, message, throwable) }
    }

    @Synchronized
    private fun write(context: Context?, stamped: String, message: String, throwable: Throwable?) {
        try {
            val target = file ?: run {
                val appContext = context?.applicationContext ?: return
                val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                File(dir, "radio-sync.log").also { file = it }
            }
            if (target.length() > MAX_BYTES) {
                val previous = File(target.parentFile, "radio-sync.1.log")
                previous.delete()
                target.renameTo(previous)
            }
            FileWriter(target, true).use { out ->
                out.write(stamped)
                out.write(" ")
                out.write(message)
                if (throwable != null) {
                    out.write(" | ")
                    out.write(throwable.toString())
                }
                out.write("\n")
            }
        } catch (_: Throwable) {
            // A log that cannot be written must never disturb playback
        }
    }
}
