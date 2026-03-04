package com.scanner.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.collectLatest

private const val TAG = "AppScanner"

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — either way proceed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity.onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppShell()
            }
        }
    }
}

fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val expected = "${context.packageName}/${AppScannerService::class.java.name}"
    val isEnabled = enabled.contains(expected)
    Log.d(TAG, "isAccessibilityEnabled: $isEnabled")
    return isEnabled
}

@Composable
fun AppShell() {
    val bg = Color(0xFF0D0F14)
    val accent = Color(0xFF00E5A0)
    val guardianBlue = Color(0xFF448AFF)
    val cardBg = Color(0xFF161A22)
    val textSecondary = Color(0xFF7A8394)

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = bg,
        bottomBar = {
            NavigationBar(
                containerColor = cardBg,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Scanner"
                        )
                    },
                    label = { Text("Scanner", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = accent,
                        selectedTextColor = accent,
                        unselectedIconColor = textSecondary,
                        unselectedTextColor = textSecondary,
                        indicatorColor = Color(0xFF1E2530)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Guardian"
                        )
                    },
                    label = { Text("Guardian", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = guardianBlue,
                        selectedTextColor = guardianBlue,
                        unselectedIconColor = textSecondary,
                        unselectedTextColor = textSecondary,
                        indicatorColor = Color(0xFF1E2530)
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> ScannerScreen()
                1 -> GuardianScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    var isActive by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val recentInstalls = remember { mutableStateListOf<InstallEvent>() }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val prev = isActive
                isActive = isAccessibilityEnabled(context)
                Log.d(TAG, "ON_RESUME: isActive=$isActive (was $prev)")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "UI: collecting install events")
        InstallEventSource.installEvents.collectLatest { event ->
            Log.d(TAG, "UI: received event appName=${event.appName}")
            if (recentInstalls.none { it.packageName == event.packageName && it.timestamp == event.timestamp }) {
                recentInstalls.add(0, event)
            }
            snackbarHostState.showSnackbar(
                message = "${event.appName} was just installed",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        }
    }

    val bg = Color(0xFF0D0F14)
    val accent = Color(0xFF00E5A0)
    val cardBg = Color(0xFF161A22)
    val textSecondary = Color(0xFF7A8394)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(48.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "APP SCANNER",
                        color = accent,
                        fontSize = 11.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(if (isActive) accent.copy(alpha = 0.15f) else cardBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(if (isActive) accent.copy(alpha = 0.3f) else Color(0xFF2A3040)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) accent else Color(0xFF3A4050))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isActive) "Scanner Active" else "Scanner Inactive",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isActive)
                            "Monitoring all app installs.\nYou can close this app — it still works."
                        else
                            "Enable the accessibility service\nto start monitoring installs.",
                        color = textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            if (recentInstalls.isNotEmpty()) {
                item {
                    Text(
                        text = "RECENTLY INSTALLED",
                        color = accent,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                items(recentInstalls, key = { "${it.packageName}_${it.timestamp}" }) { event ->
                    val dotColor = when (event.riskLevel) {
                        "SAFE" -> accent
                        "LOW" -> Color(0xFF448AFF)
                        "MEDIUM" -> Color(0xFFFFB300)
                        "HIGH" -> Color(0xFFFF6D00)
                        "CRITICAL" -> Color(0xFFFF1744)
                        else -> textSecondary
                    }
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardBg)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.appName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = event.packageName,
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                                if (event.primaryReason != null) {
                                    Text(
                                        text = event.primaryReason,
                                        color = textSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (event.riskLevel != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = event.riskLevel,
                                    color = dotColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            if (!isActive) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "HOW TO ENABLE",
                            color = accent,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(
                            "Tap the button below",
                            "Find AppScanner in the list",
                            "Toggle it ON",
                            "Close this app — scanner keeps running",
                            "Install any app to trigger a notification"
                        ).forEachIndexed { i, step ->
                            Text(
                                text = "${i + 1}.  $step",
                                color = textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) cardBg else accent,
                        contentColor = if (isActive) accent else Color(0xFF0D0F14)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isActive) "Open Accessibility Settings" else "Enable Scanner",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = accent,
                contentColor = bg,
                dismissActionContentColor = bg,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
