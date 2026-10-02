package com.cara.dj

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- the cover, name and line under it (albums and playlists)
@Composable
private fun CoverHeader(art: String, title: String, sub: String, onSub: (() -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp).padding(top = 4.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(art, Modifier.padding(bottom = 12.dp).size(250.dp).shadow(24.dp, RoundedCornerShape(16.dp)), px = 800, corner = 16.dp)
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(sub, color = Color.White.copy(alpha = 0.78f), fontSize = 19.sp, textAlign = TextAlign.Center,
            modifier = if (onSub != null) Modifier.tap(onClick = onSub) else Modifier)
        extra()
    }
}

@Composable
private fun SavedButton(uri: String, toggle: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved = Library.savedState[uri] ?: false
    BarButton(if (saved) "checkmark.circle.fill" else "plus.circle", if (saved) "Saved" else "Save") {
        Haptics.tap(); scope.launch { toggle() }
    }
}

// ---------------------------------------------------------------- album
class AlbumModel(seed: Album) {
    var album by mutableStateOf(seed)
    var tracks by mutableStateOf(listOf<Track>())
    var total by mutableStateOf(0)
    var copyright by mutableStateOf("")
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)

    suspend fun load() {
        if (loaded) return
        failed = false
        val r = Spotify.album(album.id) ?: run { failed = true; return }
        album = r.album
        var list = r.tracks
        total = r.total
        copyright = r.copyright
        while (list.size < total) {
            val more = Spotify.albumTracks(album, list.size)
            if (more.isEmpty()) break
            list = list + more
        }
        tracks = list
        loaded = true
        Library.checkLiked(list)
        Library.checkSaved(album.uri)
    }

    val lengthMs: Int get() = tracks.sumOf { it.durationMs }
}

@Composable
fun AlbumScreen(entry: Entry, seed: Album) {
    val model = remember { (entry.model as? AlbumModel) ?: AlbumModel(seed).also { entry.model = it } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    val a = model.album
    Page(
        inlineTitle = a.name, back = true, art = a.artMid,
        onRefresh = { model.loaded = false; model.load() },
        actions = {
            SavedButton(a.uri) { Library.toggleSaved(a.uri, album = a) }
            a.shareURL?.let { u -> BarButton("square.and.arrow.up", "Share") { shareLink(u) } }
        },
    ) {
        item(key = "head") {
            CoverHeader(a.art.ifEmpty { a.artMid }, a.name, a.artist, onSub = if (a.artistId.isEmpty()) null else {
                { Router.open(Route.ArtistPage(Artist(id = a.artistId, uri = "spotify:artist:" + a.artistId, name = a.artist))) }
            }) {
                Text(
                    listOf(a.typeLabel, a.year).filter { it.isNotEmpty() }.joinToString(" · ").uppercase(),
                    color = Theme.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item(key = "buttons") {
            Box(Modifier.padding(bottom = 14.dp)) {
                PlayShuffleButtons(
                    play = { scope.launch { Engine.playContext(a.uri, name = a.name, preview = model.tracks.firstOrNull()) } },
                    shuffle = { scope.launch { Engine.playContext(a.uri, name = a.name, shuffle = true, count = maxOf(model.tracks.size, a.totalTracks)) } },
                )
            }
        }
        if (!model.loaded && !model.failed) item(key = "loading") { LoadingRow() }
        if (model.failed) item(key = "failed") { EmptyNote("wifi.exclamationmark", "Couldn't load this album", "Pull down to try again.") }
        itemsIndexed(model.tracks, key = { i, t -> "$i-${t.uri}" }) { i, t ->
            TrackRow(t, number = if (t.trackNumber > 0) t.trackNumber else i + 1, showAlbumInMenu = false) {
                scope.launch { Engine.playContext(a.uri, name = a.name, startAt = t.uri, preview = t) }
            }
        }
        if (model.loaded) item(key = "foot") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Theme.hPad).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val n = model.tracks.size
                if (a.release.length > 4) Text(prettyDate(a.release), color = Theme.text2, fontSize = 13.sp)
                Text("$n song${if (n == 1) "" else "s"}, ${formatLength(model.lengthMs)}", color = Theme.text2, fontSize = 13.sp)
                if (model.copyright.isNotEmpty()) Text(model.copyright, color = Theme.text2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- artist
class ArtistModel(seed: Artist) {
    var artist by mutableStateOf(seed)
    var top by mutableStateOf(listOf<Track>())
    var albums by mutableStateOf(listOf<Album>())
    var singles by mutableStateOf(listOf<Album>())
    var bio by mutableStateOf("")
    var loaded by mutableStateOf(false)

    suspend fun load() {
        if (loaded) return
        Spotify.artist(artist.id)?.let { artist = it }
        val a = artist
        coroutineScope {
            val t = async { Spotify.artistTopTracks(a) }
            val al = async { Spotify.artistAlbums(a.id, "album") }
            val sg = async { Spotify.artistAlbums(a.id, "single") }
            val b = async { try { wikiLookup("${a.name} musician band singer", listOf(a.name)) } catch (e: Exception) { null } }
            top = t.await()
            albums = al.await()
            singles = sg.await()
            bio = b.await() ?: ""
        }
        loaded = true
        Library.checkSaved(artist.uri)
        Library.checkLiked(top)
    }
}

@Composable
fun ArtistScreen(entry: Entry, seed: Artist) {
    val model = remember { (entry.model as? ArtistModel) ?: ArtistModel(seed).also { entry.model = it } }
    val scope = rememberCoroutineScope()
    var bioOpen by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    val a = model.artist
    Page(
        inlineTitle = a.name, back = true, art = a.imageMid, underBar = true,
        onRefresh = { model.loaded = false; model.load() },
        actions = {
            val following = Library.savedState[a.uri] ?: false
            Text(
                if (following) "Following" else "Follow", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.press { Haptics.tap(); scope.launch { Library.toggleSaved(a.uri, artist = a) } }
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        },
    ) {
        item(key = "head") {
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                Artwork(a.image.ifEmpty { a.imageMid }, Modifier.fillMaxSize(), px = 1000, corner = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Theme.hPad).padding(bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
                    Text(a.name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 40.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(54.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(Color.White)
                            .press(0.9f) { Haptics.firm(); scope.launch { Engine.playContext(a.uri, name = a.name, preview = model.top.firstOrNull()) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(sym("play.fill"), contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(28.dp).offset(x = 1.dp)) }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
        if (model.top.isNotEmpty()) {
            item(key = "top-h") { Box(Modifier.padding(bottom = 6.dp)) { SectionHeader("Top Songs") } }
            itemsIndexed(model.top.take(10), key = { i, t -> "t$i-${t.uri}" }) { i, t ->
                TrackRow(t) { scope.launch { Engine.playTracks(model.top, startAt = i, name = a.name) } }
            }
            item(key = "top-gap") { Spacer(Modifier.height(26.dp)) }
        }
        if (model.albums.isNotEmpty()) item(key = "albums") {
            Column(Modifier.padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Albums")
                Carousel { items(model.albums) { al -> AlbumCard(al, subtitle = al.year) } }
            }
        }
        if (model.singles.isNotEmpty()) item(key = "singles") {
            Column(Modifier.padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Singles & EPs")
                Carousel { items(model.singles) { al -> AlbumCard(al, 140.dp, al.year) } }
            }
        }
        if (model.bio.isNotEmpty() || a.genres.isNotEmpty()) item(key = "about") {
            Column(
                Modifier.padding(horizontal = Theme.hPad).padding(bottom = 26.dp).fillMaxWidth().glass(22.dp).padding(20.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("About", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (a.genres.isNotEmpty()) {
                    Text(
                        a.genres.take(4).joinToString(" · ") { g -> g.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } },
                        color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (model.bio.isNotEmpty()) {
                    Text(model.bio, color = Theme.text2, fontSize = 17.sp, maxLines = if (bioOpen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                    if (model.bio.length > 260) {
                        Text(if (bioOpen) "Less" else "More", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.tap { bioOpen = !bioOpen })
                    }
                }
            }
        }
        if (!model.loaded) item(key = "loading") { LoadingRow() }
    }
}

// ---------------------------------------------------------------- playlist
class PlaylistModel(seed: Playlist) {
    var playlist by mutableStateOf(seed)
    var tracks by mutableStateOf(listOf<Track>())
    var total by mutableStateOf(seed.total)
    var canList by mutableStateOf(true)
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private var nextOffset = 0
    private var loadingMore = false

    suspend fun load() {
        if (loaded) return
        failed = false
        val r = Spotify.playlist(playlist.id)
        if (r == null) { failed = true; loaded = true; return }
        playlist = r.playlist
        tracks = r.tracks
        total = r.total
        canList = r.canList
        nextOffset = r.rows
        loaded = true
        Library.checkSaved(playlist.uri)
        Library.checkLiked(tracks.take(40))
    }

    suspend fun more() {
        if (!canList || failed || loadingMore || nextOffset >= total) return
        loadingMore = true
        try {
            val (list, rows) = Spotify.playlistItems(playlist.id, nextOffset) ?: return
            if (rows == 0) { total = nextOffset; return }          // Spotify has nothing more
            tracks = tracks + list
            nextOffset += rows
        } finally {
            loadingMore = false
        }
    }
}

@Composable
fun PlaylistScreen(entry: Entry, seed: Playlist) {
    val model = remember { (entry.model as? PlaylistModel) ?: PlaylistModel(seed).also { entry.model = it } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    val p = model.playlist
    val mine = Library.owns(p)
    Page(
        inlineTitle = p.name, back = true, art = p.imageMid,
        onRefresh = { model.loaded = false; model.load() },
        actions = {
            if (!mine) SavedButton(p.uri) { Library.toggleSaved(p.uri, playlist = p) }
            p.shareURL?.let { u -> BarButton("square.and.arrow.up", "Share") { shareLink(u) } }
        },
    ) {
        item(key = "head") {
            CoverHeader(p.image.ifEmpty { p.imageMid }, p.name, p.owner.ifEmpty { "Playlist" }) {
                if (p.about.isNotEmpty()) Text(p.about, color = Theme.text2, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (model.total > 0) Text("${model.total} SONGS", color = Theme.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item(key = "buttons") {
            Box(Modifier.padding(bottom = 14.dp)) {
                PlayShuffleButtons(
                    play = { scope.launch { Engine.playContext(p.uri, name = p.name, preview = model.tracks.firstOrNull()) } },
                    shuffle = { scope.launch { Engine.playContext(p.uri, name = p.name, shuffle = true, count = maxOf(model.total, model.tracks.size)) } },
                )
            }
        }
        if (!model.loaded) item(key = "loading") { LoadingRow() }
        if (model.failed) item(key = "failed") { EmptyNote("wifi.exclamationmark", "Couldn't load this playlist", "Pull down to try again.") }
        if (model.loaded && !model.failed && !model.canList) item(key = "locked") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(sym("lock.fill"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(24.dp))
                Text(
                    "Spotify only lets apps like this one list the songs in playlists you made or collaborate on. You can still play this one.",
                    color = Theme.text2, fontSize = 15.sp, textAlign = TextAlign.Center,
                )
            }
        }
        val list = model.tracks
        itemsIndexed(list, key = { i, t -> "$i-${t.uri}" }) { i, t ->
            TrackRow(t) { scope.launch { Engine.playContext(p.uri, name = p.name, startAt = t.uri, preview = t) } }
            if (i == list.lastIndex) LaunchedEffect(list.size) { model.more() }
        }
        if (model.loaded && !model.failed && model.canList && list.isEmpty()) item(key = "empty") {
            EmptyNote("music.note.list", "Empty Playlist", "Add songs from any song's ••• menu.")
        }
    }
}
