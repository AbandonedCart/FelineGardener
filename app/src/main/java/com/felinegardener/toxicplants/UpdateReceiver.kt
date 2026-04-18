package com.felinegardener.toxicplants

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            if (context.isAppRunning) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        } else {
            when (
                intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
            ) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_INTENT,
                        Intent::class.java
                    )?.let { userIntent ->
                        context.startActivity(
                            userIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } ?: context.toast("Installation was rejected.")
                }
                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    context.toast("Installation was blocked by the system.")
                }
                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    context.toast("Not enough storage to install the update.")
                }
                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    context.toast("Installation conflict — please uninstall and retry.")
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    context.toast("Installation was aborted.")
                }
                PackageInstaller.STATUS_SUCCESS -> { }
                else -> {
                    val error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    if (error?.contains("Session was abandoned") != true) {
                        error?.let { context.toast(it) }
                    }
                }
            }
        }
    }

    private fun Context.toast(message: String) {
        MainScope().launch {
            Toast.makeText(this@toast, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val Context.isAppRunning: Boolean
        get() = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses
            .any { packageName == it.processName }
}
