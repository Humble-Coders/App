package com.scanner.app

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class InstallEvent(val appName: String, val packageName: String, val timestamp: Long = System.currentTimeMillis())

object InstallEventSource {

    private const val TAG = "AppScanner"

    private val _installEvents = MutableSharedFlow<InstallEvent>(replay = 20, extraBufferCapacity = 5)
    val installEvents: SharedFlow<InstallEvent> = _installEvents.asSharedFlow()

    fun tryEmit(appName: String, packageName: String) {
        val event = InstallEvent(appName, packageName)
        val emitted = _installEvents.tryEmit(event)
        Log.d(TAG, "InstallEventSource.tryEmit: appName=$appName pkg=$packageName emitted=$emitted")
    }
}
