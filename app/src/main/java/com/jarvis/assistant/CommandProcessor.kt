package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns spoken text into phone actions. Add your own commands in handle(). */
class CommandProcessor(private val app: Context, private val say: (String) -> Unit) {

    // Prefer accessibility context: it is allowed to launch apps from the background.
    private val ctx: Context get() = JarvisAccessibility.instance ?: app
    private fun go(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun handle(raw: String) {
        val c = raw.lowercase(Locale.getDefault()).replace("please", "").trim()
        try {
            when {
                c.isEmpty() -> say("Yes?")
                Regex("^(open|launch|start) ").containsMatchIn(c) -> openApp(c.substringAfter(" ").trim())
                c.startsWith("call ") || c.startsWith("phone ") -> call(c.substringAfter(" ").trim())
                Regex("^(search|find|look up|google) ").containsMatchIn(c) ||
                    Regex("^play .+ (on|in) (youtube|spotify)$").containsMatchIn(c) -> smartSearch(c)
                c.contains("flashlight") || c.contains("torch") -> torch(!c.contains("off"))
                c.contains("volume up") || c.contains("louder") -> vol(AudioManager.ADJUST_RAISE, "Volume up")
                c.contains("volume down") || c.contains("quieter") -> vol(AudioManager.ADJUST_LOWER, "Volume down")
                c.contains("mute") -> vol(AudioManager.ADJUST_MUTE, "Muted")
                c.contains("unmute") -> vol(AudioManager.ADJUST_UNMUTE, "Unmuted")
                c.contains("go home") || c == "home" -> a11y(AccessibilityService.GLOBAL_ACTION_HOME, "Going home")
                c.contains("go back") || c == "back" -> a11y(AccessibilityService.GLOBAL_ACTION_BACK, "Going back")
                c.contains("recent") -> a11y(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
                c.contains("notification") -> a11y(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
                c.contains("quick settings") -> a11y(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
                c.contains("lock") -> a11y(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking")
                c.contains("screenshot") -> a11y(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken")
                c.contains("scroll down") -> say(if (JarvisAccessibility.swipe(true)) "Scrolling down" else needA11y())
                c.contains("scroll up") -> say(if (JarvisAccessibility.swipe(false)) "Scrolling up" else needA11y())
                c.contains("timer") -> timer(c)
                c.contains("alarm") -> alarm(c)
                c.contains("next song") || c.contains("next track") -> media(KeyEvent.KEYCODE_MEDIA_NEXT, "Next")
                c.contains("previous song") || c.contains("previous track") -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous")
                c.contains("pause") || c.contains("stop music") -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
                c.contains("play music") || c == "play" || c.contains("resume") -> media(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing")
                c.contains("wifi") || c.contains("wi-fi") -> settings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
                c.contains("bluetooth") -> settings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
                c.contains("settings") -> settings(Settings.ACTION_SETTINGS, "Opening settings")
                c.contains("camera") || c.contains("take a photo") || c.contains("selfie") -> {
                    go(Intent(MediaStore.ACTION_IMAGE_CAPTURE)); say("Opening camera") }
                c.startsWith("navigate to ") || c.startsWith("directions to ") -> {
                    val place = c.substringAfter(" to ")
                    go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps"))
                    say("Navigating to $place") }
                c.contains("time") -> say("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
                c.contains("date") || c.contains("today") -> say(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()))
                c.contains("battery") -> battery()
                c.contains("hello") || c.contains("how are you") -> say("At your service, sir.")
                c.contains("thank") -> say("Always a pleasure.")
                else -> say("I didn't catch that command.")
            }
        } catch (e: Exception) { say("Sorry, that failed.") }
    }

    private fun needA11y() = "Please enable Jarvis in Accessibility settings first."
    private fun a11y(action: Int, ok: String) = say(if (JarvisAccessibility.global(action)) ok else needA11y())
    private fun settings(a: String, msg: String) { go(Intent(a)); say(msg) }
    private fun vol(dir: Int, msg: String) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI); say(msg)
    }
    private fun media(key: Int, msg: String) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)); say(msg)
    }

    /** "search cats on youtube", "search amazon for shoes", "google weather", "play believer on spotify" ... */
    private fun smartSearch(c: String) {
        val known = "youtube|google maps|maps|amazon|flipkart|spotify|play store|wikipedia|google|chrome|twitter"
        val a = Regex("^(?:search|find|look up|google|play)(?: for)? (.+?) (?:on|in|at) ($known)$").find(c)
        val b = Regex("^(?:search|find|look up) ($known) for (.+)$").find(c)
        val q: String
        val t: String
        when {
            a != null -> { q = a.groupValues[1]; t = a.groupValues[2] }
            b != null -> { t = b.groupValues[1]; q = b.groupValues[2] }
            else -> { q = c.replace(Regex("^(search|google|look up|find)( for)? "), ""); t = "google" }
        }
        val e = Uri.encode(q)
        fun view(uri: String, pkg: String? = null) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                if (pkg != null) i.setPackage(pkg)
                go(i)
            } catch (ex: Exception) { go(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
        }
        when (t) {
            "youtube" -> try {
                go(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
            } catch (ex: Exception) { view("https://www.youtube.com/results?search_query=$e") }
            "spotify" -> view("spotify:search:$e")
            "maps", "google maps" -> view("geo:0,0?q=$e")
            "amazon" -> view("https://www.amazon.in/s?k=$e")
            "flipkart" -> view("https://www.flipkart.com/search?q=$e")
            "play store" -> view("market://search?q=$e&c=apps")
            "wikipedia" -> view("https://en.wikipedia.org/wiki/Special:Search?search=$e")
            "twitter" -> view("https://twitter.com/search?q=$e")
            "chrome" -> view("https://www.google.com/search?q=$e", "com.android.chrome")
            else -> go(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q))
        }
        say("Searching $q on $t")
    }

    private fun openApp(name: String) {
        val pm = app.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun label(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val m = list.firstOrNull { label(it) == name } ?: list.firstOrNull { label(it).contains(name) }
            ?: list.firstOrNull { name.contains(label(it)) && label(it).length > 2 }
        val launch = m?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) { go(launch); say("Opening ${m.loadLabel(pm)}") } else say("I couldn't find $name.")
    }

    private fun call(target: String) {
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            say("I need phone permission first."); return }
        val digits = target.replace(" ", "")
        val number = if (digits.isNotEmpty() && digits.all { it.isDigit() || it == '+' }) digits else lookup(target)
        if (number == null) { say("I couldn't find $target in your contacts."); return }
        go(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))); say("Calling $target")
    }

    private fun lookup(name: String): String? {
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        app.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun torch(on: Boolean) {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) { say("No flashlight found."); return }
        cm.setTorchMode(id, on); say(if (on) "Flashlight on" else "Flashlight off")
    }

    private fun timer(c: String) {
        val n = Regex("(\\d+)").find(c)?.value?.toInt()
        if (n == null) { say("For how long?"); return }
        val secs = when { c.contains("hour") -> n * 3600; c.contains("second") -> n; else -> n * 60 }
        go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Timer set")
    }

    private fun alarm(c: String) {
        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(c.substringAfter("alarm"))
        if (m == null) { say("What time?"); return }
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (ap.startsWith("p") && h < 12) h += 12
        if (ap.startsWith("a") && h == 12) h = 0
        go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Alarm set for %d:%02d".format(h, min))
    }

    private fun battery() {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = (i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / (i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
        say("Battery is at $pct percent")
    }
}
