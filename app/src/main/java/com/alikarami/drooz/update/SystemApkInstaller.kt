package com.alikarami.drooz.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import com.alikarami.drooz.DroozApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs through [PackageInstaller]. The system answers [InstallStatusReceiver]: it shows the user's
 * confirmation screen when one is needed, and passes the verdict to [UpdateManager.onInstallResult].
 * Once this app is the installer of record (after its first self-update), Android 12+ can update it
 * without the confirmation screen.
 */
class SystemApkInstaller(private val context: Context) : ApkInstaller {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some TV builds have no per-app screen; the general security settings are the next best place.
        runCatching { context.startActivity(intent) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val status = Intent(context, InstallStatusReceiver::class.java)
                // Mutable: the system adds the status extras to it.
                val pending = PendingIntent.getBroadcast(
                    context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** The system installer's answers for sessions [SystemApkInstaller] committed. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as DroozApp).graph.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    updates.onInstallResult(InstallResult.Failed("no confirmation intent"))
                } else {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { updates.onInstallResult(InstallResult.Failed(it.message)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.onInstallResult(InstallResult.Succeeded)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.onInstallResult(InstallResult.Cancelled)
            else -> updates.onInstallResult(
                InstallResult.Failed("$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"),
            )
        }
    }
}
