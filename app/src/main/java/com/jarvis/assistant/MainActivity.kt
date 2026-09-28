package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(0xFF05080F.toInt())
        }
        fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = t; textSize = sp; setTextColor(color); gravity = Gravity.CENTER_HORIZONTAL
            if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }; isAllCaps = false
        }
        col.addView(text("J.A.R.V.I.S", 34f, 0xFF3DD9FF.toInt(), true))
        status = text("", 15f, 0xFFB8C7D9.toInt()); col.addView(status)
        col.addView(button("1. Allow microphone, phone, contacts") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS), 1) })
        col.addView(button("2. Enable Jarvis Accessibility (control phone)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(button("3. Allow display over other apps (open apps)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        col.addView(button("START JARVIS") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                getSharedPreferences("jarvis", 0).edit().putBoolean("enabled", true).apply()
                startForegroundService(Intent(this, JarvisService::class.java)); refresh()
            } })
        col.addView(button("STOP JARVIS") {
            getSharedPreferences("jarvis", 0).edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, JarvisService::class.java)); refresh() })
        col.addView(text(
            "Try saying:\n\n" +
            "\"Jarvis, open WhatsApp\"\n\"Jarvis, call Mom\"\n\"Jarvis, flashlight on\"\n" +
            "\"Jarvis, volume up\"\n\"Jarvis, go home / go back\"\n\"Jarvis, scroll down\"\n" +
            "\"Jarvis, take a screenshot\"\n\"Jarvis, lock the phone\"\n\"Jarvis, set alarm for 7:30 am\"\n" +
            "\"Jarvis, set timer for 5 minutes\"\n\"Jarvis, navigate to Marina Beach\"\n" +
            "\"Jarvis, play Believer on YouTube\"\n\"Jarvis, search for weather\"\n" +
            "\"Jarvis, next song / pause\"\n\"Jarvis, battery level\"", 14f, 0xFF7F8FA3.toInt()))
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF05080F.toInt()); addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val a11y = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
        val overlay = Settings.canDrawOverlays(this)
        val on = getSharedPreferences("jarvis", 0).getBoolean("enabled", false)
        fun m(x: Boolean) = if (x) "✅" else "❌"
        status.text = "${m(mic)} Permissions   ${m(a11y)} Accessibility   ${m(overlay)} Overlay\n" +
            if (on) "● Jarvis is LISTENING" else "○ Jarvis is stopped"
    }
}
