package com.watchtastic.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.watchtastic.di.AppGraph

/**
 * Receives the outcome of a [PackageInstaller] session.
 *
 * The important branch is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system is
 * saying "ask the wearer first" and handing back an Intent that shows the confirmation.
 * Launching it is what puts the decision in front of the person wearing the watch — this
 * app never installs anything on its own authority.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "InstallStatus"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_INSTALL_STATUS) return
        val updates = AppGraph.from(context).updates

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirm == null) {
                    updates.onInstallFailed("System did not return a confirmation screen")
                    return
                }
                // NEW_TASK is mandatory here, not a style choice: a BroadcastReceiver
                // has no activity context to launch from. Lint's Wear recents advice
                // doesn't apply to a system-owned installer screen.
                @Suppress("WearRecents")
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure {
                        Log.w(TAG, "could not show install confirmation", it)
                        updates.onInstallFailed("Couldn't open the installer")
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> updates.onInstallSucceeded()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed status=$status message=$message")
                updates.onInstallFailed(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
                        PackageInstaller.STATUS_FAILURE_CONFLICT ->
                            "Conflicts with the installed build"

                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "Not compatible with this watch"

                        PackageInstaller.STATUS_FAILURE_INVALID -> "The APK was not valid"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
                        else -> message ?: "Install failed"
                    },
                )
            }
        }
    }
}
