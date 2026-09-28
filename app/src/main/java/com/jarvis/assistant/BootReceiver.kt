package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts Jarvis after reboot if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val on = ctx.getSharedPreferences("jarvis", 0).getBoolean("enabled", false)
        if (on) try { ctx.startForegroundService(Intent(ctx, JarvisService::class.java)) } catch (_: Exception) {}
    }
}
