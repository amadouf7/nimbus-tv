package com.nimbus.tv

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/** Mini serveur HTTP : permet d'envoyer une playlist depuis le navigateur d'un téléphone. */
class RemoteServer(private val onAdd: (String) -> Unit) {
    private var ss: ServerSocket? = null
    var port: Int = 0
        private set

    fun start() {
        for (p in 8765..8775) {
            try {
                ss = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
            }
        }
        val s = ss ?: return
        val t = Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                } catch (e: Exception) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        try {
            ss?.close()
        } catch (e: Exception) {
        }
    }

    private fun readLine(inp: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 8000
            val inp = BufferedInputStream(c.getInputStream())
            val first = readLine(inp)
            val hdr = HashMap<String, String>()
            while (true) {
                val l = readLine(inp)
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) hdr[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val out = c.getOutputStream()
            if (first.startsWith("POST")) {
                val n = (hdr["content-length"] ?: "0").toInt().coerceIn(0, 20000)
                val body = ByteArray(n)
                var r = 0
                while (r < n) {
                    val k = inp.read(body, r, n - r)
                    if (k < 0) break
                    r += k
                }
                val js = JSONObject()
                for (kv in String(body, 0, r, Charsets.UTF_8).split("&")) {
                    val i = kv.indexOf('=')
                    if (i > 0) js.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"))
                }
                onAdd(js.toString())
                respond(out, "<meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:24px'><h2>✓ Envoyé à la TV</h2><p>Vous pouvez fermer cette page.</p>")
            } else {
                respond(out, FORM)
            }
        } catch (e: Exception) {
        } finally {
            try {
                c.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun respond(out: OutputStream, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
        out.flush()
    }

    companion object {
        private const val FORM = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nimbus TV</title>
<body style="font-family:system-ui,sans-serif;background:#0b0d14;color:#f4f5fb;padding:20px;max-width:560px;margin:auto">
<h2>Ajouter une playlist à la TV</h2>
<form method="post" action="/add">
<p><label>Type<br><select name="type" id="t" style="width:100%;padding:12px;font-size:16px"><option value="m3u">Lien M3U</option><option value="xtream">Compte Xtream</option></select></label></p>
<p><label>Nom (facultatif)<br><input name="name" style="width:100%;padding:12px;font-size:16px"></label></p>
<div id="a"><p><label>Lien M3U<br><input name="url" type="url" style="width:100%;padding:12px;font-size:16px"></label></p></div>
<div id="b" style="display:none">
<p><label>Serveur (http://hote:port)<br><input name="server" style="width:100%;padding:12px;font-size:16px"></label></p>
<p><label>Identifiant<br><input name="user" style="width:100%;padding:12px;font-size:16px"></label></p>
<p><label>Mot de passe<br><input name="pass" style="width:100%;padding:12px;font-size:16px"></label></p></div>
<p><label>URL du guide XMLTV (facultatif)<br><input name="epg" type="url" style="width:100%;padding:12px;font-size:16px"></label></p>
<button style="width:100%;padding:14px;font-size:18px;background:#7c8cff;color:#fff;border:0;border-radius:10px">Envoyer à la TV</button>
</form>
<script>document.getElementById('t').onchange=function(){var x=this.value=='xtream';document.getElementById('a').style.display=x?'none':'';document.getElementById('b').style.display=x?'':'none'}</script>"""
    }
}
