package com.cara.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Library: your playlists, artists, albums and liked songs. */
@Composable
fun LibraryScreen() {
    val scope = rememberCoroutineScope()
    var askName by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Library.loadAll() }
    Page(
        title = "Library",
        onRefresh = { Library.loadAll(force = true) },
        actions = {
            Box {
                BarButton("plus", "New") { menu = true }
                DarkMenu(menu, { menu = false }) {
                    MenuItem("New Playlist", "music.note.list") { menu = false; askName = true }
                }
            }
            AvatarButton()
        },
    ) {
        if (!Engine.loggedIn) {
            item(key = "connect") { Box(Modifier.padding(top = 12.dp)) { ConnectCard() } }
        } else {
            if (Library.needsReconnect) item(key = "reconnect") { Box(Modifier.padding(top = 6.dp, bottom = 26.dp)) { ReconnectBanner() } }
            item(key = "rows") {
                Column(Modifier.padding(top = 6.dp, bottom = 26.dp).padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp)) {
                    LibraryRow(Route.Playlists, "Playlists", "music.note.list", Library.playlistsTotal, last = false)
                    LibraryRow(Route.Artists, "Artists", "music.mic", Library.artists.size, last = false)
                    LibraryRow(Route.Albums, "Albums", "square.stack", Library.albumsTotal, last = false)
                    LibraryRow(Route.Liked, "Liked Songs", "heart", Library.likedTotal, last = true)
                }
            }
            if (Library.albums.isNotEmpty() || Library.playlists.isNotEmpty()) {
                item(key = "recent-title") { Box(Modifier.padding(bottom = 12.dp)) { SectionHeader("Recently Added") } }
                // albums you saved recently, then your newest playlists
                val recent: List<Route> = Library.albums.take(8).map { Route.AlbumPage(it) } +
                    Library.playlists.take(maxOf(0, 8 - minOf(8, Library.albums.size))).map { Route.PlaylistPage(it) }
                tileGrid(recent.chunked(2), "recent") { r, m ->
                    when (r) {
                        is Route.AlbumPage -> AlbumTile(r.album.name, r.album.artist, r.album.artMid, modifier = m.press { Router.open(r) })
                        is Route.PlaylistPage -> AlbumTile(r.playlist.name, r.playlist.owner, r.playlist.imageMid, modifier = m.press { Router.open(r) })
                        else -> {}
                    }
                }
            }
            if (!Library.loaded && Library.playlists.isEmpty()) item(key = "loading") { LoadingRow() }
        }
    }
    if (askName) NewPlaylistDialog(onDismiss = { askName = false }) { name ->
        askName = false
        scope.launch { Library.createPlaylist(name)?.let { p -> Toasts.show("Made ${p.name}", "music.note.list") } }
    }
}

/** Two tiles to a row, inside a page's list. */
fun <T> LazyListScope.tileGrid(rows: List<List<T>>, key: String, tile: @Composable (T, Modifier) -> Unit) {
    itemsIndexed(rows, key = { i, _ -> "$key-$i" }) { _, row ->
        Row(Modifier.padding(horizontal = Theme.hPad).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { tile(it, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LibraryRow(r: Route, title: String, icon: String, count: Int, last: Boolean) {
    Box(Modifier.fillMaxWidth().height(56.dp).tap { Haptics.tap(); Router.open(r) }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                Icon(sym(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (count > 0) Text("$count", color = Theme.text2, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
            Icon(sym("chevron.right"), contentDescription = null, tint = Theme.text3, modifier = Modifier.size(16.dp))
        }
        if (!last) Box(Modifier.align(Alignment.BottomStart).padding(start = 60.dp).fillMaxWidth().height(0.5.dp).background(Theme.line))
    }
}

@Composable
fun PlaylistsListScreen() {
    var askName by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Page(title = "Playlists", back = true, onRefresh = { Library.loadAll(force = true) }) {
        item(key = "new") {
            Row(
                Modifier.fillMaxWidth().tap { askName = true }.padding(horizontal = Theme.hPad, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).glass(9.dp, 0.08f), contentAlignment = Alignment.Center) {
                    Icon(sym("plus"), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("New Playlist…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        item(key = "liked") {
            MediaRow("Liked Songs", "${Library.likedTotal} songs", "", leading = { LikedArt(56.dp, 9.dp) }) { Router.open(Route.Liked) }
        }
        val list = Library.playlists
        itemsIndexed(list, key = { i, p -> "$i-${p.id}" }) { i, p ->
            MediaRow(p.name, "Playlist · " + p.owner.ifEmpty { "You" }, p.imageMid) { Router.open(Route.PlaylistPage(p)) }
            if (i == list.lastIndex) LaunchedEffect(list.size) { Library.loadMorePlaylists() }
        }
    }
    if (askName) NewPlaylistDialog(onDismiss = { askName = false }) { name ->
        askName = false
        scope.launch { Library.createPlaylist(name) }
    }
}

@Composable
fun AlbumsGridScreen() {
    Page(title = "Albums", back = true, onRefresh = { Library.loadAll(force = true) }) {
        val list = Library.albums
        if (list.isEmpty()) {
            item(key = "empty") { EmptyNote("square.stack", "No Saved Albums", "Albums you save on Spotify (or with the + on an album page) show up here.") }
        } else {
            item(key = "top") { Spacer(Modifier.height(8.dp)) }
            val rows = list.chunked(2)
            itemsIndexed(rows, key = { i, _ -> "albums-$i" }) { i, row ->
                Row(Modifier.padding(horizontal = Theme.hPad).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { a -> AlbumTile(a.name, a.artist, a.artMid, modifier = Modifier.weight(1f).press { Router.open(Route.AlbumPage(a)) }) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                if (i == rows.lastIndex) LaunchedEffect(list.size) { Library.loadMoreAlbums() }
            }
        }
    }
}

@Composable
fun ArtistsListScreen() {
    Page(title = "Artists", back = true, onRefresh = { Library.loadAll(force = true) }) {
        val list = Library.artists
        if (list.isEmpty()) {
            item(key = "empty") { EmptyNote("music.mic", "No Artists Yet", "Artists you follow on Spotify show up here.") }
        } else {
            itemsIndexed(list, key = { i, a -> "$i-${a.id}" }) { i, a ->
                MediaRow(a.name, "Artist", a.imageMid, circle = true) { Router.open(Route.ArtistPage(a)) }
                if (i == list.lastIndex) LaunchedEffect(list.size) { Library.loadMoreArtists() }
            }
        }
    }
}

@Composable
fun LikedSongsScreen() {
    val scope = rememberCoroutineScope()
    fun play(i: Int, shuffle: Boolean) {
        val ctx = Library.me?.id?.takeIf { it.isNotEmpty() }?.let { "spotify:user:$it:collection" }
        scope.launch { Engine.playTracks(Library.liked, startAt = i, shuffle = shuffle, context = ctx, name = "Liked Songs") }
    }
    Page(inlineTitle = "Liked Songs", back = true, onRefresh = { Library.loadAll(force = true) }) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(bottom = 8.dp).shadow(24.dp, RoundedCornerShape(16.dp), ambientColor = Theme.accent, spotColor = Theme.accent)) { LikedArt(230.dp, 16.dp) }
                Text("Liked Songs", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${Library.likedTotal} songs", color = Theme.text2, fontSize = 15.sp)
            }
        }
        item(key = "buttons") { Box(Modifier.padding(bottom = 12.dp)) { PlayShuffleButtons(play = { play(0, false) }, shuffle = { play(0, true) }) } }
        val list = Library.liked
        if (list.isEmpty() && Library.loaded) item(key = "empty") { EmptyNote("heart", "No Liked Songs", "Tap the heart in the player to save songs here.") }
        itemsIndexed(list, key = { i, t -> "$i-${t.uri}" }) { i, t ->
            TrackRow(t) { play(i, false) }
            if (i == list.lastIndex) LaunchedEffect(list.size) { Library.loadMoreLiked() }
        }
    }
}
