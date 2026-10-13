package com.callguard.app.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

data class WindowTime(val wallMs: Long, val elapsedMs: Long, val bootCount: Int?)
fun interface WindowClock { fun now(): WindowTime }
class AndroidWindowClock(context: Context) : WindowClock {
    private val app = context.applicationContext
    override fun now(): WindowTime {
        val boot = try { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 } }
            catch (_: Exception) { null }
        return WindowTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot)
    }
}
