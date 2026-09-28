package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

/**
 * Works like "Hey Google":
 *  IDLE   -> only waits for "Hey Jarvis" (everything else is ignored)
 *  ACTIVE -> beep + on-screen bubble, takes ONE command, runs it, then goes back to IDLE
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {
    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var proc: CommandProcessor
    private lateinit var nm: NotificationManager
    private var tone: ToneGenerator? = null
    private var overlay: TextView? = null
    private var speaking = false
    private var active = false
    private var running = true
    private var server: PhoneServer? = null
    private val wake = Regex("\\b(hey|hi|hay|okay|ok)\\s+(jarvis|jervis|garvis|jarvish|jarvas)\\b")
    private val timeout = Runnable { goIdle(0) }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("Idle - say \"Hey Jarvis\""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) { null }
        proc = CommandProcessor(this) { say(it) }
        tts = TextToSpeech(this, this)
        server = PhoneServer(8765, { PhoneServer.getPin(this) }) { q -> remote(q) }
        server?.start()
    }

    /** Command coming from the laptop: run it silently on the phone and return the reply text. */
    private fun remote(q: String): String {
        var reply = "Done"
        val latch = java.util.concurrent.CountDownLatch(1)
        h.post {
            try {
                showOverlay("💻  $q")
                h.postDelayed({ if (!active) hideOverlay() }, 1800)
                CommandProcessor(this) { reply = it }.handle(q.lowercase().trim())
            } finally { latch.countDown() }
        }
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        return reply
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, JarvisService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
    }

    override fun onInit(status: Int) {
        tts.language = Locale.getDefault()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { done() }
            override fun onDone(id: String?) { done() }
            private fun done() = h.post { speaking = false; if (running) h.postDelayed({ listen() }, 300) }
        })
        say("Jarvis online.")
    }

    private fun say(text: String) = h.post {
        speaking = true
        rec?.cancel()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    // ---------- states ----------
    private fun goActive() {
        active = true
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        showOverlay("🎙  Jarvis is listening…")
        nm.notify(1, notif("Listening for your command…"))
        h.removeCallbacks(timeout); h.postDelayed(timeout, 8000)   // give up after 8s of silence
    }

    private fun goIdle(hideAfterMs: Long) {
        active = false
        h.removeCallbacks(timeout)
        nm.notify(1, notif("Idle - say \"Hey Jarvis\""))
        if (hideAfterMs > 0) h.postDelayed({ if (!active) hideOverlay() }, hideAfterMs) else hideOverlay()
    }

    private fun handleText(t: String) {
        if (t.isBlank()) return
        if (active) { runCommand(t); return }                     // ACTIVE: this is the command
        val m = wake.find(t) ?: return                            // IDLE: ignore anything without "Hey Jarvis"
        val cmd = t.substring(m.range.last + 1).trim()
        goActive()
        if (cmd.isEmpty()) say("Yes?") else runCommand(cmd)
    }

    private fun runCommand(cmd: String) {
        h.removeCallbacks(timeout)
        showOverlay("✓  $cmd")
        proc.handle(cmd)
        goIdle(1800)                                              // back to sleep until next "Hey Jarvis"
    }

    // ---------- floating bubble ----------
    private fun showOverlay(text: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay == null) {
            val tv = TextView(this).apply {
                textSize = 18f; setTextColor(0xFF3DD9FF.toInt()); gravity = Gravity.CENTER
                setPadding(56, 36, 56, 36)
                background = GradientDrawable().apply { setColor(0xEE05080F.toInt()); cornerRadius = 80f; setStroke(3, 0xFF3DD9FF.toInt()) }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 220 }
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp); overlay = tv } catch (_: Exception) {}
        }
        overlay?.text = text
    }

    private fun hideOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    // ---------- speech recognition loop ----------
    private fun listen() {
        if (!running || speaking) return
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    handleText(t)
                    if (!speaking) h.postDelayed({ listen() }, 200)
                }
                override fun onError(error: Int) { if (!speaking) h.postDelayed({ listen() }, 600) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") {
            getSharedPreferences("jarvis", 0).edit().putBoolean("enabled", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        h.postDelayed({ listen() }, 1500)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; server?.stop(); h.removeCallbacksAndMessages(null); hideOverlay()
        rec?.destroy(); tone?.release(); tts.shutdown(); super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
