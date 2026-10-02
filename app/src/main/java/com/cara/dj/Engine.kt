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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Prepared(val file: File, val style: String, val forUri: String, val pauseMs: Int, val talkMs: Int, val introAtMs: Int)

/**
 * The heart of the app: keeps an eye on Spotify for the screens, runs the player buttons,
 * and (when Cara is live) writes her lines and jumps in at the right moment.
 */
object Engine {
    lateinit var app: Context
    private lateinit var audio: DJAudio
    private var inited = false
    private val guard = CoroutineExceptionHandler { _, e -> try { addLog("Hiccup: ${e.message}") } catch (x: Exception) { } }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + guard)

    // ---------------------------------------------------------------- what the screens show
    val log = mutableStateListOf<String>()
    var now by mutableStateOf(Playback())
    var running by mutableStateOf(false)
    var busy by mutableStateOf(false)
    /** Cara (or a stinger) is coming out of the speaker right now. */
    var speaking by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var queued by mutableStateOf<String?>(null)
    var line by mutableStateOf("")
    var lineStyle by mutableStateOf("")
    var upNext by mutableStateOf(listOf<Track>())
    var contextName by mutableStateOf("")
    /** What the app calls a list of songs it started itself with no playlist behind it (Liked Songs, an artist's top songs, a mood). */
    var localStation by mutableStateOf("")
    var currentLiked by mutableStateOf<Boolean?>(null)
    var devices by mutableStateOf(listOf<Device>())
    /** Shown straight away after you tap a song or skip, until Spotify confirms it. */
    var pendingItem by mutableStateOf<Track?>(null)
    var sleepAt by mutableStateOf<Long?>(null)
    var sleepAtTrackEnd by mutableStateOf(false)
    /** Spotify couldn't find anywhere to play (the Spotify app is closed). */
    var noDevice by mutableStateOf(false)
    var foreground by mutableStateOf(true)
    /** Logged in to Spotify (kept here so every screen notices the moment it changes). */
    var loggedIn by mutableStateOf(false)
    /** Why Spotify isn't working right now, in plain words ("" when all is well). */
    var problem by mutableStateOf("")
    var prepared by mutableStateOf<Prepared?>(null)
        private set
    var songsSince by mutableStateOf(0)
        private set
    var nextAfter by mutableStateOf(3)
        private set

    val displayItem: Track? get() = pendingItem ?: now.item

    /** Where the music's coming from, as Spotify names it ("" when it isn't from anything in particular). */
    val playingFrom: String get() = if (now.contextUri.isEmpty()) localStation else contextName
    /** The station takes the name of whatever's playing ("Late Night Drives"); Non Stop Pop when nothing nameable is. */
    val stationName: String get() = Station.clean(playingFrom).ifEmpty { Station.FALLBACK }
    /** On air: "Late Night Drives FM". */
    val stationFull: String get() = Station.full(stationName)
    /** What the station's named after, in Cara's words ("" for plain old Non Stop Pop). */
    val stationNote: String
        get() {
            val name = stationName
            if (name == Station.FALLBACK) return ""
            if (now.contextUri.isEmpty()) return "\"$name\""
            return when (Station.kind(now.contextUri)) {
                "playlist" -> "the playlist \"$name\""
                "album" -> "the album \"$name\""
                "artist" -> "songs by $name"
                "collection" -> "the listener's Liked Songs"
                else -> "\"$name\""
            }
        }

    // ---------------------------------------------------------------- inside
    private var loop: Job? = null
    private var lastPoll = 0L
    private var fastUntil = 0L
    private var lastOfflineLog = 0L
    private var buildRetryAt = 0L
    private var pendingSince = 0L
    private var lastUri = ""          // the DJ's idea of the current song
    private var shownUri = ""         // the screen's idea of the current song
    private var lastContext = ""
    /** Names of playlists / albums / artists we've seen, so the station is named the moment one starts. */
    private val contextNames = mutableMapOf<String, String>()
    private var contextTries = 0
    private var contextRetryAt = 0L
    private var loggedStation = ""
    private var stationAtLastBreak = ""
    // right after you press a button Spotify can still report the old state for a moment; keep ours briefly
    private var holdUntil = 0L
    private var heldPlaying: Boolean? = null
    private var heldShuffle: Boolean? = null
    private var heldRepeat: String? = null
    private var heldProgress = false
    private var lastStyle: String? = null
    private var building = false
    private var forceBreak = false
    /** The next break is Cara and Scratch together (the "With Scratch" button). */
    private var forceDuo = false
    private var lastSting = -1
    private var scopeHinted = false

    // silent breaks: a short silent track is lined up in Spotify for her to talk over
    private var silenceQueued = false          // it's sitting right at the front of Spotify's queue
    private var silenceTried = false           // already tried to line one up for this break
    private var silenceLeadMs = 0              // how long before the song's end it went in
    private var silenceMisses = 0              // times in a row Spotify played something else instead
    private var lastStraySkip = 0L

    // pop-in: a quick second drop-in a few seconds into the song after a talk-over / intro break
    private var popinArmed = false
    private var popinUri: String? = null
    private var popinAt = 0
    private var popinFile: File? = null
    private var popinBuilding = false
    private var popinForce = false
    private var popinTestNow = false

    val popinWaiting: Boolean get() = popinFile != null || popinBuilding

    /** Where Cara will talk in "Playing Next": just before upNext[slot]. null when she's off air. */
    val breakSlot: Int?
        get() {
            if (!running) return null
            if (queued != null) return 0
            return maxOf(0, nextAfter - songsSince)
        }

    val statusLine: String
        get() {
            if (speaking) return "On the mic right now"
            if (!running) return "Off air"
            if (busy) return "Getting ready to talk"
            val s = breakSlot ?: return "Live"
            if (s == 0) return if (prepared != null) "Ready to talk after this song" else "Back after this song"
            return "Next break in ${s + 1} songs"
        }

    private fun nowMs() = System.currentTimeMillis()

    // ---------------------------------------------------------------- starting up
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        audio = DJAudio(app)
        ImageCache.init(app)
        Library.init(app)
        loggedIn = Spotify.isLoggedIn
        try {
            val j = JSONObject(Config.contextNamesJson)
            for (k in j.keys()) contextNames[k] = j.optString(k, "")
        } catch (e: Exception) { }
        loadBrain()
    }

    /** Cara's brain (what she talks about, her memory, Scratch) ships inside the app. */
    private fun loadBrain(): Boolean {
        if (Brain.isReady) return true
        return try {
            Brain.init(app)
            true
        } catch (e: Exception) {
            addLog("Couldn't load Cara's brain: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- logging
    fun addLog(s: String) {
        log.add(s)
        while (log.size > 120) log.removeAt(0)
    }

    private val logger: (String) -> Unit = { msg -> scope.launch { addLog(msg) } }

    // ---------------------------------------------------------------- connecting
    suspend fun connect(forceLogin: Boolean = false) {
        try {
            if (forceLogin || !Spotify.isLoggedIn) Spotify.login(app)
            loggedIn = Spotify.isLoggedIn
            val p = Spotify.poll()
            if (p != null) {
                apply(p)
                problem = ""
                addLog("Connected to Spotify.")
            } else {
                connected = false
                problem = when (Spotify.lastStatus) {
                    403 -> "Spotify refused this account (error 403). In your Spotify developer dashboard, add this account's email under User Management (5 people at most), then reconnect."
                    401 -> "Your Spotify login expired or was rejected (error 401). Reconnect to fix it."
                    429 -> "Spotify says slow down (error 429). Wait a minute, then try again."
                    0 -> "Couldn't reach Spotify. Check the internet connection, then try again."
                    else -> "Couldn't read playback (Spotify error ${Spotify.lastStatus}). Open the Spotify app, then try again."
                }
                addLog(problem)
            }
            Library.loadAll(force = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            connected = false
            loggedIn = Spotify.isLoggedIn
            addLog("Could not connect: ${e.message}")
            Toasts.show("Couldn't connect to Spotify", "exclamationmark.triangle.fill")
        }
    }

    /** Spotify sent the browser back here without a login waiting for it (the app was closed in between). */
    suspend fun finishLogin(uri: Uri) {
        val err = uri.getQueryParameter("error")
        if (err != null) { addLog("Spotify said: $err"); return }
        val code = uri.getQueryParameter("code") ?: run { addLog("Spotify did not send back a login code."); return }
        try {
            Spotify.exchangeCode(code)
            connect()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Could not connect: ${e.message}")
        }
    }

    /** Once: an older Spotify login can't read your library, playlist names or top artists, so ask for a fresh one. */
    fun scopeHint() {
        if (scopeHinted || !Spotify.isLoggedIn || Spotify.hasAllScopes) return
        scopeHinted = true
        addLog("One-time thing: reconnect Spotify (Settings) so Cara DJ can show your library, name the station after your playlist and tease your top artists.")
    }

    /** Starts the one loop that keeps everything up to date. Safe to call more than once. */
    fun boot() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                try { cycle() } catch (e: Exception) { if (e is CancellationException) throw e; addLog("Hiccup: ${e.message}") }
                delay(300)
            }
        }
    }

    /** Check Spotify again right away (and quickly for a few seconds), e.g. after a button press. */
    fun poke() {
        lastPoll = 0L
        fastUntil = nowMs() + 4000
    }

    private suspend fun cycle() {
        if (running) {
            tick()
        } else if (Spotify.isLoggedIn) {
            val near = now.isPlaying && now.hasItem && now.remainingMs < 3500
            var interval = if (foreground) 5000L else 30000L
            if (sleepAt != null || sleepAtTrackEnd) interval = minOf(interval, 10000L)
            if (foreground && (near || nowMs() < fastUntil)) interval = 1000L
            if (nowMs() - lastPoll >= interval) {
                lastPoll = nowMs()
                Spotify.poll()?.let { apply(it) }
                // Cara is off air, so a silent track left in the queue has nothing to do: move on
                if (Silence.isSilence(now.uri) && now.isPlaying) skipStraySilence()
            }
        }
        checkSleep()
        if (pendingItem != null && nowMs() - pendingSince > 5000) pendingItem = null
    }

    // ---------------------------------------------------------------- taking in what Spotify says
    /** Keep what the buttons just set for a moment, even if Spotify still reports the old state. */
    private fun hold(playing: Boolean? = null, shuffle: Boolean? = null, repeatMode: String? = null, progress: Boolean = false) {
        holdUntil = nowMs() + 1800
        if (playing != null) heldPlaying = playing
        if (shuffle != null) heldShuffle = shuffle
        if (repeatMode != null) heldRepeat = repeatMode
        if (progress) heldProgress = true
    }

    private fun dropHold() {
        holdUntil = 0L
        heldPlaying = null
        heldShuffle = null
        heldRepeat = null
        heldProgress = false
    }

    /** A fresh reading from Spotify, and reacting when the song changes. */
    private fun apply(fresh: Playback) {
        var p = fresh
        if (Silence.isSilence(p.uri)) {
            // the silent track behind a silent break: show Cara on the air instead of "30 Seconds of Silence"
            p = p.copy(item = Silence.caraItem(p.uri, p.durationMs, stationName), track = null)
        }
        if (nowMs() < holdUntil) {
            val sameSong = p.uri == now.uri
            val hp = heldPlaying
            if (hp != null && p.isPlaying != hp && sameSong) p = p.copy(isPlaying = hp, progressMs = now.currentProgressMs, stamp = nowMs())
            heldShuffle?.let { p = p.copy(shuffle = it) }
            heldRepeat?.let { p = p.copy(repeatMode = it) }
            if (heldProgress && sameSong) p = p.copy(progressMs = now.currentProgressMs, stamp = nowMs())
        } else if (heldPlaying != null || heldShuffle != null || heldRepeat != null || heldProgress) {
            dropHold()
        }
        now = p
        connected = true
        problem = ""
        if (p.hasItem) noDevice = false
        if (p.uri != shownUri) {
            shownUri = p.uri
            pendingItem = null
            songChanged()
        } else if (pendingItem != null && pendingItem?.uri == p.uri) {
            pendingItem = null
        }
        if (p.contextUri != lastContext) {
            lastContext = p.contextUri
            contextTries = 0
            contextRetryAt = 0L
            contextName = knownName(p.contextUri) ?: ""
            if (contextName.isEmpty() && p.contextUri.isNotEmpty()) scope.launch { loadContextName() }
        } else if (lastContext.isNotEmpty() && contextName.isEmpty() && contextTries < 4 && nowMs() >= contextRetryAt) {
            // Spotify didn't answer last time (or the library hadn't loaded yet): try again now and then
            contextRetryAt = nowMs() + 15000
            scope.launch { loadContextName() }
        }
        val st = stationFull
        if (st != loggedStation) {
            loggedStation = st
            if (running) {
                addLog("[station: $st]")
                updateService()
                scope.launch {
                    delay(4000)
                    warmStingers()
                }
            }
        }
    }

    private fun songChanged() {
        currentLiked = null
        scope.launch {
            refreshQueue()
            refreshLiked()
        }
    }

    suspend fun refreshQueue() {
        Spotify.queue()?.let { upNext = it }
    }

    suspend fun refreshLiked() {
        val it = now.item
        if (it == null || it.uri.isEmpty() || it.isLocal) { currentLiked = null; return }
        val uri = it.uri
        Library.likedState[uri]?.let { known -> currentLiked = known }
        val r = Spotify.contains(listOf(uri))
        val v = r?.firstOrNull()
        if (v != null && now.item?.uri == uri) {
            currentLiked = v
            Library.likedState[uri] = v
        }
    }

    private suspend fun loadContextName() {
        val c = lastContext
        if (c.isEmpty()) { contextName = ""; return }
        knownName(c)?.let { contextName = it; return }
        contextTries += 1
        contextRetryAt = nowMs() + (if (contextTries < 3) 15000L else 120000L)
        val name = Spotify.contextName(c)
        if (c != lastContext || name.isNullOrEmpty()) {
            if (c == lastContext && contextTries >= 2 && !Spotify.hasAllScopes) scopeHint()
            return
        }
        contextName = name
        rememberName(name, c)
    }

    /** A name we already know for this playlist / album / artist, without asking Spotify. */
    private fun knownName(uri: String): String? {
        if (uri.isEmpty()) return null
        val k = Station.key(uri)
        if (k == "spotify:collection") return "Liked Songs"
        contextNames[k]?.let { if (it.isNotEmpty()) return it }
        Library.playlists.firstOrNull { Station.key(it.uri) == k }?.name?.let { if (it.isNotEmpty()) return it }
        Library.albums.firstOrNull { Station.key(it.uri) == k }?.name?.let { if (it.isNotEmpty()) return it }
        Library.artists.firstOrNull { Station.key(it.uri) == k }?.name?.let { if (it.isNotEmpty()) return it }
        return null
    }

    /** Remembers what something's called, so the station is named the moment it starts playing. */
    private fun rememberName(name: String, uri: String) {
        val k = Station.key(uri)
        if (k.isEmpty() || name.isEmpty() || contextNames[k] == name) return
        if (contextNames.size >= 150) contextNames.clear()
        contextNames[k] = name
        try {
            val j = JSONObject()
            for ((key, v) in contextNames) j.put(key, v)
            Config.contextNamesJson = j.toString()
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- keeping the app alive in the background
    /** The small "Cara DJ is live" notification: it's what keeps her (and the sleep timer) going with the screen off. */
    private fun keepAlive(on: Boolean) {
        try {
            val i = Intent(app, DjService::class.java)
            if (on) {
                i.putExtra("text", if (running) "Live on $stationFull" else "Sleep timer on")
                ContextCompat.startForegroundService(app, i)
            } else {
                app.stopService(i)
            }
        } catch (e: Exception) {
            if (on) addLog("Couldn't start background mode: ${e.message}")
        }
    }

    private fun updateService() {
        if (running || sleepAt != null || sleepAtTrackEnd) keepAlive(true)
    }

    // ---------------------------------------------------------------- start / stop the DJ
    fun start() {
        if (!connected) {
            addLog("Connect Spotify first.")
            Toasts.show("Connect Spotify first", "exclamationmark.triangle.fill")
            return
        }
        if (running) return
        loadBrain()
        running = true
        songsSince = 0; lastUri = ""; prepared = null; lastStyle = null; queued = null
        silenceQueued = false; silenceTried = false
        dropPopin()
        nextAfter = rollInterval()
        keepAlive(true)
        loggedStation = stationFull
        addLog("DJ is live on $stationFull. You can lock the screen: it keeps working in the background.")
        lastPoll = 0L
        scope.launch { ensureSilenceTrack() }
        scope.launch {
            delay(3000)
            warmStingers()
        }
    }

    fun stop() {
        running = false
        prepared?.file?.delete()
        prepared = null
        queued = null
        silenceQueued = false; silenceTried = false
        dropPopin()
        if (sleepAt == null && !sleepAtTrackEnd) keepAlive(false) else updateService()
        addLog("DJ stopped.")
    }

    private fun rollInterval(): Int {
        val lo = maxOf(1, Config.breakMin)
        val hi = maxOf(lo, Config.breakMax)
        val n = randInt(lo, hi)
        addLog("Next DJ break after $n song${if (n == 1) "" else "s"}.")
        return n
    }

    // ---------------------------------------------------------------- queue / test buttons
    private fun needLive(): Boolean {
        if (running && now.isPlaying) return true
        addLog("Start the DJ and play a song first.")
        Toasts.show("Go live and play a song first", "dot.radiowaves.left.and.right")
        return false
    }

    fun queue(style: String) {
        if (!needLive()) return
        queued = style
        addLog("Queued: $style transition, coming up at the end of this song.")
        Toasts.show("Cara's on after this song", "dot.radiowaves.left.and.right")
        if (style == "silent" && !silenceQueued) {
            silenceTried = true
            scope.launch { lineUpSilence() }          // the earlier Spotify knows, the surer it is
        }
    }

    fun testBreak() {
        if (!needLive()) return
        forceBreak = true
    }

    /** Cara and Scratch, right now. */
    fun testDuo() {
        if (!needLive()) return
        forceDuo = true
        forceBreak = true
    }

    fun testPopin() {
        if (!needLive()) return
        popinTestNow = true
    }

    suspend fun testStinger() {
        var station: File? = null
        val name = stationName
        if (Config.stationStingers && name != Station.FALLBACK) {
            // one may already be on its way
            var waited = 0
            while (StationStingers.isMaking(name) && waited < 60) {
                delay(500)
                waited++
            }
            station = StationStingers.ready(name)
            if (station == null) {
                Toasts.show("Making a ${Station.full(name)} stinger…", "bolt.fill")
                station = StationStingers.make(name, logger)
            }
        }
        val clip = if (station != null) Clip(file = station, volume = Config.stingerVolume / 100f) else originalStinger()
        if (clip == null) { addLog("No stingers found in the app."); return }
        speaking = true
        try { audio.speak(listOf(clip)) } finally { speaking = false }
    }

    // ---------------------------------------------------------------- the DJ loop
    /** The stinger before a silent break: one made for this station, or one of your originals on plain Non Stop Pop. */
    private fun pickStinger(): Clip? {
        val name = stationName
        if (Config.stationStingers && name != Station.FALLBACK && !StationStingers.failingLately) {
            StationStingers.ready(name)?.let { return Clip(file = it, volume = Config.stingerVolume / 100f) }
            addLog("[no ${Station.full(name)} stinger made yet, so none this time]")
            warmStingers()
            return null
        }
        return originalStinger()
    }

    /** Gets another stinger made for the station that's playing, in the background. */
    private fun warmStingers() {
        if (!Config.stationStingers || Config.stingerChance <= 0 || stationName == Station.FALLBACK) return
        StationStingers.warm(stationName, logger)
    }

    /** One of your six original stingers. */
    private fun originalStinger(): Clip? {
        val all = audio.stingers()
        if (all.isEmpty()) return null
        var i = randInt(0, all.size - 1)
        if (all.size > 1) while (i == lastSting) i = randInt(0, all.size - 1)
        lastSting = i
        return Clip(asset = all[i], volume = Config.stingerVolume / 100f)
    }

    private suspend fun tick() {
        if (busy) return
        val remainingEst = now.remainingMs
        val near = remainingEst != Int.MAX_VALUE && (remainingEst < maxOf(12000, (prepared?.talkMs ?: 0) + 4000) || prepared?.style == "intro")
        val interval = if (near || nowMs() < fastUntil || Silence.isSilence(now.uri)) 1200L else 6000L
        if (nowMs() - lastPoll >= interval) {
            lastPoll = nowMs()
            val p = Spotify.poll()
            if (p != null) {
                apply(p)
                // a new song (the silent track between songs doesn't count as one)
                if (p.hasItem && p.uri != lastUri && !Silence.isSilence(p.uri)) {
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
                if (nowMs() - lastOfflineLog > 30000) { lastOfflineLog = nowMs(); addLog("Spotify isn't answering, using my own clock for now.") }
            }
        }
        if (!running || !now.isPlaying || !now.hasItem) return
        val remaining = now.remainingMs
        val progress = now.currentProgressMs
        val forced = queued
        val due = songsSince >= nextAfter || forced != null
        val onSilence = Silence.isSilence(now.uri)

        val pre = prepared
        if (forced != null && pre != null && pre.style != forced) {      // a different style was already written: redo it
            pre.file.delete()
            prepared = null
        }

        // the silent track is on, but no break is waiting for it (you skipped around, or a break went another way): move on
        if (onSilence) {
            val waiting = due && (prepared != null || (building && progress < 15000))
            if (!waiting) {
                skipStraySilence()
                return
            }
        }

        if (forceBreak && !building) {
            forceBreak = false
            val duo = forceDuo
            forceDuo = false
            addLog(if (duo) "Testing Cara and ${Brain.coShort}..." else "Testing a DJ break...")
            buildBreak("intro", now.uri, immediate = true, duo = duo)
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

        if (due && prepared == null && !building && !onSilence && nowMs() >= buildRetryAt && (remaining < 150000 || forced != null)) {
            // Scratch can join any kind of break, queued or not. Their talk-overs finish as the song ends and their
            // intros are a quick two-liner, so a song that starts straight away isn't buried under their chat.
            val duo = Config.coHost && randInt(0, 99) < Config.coHostChance
            val style = forced ?: pickStyle()
            val uri = now.uri
            building = true
            scope.launch { buildBreak(style, uri, immediate = false, duo = duo) }
            if (style == "silent" && !silenceQueued && !silenceTried && remaining > 4000) {
                silenceTried = true
                scope.launch { lineUpSilence() }
            }
        }

        // a silent break: line the silent track up shortly before this song ends, so the music really stops while she talks
        val ps = prepared
        if (due && ps != null && ps.style == "silent" && now.uri == ps.forUri && !silenceQueued && !silenceTried &&
            remaining < 40000 && remaining > 2500 && now.repeatMode != "track" && Config.silenceURI.isNotEmpty()) {
            silenceTried = true
            lineUpSilence()
        }

        val p = prepared
        if (due && p != null) {
            val go = if (onSilence) {
                true                                            // the music has stopped: her moment
            } else when (p.style) {
                // with the silent track lined up she waits for it; without one she goes right at the end of the song.
                // A silent break that missed its moment (a different song started) pauses that song for her instead
                "silent" -> if (now.uri == p.forUri) (!silenceQueued && remaining <= p.pauseMs) else progress >= 1200
                // if the clip was ready after its song ended: pause the next song, talk, then play it from the top
                "talkover" -> if (now.uri == p.forUri) remaining <= p.talkMs else progress >= 1200
                else -> now.uri != p.forUri && progress >= p.introAtMs
            }
            if (go) {
                val late = !onSilence && (p.style == "talkover" || p.style == "silent") && now.uri != p.forUri
                if (late && p.style == "silent" && silenceQueued) silenceMissed()
                prepared = null
                silenceQueued = false
                silenceTried = false
                songsSince = 0
                lastStyle = p.style
                queued = null
                nextAfter = rollInterval()
                // Android can always pause Spotify for her (the app keeps running with the screen off)
                val silentNow = onSilence || p.style == "silent" || late
                when {
                    onSilence -> addLog(if (p.style == "silent") "[transition: silent (the music has stopped)]" else "[transition: ${p.style}, over the silent track]")
                    late -> addLog("[transition: ${p.style} (the next song had started, so it's paused for her and starts again from the top after)]")
                    else -> addLog("[transition: ${p.style}]")
                }
                if (Config.popinEnabled && !silentNow) {
                    if (Config.popinTest || randInt(0, 99) < Config.popinChance) {
                        if (p.style == "intro") {               // already inside the new song
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
                p.file.delete()
                addLog("[a break missed its moment, rebuilding it]")
                prepared = null                                  // missed its moment; it will be rebuilt
                silenceQueued = false; silenceTried = false
            }
        }
    }

    // ---------------------------------------------------------------- the silent track (makes silent breaks really silent)
    /** Makes sure a silent track this account can definitely play is known (checked about once a week,
     *  and straight away after Spotify refuses one). */
    private suspend fun ensureSilenceTrack() {
        val nowSec = nowMs() / 1000.0
        if (Config.silenceURI.isNotEmpty() && nowSec - Config.silenceCheckedAt < 7 * 86400) return
        val (uri, reached) = Spotify.findSilence(Config.silenceBadList)
        if (!reached) {
            // couldn't ask Spotify right now: use the usual one if it hasn't been refused, and check again next time
            if (Config.silenceURI.isEmpty()) Silence.candidates.firstOrNull { it !in Config.silenceBadList }?.let { Config.silenceURI = it }
            return
        }
        Config.silenceURI = uri ?: ""
        Config.silenceCheckedAt = nowSec
        if (uri != null) addLog("[silent track ready: $uri]")
        else addLog("Spotify has no silent track this account can play, so silent breaks pause the music instead.")
    }

    /** Puts the silent track at the front of Spotify's queue for her silent break, then checks where it landed. */
    private suspend fun lineUpSilence() {
        if (!running || silenceQueued) return
        if (Config.silenceURI.isEmpty()) ensureSilenceTrack()
        if (Config.silenceURI.isEmpty()) return
        if (now.repeatMode == "track") {
            addLog("[repeat-one is on, so this break can't use the silent track]")
            return
        }
        val before = Spotify.queue()
        if (before != null) upNext = before
        val at = before?.indexOfFirst { Silence.isSilence(it.uri) } ?: -1
        if (at == 0) {
            silenceQueued = true
            silenceLeadMs = now.remainingMs
            addLog("[the silent track is already next in Spotify's queue]")
            return
        }
        val st = Spotify.addToQueue(Config.silenceURI, now.deviceID)
        if (!ok(st)) {
            addLog("[couldn't line up the silent track (Spotify said $st), so this break stops the music another way]")
            return
        }
        delay(900)
        val q = Spotify.queue()
        if (q == null) {
            silenceQueued = true                     // couldn't check; trust it
            silenceLeadMs = now.remainingMs
            addLog("[silent track lined up]")
            return
        }
        upNext = q
        val left = maxOf(0, now.remainingMs / 1000)
        val i = q.indexOfFirst { Silence.isSilence(it.uri) }
        if (i == 0) {
            silenceQueued = true
            silenceLeadMs = now.remainingMs
            addLog("[silent track is next in Spotify's queue, ${left}s before the end: the music will stop for her]")
        } else if (i > 0) {
            addLog("[the silent track landed behind $i song${if (i == 1) "" else "s"} you queued in Spotify, so this break can't be silent]")
        } else {
            addLog("[Spotify took the silent track but didn't queue it, so it can't play on this account. Finding another one.]")
            markSilenceBad()
        }
    }

    /** The silent track was next in the queue, but Spotify played a different song. */
    private fun silenceMissed() {
        silenceMisses += 1
        val early = silenceLeadMs > 20000
        addLog("[Spotify skipped the silent track (lined up ${maxOf(0, silenceLeadMs / 1000)}s before the end)]")
        // lined up in good time and still skipped, or skipped twice: this one doesn't play here, try another
        if (early || silenceMisses >= 2) {
            markSilenceBad()
            silenceMisses = 0
        }
    }

    private fun markSilenceBad() {
        val u = Config.silenceURI
        if (u.isNotEmpty()) {
            val bad = Config.silenceBadList
            if (u !in bad) Config.silenceBadList = bad + u
        }
        Config.silenceURI = ""
        Config.silenceCheckedAt = 0.0
        scope.launch { ensureSilenceTrack() }
    }

    /** A silent track came up with nothing waiting for it: skip on to the next song. */
    private suspend fun skipStraySilence() {
        if (nowMs() - lastStraySkip <= 4000) return
        lastStraySkip = nowMs()
        addLog("[skipping a leftover silent track]")
        val st = Spotify.skipNext()
        if (ok(st)) silenceQueued = false
        poke()
    }

    /** While she talks over the silent track, keeps it from running out (jumps it back to its start). */
    private fun keepSilenceGoing(): Job {
        val length = now.durationMs
        val startAt = now.currentProgressMs
        return scope.launch {
            var pos = startAt
            var mark = nowMs()
            while (isActive) {
                delay(500)
                val at = pos + (nowMs() - mark).toInt()
                if (length > 0 && length - at < 4000) {
                    Spotify.seek(0)
                    pos = 0
                    mark = nowMs()
                }
            }
        }
    }

    /** After a silent break: on to the next song (only if the silent track is still the one playing). */
    private suspend fun leaveSilence() {
        for (attempt in 0 until 4) {
            val cur = Spotify.poll()
            if (cur != null) {
                apply(cur)
                if (!Silence.isSilence(cur.uri)) return          // Spotify already moved on
                val next = upNext.firstOrNull { !Silence.isSilence(it.uri) }
                val st = Spotify.skipNext()
                if (ok(st)) {
                    if (next != null) {
                        pendingItem = next
                        pendingSince = nowMs()
                    }
                    poke()
                    return
                }
            }
            delay((attempt + 1) * 700L)
        }
        addLog("[couldn't skip the silent track; Spotify moves on by itself when it ends]")
    }

    private fun pickStyle(): String {
        val all = listOf("talkover", "intro", "silent").filter { it != lastStyle }
        val w = mapOf("talkover" to 4, "intro" to 3, "silent" to 2)
        return weightedPick(all.map { it to (w[it] ?: 1) })
    }

    // ---------------------------------------------------------------- writing and voicing a break
    private suspend fun buildBreak(style: String, forUri: String, immediate: Boolean, duo: Boolean = false) {
        building = true
        try {
            if (!loadBrain()) { buildRetryAt = nowMs() + 60000; return }
            if (style == "silent") warmStingers()
            // the station's named after whatever's playing; if that changed since her last break, she welcomes you to the new one
            val station = stationName
            val before = stationAtLastBreak
            stationAtLastBreak = station
            val switched = if (before.isNotEmpty() && before != station && before != Station.FALLBACK && station != Station.FALLBACK) before else null
            val ctx = Ctx(now.track, Spotify.nextTrack(), station, stationNote, switched)
            // sometimes it's Cara and Scratch together (rolled when the break was planned)
            if (duo && buildDuo(style, ctx, forUri, immediate)) return
            val text = Brain.writeBreak(style, ctx, logger)
            addLog("[DJ:$style] $text")
            line = text
            lineStyle = style
            try {
                val data = elevenLabsTTS(text)
                val file = File(app.cacheDir, "dj_${nowMs()}_${randInt(0, 999)}.mp3")
                file.writeBytes(data)
                ready(file, style, forUri, immediate)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addLog("Could not make her voice: ${e.message}. Trying again in a few seconds.")
                buildRetryAt = nowMs() + 20000
                if (queued != null) queued = null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Couldn't write the break: ${e.message}. Trying again in a few seconds.")
            buildRetryAt = nowMs() + 20000
        } finally {
            building = false
        }
    }

    /** Cara and Scratch together: writes their exchange, voices each line in its own voice and joins it into one clip.
     *  Returns false if it couldn't, and Cara takes the break solo instead. */
    private suspend fun buildDuo(style: String, ctx: Ctx, forUri: String, immediate: Boolean): Boolean {
        val co = Brain.coShort
        if (Config.geminiKey.isEmpty()) {
            addLog("[$co needs a Gemini key to write his lines, so Cara takes it solo]")
            return false
        }
        val script = Brain.writeDuo(style, ctx, logger)
        if (script.size < 2) {
            addLog("[$co couldn't make it this time, so Cara takes it solo]")
            return false
        }
        val shown = script.joinToString("\n") { (who, text) -> (if (who == "CARA") "Cara" else co) + ": " + text }
        addLog("[DUO:$style]\n$shown")
        val file = try {
            Duo.render(script, app.cacheDir)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Could not make their voices: ${e.message}. Cara takes it solo.")
            return false
        }
        line = shown
        lineStyle = style
        ready(file, style, forUri, immediate)
        return true
    }

    /** The clip is made: work out when it goes (talk-overs end with the song, intros come in quick), then play it or hold it. */
    private suspend fun ready(file: File, style: String, forUri: String, immediate: Boolean) {
        val ms = if (file.name.endsWith(".wav")) Duo.wavMs(file) else DJAudio.duration(file)
        val p = Prepared(
            file, style, forUri,
            pauseMs = randInt(700, 1100),
            talkMs = maxOf(4000, minOf(25000, ms - randInt(400, 1500))),
            introAtMs = randInt(400, 1500),
        )
        if (immediate) {
            busy = true
            perform(p)
        } else prepared = p
    }

    // ---------------------------------------------------------------- doing the transition
    private suspend fun perform(p: Prepared, late: Boolean = false) {
        busy = true
        try {
            val voiceVol = Config.djVolume / 100f
            val onSilence = Silence.isSilence(now.uri)
            // a silent break, or a talk-over that missed its moment (the next song already started): the music stops while
            // she talks. Android can pause Spotify any time, so this works with the screen off too
            val silentBreak = onSilence || p.style == "silent" || (late && p.style == "talkover")
            val items = mutableListOf<Clip>()
            if (silentBreak && randInt(0, 99) < Config.stingerChance) {
                pickStinger()?.let {
                    addLog("[stinger before Cara]")
                    items.add(it)
                }
            }
            items.add(Clip(file = p.file, volume = voiceVol))

            if (onSilence) {
                // the silent track is playing, so the music has really stopped: a beat of quiet, she talks, then on to the next song
                val into = now.currentProgressMs
                if (into < 600) delay((600 - into).toLong())
                val keeper = keepSilenceGoing()
                speaking = true
                try {
                    audio.speak(items)
                } finally {
                    speaking = false
                    keeper.cancel()
                    withContext(NonCancellable) { leaveSilence() }
                }
            } else if (silentBreak) {
                // no silent track this time: pause Spotify while she talks, then carry on
                val device = now.deviceID
                val uri = now.uri
                val st = Spotify.pause()
                val paused = ok(st)
                if (!paused) addLog("[couldn't pause Spotify (it said $st), so she talks over the music]")
                speaking = true
                try {
                    audio.speak(items)
                } finally {
                    speaking = false
                    if (paused) withContext(NonCancellable) {
                        delay(200)
                        if (late) {
                            // the next song had only just started when she cut in: play it again from the top
                            Spotify.seek(0)
                            delay(300)
                        } else {
                            // the old song was about a second from its end: if it's still the one playing, jump on to the next song
                            val cur = Spotify.poll()
                            if (cur != null && cur.uri == uri) {
                                Spotify.skipNext()
                                delay(400)
                            }
                        }
                        resumeMusic(device)
                    }
                }
            } else {
                speaking = true
                try {
                    audio.speak(items)      // Android turns the Spotify app down while she talks
                } finally {
                    speaking = false
                }
            }
        } finally {
            p.file.delete()
            busy = false
            lastPoll = 0L
        }
    }

    // ---------------------------------------------------------------- pop-in
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
            if (!loadBrain()) throw Exception("her brain didn't load")
            val text = Brain.writePopin(t, stationName, logger)
            addLog("[POP-IN] $text")
            val data = elevenLabsTTS(text)
            val file = File(app.cacheDir, "popin_${nowMs()}_${randInt(0, 999)}.mp3")
            file.writeBytes(data)
            popinFile = file
            line = text
            lineStyle = "pop-in"
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
            speaking = true
            audio.speak(listOf(Clip(file = file, volume = Config.djVolume / 100f)))      // Android turns the Spotify app down while she talks
        } finally {
            speaking = false
            file.delete()
            busy = false
            lastPoll = 0L
        }
    }

    private fun dropPopin() {
        popinFile?.delete()
        popinFile = null; popinUri = null; popinArmed = false; popinForce = false
    }

    /** Gets the music going again, and keeps trying for up to two minutes: Spotify is often busy for a second right after
     *  a skip, or asks us to slow down. */
    private suspend fun resumeMusic(device: String?) {
        val started = nowMs()
        var attempt = 0
        var warned = false
        var noDeviceLogged = false
        while (nowMs() - started < 120_000) {
            val cur = Spotify.poll()
            if (cur != null && cur.isPlaying) return
            // only ever this phone: never another device such as a speaker or soundbar
            val dev = Spotify.phoneDevice() ?: device
            if (dev != null) {
                var status = 0
                if (attempt % 4 == 3) {
                    Spotify.transfer(dev)          // every few tries: wake the Spotify app on this phone
                    delay(1000)
                } else {
                    status = Spotify.play(dev)
                    delay(800)
                }
                if (status >= 400 && status != 403 && !warned) {
                    warned = true
                    addLog("Spotify said $status when restarting the music. Still trying...")
                }
            } else {
                if (!noDeviceLogged) { noDeviceLogged = true; addLog("Can't find this phone in Spotify yet. Open the Spotify app, then tap PLAY.") }
                delay(1500)
            }
            val wait = Spotify.blockedUntil - nowMs()          // Spotify asked us to slow down: wait it out
            if (wait > 0) delay(minOf(wait + 300, 8000))
            attempt++
        }
        addLog("Spotify didn't restart by itself. Tap PLAY.")
    }

    // ---------------------------------------------------------------- player buttons
    /** Where to send "play": whatever is playing now, otherwise this phone. */
    private suspend fun playTarget(): String? {
        val d = now.deviceID
        if (!d.isNullOrEmpty()) return d
        return Spotify.phoneDevice()
    }

    private fun ok(status: Int) = status in 200..299

    /** Explain a failed Spotify command in plain words. */
    private fun report(status: Int) {
        when {
            status in 200..299 -> return
            status == 404 -> {
                noDevice = true
                Toasts.show("Open Spotify on this phone first", "exclamationmark.triangle.fill")
            }
            status == 403 -> Toasts.show("Spotify said no. Premium is needed for this", "exclamationmark.triangle.fill")
            status == 401 -> Toasts.show("Spotify login expired. Reconnect in Settings", "exclamationmark.triangle.fill")
            status == 429 -> Toasts.show("Spotify says slow down. Try again in a moment", "hourglass")
            status == 0 -> Toasts.show("Couldn't reach Spotify. Check your connection", "wifi.exclamationmark")
            else -> Toasts.show("Spotify couldn't do that ($status)", "exclamationmark.triangle.fill")
        }
    }

    suspend fun togglePlay() {
        if (now.isPlaying) {
            now = now.copy(progressMs = now.currentProgressMs, stamp = nowMs(), isPlaying = false)
            hold(playing = false)
            val st = Spotify.pause()
            if (!ok(st) && st != 403) {          // 403 here usually just means it was already paused
                dropHold()
                now = now.copy(isPlaying = true)
                report(st)
            }
        } else {
            now = now.copy(progressMs = now.currentProgressMs, stamp = nowMs(), isPlaying = true)
            hold(playing = true)
            var st = Spotify.play(now.deviceID)
            if (st == 404 || st == 0) {
                Spotify.phoneDevice()?.let { phone -> st = Spotify.play(phone) }
            }
            if (!ok(st)) {
                dropHold()
                now = now.copy(isPlaying = false)
                report(st)
            }
        }
        poke()
    }

    suspend fun next() {
        val before = upNext
        val n = upNext.firstOrNull()
        if (n != null) {
            pendingItem = if (Silence.isSilence(n.uri)) Silence.caraItem(n.uri, n.durationMs, stationName) else n
            pendingSince = nowMs()
            upNext = upNext.drop(1)
        }
        val st = Spotify.skipNext()
        if (!ok(st)) {
            pendingItem = null
            upNext = before
            report(st)
        }
        poke()
    }

    suspend fun previous() {
        if (now.currentProgressMs > 4000) {
            seek(0)
            return
        }
        val st = Spotify.skipPrevious()
        if (!ok(st)) report(st)
        poke()
    }

    suspend fun toggleShuffle() {
        val on = !now.shuffle
        now = now.copy(shuffle = on)
        hold(shuffle = on)
        val st = Spotify.setShuffle(on)
        if (!ok(st)) {
            dropHold()
            now = now.copy(shuffle = !on)
            report(st)
            return
        }
        poke()
        delay(700)
        refreshQueue()
    }

    suspend fun cycleRepeat() {
        val old = now.repeatMode
        val next = when (old) { "off" -> "context"; "context" -> "track"; else -> "off" }
        now = now.copy(repeatMode = next)
        hold(repeatMode = next)
        val st = Spotify.setRepeat(next)
        if (!ok(st)) {
            dropHold()
            now = now.copy(repeatMode = old)
            report(st)
        }
        poke()
    }

    suspend fun seek(ms: Int) {
        now = now.copy(progressMs = ms, stamp = nowMs())
        hold(progress = true)
        val st = Spotify.seek(ms)
        if (!ok(st)) {
            dropHold()
            report(st)
        }
        poke()
    }

    /** Play an album / playlist / artist, optionally starting at a song, or shuffled. */
    suspend fun playContext(uri: String, name: String? = null, startAt: String? = null, shuffle: Boolean = false, count: Int = 0, preview: Track? = null) {
        if (name != null) rememberName(name, uri)
        if (preview != null) { pendingItem = preview; pendingSince = nowMs() }
        val dev = playTarget()
        // set shuffle first, so where it starts isn't decided by the old setting
        if (dev != null) Spotify.setShuffle(shuffle, dev)
        // only albums and playlists can start at a chosen song
        val canOffset = uri.contains(":album:") || uri.contains(":playlist:")
        var offsetUri: String? = null
        var position: Int? = null
        if (canOffset) {
            if (startAt != null) offsetUri = startAt
            else if (shuffle && count > 1) position = randInt(0, count - 1)
            else position = 0
        }
        val st = Spotify.startPlayback(uri, offsetUri, position, null, dev)
        if (ok(st)) {
            if (dev == null) Spotify.setShuffle(shuffle)
            now = now.copy(shuffle = shuffle)
            hold(shuffle = shuffle)
        } else {
            pendingItem = null
            report(st)
        }
        poke()
    }

    /** Play a list of songs (Liked Songs, search results, top songs), starting at one of them. */
    suspend fun playTracks(list: List<Track>, startAt: Int, shuffle: Boolean = false, context: String? = null, name: String? = null) {
        if (context != null && name != null) rememberName(name, context)
        val playable = list.filter { it.uri.isNotEmpty() && !it.isLocal }
        if (playable.isEmpty()) return
        var first = playable[0]
        if (shuffle) {
            first = playable.random()
        } else if (startAt >= 0 && startAt < list.size) {
            val picked = list[startAt]
            if (picked.uri.isNotEmpty() && !picked.isLocal) first = picked
        }
        pendingItem = first
        pendingSince = nowMs()
        val dev = playTarget()
        // first try the real collection (Liked Songs), so Spotify carries on through all of it
        if (context != null) {
            if (dev != null) Spotify.setShuffle(shuffle, dev)
            val st = Spotify.startPlayback(context, first.uri, null, null, dev)
            if (ok(st)) {
                if (dev == null) Spotify.setShuffle(shuffle)
                now = now.copy(shuffle = shuffle)
                hold(shuffle = shuffle)
                localStation = name ?: ""
                poke()
                return
            }
        }
        // otherwise hand Spotify the list itself, already in the right order
        val uris = if (shuffle) {
            listOf(first.uri) + playable.filter { it.uri != first.uri }.shuffled().map { it.uri }
        } else {
            val k = maxOf(0, playable.indexOfFirst { it.uri == first.uri })
            playable.drop(k).map { it.uri }
        }
        if (dev != null) Spotify.setShuffle(false, dev)
        val st = Spotify.startPlayback(null, null, null, uris.take(100), dev)
        if (ok(st)) {
            if (dev == null) Spotify.setShuffle(false)
            now = now.copy(shuffle = false)
            hold(shuffle = false)
            // no playlist behind a list of songs, so the station takes the list's name (or Non Stop Pop)
            localStation = name ?: ""
        } else {
            pendingItem = null
            report(st)
        }
        poke()
    }

    suspend fun addToQueue(t: Track) {
        val st = Spotify.addToQueue(t.uri, now.deviceID)
        if (ok(st)) {
            Toasts.show("Added to Queue", "text.line.last.and.arrowtriangle.forward")
            delay(700)
            refreshQueue()
        } else {
            report(st)
        }
    }

    /** Skip ahead to a song further down "Playing Next". */
    suspend fun skip(to: Int) {
        if (to < 0 || to >= upNext.size || to >= 15) return
        val target = upNext[to]
        pendingItem = if (Silence.isSilence(target.uri)) Silence.caraItem(target.uri, target.durationMs, stationName) else target
        pendingSince = nowMs()
        for (i in 0..to) {
            val st = Spotify.skipNext()
            if (!ok(st)) {
                pendingItem = null
                report(st)
                break
            }
            delay(250)
        }
        poke()
    }

    suspend fun toggleLikeCurrent() {
        val it = now.item
        if (pendingItem != null || it == null || it.uri.isEmpty() || it.isLocal) return
        val want = !(currentLiked ?: false)
        currentLiked = want
        val done = Library.setLiked(it, want)
        if (!done) currentLiked = !want
    }

    suspend fun loadDevices() {
        devices = Spotify.devices()
    }

    suspend fun transfer(d: Device) {
        val st = Spotify.transfer(d.id)
        if (!ok(st)) report(st)
        poke()
        delay(900)
        loadDevices()
    }

    // ---------------------------------------------------------------- sleep timer
    fun setSleep(minutes: Int?) {
        sleepAtTrackEnd = false
        if (minutes != null) {
            sleepAt = nowMs() + minutes * 60_000L
            keepAlive(true)                  // keeps the app going in the background so the timer can fire
            Toasts.show("Sleep timer: $minutes minutes", "moon.zzz.fill")
        } else {
            sleepAt = null
            if (!running) keepAlive(false)
            Toasts.show("Sleep timer off", "moon.zzz")
        }
    }

    fun setSleepAtEndOfSong() {
        sleepAt = null
        sleepAtTrackEnd = true
        keepAlive(true)
        Toasts.show("Stopping after this song", "moon.zzz.fill")
    }

    private suspend fun checkSleep() {
        val s = sleepAt
        if (s != null && nowMs() >= s) {
            sleepAt = null
            sleepNow()
        } else if (sleepAtTrackEnd && now.isPlaying && now.hasItem && now.remainingMs < 1500) {
            sleepAtTrackEnd = false
            sleepNow()
        }
    }

    private suspend fun sleepNow() {
        addLog("Sleep timer: goodnight.")
        if (running) stop()
        Spotify.pause()
        now = now.copy(progressMs = now.currentProgressMs, stamp = nowMs(), isPlaying = false)
        keepAlive(false)
        poke()
    }

    suspend fun changeCity(city: String) {
        val c = city.trim()
        if (c.isEmpty() || c == Config.city) return
        Config.city = c
        val g = geocodeCity(c)
        if (g != null) { Config.lat = g.first; Config.lon = g.second }
        else addLog("Couldn't find that town's location; weather may be for the old town.")
    }

    fun logout() {
        if (running) stop()
        Spotify.logout()
        loggedIn = false
        problem = ""
        connected = false
        now = Playback()
        upNext = emptyList()
        shownUri = ""
        Library.clear()
    }
}
