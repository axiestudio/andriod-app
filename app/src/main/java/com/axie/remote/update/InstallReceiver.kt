package com.axie.remote.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/** Receives the PackageInstaller commit status for self-updates. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.STATUS_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status detail=$detail")
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Update installed — open Axie Remote."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
            else -> "Install failed${if (detail != null) ": $detail" else "."}"
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "AxieRemote"
    }
}
