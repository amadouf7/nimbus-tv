@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nimbus.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.zip.GZIPInputStream

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var pv: PlayerView
    private var player: ExoPlayer? = null
    private val results = ConcurrentHashMap<Int, String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private var server: RemoteServer? = null
    private val ua = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Nimbus/0.1"

    private val ticker = object : Runnable {
        override fun run() {
            player?.let { p ->
                val d = if (p.duration == C.TIME_UNSET) 0L else p.duration
                js("window.__n&&window.__n('pos',${p.currentPosition},$d,${p.isPlaying})")
            }
            ui.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        pv = PlayerView(this)
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.visibility = View.INVISIBLE

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.allowFileAccess = false
        WebView.setWebContentsDebuggingEnabled(true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")

        val lp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(pv, FrameLayout.LayoutParams(lp, lp))
        root.addView(web, FrameLayout.LayoutParams(lp, lp))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back&&window.__back()") { r ->
                    if (r != "true") finish()
                }
            }
        })

        server = RemoteServer { json -> js("window.__n&&window.__n('remote',${JSONObject.quote(json)})") }.also { it.start() }

        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        web.requestFocus()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        player?.release()
        player = null
        server?.stop()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------- utilitaires ----------
    private fun js(code: String) {
        ui.post { web.evaluateJavascript(code, null) }
    }

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val t = FutureTask(Callable { f() })
        ui.post(t)
        return t.get()
    }

    private fun done(id: Int, ok: Boolean, info: String) {
        js("window.__n('done',$id,$ok,$info)")
    }

    private fun fileOf(name: String) = File(filesDir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    private fun open(url: String): InputStream {
        var u = url
        var n = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            val code = c.responseCode
            if (code in 300..399 && n < 6) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirection invalide")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                n++
                continue
            }
            if (code >= 400) throw IOException("HTTP $code")
            val raw = BufferedInputStream(c.inputStream, 65536)
            raw.mark(2)
            val a = raw.read()
            val b = raw.read()
            raw.reset()
            return if (a == 0x1f && b == 0x8b) GZIPInputStream(raw, 65536) else raw
        }
    }

    private fun readText(url: String): String {
        open(url).use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0
            while (true) {
                val k = s.read(buf)
                if (k < 0) break
                total += k
                if (total > 90_000_000) throw IOException("Fichier trop volumineux")
                out.write(buf, 0, k)
            }
            var str = out.toString("UTF-8")
            if (str.startsWith("\uFEFF")) str = str.substring(1)
            return str
        }
    }

    private fun parseEpg(s: InputStream, ids: Set<String>, from: Long, to: Long): String {
        val out = JSONObject()
        val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun tm(v: String?): Long {
            if (v == null || v.length < 14) return 0L
            return try {
                val t = if (v.length > 14) v.trim() else "$v +0000"
                sdf.parse(t)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        try {
            val p = Xml.newPullParser()
            p.setInput(s, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel") ?: ""
                    val st = tm(p.getAttributeValue(null, "start"))
                    val en = tm(p.getAttributeValue(null, "stop"))
                    val keep = ids.contains(ch) && en > from && st < to
                    var title = ""
                    var desc = ""
                    while (true) {
                        ev = p.next()
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        if (ev == XmlPullParser.START_TAG) {
                            if (keep && p.name == "title" && title.isEmpty()) title = p.nextText()
                            else if (keep && p.name == "desc" && desc.isEmpty()) desc = p.nextText().take(180)
                        } else if (ev == XmlPullParser.END_TAG && p.name == "programme") break
                    }
                    if (keep && title.isNotEmpty()) {
                        val arr = out.optJSONArray(ch) ?: JSONArray().also { out.put(ch, it) }
                        arr.put(JSONArray().put(st).put(en).put(title).put(desc))
                    }
                    if (ev == XmlPullParser.END_DOCUMENT) break
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // flux tronqué ou invalide : on renvoie ce qui a été lu
        }
        return out.toString()
    }

    private fun localIp(): String {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (a in Collections.list(ni.inetAddresses)) {
                    if (!a.isLoopbackAddress && a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress ?: ""
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    // ---------- lecteur ----------
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> js("window.__n('pl','buffering','')")
                    Player.STATE_READY -> js("window.__n('pl','ready','')")
                    Player.STATE_ENDED -> js("window.__n('pl','ended','')")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                js("window.__n('pl','error',${JSONObject.quote(error.errorCodeName)})")
            }
        })
        pv.player = p
        player = p
        return p
    }

    private fun playUrl(url: String, start: Long) {
        val p = ensurePlayer()
        pv.visibility = View.VISIBLE
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (start > 0) p.setMediaItem(b.build(), start) else p.setMediaItem(b.build())
        p.prepare()
        p.playWhenReady = true
    }

    private fun label(g: Tracks.Group): String {
        val f = g.getTrackFormat(0)
        val lang = f.language
        return f.label ?: (if (lang != null && lang != "und") Locale(lang).displayLanguage else "Piste")
    }

    private fun tracksJson(): String {
        val p = player ?: return "{}"
        val a = JSONArray()
        val t = JSONArray()
        var asel = -1
        var tsel = -1
        var ai = 0
        var ti = 0
        for (g in p.currentTracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO) {
                a.put(label(g)); if (g.isSelected) asel = ai; ai++
            } else if (g.type == C.TRACK_TYPE_TEXT) {
                t.put(label(g)); if (g.isSelected) tsel = ti; ti++
            }
        }
        return JSONObject().put("a", a).put("as", asel).put("t", t).put("ts", tsel).toString()
    }

    private fun selectTrack(type: String, idx: Int) {
        val p = player ?: return
        val ct = if (type == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val groups = p.currentTracks.groups.filter { it.type == ct }
        val b = p.trackSelectionParameters.buildUpon()
        if (ct == C.TRACK_TYPE_TEXT && idx < 0) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (idx in groups.indices) {
            b.setTrackTypeDisabled(ct, false)
            b.setOverrideForType(TrackSelectionOverride(groups[idx].mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
    }

    // ---------- pont JavaScript ----------
    inner class Bridge {
        @JavascriptInterface
        fun fetch(id: Int, url: String) {
            pool.execute {
                try {
                    val s = readText(url)
                    results[id] = s
                    done(id, true, s.length.toString())
                } catch (e: Exception) {
                    done(id, false, JSONObject.quote(e.message ?: "Erreur réseau"))
                }
            }
        }

        @JavascriptInterface
        fun epg(id: Int, url: String, ids: String, from: Long, to: Long) {
            pool.execute {
                try {
                    val set = ids.split(",").toHashSet()
                    val out = open(url).use { parseEpg(it, set, from, to) }
                    results[id] = out
                    done(id, true, out.length.toString())
                } catch (e: Exception) {
                    done(id, false, JSONObject.quote(e.message ?: "Erreur EPG"))
                }
            }
        }

        @JavascriptInterface
        fun chunk(id: Int, off: Int, len: Int): String {
            val s = results[id] ?: return ""
            if (off >= s.length) return ""
            return s.substring(off, minOf(s.length, off + len))
        }

        @JavascriptInterface
        fun release(id: Int) {
            results.remove(id)
        }

        @JavascriptInterface
        fun saveFile(name: String, text: String) {
            try {
                fileOf(name).writeText(text)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun readFile(name: String): String {
            val f = fileOf(name)
            return try {
                if (f.exists()) f.readText() else ""
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun fileAge(name: String): Long {
            val f = fileOf(name)
            return if (f.exists()) System.currentTimeMillis() - f.lastModified() else -1L
        }

        @JavascriptInterface
        fun play(url: String, startMs: Long) {
            ui.post { playUrl(url, startMs) }
        }

        @JavascriptInterface
        fun pause(p: Boolean) {
            ui.post { player?.playWhenReady = !p }
        }

        @JavascriptInterface
        fun seek(ms: Long) {
            ui.post { player?.seekTo(ms) }
        }

        @JavascriptInterface
        fun stop() {
            ui.post {
                player?.stop()
                player?.clearMediaItems()
                pv.visibility = View.INVISIBLE
            }
        }

        @JavascriptInterface
        fun tracks(): String = onMain { tracksJson() }

        @JavascriptInterface
        fun selTrack(type: String, idx: Int) {
            ui.post { selectTrack(type, idx) }
        }

        @JavascriptInterface
        fun remoteInfo(): String {
            val ip = localIp()
            val p = server?.port ?: 0
            return if (ip.isEmpty() || p == 0) "" else "$ip:$p"
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }
    }
}
