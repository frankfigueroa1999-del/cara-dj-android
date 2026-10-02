package com.cara.dj

import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------------------------------------------------------- small JSON helpers
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it, "") }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

// ---------------------------------------------------------------- picking the right picture size out of Spotify's image lists
object Img {
    /** (biggest picture, a ~300px one for lists and small cards) */
    fun pick(arr: JSONArray?): Pair<String, String> {
        val found = arr.objects().mapNotNull { o ->
            val u = o.str("url")
            if (u.isEmpty()) null else Pair(u, o.optInt("width", 0))
        }.sortedByDescending { it.second }
        val first = found.firstOrNull() ?: return Pair("", "")
        var mid = first.first
        for (f in found) if (f.second >= 250) mid = f.first
        return Pair(first.first, mid)
    }
}

// ---------------------------------------------------------------- a song
data class Track(
    val sid: String = "",
    val uri: String = "",
    val title: String = "",
    val artist: String = "",
    val artists: List<String> = emptyList(),
    val artistId: String = "",
    val artistIds: List<String> = emptyList(),
    val album: String = "",
    val albumId: String = "",
    val year: String = "",
    val release: String = "",
    val art: String = "",
    val artMid: String = "",
    val durationMs: Int = 0,
    val explicit: Boolean = false,
    val isLocal: Boolean = false,
    val trackNumber: Int = 0,
) {
    val id: String get() = if (uri.isEmpty()) "$title|$artist" else uri
    val describe: String get() = "$title by $artist"
    val artistLine: String get() = if (artists.isEmpty()) artist else artists.joinToString(", ")
    val shareURL: String? get() = if (sid.isEmpty()) null else "https://open.spotify.com/track/$sid"

    /** Cara clips, station adverts and jingles in your playlist are not songs she should talk about. */
    val isMusic: Boolean
        get() {
            if (isLocal || artist.isEmpty() || Silence.isSilence(uri)) return false
            val blob = (listOf(title, album) + artists).joinToString(" ").lowercase()
            return notMusic.none { blob.contains(it) }
        }

    val albumRef: Album
        get() = Album(id = albumId, uri = if (albumId.isEmpty()) "" else "spotify:album:$albumId", name = album, artist = artist,
            artistId = artistId, art = art, artMid = artMid, year = year, release = release)

    val artistRef: Artist
        get() = Artist(id = artistId, uri = if (artistId.isEmpty()) "" else "spotify:artist:$artistId", name = artist)

    fun toJson(): JSONObject = JSONObject().put("sid", sid).put("uri", uri).put("title", title).put("artist", artist)
        .put("artists", artists.toJsonArray()).put("artistId", artistId).put("artistIds", artistIds.toJsonArray())
        .put("album", album).put("albumId", albumId).put("year", year).put("release", release).put("art", art)
        .put("artMid", artMid).put("durationMs", durationMs).put("explicit", explicit).put("isLocal", isLocal)
        .put("trackNumber", trackNumber)

    companion object {
        val notMusic = listOf("cara", "non stop pop", "non-stop", "advert", "commercial", "sponsor", "jingle")

        fun fromJson(o: JSONObject): Track = Track(
            sid = o.str("sid"), uri = o.str("uri"), title = o.str("title"), artist = o.str("artist"),
            artists = o.optJSONArray("artists").strings(), artistId = o.str("artistId"), artistIds = o.optJSONArray("artistIds").strings(),
            album = o.str("album"), albumId = o.str("albumId"), year = o.str("year"), release = o.str("release"),
            art = o.str("art"), artMid = o.str("artMid"), durationMs = o.optInt("durationMs", 0), explicit = o.optBoolean("explicit", false),
            isLocal = o.optBoolean("isLocal", false), trackNumber = o.optInt("trackNumber", 0),
        )

        /** A song as Spotify describes it (also inside an album page, where each song leaves the album out). */
        fun parse(d: JSONObject?, album: Album? = null): Track? {
            if (d == null) return null
            val name = d.str("name")
            if (name.isEmpty() && !d.has("name")) return null
            val type = d.str("type")
            if (type.isNotEmpty() && type != "track") return null
            val arts = d.optJSONArray("artists").objects()
            val names = arts.map { it.str("name") }.filter { it.isNotEmpty() }
            val ids = arts.map { it.str("id") }.filter { it.isNotEmpty() }
            var albumName = ""
            var albumId = ""
            var release = ""
            var art = ""
            var artMid = ""
            val al = d.optJSONObject("album")
            if (al != null) {
                albumName = al.str("name")
                albumId = al.str("id")
                release = al.str("release_date")
                val im = Img.pick(al.optJSONArray("images"))
                art = im.first
                artMid = im.second
            } else if (album != null) {
                albumName = album.name
                albumId = album.id
                release = album.release
                art = album.art
                artMid = album.artMid
            }
            return Track(
                sid = d.str("id"), uri = d.str("uri"), title = name, artist = names.firstOrNull() ?: "", artists = names,
                artistId = ids.firstOrNull() ?: "", artistIds = ids, album = albumName, albumId = albumId,
                year = release.take(4), release = release, art = art, artMid = artMid,
                durationMs = d.optInt("duration_ms", 0), explicit = d.optBoolean("explicit", false),
                isLocal = d.optBoolean("is_local", false), trackNumber = d.optInt("track_number", 0),
            )
        }
    }
}

// ---------------------------------------------------------------- the silent track behind silent breaks
/**
 * Right before a silent break the app lines up a short silent track in Spotify, and Cara talks over it.
 * The music really stops, yet Spotify never pauses, and as soon as she's done the app skips on to the next song.
 */
object Silence {
    /** Short silent tracks on Spotify, tried in this order. */
    val candidates = listOf(
        "spotify:track:4KPym5ynDxNeAsgMqubgAt",     // "30 Seconds of Silence! (Silent Track)"
        "spotify:track:0OBG3xvk92jhezHTuyrnSo",     // "30 Seconds of Silence (Reflexion)"
    )
    /** The made-up "cover" address for Cara's own artwork. */
    const val LOGO = "cara:logo"

    fun isSilence(uri: String): Boolean {
        if (uri.isEmpty()) return false
        return uri in candidates || uri == Config.silenceURI || uri in Config.silenceBadList
    }

    /** What the screens show while it plays: Cara on the air, not "30 Seconds of Silence". */
    fun caraItem(uri: String, durationMs: Int, station: String = Station.FALLBACK): Track {
        val who = Station.full(station)
        return Track(uri = uri, title = "Cara", artist = who, artists = listOf(who), album = "On the air",
            art = LOGO, artMid = LOGO, durationMs = durationMs, isLocal = true)
    }
}

// ---------------------------------------------------------------- the station's name
/**
 * The station takes the name of whatever's playing: the playlist, album or artist (or Liked Songs).
 * Non Stop Pop is only the fallback, for when nothing nameable is playing (it's where Cara started out, back in Los Santos).
 */
object Station {
    const val FALLBACK = "Non Stop Pop"

    /** A name that looks right on screen and sounds right out loud: no emojis or odd symbols, not too long. */
    fun clean(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val emoji = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF
            val ch = String(Character.toChars(cp))
            if (!emoji && (Character.isLetterOrDigit(cp) || ch in setOf("'", "’", "&", "!", "?", ".", ",", "-", "+", ":", "/", "(", ")", "$", "#", "@", "%"))) {
                sb.append(if (ch == "’") "'" else ch)
            } else sb.append(' ')
            i += Character.charCount(cp)
        }
        var name = sb.toString().split(" ").filter { it.isNotEmpty() }.joinToString(" ").trim(' ', '-', ':', '/', '.', ',', '+')
        if (name.length > 40) {
            val kept = mutableListOf<String>()
            for (w in name.split(" ")) {
                if ((kept + w).joinToString(" ").length > 36) break
                kept.add(w)
            }
            name = if (kept.isEmpty()) name.take(36) else kept.joinToString(" ")
        }
        return if (name.any { it.isLetterOrDigit() }) name else ""
    }

    /** On air: "Late Night Drives" becomes "Late Night Drives FM"; names that already sound like a station keep theirs. */
    fun full(name: String): String {
        val last = name.split(" ").lastOrNull()?.lowercase() ?: ""
        return if (last in setOf("fm", "am", "radio", "station")) name else "$name FM"
    }

    /** One spelling per playlist / album / artist ("spotify:user:x:playlist:ID" and "spotify:playlist:ID" are the same). */
    fun key(uri: String): String {
        val parts = uri.split(":")
        if ("collection" in parts) return "spotify:collection"
        for (kind in listOf("playlist", "album", "artist", "show")) {
            val i = parts.indexOf(kind)
            if (i >= 0 && i + 1 < parts.size) return "spotify:$kind:${parts[i + 1]}"
        }
        return uri
    }

    /** What kind of thing the station's named after: "playlist", "album", "artist", "collection" or "". */
    fun kind(uri: String): String {
        val k = key(uri)
        if (k == "spotify:collection") return "collection"
        val parts = k.split(":")
        return if (parts.size == 3) parts[1] else ""
    }
}

// ---------------------------------------------------------------- an album
data class Album(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    val art: String = "",
    val artMid: String = "",
    val year: String = "",
    val release: String = "",
    val type: String = "album",
    val totalTracks: Int = 0,
) {
    val typeLabel: String
        get() = when (type) {
            "single" -> if (totalTracks > 3) "EP" else "Single"
            "compilation" -> "Compilation"
            else -> "Album"
        }
    val shareURL: String? get() = if (id.isEmpty()) null else "https://open.spotify.com/album/$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("name", name).put("artist", artist)
        .put("artistId", artistId).put("art", art).put("artMid", artMid).put("year", year).put("release", release)
        .put("type", type).put("totalTracks", totalTracks)

    companion object {
        fun fromJson(o: JSONObject) = Album(o.str("id"), o.str("uri"), o.str("name"), o.str("artist"), o.str("artistId"),
            o.str("art"), o.str("artMid"), o.str("year"), o.str("release"), o.str("type").ifEmpty { "album" }, o.optInt("totalTracks", 0))

        fun parse(d: JSONObject?): Album? {
            if (d == null) return null
            val id = d.str("id")
            val name = d.str("name")
            if (id.isEmpty() || name.isEmpty()) return null
            val arts = d.optJSONArray("artists").objects()
            val im = Img.pick(d.optJSONArray("images"))
            val release = d.str("release_date")
            return Album(
                id = id, uri = d.str("uri").ifEmpty { "spotify:album:$id" }, name = name,
                artist = arts.map { it.str("name") }.filter { it.isNotEmpty() }.joinToString(", "),
                artistId = arts.firstOrNull()?.str("id") ?: "", art = im.first, artMid = im.second,
                year = release.take(4), release = release, type = d.str("album_type").ifEmpty { "album" }.lowercase(),
                totalTracks = d.optInt("total_tracks", 0),
            )
        }
    }
}

// ---------------------------------------------------------------- an artist
data class Artist(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val image: String = "",
    val imageMid: String = "",
    val genres: List<String> = emptyList(),
) {
    val shareURL: String? get() = if (id.isEmpty()) null else "https://open.spotify.com/artist/$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("name", name).put("image", image)
        .put("imageMid", imageMid).put("genres", genres.toJsonArray())

    companion object {
        fun fromJson(o: JSONObject) = Artist(o.str("id"), o.str("uri"), o.str("name"), o.str("image"), o.str("imageMid"),
            o.optJSONArray("genres").strings())

        fun parse(d: JSONObject?): Artist? {
            if (d == null) return null
            val id = d.str("id")
            val name = d.str("name")
            if (id.isEmpty() || name.isEmpty()) return null
            val im = Img.pick(d.optJSONArray("images"))
            return Artist(id = id, uri = d.str("uri").ifEmpty { "spotify:artist:$id" }, name = name, image = im.first,
                imageMid = im.second, genres = d.optJSONArray("genres").strings())
        }
    }
}

// ---------------------------------------------------------------- a playlist
data class Playlist(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val owner: String = "",
    val ownerId: String = "",
    val image: String = "",
    val imageMid: String = "",
    val about: String = "",
    val total: Int = 0,
    val collaborative: Boolean = false,
) {
    val shareURL: String? get() = if (id.isEmpty()) null else "https://open.spotify.com/playlist/$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("name", name).put("owner", owner)
        .put("ownerId", ownerId).put("image", image).put("imageMid", imageMid).put("about", about).put("total", total)
        .put("collaborative", collaborative)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(o.str("id"), o.str("uri"), o.str("name"), o.str("owner"), o.str("ownerId"),
            o.str("image"), o.str("imageMid"), o.str("about"), o.optInt("total", 0), o.optBoolean("collaborative", false))

        fun parse(d: JSONObject?): Playlist? {
            if (d == null) return null
            val id = d.str("id")
            val name = d.str("name")
            if (id.isEmpty() || name.isEmpty()) return null
            val o = d.optJSONObject("owner")
            val ownerId = o?.str("id") ?: ""
            val owner = o?.str("display_name")?.ifEmpty { ownerId } ?: ownerId
            val im = Img.pick(d.optJSONArray("images"))
            // Spotify renamed "tracks" to "items" in 2026; read whichever is there
            val ref = d.optJSONObject("items") ?: d.optJSONObject("tracks")
            return Playlist(id = id, uri = d.str("uri").ifEmpty { "spotify:playlist:$id" }, name = name, owner = owner,
                ownerId = ownerId, image = im.first, imageMid = im.second, about = plain(d.str("description")),
                total = ref?.optInt("total", 0) ?: 0, collaborative = d.optBoolean("collaborative", false))
        }

        private val tags = Regex("<[^>]+>")

        /** Playlist descriptions come with HTML bits in them. */
        fun plain(s: String): String {
            var t = s.replace(tags, "")
            val entities = mapOf("&amp;" to "&", "&quot;" to "\"", "&#x27;" to "'", "&#39;" to "'", "&lt;" to "<", "&gt;" to ">", "&#x2F;" to "/", "&nbsp;" to " ")
            for ((k, v) in entities) t = t.replace(k, v)
            return t.trim()
        }
    }
}

// ---------------------------------------------------------------- somewhere Spotify can play
data class Device(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val isActive: Boolean = false,
    val isRestricted: Boolean = false,
) {
    /** The icon name (same names as the iPhone app's symbols). */
    val symbol: String
        get() = when (type.lowercase()) {
            "smartphone" -> "iphone"
            "computer" -> "laptopcomputer"
            "tablet" -> "ipad"
            "speaker" -> "hifispeaker.fill"
            "tv" -> "tv"
            "automobile" -> "car.fill"
            "gameconsole" -> "gamecontroller.fill"
            "castvideo", "castaudio" -> "tv.and.hifispeaker.fill"
            else -> "speaker.wave.2.fill"
        }

    companion object {
        fun parse(d: JSONObject?): Device? {
            if (d == null) return null
            val id = d.str("id")
            if (id.isEmpty()) return null
            return Device(id, d.str("name").ifEmpty { "Device" }, d.str("type"), d.optBoolean("is_active", false), d.optBoolean("is_restricted", false))
        }
    }
}

// ---------------------------------------------------------------- you
data class UserProfile(val id: String = "", val name: String = "", val image: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("image", image)
    companion object {
        fun fromJson(o: JSONObject) = UserProfile(o.str("id"), o.str("name"), o.str("image"))
    }
}

// ---------------------------------------------------------------- what's playing right now
data class Playback(
    val isPlaying: Boolean = false,
    val hasItem: Boolean = false,
    val uri: String = "",
    /** What's playing, for the screen (songs, Cara clips, adverts, everything). */
    val item: Track? = null,
    /** The same thing, but only when it's a real song: the DJ never talks about her own clips or adverts. */
    val track: Track? = null,
    val durationMs: Int = 0,
    val progressMs: Int = 0,
    val stamp: Long = System.currentTimeMillis(),
    val deviceID: String? = null,
    val deviceName: String = "",
    val deviceType: String = "",
    val contextUri: String = "",
    val shuffle: Boolean = false,
    val repeatMode: String = "off",
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
    val onThisPhone: Boolean get() = deviceType.isEmpty() || deviceType.equals("smartphone", ignoreCase = true)
}

// ---------------------------------------------------------------- search results
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val totals: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

// ---------------------------------------------------------------- browse tiles on the Search page
data class Genre(val id: String, val title: String, val trackQuery: String, val playlistQuery: String, val symbol: String, val hue: Float) {
    companion object {
        val all = listOf(
            Genre("pop", "Pop", "genre:pop", "pop hits", "sparkles", 0.93f),
            Genre("hiphop", "Hip-Hop", "genre:hip-hop", "hip hop", "music.mic", 0.08f),
            Genre("dance", "Dance", "genre:dance", "dance hits", "figure.dance", 0.78f),
            Genre("rnb", "R&B", "genre:r-n-b", "r&b", "heart.fill", 0.62f),
            Genre("rock", "Rock", "genre:rock", "rock classics", "guitars.fill", 0.02f),
            Genre("indie", "Indie", "genre:indie", "indie", "leaf.fill", 0.33f),
            Genre("latin", "Latin", "genre:latin", "latin hits", "sun.max.fill", 0.12f),
            Genre("kpop", "K-Pop", "genre:k-pop", "k-pop", "star.fill", 0.85f),
            Genre("country", "Country", "genre:country", "country hits", "music.quarternote.3", 0.1f),
            Genre("2000s", "2000s", "year:2000-2009", "2000s pop", "opticaldisc.fill", 0.55f),
            Genre("2010s", "2010s", "year:2010-2019", "2010s hits", "headphones", 0.7f),
            Genre("chill", "Chill", "genre:chill", "chill vibes", "moon.stars.fill", 0.5f),
            Genre("workout", "Workout", "genre:work-out", "workout", "flame.fill", 0.04f),
            Genre("party", "Party", "genre:party", "party", "balloon.2.fill", 0.9f),
        )
    }
}

// ---------------------------------------------------------------- little helpers for times
fun formatClock(ms: Int): String {
    val s = maxOf(0, ms / 1000)
    return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
}

fun formatLength(ms: Int): String {
    val mins = maxOf(0, ms / 60000)
    if (mins >= 60) return "${mins / 60} hr ${mins % 60} min"
    return "$mins min"
}

fun prettyDate(s: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)
    if (d == null) s else DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(d)
} catch (e: Exception) { s }
