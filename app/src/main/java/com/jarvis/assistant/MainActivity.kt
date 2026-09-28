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
    private lateinit var bridge: TextView

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
        bridge = text("", 14f, 0xFF3DD9FF.toInt()); col.addView(bridge)
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
            "\"Hey Jarvis, open WhatsApp\"\n\"Hey Jarvis, call Mom\"\n\"Hey Jarvis, flashlight on\"\n" +
            "\"Hey Jarvis, volume up\"\n\"Hey Jarvis, go home / go back\"\n\"Hey Jarvis, scroll down\"\n" +
            "\"Hey Jarvis, take a screenshot\"\n\"Hey Jarvis, lock the phone\"\n\"Hey Jarvis, set alarm for 7:30 am\"\n" +
            "\"Hey Jarvis, set timer for 5 minutes\"\n\"Hey Jarvis, navigate to Marina Beach\"\n" +
            "\"Hey Jarvis, play Believer on YouTube\"\n\"Hey Jarvis, search for weather\"\n\"Hey Jarvis, search cats on YouTube\"\n\"Hey Jarvis, search shoes on Amazon\"\n\"Hey Jarvis, find pizza on maps\"\n" +
            "\"Hey Jarvis, next song / pause\"\n\"Hey Jarvis, battery level\"", 14f, 0xFF7F8FA3.toInt()))
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF05080F.toInt()); addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun ip(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "not on Wi-Fi"
    } catch (e: Exception) { "unknown" }

    private fun refresh() {
        bridge.text = "💻 Laptop bridge\nPhone IP: ${ip()}   PIN: ${PhoneServer.getPin(this)}"
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val a11y = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
        val overlay = Settings.canDrawOverlays(this)
        val on = getSharedPreferences("jarvis", 0).getBoolean("enabled", false)
        fun m(x: Boolean) = if (x) "✅" else "❌"
        status.text = "${m(mic)} Permissions   ${m(a11y)} Accessibility   ${m(overlay)} Overlay\n" +
            if (on) "● Jarvis is LISTENING" else "○ Jarvis is stopped"
    }
}
