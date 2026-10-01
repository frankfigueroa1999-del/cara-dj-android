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

    // pop-in: a quick second drop-in a few seconds into the song after a talk-over / intro break
    private var popinArmed = false
    private var popinUri: String? = null
    private var popinAt = 0
    private var popinFile: File? = null
    private var popinBuilding = false
    private var popinForce = false
    private var popinTestNow = false

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
            else {
                connected = false
                when (Spotify.lastStatus) {
                    403 -> addLog("Spotify refused this account (error 403). Spotify apps in development mode only work for accounts added under User Management in the developer dashboard. Add your Spotify email there (or use your own Client ID in Settings), then log out and back in.")
                    401 -> addLog("Spotify login expired or was rejected (error 401). Open Settings, log out of Spotify, and connect again.")
                    429 -> addLog("Spotify says slow down (error 429). Wait a minute and try again.")
                    0 -> addLog("Couldn't reach Spotify. Check the internet connection.")
                    else -> addLog("Couldn't read playback (Spotify error ${Spotify.lastStatus}). Play something in the Spotify app, then try again.")
                }
            }
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
                delay(12000)
            }
        }
    }

    // ---------- start / stop ----------
    fun start() {
        if (!connected) { addLog("Connect Spotify first."); return }
        if (running) return
        running = true
        songsSince = 0; lastUri = ""; prepared = null; lastStyle = null; queued = null
        dropPopin()
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
        dropPopin()
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

    fun testPopin() {
        if (!running || !now.isPlaying) { addLog("Start the DJ and play a song first."); return }
        popinTestNow = true
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

    private var lastOfflineLog = 0L
    private var buildRetryAt = 0L

    private suspend fun tick() {
        if (busy) return
        val remainingEst = now.remainingMs
        val near = remainingEst != Int.MAX_VALUE && (remainingEst < 12000 || prepared?.style == "intro")
        val interval = if (near) 1200L else 6000L
        if (System.currentTimeMillis() - lastPoll >= interval) {
            lastPoll = System.currentTimeMillis()
            val p = Spotify.poll()
            if (p != null) {
                now = p
                connected = true
                if (p.hasItem && p.uri != lastUri) {
                    lastUri = p.uri
                    songsSince += 1
                    if (popinFile != null && popinUri != p.uri && !popinForce) dropPopin()
                    if (popinArmed) {
                        popinArmed = false
                        if (Config.popinEnabled && !popinBuilding && popinFile == null) planPopin(p.track, p.uri, p.durationMs)
                        else addLog("[pop-in skipped: " + (if (Config.popinEnabled) "still busy with the last one" else "turned off") + "]")
                    }
                }
            } else {
                // can't reach Spotify for a moment (busy, rate limit, bad signal): carry on with our own clock so the break isn't missed
                if (!(running && now.isPlaying && now.hasItem && now.remainingMs > -2000)) return
                if (System.currentTimeMillis() - lastOfflineLog > 30000) { lastOfflineLog = System.currentTimeMillis(); addLog("Spotify isn't answering, using my own clock for now.") }
            }
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

        // test button: make a pop-in for the current song and play it as soon as it is ready
        if (popinTestNow && !popinBuilding && popinFile == null) {
            popinTestNow = false
            addLog("Testing pop-in...")
            popinUri = now.uri; popinAt = 0; popinForce = true
            val t = now.track
            val u = now.uri
            scope.launch { buildPopin(t, u) }
        }

        // pop-in: once its clip is ready and we are a few seconds into the song, and a break isn't about to start
        val pf = popinFile
        if (pf != null) {
            if (popinForce || (now.uri == popinUri && progress >= popinAt && remaining > 25000)) {
                popinFile = null; popinUri = null; popinForce = false
                addLog("[pop-in]")
                playPopin(pf)
                return
            }
            if (now.uri != popinUri) dropPopin()
        }

        if (due && prepared == null && !building && System.currentTimeMillis() >= buildRetryAt && (remaining < 150000 || forced != null)) {
            val style = forced ?: pickStyle()
            val uri = now.uri
            scope.launch { buildBreak(style, uri, immediate = false) }
        }

        val p = prepared
        if (due && p != null) {
            val go = when (p.style) {
                "silent" -> if (now.uri == p.forUri) remaining <= p.pauseMs else progress >= 1200
                // if the clip finished after its song ended, talk over the start of the next song instead of losing the break
                "talkover" -> if (now.uri == p.forUri) remaining <= p.talkMs else progress >= 1200
                else -> now.uri != p.forUri && progress >= p.introAtMs
            }
            if (go) {
                val late = (p.style == "talkover" || p.style == "silent") && now.uri != p.forUri
                prepared = null
                songsSince = 0
                lastStyle = p.style
                queued = null
                nextAfter = rollInterval()
                addLog(if (late) "[transition: talkover (late, over the start of this song)]" else "[transition: ${p.style}]")
                if (Config.popinEnabled && (p.style != "silent" || late)) {
                    if (Config.popinTest || randInt(0, 99) < Config.popinChance) {
                        if (late || p.style == "intro") {               // already inside the new song
                            planPopin(now.track, now.uri, now.durationMs)
                        } else {
                            popinArmed = true
                            addLog("[pop-in lined up for the next song]")
                        }
                    } else {
                        addLog("[no pop-in after this one (${Config.popinChance}% chance each time)]")
                    }
                }
                perform(p, late)
            } else if (p.style != "intro" && now.uri != p.forUri && progress > 20000) {
                addLog("[a break missed its moment, rebuilding it]")
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
                addLog("Could not make her voice: ${e.message}. Trying again in a few seconds.")
                buildRetryAt = System.currentTimeMillis() + 20000
                if (queued != null) queued = null
            }
        } finally {
            building = false
        }
    }

    // ---------- doing the transition ----------
    private suspend fun perform(p: Prepared, late: Boolean = false) {
        busy = true
        try {
            val voiceVol = Config.djVolume / 100f
            when (p.style) {
                "silent" -> if (!late) {
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
                } else audio.speak(listOf(Clip(file = p.file, volume = voiceVol)))
                else -> audio.speak(listOf(Clip(file = p.file, volume = voiceVol)))   // Android turns the Spotify app down while she talks
            }
        } finally {
            p.file.delete()
            busy = false
            lastPoll = 0L
        }
    }

    // ---------- pop-in ----------
    private fun planPopin(t: Track?, uri: String, duration: Int) {
        if (popinBuilding || popinFile != null) return
        val after = maxOf(5, Config.popinSeconds)
        val jitter = minOf(5, after / 2)
        val at = after * 1000 + randInt(-jitter * 1000, jitter * 1000)
        if (duration < at + 40000) { addLog("[pop-in skipped: this song is too short for one]"); return }
        popinUri = uri; popinAt = at; popinForce = false
        addLog("[pop-in planned about ${at / 1000}s into this song]")
        scope.launch { buildPopin(t, uri) }
    }

    private suspend fun buildPopin(t: Track?, uri: String) {
        popinBuilding = true
        try {
            val text = writePopIn(t, logger)
            addLog("[POP-IN] $text")
            val data = elevenLabsTTS(text)
            val file = File(app.cacheDir, "popin_${System.currentTimeMillis()}_${randInt(0, 999)}.mp3")
            file.writeBytes(data)
            popinFile = file
            addLog("[pop-in ready, waiting for its moment]")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Could not make the pop-in voice: ${e.message}")
            popinUri = null
            popinForce = false
        } finally {
            popinBuilding = false
        }
    }

    private suspend fun playPopin(file: File) {
        busy = true
        try {
            audio.speak(listOf(Clip(file = file, volume = Config.djVolume / 100f)))      // Android turns the Spotify app down while she talks
        } finally {
            file.delete()
            busy = false
            lastPoll = 0L
        }
    }

    private fun dropPopin() {
        popinFile?.delete()
        popinFile = null; popinUri = null; popinArmed = false; popinForce = false
    }

    /** Gets the music going again, retrying: Spotify is often busy for a second right after a skip. */
    private suspend fun resumeMusic(device: String?) {
        for (attempt in 0 until 6) {
            val cur = Spotify.poll()
            if (cur != null && cur.isPlaying) return
            // only ever this phone: never another device such as a speaker or soundbar
            val dev = Spotify.phoneDevice() ?: device
            if (dev == null) {
                addLog("Can't find this phone in Spotify. Open the Spotify app, then tap PLAY.")
                return
            }
            if (attempt >= 3) {
                Spotify.transfer(dev)                        // last resort: wake the Spotify app on this phone
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
    suspend fun toggleShuffle() {
        val on = !now.shuffle
        now = now.copy(shuffle = on)
        Spotify.setShuffle(on)
        lastPoll = 0L
    }
    suspend fun cycleRepeat() {
        val next = when (now.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }
        now = now.copy(repeat = next)
        Spotify.setRepeat(next)
        lastPoll = 0L
    }
    suspend fun seek(ms: Int) {
        now = now.copy(progressMs = ms, stamp = System.currentTimeMillis())
        Spotify.seek(ms)
        lastPoll = 0L
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
