package com.jarvis.assistant

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Tiny HTTP server so your laptop can send commands to this phone (same Wi-Fi). Protected by a PIN. */
class PhoneServer(private val port: Int, private val pin: () -> String, private val exec: (String) -> String) {
    companion object {
        fun getPin(ctx: Context): String {
            val sp = ctx.getSharedPreferences("jarvis", 0)
            var p = sp.getString("pin", null)
            if (p == null) { p = (100000..999999).random().toString(); sp.edit().putString("pin", p).apply() }
            return p
        }
    }

    @Volatile private var srv: ServerSocket? = null
    private var fails = 0
    private var lockUntil = 0L

    fun start() {
        thread {
            try {
                srv = ServerSocket(port)
                while (true) { val s = srv!!.accept(); thread { handle(s) } }
            } catch (_: Exception) {}
        }
    }

    fun stop() { try { srv?.close() } catch (_: Exception) {} }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            val r = s.getInputStream().bufferedReader()
            val line = r.readLine() ?: return
            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
            val p = line.split(" ")
            val uri = Uri.parse("http://x" + p.getOrElse(1) { "/" })
            var code = 200
            var body = "{}"
            val now = System.currentTimeMillis()
            val pinOk = uri.getQueryParameter("pin") == pin()
            when {
                p[0] == "OPTIONS" -> { code = 204; body = "" }
                now < lockUntil -> { code = 429; body = "{\"error\":\"locked\"}" }
                !pinOk -> {
                    fails++; if (fails >= 8) { lockUntil = now + 60_000; fails = 0 }
                    code = 401; body = "{\"error\":\"wrong pin\"}"
                }
                uri.path == "/ping" -> { fails = 0; body = JSONObject().put("ok", true).put("device", Build.MODEL).toString() }
                uri.path == "/cmd" -> {
                    fails = 0
                    body = JSONObject().put("reply", exec(uri.getQueryParameter("q") ?: "")).toString()
                }
                else -> code = 404
            }
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 $code OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n" +
                "Access-Control-Allow-Private-Network: true\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }
}
