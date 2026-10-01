package com.cara.dj

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Track(val title: String, val artist: String, val album: String, val year: String, val art: String = "",
                 val artistId: String = "", val albumId: String = "", val artists: List<String> = emptyList(), val release: String = "", val uri: String = "") {
    val describe: String get() = "$title by $artist"
}

data class Playback(
    val isPlaying: Boolean = false,
    val hasItem: Boolean = false,
    val uri: String = "",
    val track: Track? = null,
    val durationMs: Int = 0,
    val progressMs: Int = 0,
    val stamp: Long = System.currentTimeMillis(),
    val deviceID: String? = null,
    val deviceName: String = "",
    val shuffle: Boolean = false,
    val repeat: String = "off",
) {
    /** Milliseconds left in the song, estimated from the last check. */
    val remainingMs: Int
        get() {
            if (!isPlaying) return Int.MAX_VALUE
            val elapsed = (System.currentTimeMillis() - stamp).toInt()
            return durationMs - (progressMs + elapsed)
        }
    val currentProgressMs: Int
        get() {
            val elapsed = if (isPlaying) (System.currentTimeMillis() - stamp).toInt() else 0
            return minOf(durationMs, progressMs + elapsed)
        }
}

object Spotify {
    const val REDIRECT = "caradj://callback"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state"

    val isLoggedIn: Boolean get() = Config.refreshToken.isNotEmpty()

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
            "&scope=" + enc(SCOPES) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=" + enc(challenge)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
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
        val access = j?.optString("access_token", "") ?: ""
        if (status != 200 || access.isEmpty()) throw Exception("Spotify login failed ($status): " + text.take(150))
        Config.accessToken = access
        val r = j?.optString("refresh_token", "") ?: ""
        if (r.isNotEmpty()) Config.refreshToken = r
        Config.tokenExpiry = System.currentTimeMillis() / 1000.0 + j!!.optDouble("expires_in", 3600.0) - 60
    }

    fun logout() {
        Config.accessToken = ""; Config.refreshToken = ""; Config.tokenExpiry = 0.0
    }

    private suspend fun validToken(): String {
        if (Config.accessToken.isNotEmpty() && System.currentTimeMillis() / 1000.0 < Config.tokenExpiry) return Config.accessToken
        if (Config.refreshToken.isEmpty()) throw Exception("Not logged in to Spotify.")
        tokenRequest(mapOf("client_id" to Config.clientID.trim(), "grant_type" to "refresh_token", "refresh_token" to Config.refreshToken))
        return Config.accessToken
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
            val res = fetchBytes(
                url, timeout = 12000, method = method, body = body,
                headers = if (body != null) mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json") else mapOf("Authorization" to "Bearer $token"),
            )
            if (res.first == 429) blockedUntil = System.currentTimeMillis() + 60_000
            res
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair(0, ByteArray(0))
        }
    }

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
        val item = j.optJSONObject("item")
        val isTrack = j.optString("currently_playing_type", "track") == "track"
        var hasItem = false
        var uri = ""
        var dur = 0
        var track: Track? = null
        if (item != null && isTrack) {
            hasItem = true
            uri = item.optString("uri", "")
            dur = item.optInt("duration_ms", 0)
            track = trackInfo(item)
        }
        return Playback(
            isPlaying = j.optBoolean("is_playing", false),
            hasItem = hasItem, uri = uri, track = track, durationMs = dur,
            progressMs = j.optInt("progress_ms", 0),
            stamp = t0 + (t1 - t0) / 2,
            deviceID = j.optJSONObject("device")?.optString("id", "")?.ifEmpty { null },
            deviceName = j.optJSONObject("device")?.optString("name", "") ?: "",
            shuffle = j.optBoolean("shuffle_state", false),
            repeat = j.optString("repeat_state", "off"),
        )
    }

    private val notMusic = listOf("cara", "non stop pop", "non-stop", "advert", "commercial", "sponsor", "jingle")

    fun trackInfo(item: JSONObject?): Track? {
        if (item == null || item.optBoolean("is_local", false)) return null
        val name = item.optString("name", "")
        if (name.isEmpty()) return null
        val arr = item.optJSONArray("artists")
        val artists = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("name", "") }.filter { it.isNotEmpty() }
        val first = artists.firstOrNull() ?: return null
        val albumObj = item.optJSONObject("album")
        val album = albumObj?.optString("name", "") ?: ""
        val year = (albumObj?.optString("release_date", "") ?: "").take(4)
        val blob = (listOf(name, album) + artists).joinToString(" ").lowercase()
        if (notMusic.any { blob.contains(it) }) return null
        val imgs = albumObj?.optJSONArray("images")
        val art = if (imgs != null && imgs.length() > 0) imgs.optJSONObject(0)?.optString("url", "") ?: "" else ""
        return Track(name, first, album, year, art,
            artistId = arr?.optJSONObject(0)?.optString("id", "") ?: "",
            albumId = albumObj?.optString("id", "") ?: "",
            artists = artists,
            release = albumObj?.optString("release_date", "") ?: "",
            uri = item.optString("uri", ""))
    }

    suspend fun nextTrack(): Track? {
        val (status, data) = call("GET", "/me/player/queue")
        if (status != 200) return null
        val j = try { JSONObject(String(data)) } catch (e: Exception) { return null }
        val q = j.optJSONArray("queue") ?: return null
        if (q.length() == 0) return null
        return trackInfo(q.optJSONObject(0))
    }

    /** This phone's Spotify device (type "Smartphone"). The DJ only ever plays here, never on speakers or other devices. */
    suspend fun phoneDevice(): String? {
        val (status, data) = call("GET", "/me/player/devices")
        if (status != 200) return null
        return try {
            val arr = JSONObject(String(data)).optJSONArray("devices") ?: return null
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            val phones = list.filter { it.optString("type", "").equals("Smartphone", ignoreCase = true) && !it.optBoolean("is_restricted", false) }
            val pick = phones.firstOrNull { it.optBoolean("is_active", false) } ?: phones.firstOrNull()
            pick?.optString("id", "")?.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    suspend fun transfer(id: String) {
        val body = JSONObject().put("device_ids", org.json.JSONArray().put(id)).put("play", true).toString().toByteArray()
        call("PUT", "/me/player", body = body)
    }

    suspend fun playContext(contextUri: String?, trackUri: String?, device: String?) {
        val o = JSONObject()
        if (contextUri != null) o.put("context_uri", contextUri)
        if (trackUri != null) o.put("uris", org.json.JSONArray().put(trackUri))
        call("PUT", "/me/player/play", if (device != null) mapOf("device_id" to device) else emptyMap(), o.toString().toByteArray())
    }
    suspend fun pause() { call("PUT", "/me/player/pause") }
    suspend fun play(device: String?) { call("PUT", "/me/player/play", if (device != null) mapOf("device_id" to device) else emptyMap()) }
    suspend fun setShuffle(on: Boolean) { call("PUT", "/me/player/shuffle", mapOf("state" to on.toString())) }
    suspend fun setRepeat(mode: String) { call("PUT", "/me/player/repeat", mapOf("state" to mode)) }
    suspend fun seek(ms: Int) { call("PUT", "/me/player/seek", mapOf("position_ms" to ms.toString())) }
    suspend fun skipNext() { call("POST", "/me/player/next") }
    suspend fun skipPrevious() { call("POST", "/me/player/previous") }
}
