package com.axie.remote.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives the PackageInstaller commit status for self-updates.
 *
 * IMPORTANT: with the default `requireUserAction=true`, the first callback is
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] carrying the system's install
 * confirmation dialog in [Intent.EXTRA_INTENT] — it MUST be started
 * (with FLAG_ACTIVITY_NEW_TASK, since we are a receiver). Skipping it made
 * every in-app update stall at "installing" forever, pushing users to manual
 * APK installs (which can look like a brand-new installation).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.STATUS_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status detail=$detail")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        Intent.EXTRA_INTENT,
                        Intent::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                    Toast.makeText(context, "Confirm the update in the dialog", Toast.LENGTH_LONG).show()
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION without EXTRA_INTENT — cannot confirm")
                    Toast.makeText(context, "Update needs confirmation but no dialog arrived", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Update installed — open Axie Remote.", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(context, "Install cancelled.", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(
                    context,
                    "Install failed${if (detail != null) ": $detail" else "."}",
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    companion object {
        private const val TAG = "AxieRemote"
    }
}
