package com.cara.dj

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your Spotify library: playlists, albums, artists, liked songs, plus what you've been playing lately.
 *  Kept on the phone too, so the app opens straight to your stuff. */
object Library {
    var me by mutableStateOf<UserProfile?>(null)
    var playlists by mutableStateOf(listOf<Playlist>())
    var playlistsTotal by mutableStateOf(0)
    var albums by mutableStateOf(listOf<Album>())
    var albumsTotal by mutableStateOf(0)
    var artists by mutableStateOf(listOf<Artist>())
    var artistsNext by mutableStateOf<String?>(null)
    var liked by mutableStateOf(listOf<Track>())
    var likedTotal by mutableStateOf(0)
    var recent by mutableStateOf(listOf<Track>())
    var topTracks by mutableStateOf(listOf<Track>())
    var topArtists by mutableStateOf(listOf<Artist>())
    /** uri -> is it a liked song */
    val likedState = mutableStateMapOf<String, Boolean>()
    /** album / artist / playlist uri -> is it saved / followed */
    val savedState = mutableStateMapOf<String, Boolean>()
    var loaded by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var needsReconnect by mutableStateOf(false)
    private var lastLoad = 0L
    private val busyLoading = mutableSetOf<String>()
    private var cacheFile: File? = null
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        cacheFile = File(ctx.cacheDir, "cara-library-v2.json")
        loadCache()
        needsReconnect = Spotify.isLoggedIn && !Spotify.hasAllScopes
    }

    // ---------- handy lists for the screens ----------
    /** Albums you've been playing lately (newest first, no repeats). */
    val recentAlbums: List<Album>
        get() {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<Album>()
            for (t in recent) {
                if (t.albumId.isEmpty() || t.albumId in seen) continue
                seen.add(t.albumId)
                out.add(t.albumRef)
                if (out.size >= 14) break
            }
            return out
        }

    /** Playlists you can add songs to. */
    val editablePlaylists: List<Playlist>
        get() {
            val mine = me?.id ?: ""
            return playlists.filter { it.ownerId == mine || it.collaborative }
        }

    fun owns(p: Playlist): Boolean {
        val mine = me?.id ?: ""
        return mine.isNotEmpty() && (p.ownerId == mine || p.collaborative)
    }

    // ---------- loading ----------
    suspend fun loadAll(force: Boolean = false) {
        if (!Spotify.isLoggedIn) return
        needsReconnect = !Spotify.hasAllScopes
        if (refreshing) return
        if (!force && loaded && System.currentTimeMillis() - lastLoad < 300_000) return
        refreshing = true
        lastLoad = System.currentTimeMillis()
        try {
            coroutineScope {
                val meR = async { Spotify.me() }
                val plR = async { Spotify.myPlaylists(0) }
                val ttR = async { Spotify.topTracks() }
                val taR = async { Spotify.topArtists() }
                val rpR = async { Spotify.recentlyPlayed() }
                meR.await()?.let { me = it }
                plR.await()?.let { playlists = it.first; playlistsTotal = it.second }
                // the silent track the iPhone app uses for silent breaks is not something you listened to
                val tt = ttR.await()
                if (tt.isNotEmpty()) topTracks = tt.filter { !Silence.isSilence(it.uri) }
                val ta = taR.await()
                if (ta.isNotEmpty()) topArtists = ta
                val rp = rpR.await()
                if (rp.isNotEmpty()) recent = rp.filter { !Silence.isSilence(it.uri) }
            }
            coroutineScope {
                val alR = async { Spotify.savedAlbums(0) }
                val arR = async { Spotify.followedArtists(null) }
                val lkR = async { Spotify.savedTracks(0) }
                alR.await()?.let { r ->
                    albums = r.first
                    albumsTotal = r.second
                    for (a in r.first) savedState[a.uri] = true
                }
                arR.await()?.let { r ->
                    artists = r.first
                    artistsNext = r.second
                    for (a in r.first) savedState[a.uri] = true
                }
                lkR.await()?.let { r ->
                    liked = r.first
                    likedTotal = r.second
                    for (t in r.first) likedState[t.uri] = true
                }
            }
            loaded = true
            saveCache()
        } finally {
            refreshing = false
        }
    }

    suspend fun loadMorePlaylists() {
        if (playlists.size >= playlistsTotal || "pl" in busyLoading) return
        busyLoading.add("pl")
        try {
            val r = Spotify.myPlaylists(playlists.size) ?: return
            val have = playlists.map { it.id }.toSet()
            playlists = playlists + r.first.filter { it.id !in have }
            playlistsTotal = r.second
        } finally {
            busyLoading.remove("pl")
        }
    }

    /** Every page of your playlists (the "Add to a Playlist" list needs them all). */
    suspend fun loadAllPlaylists() {
        var guard = 0
        while (playlists.size < playlistsTotal && guard < 20) {
            val before = playlists.size
            loadMorePlaylists()
            if (playlists.size == before) break
            guard++
        }
    }

    suspend fun loadMoreLiked() {
        if (liked.size >= likedTotal || "lk" in busyLoading) return
        busyLoading.add("lk")
        try {
            val r = Spotify.savedTracks(liked.size) ?: return
            liked = liked + r.first
            likedTotal = r.second
            for (t in r.first) likedState[t.uri] = true
        } finally {
            busyLoading.remove("lk")
        }
    }

    suspend fun loadMoreAlbums() {
        if (albums.size >= albumsTotal || "al" in busyLoading) return
        busyLoading.add("al")
        try {
            val r = Spotify.savedAlbums(albums.size) ?: return
            val have = albums.map { it.id }.toSet()
            albums = albums + r.first.filter { it.id !in have }
            albumsTotal = r.second
        } finally {
            busyLoading.remove("al")
        }
    }

    suspend fun loadMoreArtists() {
        val next = artistsNext ?: return
        if ("ar" in busyLoading) return
        busyLoading.add("ar")
        try {
            val r = Spotify.followedArtists(next) ?: return
            val have = artists.map { it.id }.toSet()
            artists = artists + r.first.filter { it.id !in have }
            artistsNext = r.second
        } finally {
            busyLoading.remove("ar")
        }
    }

    // ---------- liking and saving ----------
    suspend fun setLiked(t: Track, on: Boolean): Boolean {
        if (t.uri.isEmpty()) return false
        likedState[t.uri] = on
        val ok = if (on) Spotify.save(listOf(t.uri)) else Spotify.remove(listOf(t.uri))
        if (ok) {
            if (on) {
                if (liked.none { it.uri == t.uri }) { liked = listOf(t) + liked; likedTotal += 1 }
            } else {
                liked = liked.filter { it.uri != t.uri }
                likedTotal = maxOf(0, likedTotal - 1)
            }
            Toasts.show(if (on) "Added to Liked Songs" else "Removed from Liked Songs", if (on) Icons.Filled.Favorite else Icons.Filled.HeartBroken)
            saveCache()
        } else {
            likedState[t.uri] = !on
            Toasts.show(if (needsReconnect) "Reconnect Spotify in Settings first" else "Spotify didn't save that", Icons.Filled.Warning)
        }
        return ok
    }

    /** Ask Spotify which of these songs are liked (so the menus say the right thing). */
    suspend fun checkLiked(tracks: List<Track>) {
        val uris = tracks.map { it.uri }.filter { it.isNotEmpty() && likedState[it] == null }
        var i = 0
        while (i < uris.size) {
            val chunk = uris.subList(i, minOf(i + 40, uris.size))
            val r = Spotify.contains(chunk)
            if (r != null && r.size == chunk.size) for (k in chunk.indices) likedState[chunk[k]] = r[k]
            i += 40
        }
    }

    suspend fun checkSaved(uri: String) {
        if (uri.isEmpty()) return
        Spotify.contains(listOf(uri))?.firstOrNull()?.let { savedState[uri] = it }
    }

    /** Save / unsave an album, follow / unfollow an artist or playlist. */
    suspend fun toggleSaved(uri: String, album: Album? = null, artist: Artist? = null, playlist: Playlist? = null) {
        if (uri.isEmpty()) return
        val want = !(savedState[uri] ?: false)
        savedState[uri] = want
        val ok = if (want) Spotify.save(listOf(uri)) else Spotify.remove(listOf(uri))
        if (!ok) {
            savedState[uri] = !want
            Toasts.show(if (needsReconnect) "Reconnect Spotify in Settings first" else "Spotify didn't save that", Icons.Filled.Warning)
            return
        }
        if (album != null) {
            if (want) { if (albums.none { it.id == album.id }) { albums = listOf(album) + albums; albumsTotal += 1 } }
            else { albums = albums.filter { it.id != album.id }; albumsTotal = maxOf(0, albumsTotal - 1) }
            Toasts.show(if (want) "Added to Library" else "Removed from Library", if (want) Icons.Filled.Check else Icons.Filled.RemoveCircleOutline)
        }
        if (artist != null) {
            if (want) { if (artists.none { it.id == artist.id }) artists = listOf(artist) + artists }
            else artists = artists.filter { it.id != artist.id }
            Toasts.show(if (want) "Following ${artist.name}" else "Unfollowed ${artist.name}", if (want) Icons.Filled.Check else Icons.Filled.RemoveCircleOutline)
        }
        if (playlist != null) {
            if (want) { if (playlists.none { it.id == playlist.id }) { playlists = listOf(playlist) + playlists; playlistsTotal += 1 } }
            else { playlists = playlists.filter { it.id != playlist.id }; playlistsTotal = maxOf(0, playlistsTotal - 1) }
            Toasts.show(if (want) "Added to Library" else "Removed from Library", if (want) Icons.Filled.Check else Icons.Filled.RemoveCircleOutline)
        }
        saveCache()
    }

    suspend fun createPlaylist(name: String): Playlist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        var p = Spotify.createPlaylist(clean)
        if (p == null) {
            Toasts.show(if (needsReconnect) "Reconnect Spotify in Settings first" else "Couldn't make that playlist", Icons.Filled.Warning)
            return null
        }
        if (p.ownerId.isEmpty()) p = p.copy(ownerId = me?.id ?: "")
        playlists = listOf(p) + playlists
        playlistsTotal += 1
        saveCache()
        return p
    }

    suspend fun add(t: Track, p: Playlist): Boolean {
        val ok = Spotify.addToPlaylist(p.id, listOf(t.uri))
        if (ok) {
            playlists = playlists.map { if (it.id == p.id) it.copy(total = it.total + 1) else it }
            Toasts.show("Added to ${p.name}", Icons.AutoMirrored.Filled.PlaylistAdd)
        } else {
            Toasts.show("Couldn't add to ${p.name}", Icons.Filled.Warning)
        }
        return ok
    }

    fun clear() {
        me = null; playlists = emptyList(); albums = emptyList(); artists = emptyList(); liked = emptyList(); recent = emptyList()
        topTracks = emptyList(); topArtists = emptyList(); likedState.clear(); savedState.clear(); loaded = false; needsReconnect = false
        playlistsTotal = 0; albumsTotal = 0; likedTotal = 0; artistsNext = null
        try { cacheFile?.delete() } catch (e: Exception) { }
    }

    // ---------- kept on the phone between launches ----------
    private fun saveCache() {
        val f = cacheFile ?: return
        try {
            val j = JSONObject()
            me?.let { j.put("me", it.toJson()) }
            j.put("playlists", JSONArray(playlists.map { it.toJson() }))
            j.put("playlistsTotal", playlistsTotal)
            j.put("albums", JSONArray(albums.take(100).map { it.toJson() }))
            j.put("albumsTotal", albumsTotal)
            j.put("artists", JSONArray(artists.take(100).map { it.toJson() }))
            j.put("liked", JSONArray(liked.take(100).map { it.toJson() }))
            j.put("likedTotal", likedTotal)
            j.put("recent", JSONArray(recent.map { it.toJson() }))
            j.put("topTracks", JSONArray(topTracks.map { it.toJson() }))
            j.put("topArtists", JSONArray(topArtists.map { it.toJson() }))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) { f.writeText(j.toString()); tmp.delete() }
        } catch (e: Exception) { }
    }

    private fun loadCache() {
        val f = cacheFile ?: return
        try {
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            j.optJSONObject("me")?.let { me = UserProfile.fromJson(it) }
            playlists = (j.optJSONArray("playlists")?.objects() ?: emptyList()).map { Playlist.fromJson(it) }
            playlistsTotal = j.optInt("playlistsTotal", playlists.size)
            albums = (j.optJSONArray("albums")?.objects() ?: emptyList()).map { Album.fromJson(it) }
            albumsTotal = j.optInt("albumsTotal", albums.size)
            artists = (j.optJSONArray("artists")?.objects() ?: emptyList()).map { Artist.fromJson(it) }
            liked = (j.optJSONArray("liked")?.objects() ?: emptyList()).map { Track.fromJson(it) }
            likedTotal = j.optInt("likedTotal", liked.size)
            recent = (j.optJSONArray("recent")?.objects() ?: emptyList()).map { Track.fromJson(it) }
            topTracks = (j.optJSONArray("topTracks")?.objects() ?: emptyList()).map { Track.fromJson(it) }
            topArtists = (j.optJSONArray("topArtists")?.objects() ?: emptyList()).map { Artist.fromJson(it) }
            for (t in liked) likedState[t.uri] = true
            for (a in albums) savedState[a.uri] = true
            for (a in artists) savedState[a.uri] = true
        } catch (e: Exception) { }
    }
}
