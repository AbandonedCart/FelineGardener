package com.felinegardener.toxicplants

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.IntentSanitizer

class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            if (context.isAppRunning) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java
                )?.let { userIntent ->
                    // Sanitize the intent before launching it to satisfy security requirements.
                    // We allow all standard components because this intent is provided by the
                    // system PackageInstaller to initiate the user-facing install dialog.
                    val sanitizedIntent = IntentSanitizer.Builder()
                        .allowAnyAction()
                        .allowAnyCategory()
                        .allowAnyData()
                        .allowAnyExtras()
                        .allowAnyFlags()
                        .build()
                        .sanitizeByFiltering(userIntent)

                    context.startActivity(sanitizedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } ?: context.toast("Installation was rejected.")
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> context.toast("Installation was blocked by the system.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> context.toast("Not enough storage to install the update.")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> context.toast("Installation conflict — please uninstall and retry.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> context.toast("Installation was aborted.")
            PackageInstaller.STATUS_SUCCESS -> { /* Update successful */ }
            else -> {
                val error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (error?.contains("Session was abandoned") != true) {
                    error?.let { context.toast(it) }
                }
            }
        }
    }

    private fun Context.toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val Context.isAppRunning: Boolean
        get() = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses
            ?.any { it.processName == packageName } == true
}
