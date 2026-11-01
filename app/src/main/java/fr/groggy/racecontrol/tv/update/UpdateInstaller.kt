package fr.groggy.racecontrol.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import fr.groggy.racecontrol.tv.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a release APK into the cache dir and hands it to the system PackageInstaller. */
object UpdateInstaller {
    private val TAG = UpdateInstaller::class.simpleName
    internal const val ACTION_INSTALL_STATUS = "fr.groggy.racecontrol.tv.update.INSTALL_STATUS"

    /** True from the moment the install session is committed until the system reports a result. */
    @Volatile var installing = false

    /** Blocking; call off the main thread. [isCancelled] is polled while downloading; [onProgress] gets 0..100, or -1 when the size is unknown. */
    fun download(
        context: Context,
        release: ReleaseInfo,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")
        val part = File(dir, "update.apk.part")

        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "f1tv-player-updater")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            var lastPercent = -2
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("incomplete download ($done of $total bytes)")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        if (!part.renameTo(target)) throw IOException("could not store the downloaded file")
        return target
    }

    /** Blocking; call off the main thread. The result arrives at [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Best effort: only honoured when this app is the installer of record for itself
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                // The installer fills in status extras, so the PendingIntent must be mutable on API 31+
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                installing = true
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installing = false
            try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            throw e
        }
    }

    internal fun logStatus(status: Int, message: String?) {
        Log.d(TAG, "install status=$status message=$message")
    }
}

/** Receives the PackageInstaller result and launches the system confirmation screen when needed. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        UpdateInstaller.logStatus(status, message)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    UpdateInstaller.installing = false
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    UpdateInstaller.installing = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_install_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.installing = false
            else -> {
                UpdateInstaller.installing = false
                Toast.makeText(
                    context,
                    context.getString(R.string.update_install_failed, message ?: status.toString()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
