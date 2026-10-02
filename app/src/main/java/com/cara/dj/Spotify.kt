package com.cara.dj

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Talks to Spotify: logging in, reading what's playing, your library, search, and the player buttons. */
object Spotify {
    const val REDIRECT = "caradj://callback"
    /** Everything this app asks permission for. Older logins only had a few, so they get asked once more. */
    val scopeList = listOf(
        "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing",
        "user-read-recently-played", "user-top-read", "user-library-read", "user-library-modify",
        "playlist-read-private", "playlist-read-collaborative", "playlist-modify-private", "playlist-modify-public",
        "user-follow-read", "user-follow-modify",
    )

    val isLoggedIn: Boolean get() = Config.refreshToken.isNotEmpty()

    /** True once the login includes the library / playlist permissions the screens need. */
    val hasAllScopes: Boolean
        get() {
            val granted = Config.grantedScopes.split(" ").filter { it.isNotEmpty() }.toSet()
            return scopeList.all { it in granted }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- login (PKCE, no secret needed) ----------
    fun startLogin(ctx: Context) {
        val clientID = Config.clientID.trim()
        if (clientID.isEmpty()) throw Exception("Add your Spotify Client ID in Settings first.")
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        val verifier = (0 until 64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        Config.verifier = verifier
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val url = "https://accounts.spotify.com/authorize?response_type=code" +
            "&client_id=" + enc(clientID) +
            "&scope=" + enc(scopeList.joinToString(" ")) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=" + enc(challenge)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** A login waiting for Spotify to send the browser back here (caradj://callback). */
    private var pendingLogin: CompletableDeferred<Uri?>? = null
    val loginPending: Boolean get() = pendingLogin?.isCompleted == false

    /** Opens the Spotify login and waits for it to come back. Throws if it fails or you back out of it. */
    suspend fun login(ctx: Context) {
        val d = CompletableDeferred<Uri?>()
        pendingLogin?.complete(null)
        pendingLogin = d
        try {
            startLogin(ctx)
            val uri = withTimeoutOrNull(10 * 60_000L) { d.await() } ?: throw Exception("The Spotify login was closed before it finished.")
            val err = uri.getQueryParameter("error")
            if (err != null) throw Exception("Spotify said: $err")
            val code = uri.getQueryParameter("code") ?: throw Exception("Spotify did not send back a login code.")
            exchangeCode(code)
        } finally {
            if (pendingLogin === d) pendingLogin = null
        }
    }

    /** Spotify sent the browser back here. True when a login was waiting for it. */
    fun onCallback(uri: Uri): Boolean {
        val d = pendingLogin ?: return false
        if (d.isCompleted) return false
        d.complete(uri)
        return true
    }

    /** You came back to the app without finishing the login. */
    fun loginAbandoned() {
        pendingLogin?.complete(null)
    }

    suspend fun exchangeCode(code: String) {
        tokenRequest(
            mapOf(
                "client_id" to Config.clientID.trim(), "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to REDIRECT, "code_verifier" to Config.verifier,
            )
        )
    }

    private suspend fun tokenRequest(params: Map<String, String>) {
        val form = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val (status, data) = fetchBytes(
            "https://accounts.spotify.com/api/token", method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"), body = form.toByteArray(),
        )
        val text = String(data)
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        val access = j?.str("access_token") ?: ""
        if (status != 200 || j == null || access.isEmpty()) throw Exception("Spotify login failed ($status): " + text.take(150))
        Config.accessToken = access
        val r = j.str("refresh_token")
        if (r.isNotEmpty()) Config.refreshToken = r
        val sc = j.str("scope")
        if (sc.isNotEmpty()) Config.grantedScopes = sc
        Config.tokenExpiry = System.currentTimeMillis() / 1000.0 + j.optDouble("expires_in", 3600.0) - 60
    }

    fun logout() {
        Config.accessToken = ""; Config.refreshToken = ""; Config.tokenExpiry = 0.0; Config.grantedScopes = ""
    }

    /** Only one token refresh at a time, even when lots of screens load at once. */
    private val tokenLock = Mutex()

    private fun fresh(): Boolean = Config.accessToken.isNotEmpty() && System.currentTimeMillis() / 1000.0 < Config.tokenExpiry

    private suspend fun validToken(): String {
        if (fresh()) return Config.accessToken
        return tokenLock.withLock {
            if (fresh()) return@withLock Config.accessToken
            if (Config.refreshToken.isEmpty()) throw Exception("Not logged in to Spotify.")
            tokenRequest(mapOf("client_id" to Config.clientID.trim(), "grant_type" to "refresh_token", "refresh_token" to Config.refreshToken))
            Config.accessToken
        }
    }

    // ---------- Web API ----------
    /** After Spotify says "slow down" (429) the app stays quiet for a while instead of making it worse. */
    @Volatile var blockedUntil = 0L

    suspend fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: ByteArray? = null): Pair<Int, ByteArray> {
        if (System.currentTimeMillis() < blockedUntil) return Pair(429, ByteArray(0))
        return try {
            val token = validToken()
            var url = "https://api.spotify.com/v1$path"
            if (query.isNotEmpty()) url += "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            val headers = if (body != null) mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
                else mapOf("Authorization" to "Bearer $token")
            val res = http(url, timeout = 12000, method = method, body = body, headers = headers)
            if (res.status == 429) blockedUntil = System.currentTimeMillis() + (res.retryAfter.coerceIn(20.0, 600.0) * 1000).toLong()
            Pair(res.status, res.data)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair(0, ByteArray(0))
        }
    }

    /** GET something and hand back the JSON object (null unless Spotify said 200). */
    suspend fun getJSON(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val (st, data) = call("GET", path, query)
        if (st != 200) return null
        return try { JSONObject(String(data)) } catch (e: Exception) { null }
    }

    private fun jsonBody(o: JSONObject): ByteArray = o.toString().toByteArray()

    // ---------- what's playing ----------
    /** The HTTP status of the most recent playback check, so the app can say WHY it couldn't read playback. */
    @Volatile var lastStatus = 0

    suspend fun poll(): Playback? {
        val t0 = System.currentTimeMillis()
        val (status, data) = call("GET", "/me/player")
        val t1 = System.currentTimeMillis()
        lastStatus = status
        if (status == 204) return Playback()          // nothing playing anywhere
        if (status != 200) return null
        val j = try { JSONObject(String(data)) } catch (e: Exception) { return null }
        val dev = j.optJSONObject("device")
        var p = Playback(
            isPlaying = j.optBoolean("is_playing", false),
            progressMs = j.optInt("progress_ms", 0),
            stamp = t0 + (t1 - t0) / 2,
            deviceID = dev?.str("id")?.ifEmpty { null },
            deviceName = dev?.str("name") ?: "",
            deviceType = dev?.str("type") ?: "",
            contextUri = j.optJSONObject("context")?.str("uri") ?: "",
            shuffle = j.optBoolean("shuffle_state", false),
            repeatMode = j.str("repeat_state").ifEmpty { "off" },
        )
        val item = j.optJSONObject("item")
        if (item != null && j.str("currently_playing_type").ifEmpty { "track" } == "track") {
            val uri = realUri(item)
            val t = Track.parse(item)?.copy(uri = uri)
            p = p.copy(hasItem = true, uri = uri, durationMs = item.optInt("duration_ms", 0), item = t,
                track = if (t != null && t.isMusic) t else null)
        }
        return p
    }

    /** Everything waiting to play after the current song. null when Spotify couldn't be asked (so the screen keeps what it had). */
    suspend fun queue(): List<Track>? {
        val j = getJSON("/me/player/queue") ?: return null
        val rows = j.optJSONArray("queue") ?: return emptyList()
        return rows.objects().mapNotNull { row -> Track.parse(row)?.copy(uri = realUri(row)) }
    }

    /** When Spotify swaps a track for another version of it ("relinking"), the silent track can come back under a
     *  different address; this hands back the one that was asked for, so it's still recognised. */
    fun realUri(item: JSONObject): String {
        val uri = item.str("uri")
        val from = item.optJSONObject("linked_from")?.str("uri") ?: ""
        if (from.isNotEmpty() && Silence.isSilence(from)) return from
        return uri
    }

    /** The next real song (for Cara to talk about), skipping her own clips, adverts and the silent track. */
    suspend fun nextTrack(): Track? {
        val q = queue() ?: return null
        val first = q.firstOrNull { !Silence.isSilence(it.uri) } ?: return null
        return if (first.isMusic) first else null
    }

    /** One song's details, and whether this account can play it (some songs aren't available everywhere). */
    suspend fun trackInfo(id: String): Pair<Track, Boolean>? {
        val j = getJSON("/tracks/$id", mapOf("market" to "from_token")) ?: return null
        val t = Track.parse(j) ?: return null
        return Pair(t, j.optBoolean("is_playable", true))
    }

    /** Finds a short silent track this account can definitely play (Spotify says so for your country), preferring the usual
     *  ones, and skipping any that Spotify refused before. A null track with reached = false means Spotify couldn't be asked. */
    suspend fun findSilence(bad: List<String>): Pair<String?, Boolean> {
        var reached = false
        var fallback: String? = null
        for (q in listOf("30 seconds of silence", "silent track", "1 minute of silence")) {
            val j = getJSON("/search", mapOf("q" to q, "type" to "track", "limit" to "10", "market" to "from_token")) ?: continue
            reached = true
            val found = mutableListOf<Pair<String, Boolean>>()
            for (row in j.optJSONObject("tracks")?.optJSONArray("items")?.objects() ?: emptyList()) {
                val t = Track.parse(row) ?: continue
                if (t.uri.isEmpty() || t.uri in bad) continue
                val name = t.title.lowercase()
                val looksSilent = name.contains("silen") && (name.contains("second") || name.contains("minute") || name.contains("silent track"))
                if (!looksSilent || t.durationMs < 20000 || t.durationMs > 150000) continue
                val playable: Boolean? = if (row.has("is_playable") && !row.isNull("is_playable")) row.optBoolean("is_playable") else null
                if (playable == false) continue
                found.add(Pair(t.uri, playable == true))
            }
            found.firstOrNull { it.second && it.first in Silence.candidates }?.let { return Pair(it.first, true) }
            found.firstOrNull { it.second }?.let { return Pair(it.first, true) }
            if (fallback == null) fallback = found.firstOrNull()?.first
        }
        return Pair(fallback, reached)
    }

    suspend fun devices(): List<Device> {
        val j = getJSON("/me/player/devices") ?: return emptyList()
        return (j.optJSONArray("devices")?.objects() ?: emptyList()).mapNotNull { Device.parse(it) }
    }

    /** This phone's Spotify device (type "Smartphone"). The DJ only ever plays here, never on speakers or other devices. */
    suspend fun phoneDevice(): String? {
        val phones = devices().filter { it.type.equals("smartphone", ignoreCase = true) && !it.isRestricted }
        return (phones.firstOrNull { it.isActive } ?: phones.firstOrNull())?.id
    }

    suspend fun transfer(id: String): Int =
        call("PUT", "/me/player", body = jsonBody(JSONObject().put("device_ids", JSONArray().put(id)).put("play", true))).first

    /** The name of the album / playlist / artist the music is playing from. */
    suspend fun contextName(uri: String): String? {
        val parts = Station.key(uri).split(":")
        if (parts.lastOrNull() == "collection") return "Liked Songs"
        if (parts.size != 3) return null
        val id = parts[2]
        val name = when (parts[1]) {
            "playlist" -> getJSON("/playlists/$id", mapOf("fields" to "name"))?.str("name")
            "album" -> getJSON("/albums/$id")?.str("name")
            "artist" -> getJSON("/artists/$id")?.str("name")
            else -> null
        }
        return if (name.isNullOrEmpty()) null else name
    }

    // ---------- player buttons ----------
    private fun dev(device: String?): Map<String, String> = if (device != null) mapOf("device_id" to device) else emptyMap()

    suspend fun pause(): Int = call("PUT", "/me/player/pause").first
    suspend fun play(device: String?): Int = call("PUT", "/me/player/play", dev(device)).first

    /** Start an album / playlist / artist (optionally at a given song), or a list of songs. */
    suspend fun startPlayback(context: String?, offsetUri: String? = null, position: Int? = null, uris: List<String>? = null, device: String?): Int {
        val o = JSONObject()
        if (context != null) o.put("context_uri", context)
        if (uris != null) o.put("uris", JSONArray(uris))
        if (offsetUri != null) o.put("offset", JSONObject().put("uri", offsetUri))
        else if (position != null) o.put("offset", JSONObject().put("position", position))
        return call("PUT", "/me/player/play", dev(device), jsonBody(o)).first
    }

    suspend fun setShuffle(on: Boolean, device: String? = null): Int {
        val q = mutableMapOf("state" to on.toString())
        if (device != null) q["device_id"] = device
        return call("PUT", "/me/player/shuffle", q).first
    }
    suspend fun setRepeat(mode: String): Int = call("PUT", "/me/player/repeat", mapOf("state" to mode)).first
    suspend fun seek(ms: Int): Int = call("PUT", "/me/player/seek", mapOf("position_ms" to ms.toString())).first
    suspend fun skipNext(): Int = call("POST", "/me/player/next").first
    suspend fun skipPrevious(): Int = call("POST", "/me/player/previous").first

    suspend fun addToQueue(uri: String, device: String?): Int {
        val q = mutableMapOf("uri" to uri)
        if (device != null) q["device_id"] = device
        return call("POST", "/me/player/queue", q).first
    }

    // ---------- you and your library ----------
    suspend fun me(): UserProfile? {
        val j = getJSON("/me") ?: return null
        val id = j.str("id")
        return UserProfile(id, j.str("display_name").ifEmpty { id }, Img.pick(j.optJSONArray("images")).second)
    }

    suspend fun myPlaylists(offset: Int): Pair<List<Playlist>, Int>? {
        val j = getJSON("/me/playlists", mapOf("limit" to "50", "offset" to offset.toString())) ?: return null
        val items = (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Playlist.parse(it) }
        return Pair(items, j.optInt("total", items.size))
    }

    suspend fun savedTracks(offset: Int): Pair<List<Track>, Int>? {
        val j = getJSON("/me/tracks", mapOf("limit" to "50", "offset" to offset.toString())) ?: return null
        val items = (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it.optJSONObject("track")) }
        return Pair(items, j.optInt("total", items.size))
    }

    suspend fun savedAlbums(offset: Int): Pair<List<Album>, Int>? {
        val j = getJSON("/me/albums", mapOf("limit" to "50", "offset" to offset.toString())) ?: return null
        val items = (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Album.parse(it.optJSONObject("album")) }
        return Pair(items, j.optInt("total", items.size))
    }

    suspend fun followedArtists(after: String?): Pair<List<Artist>, String?>? {
        val q = mutableMapOf("type" to "artist", "limit" to "50")
        if (after != null) q["after"] = after
        val page = getJSON("/me/following", q)?.optJSONObject("artists") ?: return null
        val items = (page.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Artist.parse(it) }
        val next = page.optJSONObject("cursors")?.str("after")
        return Pair(items, if (next.isNullOrEmpty()) null else next)
    }

    suspend fun topTracks(range: String = "short_term"): List<Track> {
        val j = getJSON("/me/top/tracks", mapOf("limit" to "20", "time_range" to range)) ?: return emptyList()
        return (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it) }
    }

    suspend fun topArtists(range: String = "medium_term"): List<Artist> {
        val j = getJSON("/me/top/artists", mapOf("limit" to "20", "time_range" to range)) ?: return emptyList()
        return (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Artist.parse(it) }
    }

    suspend fun recentlyPlayed(): List<Track> {
        val j = getJSON("/me/player/recently-played", mapOf("limit" to "50")) ?: return emptyList()
        return (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it.optJSONObject("track")) }
    }

    /** Are these songs / albums / artists / playlists in your library? (40 at a time at most) */
    suspend fun contains(uris: List<String>): List<Boolean>? {
        val list = uris.filter { it.isNotEmpty() }.take(40)
        if (list.isEmpty()) return emptyList()
        val (st, data) = call("GET", "/me/library/contains", mapOf("uris" to list.joinToString(",")))
        if (st != 200) return null
        return try {
            val a = JSONArray(String(data))
            (0 until a.length()).map { a.optBoolean(it, false) }
        } catch (e: Exception) { null }
    }

    /** Like a song, save an album, follow an artist or playlist. */
    suspend fun save(uris: List<String>): Boolean {
        val list = uris.filter { it.isNotEmpty() }.take(40)
        if (list.isEmpty()) return false
        val st = call("PUT", "/me/library", mapOf("uris" to list.joinToString(","))).first
        return st in 200..299
    }

    suspend fun remove(uris: List<String>): Boolean {
        val list = uris.filter { it.isNotEmpty() }.take(40)
        if (list.isEmpty()) return false
        val st = call("DELETE", "/me/library", mapOf("uris" to list.joinToString(","))).first
        return st in 200..299
    }

    suspend fun createPlaylist(name: String): Playlist? {
        val body = JSONObject().put("name", name).put("public", false).put("description", "Made with Cara DJ")
        val (st, data) = call("POST", "/me/playlists", body = jsonBody(body))
        if (st != 200 && st != 201) return null
        return try { Playlist.parse(JSONObject(String(data))) } catch (e: Exception) { null }
    }

    suspend fun addToPlaylist(id: String, uris: List<String>): Boolean {
        val st = call("POST", "/playlists/$id/items", body = jsonBody(JSONObject().put("uris", JSONArray(uris)))).first
        return st == 200 || st == 201
    }

    // ---------- albums, artists, playlists ----------
    class AlbumPage(val album: Album, val tracks: List<Track>, val total: Int, val copyright: String)

    suspend fun album(id: String): AlbumPage? {
        val j = getJSON("/albums/$id") ?: return null
        val al = Album.parse(j) ?: return null
        val page = j.optJSONObject("tracks")
        val tracks = (page?.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it, al) }
        val total = page?.optInt("total", tracks.size) ?: tracks.size
        val copyright = j.optJSONArray("copyrights")?.optJSONObject(0)?.str("text") ?: ""
        return AlbumPage(al, tracks, total, copyright)
    }

    suspend fun albumTracks(al: Album, offset: Int): List<Track> {
        val j = getJSON("/albums/${al.id}/tracks", mapOf("limit" to "50", "offset" to offset.toString())) ?: return emptyList()
        return (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it, al) }
    }

    suspend fun artist(id: String): Artist? = Artist.parse(getJSON("/artists/$id"))

    /** Spotify only hands these out 10 at a time now. */
    suspend fun artistAlbums(id: String, groups: String, offset: Int = 0): List<Album> {
        val j = getJSON("/artists/$id/albums", mapOf("include_groups" to groups, "limit" to "10", "offset" to offset.toString())) ?: return emptyList()
        return (j.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Album.parse(it) }
    }

    /** Spotify took away "Top Songs" for apps like this one, so search for the artist's best-known songs instead. */
    suspend fun artistTopTracks(artist: Artist): List<Track> {
        val j = getJSON("/search", mapOf("q" to "artist:\"${artist.name}\"", "type" to "track", "limit" to "10")) ?: return emptyList()
        val items = (j.optJSONObject("tracks")?.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it) }
        val theirs = items.filter { artist.id in it.artistIds }
        return theirs.ifEmpty { items }
    }

    class PlaylistPage(val playlist: Playlist, val tracks: List<Track>, val total: Int, val canList: Boolean, val rows: Int)

    /** A playlist. Spotify only lists the songs of playlists you made or collaborate on. */
    suspend fun playlist(id: String): PlaylistPage? {
        val j = getJSON("/playlists/$id") ?: return null
        val p = Playlist.parse(j) ?: return null
        val page = j.optJSONObject("items") ?: j.optJSONObject("tracks")
        val rows = page?.optJSONArray("items")?.objects()
        val tracks = (rows ?: emptyList()).mapNotNull { Track.parse(it.optJSONObject("item") ?: it.optJSONObject("track")) }
        val total = page?.optInt("total", p.total) ?: p.total
        return PlaylistPage(p, tracks, total, rows != null, rows?.size ?: 0)
    }

    /** The next page of a playlist. The second value is how many entries Spotify sent (some may be podcasts or gone, and get skipped). */
    suspend fun playlistItems(id: String, offset: Int): Pair<List<Track>, Int>? {
        val j = getJSON("/playlists/$id/items", mapOf("limit" to "50", "offset" to offset.toString())) ?: return null
        val rows = j.optJSONArray("items")?.objects() ?: emptyList()
        return Pair(rows.mapNotNull { Track.parse(it.optJSONObject("item") ?: it.optJSONObject("track")) }, rows.size)
    }

    // ---------- search ----------
    suspend fun search(q: String, types: List<String>, offset: Int = 0): SearchResults? {
        val j = getJSON("/search", mapOf("q" to q, "type" to types.joinToString(","), "limit" to "10", "offset" to offset.toString())) ?: return null
        val totals = mutableMapOf<String, Int>()
        var tracks = emptyList<Track>()
        var artists = emptyList<Artist>()
        var albums = emptyList<Album>()
        var playlists = emptyList<Playlist>()
        j.optJSONObject("tracks")?.let { t ->
            tracks = (t.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Track.parse(it) }
            totals["track"] = t.optInt("total", 0)
        }
        j.optJSONObject("artists")?.let { t ->
            artists = (t.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Artist.parse(it) }
            totals["artist"] = t.optInt("total", 0)
        }
        j.optJSONObject("albums")?.let { t ->
            albums = (t.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Album.parse(it) }
            totals["album"] = t.optInt("total", 0)
        }
        j.optJSONObject("playlists")?.let { t ->
            playlists = (t.optJSONArray("items")?.objects() ?: emptyList()).mapNotNull { Playlist.parse(it) }
            totals["playlist"] = t.optInt("total", 0)
        }
        return SearchResults(tracks, artists, albums, playlists, totals)
    }
}
