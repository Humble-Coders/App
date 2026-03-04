package com.scanner.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppScannerService : AccessibilityService() {

    companion object {
        private const val TAG = "AppScanner"
        const val CHANNEL_ID = "app_scanner_channel"
        private const val POLL_INTERVAL_MS = 3000L
        private var notifId = 2000
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var knownPackages = mutableSetOf<String>()
    private var polling = false
    private var currentOverlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service.onCreate")
        // Start collecting guardian alerts that arrive via FCM
        serviceScope.launch {
            GuardianAlertSource.alerts.collect { alert ->
                showGuardianAlertOverlay(alert)
            }
        }
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
            installer == PLAY_STORE_PACKAGE
        } catch (e: Exception) {
            Log.e(TAG, "isFromPlayStore failed for $packageName", e)
            false
        }
    }

    // ── Main entry point for each new install ──────────────────────────

    private fun handleAppInstalled(appName: String, packageName: String) {
        Log.d(TAG, "handleAppInstalled: appName=$appName pkg=$packageName")
        showScanningNotification(appName)

        serviceScope.launch {
            val response = withContext(Dispatchers.IO) {
                val cert = AppShieldApi.extractCertificateSha256(this@AppScannerService, packageName)
                val installer = AppShieldApi.extractInstallerPackage(this@AppScannerService, packageName)
                val perms = AppShieldApi.extractPermissions(this@AppScannerService, packageName)
                Log.d(TAG, "Extracted: cert=${cert?.take(16)}…, installer=$installer, perms=${perms.size}")
                AppShieldApi.verifyApp(packageName, cert, installer, perms)
            }

            if (response != null) {
                Log.d(TAG, "AppShield result: ${response.riskLevel} (${response.riskScore})")
                InstallEventSource.tryEmit(
                    appName, packageName,
                    response.riskLevel, response.riskScore, response.primaryReason
                )

                when (response.riskLevel) {
                    "SAFE" -> showResultNotification(appName, packageName, response)
                    else -> {
                        showResultNotification(appName, packageName, response)
                        showWarningOverlay(appName, packageName, response)
                        notifyGuardiansAsync(appName, packageName, response)
                    }
                }
            } else {
                Log.w(TAG, "AppShield API unavailable, falling back to Play Store check")
                InstallEventSource.tryEmit(appName, packageName)
                if (!isFromPlayStore(packageName)) {
                    val fallback = AppShieldResponse(
                        status = "fallback",
                        riskScore = 50,
                        riskLevel = "MEDIUM",
                        primaryReason = "Could not verify — app was not installed from the Play Store",
                        secondaryFlags = listOf("Installed outside Play Store", "AppShield verification unavailable"),
                        matchedEntity = null
                    )
                    showWarningOverlay(appName, packageName, fallback)
                    notifyGuardiansAsync(appName, packageName, fallback)
                }
                showResultNotification(appName, packageName, null)
            }
        }
    }

    // ── Warning overlay (risk-level aware) ─────────────────────────────

    private fun showWarningOverlay(appName: String, packageName: String, response: AppShieldResponse) {
        handler.post {
            try {
                removeCurrentOverlay()
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.harmful_app_warning, null)
                val riskColor = riskColor(response.riskLevel)

                // Badge
                val badge = root.findViewById<TextView>(R.id.risk_level_badge)
                badge.text = response.riskLevel
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12f)
                    setColor(riskColor)
                }

                // Title
                val title = root.findViewById<TextView>(R.id.title)
                title.text = when (response.riskLevel) {
                    "LOW" -> getString(R.string.risk_title_low)
                    "MEDIUM" -> getString(R.string.risk_title_medium)
                    "HIGH" -> getString(R.string.risk_title_high)
                    "CRITICAL" -> getString(R.string.risk_title_critical)
                    else -> getString(R.string.harmful_app_warning_title)
                }
                title.setTextColor(riskColor)

                // Message
                root.findViewById<TextView>(R.id.message).text =
                    response.primaryReason ?: getString(R.string.harmful_app_warning_message)

                // App info
                root.findViewById<TextView>(R.id.app_name).text = appName
                root.findViewById<TextView>(R.id.package_name).text = packageName

                // Risk score
                root.findViewById<TextView>(R.id.risk_score).text =
                    "Risk Score: ${response.riskScore}/100"

                // Secondary flags
                val flagsView = root.findViewById<TextView>(R.id.secondary_flags)
                if (response.secondaryFlags.isNotEmpty()) {
                    flagsView.visibility = View.VISIBLE
                    flagsView.text = response.secondaryFlags.joinToString("\n") { "• $it" }
                }

                // Uninstall button — visible for MEDIUM and above
                val uninstallBtn = root.findViewById<Button>(R.id.uninstall_button)
                if (response.riskLevel in listOf("MEDIUM", "HIGH", "CRITICAL")) {
                    uninstallBtn.visibility = View.VISIBLE
                    uninstallBtn.text = if (response.riskLevel == "CRITICAL")
                        getString(R.string.uninstall_now) else getString(R.string.uninstall_app)
                    uninstallBtn.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        removeCurrentOverlay()
                    }
                }

                // Dismiss button
                val okBtn = root.findViewById<Button>(R.id.ok_button)
                okBtn.text = when (response.riskLevel) {
                    "LOW" -> getString(R.string.harmful_app_warning_ok)
                    "MEDIUM" -> getString(R.string.risk_dismiss_understand)
                    "HIGH", "CRITICAL" -> getString(R.string.risk_dismiss_keep)
                    else -> getString(R.string.harmful_app_warning_ok)
                }
                if (response.riskLevel in listOf("HIGH", "CRITICAL")) {
                    okBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A3040"))
                    okBtn.setTextColor(Color.parseColor("#7A8394"))
                }
                okBtn.setOnClickListener { removeCurrentOverlay() }

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
                Log.d(TAG, "showWarningOverlay: shown for $appName (${response.riskLevel})")
            } catch (e: Exception) {
                Log.e(TAG, "showWarningOverlay failed", e)
            }
        }
    }

    // ── Guardian: notify guardians of this device's user ──────────────

    private fun notifyGuardiansAsync(appName: String, packageName: String, response: AppShieldResponse) {
        val uid = GuardianManager.getCurrentUserId() ?: return
        serviceScope.launch(Dispatchers.IO) {
            GuardianManager.notifyGuardians(
                protectedUserId = uid,
                appName = appName,
                packageName = packageName,
                riskLevel = response.riskLevel,
                riskScore = response.riskScore,
                primaryReason = response.primaryReason
            )
        }
    }

    // ── Guardian: show on-screen overlay when acting as guardian ──────

    private fun showGuardianAlertOverlay(alert: GuardianAlert) {
        handler.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.guardian_alert_overlay, null)

                val riskColor = riskColor(alert.riskLevel)

                // Guardian badge background
                val badge = root.findViewById<TextView>(R.id.guardian_badge)
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12f)
                    setColor(Color.parseColor("#448AFF"))
                }

                // Risk level badge
                val riskBadge = root.findViewById<TextView>(R.id.risk_level_badge)
                riskBadge.text = alert.riskLevel
                riskBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(10f)
                    setColor(riskColor)
                }

                // Title (risk-level aware)
                val title = root.findViewById<TextView>(R.id.title)
                title.text = when (alert.riskLevel) {
                    "LOW" -> getString(R.string.risk_title_low)
                    "MEDIUM" -> getString(R.string.risk_title_medium)
                    "HIGH" -> getString(R.string.risk_title_high)
                    "CRITICAL" -> getString(R.string.risk_title_critical)
                    else -> getString(R.string.guardian_alert_title)
                }
                title.setTextColor(riskColor)

                // App info
                root.findViewById<TextView>(R.id.app_name).text = alert.appName
                root.findViewById<TextView>(R.id.package_name).text = alert.packageName
                root.findViewById<TextView>(R.id.risk_score).text =
                    "Risk Score: ${alert.riskScore}/100"

                // Primary reason
                val reasonView = root.findViewById<TextView>(R.id.primary_reason)
                if (!alert.primaryReason.isNullOrBlank()) {
                    reasonView.visibility = View.VISIBLE
                    reasonView.text = alert.primaryReason
                }

                // Acknowledge button
                val ackBtn = root.findViewById<Button>(R.id.acknowledge_button)
                ackBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#448AFF"))
                ackBtn.setOnClickListener {
                    try {
                        wm.removeView(root)
                    } catch (e: Exception) {
                        Log.e(TAG, "removeView guardian overlay failed", e)
                    }
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
                Log.d(TAG, "showGuardianAlertOverlay: shown for ${alert.appName} (${alert.riskLevel})")
            } catch (e: Exception) {
                Log.e(TAG, "showGuardianAlertOverlay failed", e)
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

    // ── Notifications ──────────────────────────────────────────────────

    private fun showScanningNotification(appName: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            notifId++,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(getString(R.string.scanning_app, appName))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showResultNotification(appName: String, packageName: String, response: AppShieldResponse?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val level = response?.riskLevel ?: "UNKNOWN"
        val title = when (level) {
            "SAFE" -> getString(R.string.app_verified_safe, appName)
            else -> "$appName — $level risk"
        }
        val body = response?.primaryReason
            ?: "Package: $packageName"

        nm.notify(
            notifId++,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(
                    if (level == "SAFE") android.R.drawable.ic_dialog_info
                    else android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$body"))
                .setPriority(
                    if (level in listOf("HIGH", "CRITICAL")) NotificationCompat.PRIORITY_MAX
                    else NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()
        )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

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
        serviceScope.cancel()
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun riskColor(level: String): Int = when (level) {
        "LOW" -> Color.parseColor("#448AFF")
        "MEDIUM" -> Color.parseColor("#FFB300")
        "HIGH" -> Color.parseColor("#FF6D00")
        "CRITICAL" -> Color.parseColor("#FF1744")
        else -> Color.parseColor("#7A8394")
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
