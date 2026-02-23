package com.scanner.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class AppScannerService : AccessibilityService() {

    companion object {
        private const val TAG = "AppScanner"
        const val CHANNEL_ID = "app_scanner_channel"
        private const val POLL_INTERVAL_MS = 3000L
        private var notifId = 2000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var knownPackages = mutableSetOf<String>()
    private var polling = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service.onCreate")
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                checkForNewPackages()
            } catch (e: Exception) {
                Log.e(TAG, "Poll error", e)
            }
            if (polling) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private var pollCount = 0

    private fun checkForNewPackages() {
        pollCount++
        val current = getCurrentPackageNames()
        val newPackages = current - knownPackages
        val removedPackages = knownPackages - current

        // Log every 10th poll to confirm polling is alive, plus always on changes
        if (pollCount % 10 == 1 || newPackages.isNotEmpty() || removedPackages.isNotEmpty()) {
            Log.d(TAG, "Poll #$pollCount: current=${current.size} known=${knownPackages.size} new=${newPackages.size} removed=${removedPackages.size}")
        }

        if (newPackages.isNotEmpty()) {
            Log.d(TAG, "Poll: new packages: $newPackages")
            for (pkg in newPackages) {
                if (pkg == packageName) continue
                val appName = getAppLabel(pkg)
                Log.d(TAG, "Poll: NEW INSTALL -> appName=$appName pkg=$pkg")
                handleAppInstalled(appName, pkg)
            }
        }

        knownPackages = current.toMutableSet()
    }

    private fun getCurrentPackageNames(): Set<String> {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ApplicationInfoFlags.of(0)
            } else {
                null
            }
            val apps: List<ApplicationInfo> = if (flags != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(flags)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
            apps.map { it.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentPackageNames failed", e)
            knownPackages
        }
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun handleAppInstalled(appName: String, packageName: String) {
        Log.d(TAG, "handleAppInstalled: appName=$appName pkg=$packageName")
        showNotification(appName, packageName)
        InstallEventSource.tryEmit(appName, packageName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service.onServiceConnected")
        createNotificationChannel()
        startPolling()
    }

    private fun startPolling() {
        if (polling) {
            Log.d(TAG, "startPolling: already polling, skip")
            return
        }
        knownPackages = getCurrentPackageNames().toMutableSet()
        Log.d(TAG, "startPolling: baseline snapshot has ${knownPackages.size} packages")
        polling = true
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        Log.d(TAG, "startPolling: polling every ${POLL_INTERVAL_MS}ms")
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "stopPolling: stopped")
    }

    private fun showNotification(appName: String, packageName: String) {
        Log.d(TAG, "showNotification: $appName ($packageName)")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New App Installed")
            .setContentText("$appName was just installed")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("App: $appName\nPackage: $packageName")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId++, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Scanner Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service.onDestroy")
        stopPolling()
    }
}
