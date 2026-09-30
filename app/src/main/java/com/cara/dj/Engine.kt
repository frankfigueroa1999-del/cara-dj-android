package com.cara.dj

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

data class Prepared(val file: File, val style: String, val forUri: String, val pauseMs: Int, val talkMs: Int, val introAtMs: Int)

/** The DJ brain: watches Spotify, writes the lines, and jumps in at the right moment. */
object Engine {
    lateinit var app: Context
    private lateinit var audio: DJAudio
    private var inited = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val log = mutableStateListOf<String>()
    var now by mutableStateOf(Playback())
    var running by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var queued by mutableStateOf<String?>(null)
    var line by mutableStateOf("")

    private var loop: Job? = null
    private var bgStarted = false
    private var lastPoll = 0L
    private var lastUri = ""
    private var songsSince = 0
    private var nextAfter = 3
    private var lastStyle: String? = null
    private var prepared: Prepared? = null
    private var building = false
    private var forceBreak = false
    private var lastSting = -1

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        audio = DJAudio(app)
    }

    // ---------- logging ----------
    fun addLog(s: String) {
        log.add(s)
        if (log.size > 80) log.removeAt(0)
    }
    private val logger: (String) -> Unit = { msg -> scope.launch { addLog(msg) } }

    // ---------- connecting ----------
    suspend fun connect() {
        try {
            if (!Spotify.isLoggedIn) {
                Spotify.startLogin(app)
                addLog("Finish logging in with Spotify in the browser, then you'll be sent back here.")
                return
            }
            val p = Spotify.poll()
            if (p != null) { now = p; connected = true; addLog("Connected to Spotify.") }
            else { connected = false; addLog("Logged in, but couldn't read playback. Play something in the Spotify app.") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            connected = false
            addLog("Could not connect: ${e.message}")
        }
    }

    suspend fun finishLogin(uri: Uri) {
        val err = uri.getQueryParameter("error")
        if (err != null) { addLog("Spotify said: $err"); return }
        val code = uri.getQueryParameter("code")
        if (code == null) { addLog("Spotify did not send back a login code."); return }
        try {
            Spotify.exchangeCode(code)
            connect()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Could not connect: ${e.message}")
        }
    }

    fun startBackgroundPolling() {
        if (bgStarted) return
        bgStarted = true
        // keeps the "now playing" card fresh even when the DJ is off
        scope.launch {
            while (true) {
                if (!running && Spotify.isLoggedIn) {
                    val p = Spotify.poll()
                    if (p != null) { now = p; connected = true }
                }
                delay(4000)
            }
        }
    }

    // ---------- start / stop ----------
    fun start() {
        if (!connected) { addLog("Connect Spotify first."); return }
        if (running) return
        running = true
        songsSince = 0; lastUri = ""; prepared = null; lastStyle = null; queued = null
        nextAfter = rollInterval()
        try { ContextCompat.startForegroundService(app, Intent(app, DjService::class.java)) } catch (e: Exception) { addLog("Couldn't start background mode: ${e.message}") }
        addLog("DJ is live. You can lock the screen: it keeps working in the background.")
        loop = scope.launch {
            while (running) {
                try { tick() } catch (e: Exception) { if (e is CancellationException) throw e; addLog("Hiccup: ${e.message}") }
                delay(300)
            }
        }
    }

    fun stop() {
        running = false
        loop?.cancel(); loop = null
        prepared?.file?.delete()
        prepared = null
        try { app.stopService(Intent(app, DjService::class.java)) } catch (e: Exception) { }
        addLog("DJ stopped.")
    }

    private fun rollInterval(): Int {
        val lo = maxOf(1, Config.breakMin)
        val hi = maxOf(lo, Config.breakMax)
        val n = randInt(lo, hi)
        addLog("Next DJ break after $n song${if (n == 1) "" else "s"}.")
        return n
    }

    // ---------- queue / test buttons ----------
    fun queue(style: String) {
        if (!running || !now.isPlaying) { addLog("Start the DJ and play a song first."); return }
        queued = style
        addLog("Queued: $style transition, coming up at the end of this song.")
    }

    fun testBreak() {
        if (!running || !now.isPlaying) { addLog("Start the DJ and play a song first."); return }
        forceBreak = true
    }

    suspend fun testStinger() {
        val s = pickStinger()
        if (s == null) { addLog("No stingers found in the app."); return }
        audio.speak(listOf(Clip(asset = s, volume = Config.stingerVolume / 100f)))
    }

    // ---------- the loop ----------
    private fun pickStinger(): String? {
        val all = audio.stingers()
        if (all.isEmpty()) return null
        var i = randInt(0, all.size - 1)
        if (all.size > 1) while (i == lastSting) i = randInt(0, all.size - 1)
        lastSting = i
        return all[i]
    }

    private suspend fun tick() {
        if (busy) return
        val remainingEst = now.remainingMs
        val near = remainingEst != Int.MAX_VALUE && (remainingEst < 15000 || prepared?.style == "intro")
        val interval = if (near) 900L else 2500L
        if (System.currentTimeMillis() - lastPoll >= interval) {
            lastPoll = System.currentTimeMillis()
            val p = Spotify.poll() ?: return
            now = p
            connected = true
            if (p.hasItem && p.uri != lastUri) { lastUri = p.uri; songsSince += 1 }
        }
        if (!running || !now.isPlaying || !now.hasItem) return
        val remaining = now.remainingMs
        val progress = now.currentProgressMs
        val forced = queued
        val due = songsSince >= nextAfter || forced != null

        val pre = prepared
        if (forced != null && pre != null && pre.style != forced) {      // a different style was already written: redo it
            pre.file.delete()
            prepared = null
        }

        if (forceBreak && !building) {
            forceBreak = false
            addLog("Testing a DJ break...")
            buildBreak("intro", now.uri, immediate = true)
            return
        }

        if (due && prepared == null && !building && (remaining < 45000 || forced != null)) {
            val style = forced ?: pickStyle()
            val uri = now.uri
            scope.launch { buildBreak(style, uri, immediate = false) }
        }

        val p = prepared
        if (due && p != null) {
            val go = when (p.style) {
                "silent" -> now.uri == p.forUri && remaining <= p.pauseMs
                "talkover" -> now.uri == p.forUri && remaining <= p.talkMs
                else -> now.uri != p.forUri && progress >= p.introAtMs
            }
            if (go) {
                prepared = null
                songsSince = 0
                lastStyle = p.style
                queued = null
                nextAfter = rollInterval()
                addLog("[transition: ${p.style}]")
                perform(p)
            } else if (p.style != "intro" && now.uri != p.forUri) {
                p.file.delete()
                prepared = null                                          // missed its moment; it will be rebuilt
            }
        }
    }

    private fun pickStyle(): String {
        val all = listOf("talkover", "intro", "silent").filter { it != lastStyle }
        val w = mapOf("talkover" to 4, "intro" to 3, "silent" to 2)
        return weightedPick(all.map { it to (w[it] ?: 1) })
    }

    private suspend fun buildBreak(style: String, forUri: String, immediate: Boolean) {
        building = true
        try {
            val ctx = Ctx(now.track, Spotify.nextTrack())
            val topic = pickTopic(ctx)
            val mood = currentMood()
            addLog("[topic: ${topic.label}] [mood: $mood]")
            val text = writeBreak(style, topic, ctx, mood, logger)
            addLog("[DJ:$style] $text")
            line = text
            try {
                val data = elevenLabsTTS(text)
                val file = File(app.cacheDir, "dj_${System.currentTimeMillis()}_${randInt(0, 999)}.mp3")
                file.writeBytes(data)
                val ms = DJAudio.duration(file)
                val p = Prepared(
                    file, style, forUri,
                    pauseMs = randInt(700, 1100),
                    talkMs = maxOf(4000, minOf(12000, ms - randInt(2000, 4500))),
                    introAtMs = randInt(500, 2500),
                )
                if (immediate) { busy = true; perform(p) } else prepared = p
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addLog("Could not make her voice: ${e.message}")
                if (queued != null) queued = null
            }
        } finally {
            building = false
        }
    }

    // ---------- doing the transition ----------
    private suspend fun perform(p: Prepared) {
        busy = true
        try {
            val voiceVol = Config.djVolume / 100f
            when (p.style) {
                "silent" -> {
                    val device = now.deviceID
                    val uri = now.uri
                    Spotify.pause()
                    val items = mutableListOf<Clip>()
                    val s = pickStinger()
                    if (randInt(0, 99) < Config.stingerChance && s != null) {
                        addLog("[stinger before Cara]")
                        items.add(Clip(asset = s, volume = Config.stingerVolume / 100f))
                    }
                    items.add(Clip(file = p.file, volume = voiceVol))
                    audio.speak(items)
                    delay(200)
                    val cur = Spotify.poll()
                    if (cur != null && cur.uri == uri) { Spotify.skipNext(); delay(300) }
                    resumeMusic(device)
                }
                else -> audio.speak(listOf(Clip(file = p.file, volume = voiceVol)))   // Android turns the Spotify app down while she talks
            }
        } finally {
            p.file.delete()
            busy = false
            lastPoll = 0L
        }
    }

    /** Gets the music going again, retrying: Spotify is often busy for a second right after a skip. */
    private suspend fun resumeMusic(device: String?) {
        for (attempt in 0 until 6) {
            val cur = Spotify.poll()
            if (cur != null && cur.isPlaying) return
            val dev = cur?.deviceID ?: device
            val wake = if (attempt >= 3) Spotify.firstDevice() else null
            if (wake != null) {
                Spotify.transfer(wake)                       // last resort: wake / move playback to a device
                delay(1000)
            } else {
                Spotify.play(dev)
                delay(800)
            }
        }
        addLog("Spotify didn't restart by itself. Tap PLAY.")
    }

    // ---------- player buttons ----------
    suspend fun togglePlay() {
        if (now.isPlaying) Spotify.pause() else resumeMusic(now.deviceID)
        lastPoll = 0L
        delay(300)
        Spotify.poll()?.let { now = it }
    }
    suspend fun next() { Spotify.skipNext(); lastPoll = 0L }
    suspend fun previous() { Spotify.skipPrevious(); lastPoll = 0L }

    suspend fun changeCity(city: String) {
        val c = city.trim()
        if (c.isEmpty() || c == Config.city) return
        Config.city = c
        val g = geocodeCity(c)
        if (g != null) { Config.lat = g.first; Config.lon = g.second }
        else addLog("Couldn't find that town's location; weather may be for the old town.")
    }
}
