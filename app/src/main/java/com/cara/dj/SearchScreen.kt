package com.cara.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchScope(val title: String, val apiType: String) {
    ALL("Top Results", "track,artist,album,playlist"),
    SONGS("Songs", "track"),
    ARTISTS("Artists", "artist"),
    ALBUMS("Albums", "album"),
    PLAYLISTS("Playlists", "playlist"),
}

/** What you're searching for. It lives as long as the app, so switching tabs keeps your search. */
object SearchModel {
    var text by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.ALL)
    var results by mutableStateOf(SearchResults())
    var loading by mutableStateOf(false)
    var searchedFor by mutableStateOf("")
    private var offset = 0
    private var reachedEnd = false

    suspend fun run() {
        val q = text.trim()
        if (q.isEmpty()) {
            results = SearchResults(); searchedFor = ""; return
        }
        loading = true
        offset = 0
        reachedEnd = false
        val r = Spotify.search(q, scope.apiType.split(","))
        if (q == text.trim()) {
            results = r ?: SearchResults()
            searchedFor = q
        }
        loading = false
    }

    /** Spotify only sends 10 results at a time now, so lists grow as you scroll. */
    suspend fun more() {
        if (scope == SearchScope.ALL || loading || reachedEnd || searchedFor.isEmpty()) return
        loading = true
        try {
            val next = offset + 10
            if (next >= 1000) { reachedEnd = true; return }
            val r = Spotify.search(searchedFor, listOf(scope.apiType), next) ?: return   // couldn't reach Spotify: scrolling to the end again retries
            offset = next
            results = when (scope) {
                SearchScope.SONGS -> { if (r.tracks.isEmpty()) reachedEnd = true; results.copy(tracks = results.tracks + r.tracks) }
                SearchScope.ARTISTS -> { if (r.artists.isEmpty()) reachedEnd = true; results.copy(artists = results.artists + r.artists) }
                SearchScope.ALBUMS -> { if (r.albums.isEmpty()) reachedEnd = true; results.copy(albums = results.albums + r.albums) }
                SearchScope.PLAYLISTS -> { if (r.playlists.isEmpty()) reachedEnd = true; results.copy(playlists = results.playlists + r.playlists) }
                SearchScope.ALL -> results
            }
        } finally {
            loading = false
        }
    }

    fun remember(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        Config.recentSearches = (listOf(t) + Config.recentSearches.filter { it.lowercase() != t.lowercase() }).take(12)
    }
}

/** Search: browse tiles when empty, results as you type. */
@Composable
fun SearchScreen() {
    val m = SearchModel
    val state = rememberLazyListState()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(m.text + "|" + m.scope.name) {
        delay(320)
        m.run()
    }
    LaunchedEffect(state.isScrollInProgress) { if (state.isScrollInProgress) focus.clearFocus() }
    fun play(t: Track) {
        m.remember(m.text)
        scope.launch { Engine.playTracks(listOf(t), startAt = 0) }
    }
    Page(title = "Search", state = state) {
        item(key = "field") { SearchField(onSubmit = { m.remember(m.text); focus.clearFocus() }) }
        if (m.text.isBlank()) browse() else results(::play)
    }
}

@Composable
private fun SearchField(onSubmit: () -> Unit) {
    val m = SearchModel
    Row(
        Modifier.padding(horizontal = Theme.hPad).padding(bottom = 12.dp).fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sym("magnifyingglass"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (m.text.isEmpty()) Text("Artists, Songs, Albums and More", color = Theme.text2, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = m.text,
                onValueChange = { m.text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Theme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (m.text.isNotEmpty()) {
            Icon(Icons.Filled.Cancel, contentDescription = "Clear", tint = Theme.text2, modifier = Modifier.size(20.dp).tap { m.text = "" })
        }
    }
}

// ---------------------------------------------------------------- before you type
private fun LazyListScope.browse() {
    val recent = Config.recentSearches
    if (recent.isNotEmpty()) item(key = "recent") {
        Column(Modifier.padding(top = 8.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Theme.hPad), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently Searched", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Clear", color = Theme.text2, fontSize = 15.sp, modifier = Modifier.tap { Config.recentSearches = emptyList() })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = Theme.hPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { term ->
                    Text(
                        term, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.press { SearchModel.text = term }.glass(18.dp, 0.07f).padding(horizontal = 15.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
    item(key = "browse-title") {
        Text(
            "Browse Categories", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Theme.hPad).padding(top = if (recent.isEmpty()) 8.dp else 0.dp, bottom = 12.dp),
        )
    }
    itemsIndexed(Genre.all.chunked(2), key = { i, _ -> "genres-$i" }) { _, row ->
        Row(Modifier.padding(horizontal = Theme.hPad).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { g -> GenreTile(g, Modifier.weight(1f).press { Router.open(Route.GenrePage(g)) }) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- results
private fun LazyListScope.results(play: (Track) -> Unit) {
    val m = SearchModel
    val r = m.results
    item(key = "chips") { ScopeChips() }
    if (m.loading && r.isEmpty) { item(key = "loading") { LoadingRow() }; return }
    if (r.isEmpty && m.searchedFor.isNotEmpty()) {
        item(key = "none") { EmptyNote("magnifyingglass", "No Results", "Try a different spelling, or search for an artist or album.") }
        return
    }
    when (m.scope) {
        SearchScope.ALL -> topResults(r, play)
        SearchScope.SONGS -> itemsIndexed(r.tracks, key = { i, t -> "s$i-${t.uri}" }) { i, t ->
            TrackRow(t) { play(t) }
            if (i == r.tracks.lastIndex) LaunchedEffect(r.tracks.size) { m.more() }
        }
        SearchScope.ARTISTS -> itemsIndexed(r.artists, key = { i, a -> "a$i-${a.id}" }) { i, a ->
            MediaRow(a.name, "Artist", a.imageMid, circle = true) { Router.open(Route.ArtistPage(a)) }
            if (i == r.artists.lastIndex) LaunchedEffect(r.artists.size) { m.more() }
        }
        SearchScope.ALBUMS -> itemsIndexed(r.albums, key = { i, a -> "al$i-${a.id}" }) { i, a ->
            MediaRow(a.name, listOf(a.typeLabel, a.artist, a.year).filter { it.isNotEmpty() }.joinToString(" · "), a.artMid) { Router.open(Route.AlbumPage(a)) }
            if (i == r.albums.lastIndex) LaunchedEffect(r.albums.size) { m.more() }
        }
        SearchScope.PLAYLISTS -> itemsIndexed(r.playlists, key = { i, p -> "p$i-${p.id}" }) { i, p ->
            MediaRow(p.name, "Playlist · " + p.owner, p.imageMid) { Router.open(Route.PlaylistPage(p)) }
            if (i == r.playlists.lastIndex) LaunchedEffect(r.playlists.size) { m.more() }
        }
    }
    if (m.loading && m.scope != SearchScope.ALL) item(key = "more") { LoadingRow() }
}

@Composable
private fun ScopeChips() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Theme.hPad), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
    ) {
        items(SearchScope.entries) { s ->
            val on = SearchModel.scope == s
            Text(
                s.title, color = if (on) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.press { Haptics.tap(); SearchModel.scope = s }
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.08f))
                    .border(0.7.dp, if (on) Color.Transparent else Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            )
        }
    }
}

private fun LazyListScope.topResults(r: SearchResults, play: (Track) -> Unit) {
    val m = SearchModel
    val q = m.searchedFor.lowercase()
    val keep = { m.remember(m.text) }
    // the artist (or album) whose name matches what you typed, otherwise the first song
    val bestArtist = r.artists.firstOrNull { it.name.lowercase() == q }
    val bestAlbum = if (bestArtist == null) r.albums.firstOrNull { it.name.lowercase() == q } else null
    val bestTrack = if (bestArtist == null && bestAlbum == null) r.tracks.firstOrNull() else null
    val fallbackArtist = if (bestArtist == null && bestAlbum == null && bestTrack == null) r.artists.firstOrNull() else null
    item(key = "best") {
        Box(Modifier.padding(bottom = 18.dp)) {
            when {
                bestArtist != null || fallbackArtist != null -> {
                    val a = bestArtist ?: fallbackArtist!!
                    BestCard(a.imageMid, true, a.name, "Artist") { keep(); Router.open(Route.ArtistPage(a)) }
                }
                bestAlbum != null -> BestCard(bestAlbum.artMid, false, bestAlbum.name, "Album · " + bestAlbum.artist) { keep(); Router.open(Route.AlbumPage(bestAlbum)) }
                bestTrack != null -> BestCard(bestTrack.artMid, false, bestTrack.title, "Song · " + bestTrack.artistLine) { play(bestTrack) }
            }
        }
    }
    if (r.tracks.isNotEmpty()) {
        item(key = "songs-h") { Box(Modifier.padding(bottom = 6.dp)) { SectionHeader("Songs") { m.scope = SearchScope.SONGS } } }
        itemsIndexed(r.tracks.take(5), key = { i, t -> "top$i-${t.uri}" }) { _, t -> TrackRow(t) { play(t) } }
        item(key = "songs-gap") { Spacer(Modifier.height(18.dp)) }
    }
    if (r.artists.isNotEmpty()) item(key = "artists") {
        Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Artists") { m.scope = SearchScope.ARTISTS }
            Carousel(spacing = 16.dp) { items(r.artists) { a -> ArtistCircle(a, 110.dp) { keep(); Router.open(Route.ArtistPage(a)) } } }
        }
    }
    if (r.albums.isNotEmpty()) item(key = "albums") {
        Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Albums") { m.scope = SearchScope.ALBUMS }
            Carousel { items(r.albums) { a -> AlbumCard(a, 150.dp, a.artist) { keep(); Router.open(Route.AlbumPage(a)) } } }
        }
    }
    if (r.playlists.isNotEmpty()) item(key = "playlists") {
        Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Playlists") { m.scope = SearchScope.PLAYLISTS }
            Carousel { items(r.playlists) { p -> PlaylistCard(p, 150.dp) { keep(); Router.open(Route.PlaylistPage(p)) } } }
        }
    }
}

@Composable
private fun BestCard(art: String, circle: Boolean, title: String, kind: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().press(0.98f, onClick = onClick).glass(24.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(92.dp).shadow(10.dp, if (circle) CircleShape else RoundedCornerShape(12.dp)), px = 300, corner = 12.dp, circle = circle)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(kind, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(sym("play.fill"), contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(22.dp).offset(x = 1.dp))
        }
    }
}

@Composable
fun GenreTile(genre: Genre, modifier: Modifier = Modifier) {
    Box(
        modifier.height(104.dp).shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(tileBrush(genre.hue))
            .border(0.7.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
    ) {
        Icon(
            sym(genre.symbol), contentDescription = null, tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(52.dp).rotate(18f),
        )
        Text(genre.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

/** A browse category's songs and playlists, kept with the page so going back doesn't load them again. */
class GenreData(val tracks: List<Track>, val playlists: List<Playlist>)

/** A browse category: songs and playlists that fit it. */
@Composable
fun GenreScreen(entry: Entry, genre: Genre) {
    var data by remember { mutableStateOf(entry.model as? GenreData) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(genre.id) {
        if (data != null) return@LaunchedEffect
        val (ra, rb) = coroutineScope {
            val a = async { Spotify.search(genre.trackQuery, listOf("track")) }
            val b = async { Spotify.search(genre.playlistQuery, listOf("playlist")) }
            a.await() to b.await()
        }
        var tracks = ra?.tracks ?: emptyList()
        if (tracks.size >= 10) Spotify.search(genre.trackQuery, listOf("track"), 10)?.let { tracks = tracks + it.tracks }
        val d = GenreData(tracks, rb?.playlists ?: emptyList())
        entry.model = d
        data = d
    }
    val tracks = data?.tracks ?: emptyList()
    val playlists = data?.playlists ?: emptyList()
    Page(inlineTitle = genre.title, back = true) {
        item(key = "hero") {
            Box(
                Modifier.padding(top = 8.dp, bottom = 22.dp).padding(horizontal = Theme.hPad).fillMaxWidth().height(170.dp)
                    .shadow(16.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(tileBrush(genre.hue))
                    .border(0.7.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp)),
            ) {
                Icon(sym(genre.symbol), contentDescription = null, tint = Color.White.copy(alpha = 0.22f), modifier = Modifier.align(Alignment.CenterEnd).padding(20.dp).size(96.dp))
                Text(genre.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "buttons") {
                Box(Modifier.padding(bottom = 22.dp)) {
                    PlayShuffleButtons(
                        play = { scope.launch { Engine.playTracks(tracks, startAt = 0, name = genre.title) } },
                        shuffle = { scope.launch { Engine.playTracks(tracks, startAt = 0, shuffle = true, name = genre.title) } },
                    )
                }
            }
            item(key = "songs-h") { Box(Modifier.padding(bottom = 6.dp)) { SectionHeader("Songs") } }
            itemsIndexed(tracks, key = { i, t -> "g$i-${t.uri}" }) { i, t ->
                TrackRow(t) { scope.launch { Engine.playTracks(tracks, startAt = i, name = genre.title) } }
            }
            item(key = "songs-gap") { Spacer(Modifier.height(22.dp)) }
        }
        if (playlists.isNotEmpty()) item(key = "playlists") {
            Column(Modifier.padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Playlists")
                Carousel { items(playlists) { p -> PlaylistCard(p, 150.dp) } }
            }
        }
        if (data == null) item(key = "loading") { LoadingRow() }
        if (data != null && tracks.isEmpty() && playlists.isEmpty()) item(key = "empty") {
            EmptyNote("music.note", "Nothing here yet", "Spotify didn't send anything back for this one. Try again in a bit.")
        }
    }
}
