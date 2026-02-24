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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class AppScannerService : AccessibilityService() {

    companion object {
        private const val TAG = "AppScanner"
        const val CHANNEL_ID = "app_scanner_channel"
        private const val POLL_INTERVAL_MS = 3000L
        private var notifId = 2000
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var knownPackages = mutableSetOf<String>()
    private var polling = false
    private var currentOverlayView: View? = null

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

    private fun isFromPlayStore(packageName: String): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
            val isPlayStore = installer == PLAY_STORE_PACKAGE
            Log.d(TAG, "isFromPlayStore: pkg=$packageName installer=$installer -> $isPlayStore")
            isPlayStore
        } catch (e: Exception) {
            Log.e(TAG, "isFromPlayStore failed for $packageName", e)
            false
        }
    }

    private fun handleAppInstalled(appName: String, packageName: String) {
        Log.d(TAG, "handleAppInstalled: appName=$appName pkg=$packageName")
        showNotification(appName, packageName)
        InstallEventSource.tryEmit(appName, packageName)
        if (!isFromPlayStore(packageName)) {
            showHarmfulAppWarning(appName, packageName)
        }
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

    private fun showHarmfulAppWarning(appName: String, packageName: String) {
        handler.post {
            try {
                removeCurrentOverlay()
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val inflater = LayoutInflater.from(this)
                val root = inflater.inflate(R.layout.harmful_app_warning, null)
                root.findViewById<TextView>(R.id.app_name).text = appName
                root.findViewById<TextView>(R.id.package_name).text = packageName
                root.findViewById<Button>(R.id.ok_button).setOnClickListener {
                    removeCurrentOverlay()
                }
                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = android.graphics.PixelFormat.TRANSLUCENT
                }
                wm.addView(root, params)
                currentOverlayView = root
                Log.d(TAG, "showHarmfulAppWarning: overlay shown for $appName ($packageName)")
            } catch (e: Exception) {
                Log.e(TAG, "showHarmfulAppWarning failed", e)
            }
        }
    }

    private fun removeCurrentOverlay() {
        currentOverlayView?.let { view ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "removeCurrentOverlay failed", e)
            }
            currentOverlayView = null
        }
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
        removeCurrentOverlay()
    }
}
