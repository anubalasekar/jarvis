package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Always-on listener: says "Jarvis <command>" or "Jarvis" ... "<command>". */
class JarvisService : Service(), TextToSpeech.OnInitListener {
    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var proc: CommandProcessor
    private var speaking = false
    private var awaiting = false
    private var running = true
    private val wakeWords = listOf("jarvis", "jervis", "garvis", "jarvish", "jarvas")

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, JarvisService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        proc = CommandProcessor(this) { say(it) }
        tts = TextToSpeech(this, this)
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

    private fun handleText(t: String) {
        if (t.isBlank()) return
        if (awaiting) { awaiting = false; proc.handle(t); return }
        val w = wakeWords.firstOrNull { t.contains(it) } ?: return   // ignore everything without the wake word
        val cmd = t.substringAfter(w).trim()
        if (cmd.isEmpty()) { awaiting = true; h.postDelayed({ awaiting = false }, 8000); say("Yes?") }
        else proc.handle(cmd)
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
        running = false; h.removeCallbacksAndMessages(null)
        rec?.destroy(); tts.shutdown(); super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
