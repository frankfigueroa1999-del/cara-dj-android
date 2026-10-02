package com.cara.dj

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Home: a greeting, Cara's station card, what you've been playing, your favourites and your playlists. */
@Composable
fun HomeScreen() {
    val state = rememberLazyListState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrolled by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 24 } }
    val frost by animateFloatAsState(if (scrolled) 1f else 0f, tween(200), label = "frost")
    val gap = Modifier.padding(bottom = 30.dp)
    Box(Modifier.fillMaxSize()) {
        Page(state = state, showBar = false, onRefresh = { Library.loadAll(force = true); Engine.poke() }) {
            item(key = "header") { HomeHeader(Modifier.padding(top = top + 10.dp).then(gap)) }
            item(key = "banners") {
                Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    if (!Engine.loggedIn) ConnectCard()
                    else if (Engine.problem.isNotEmpty() && !Engine.connected) ProblemBanner()
                    else if (Library.needsReconnect) ReconnectBanner()
                    if (Engine.noDevice) OpenSpotifyBanner()
                    CaraHeroCard()
                }
                Spacer(Modifier.height(30.dp))
            }
            if (Library.recentAlbums.isNotEmpty()) item(key = "recent") {
                Column(gap, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("Recently Played")
                    Carousel { items(Library.recentAlbums) { AlbumCard(it) } }
                }
            }
            if (Library.topTracks.isNotEmpty()) item(key = "repeat") {
                Column(gap, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("On Repeat", "Your most played lately")
                    SongGrid(Library.topTracks, station = "On Repeat")
                }
            }
            if (Library.topArtists.isNotEmpty()) item(key = "artists") {
                Column(gap, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("Artists You Love")
                    Carousel(spacing = 16.dp) { items(Library.topArtists) { ArtistCircle(it) } }
                }
            }
            if (Library.playlists.isNotEmpty()) item(key = "playlists") {
                Column(gap, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader("Your Playlists") { Router.open(Route.Playlists) }
                    Carousel {
                        item(key = "liked") { LikedCard() }
                        items(Library.playlists.take(20)) { PlaylistCard(it) }
                    }
                }
            }
            if (Engine.loggedIn && !Library.loaded && Library.playlists.isEmpty()) item(key = "loading") { LoadingRow() }
        }
        // frosts the status bar once the page scrolls under it
        Box(Modifier.fillMaxWidth().height(top).background(Color(0xF0101014).copy(alpha = 0.94f * frost)))
    }
}

@Composable
private fun HomeHeader(modifier: Modifier) {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val part = when {
        h < 5 -> "Good night"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val first = Library.me?.name?.split(" ")?.firstOrNull().orEmpty()
    val greeting = if (first.isNotEmpty()) "$part, $first" else part
    val dateLine = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()).uppercase() }
    Row(modifier.fillMaxWidth().padding(horizontal = Theme.hPad), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(dateLine, color = Theme.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Text(greeting, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.padding(bottom = 2.dp)) { AvatarButton(38.dp) }
    }
}

@Composable
private fun LikedCard() {
    Column(Modifier.width(160.dp).press { Router.open(Route.Liked) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.shadow(10.dp, RoundedCornerShape(12.dp))) { LikedArt(160.dp, 12.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Liked Songs", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${Library.likedTotal} songs", color = Theme.text2, fontSize = 14.sp)
        }
    }
}

/** Songs in a sideways-swiping grid of four rows, like "Top Songs". [station] names what plays (there's no playlist behind them). */
@Composable
fun SongGrid(tracks: List<Track>, station: String? = null) {
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    val w = ((LocalConfiguration.current.screenWidthDp - 40) * 0.86f).dp
    val columns = remember(tracks) { tracks.withIndex().toList().chunked(4) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = Theme.hPad),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        flingBehavior = rememberSnapFlingBehavior(state),
        modifier = Modifier.height(58.dp * 4 + 12.dp),
    ) {
        items(columns) { col ->
            Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                col.forEach { (i, t) ->
                    GridSongRow(t) { scope.launch { Engine.playTracks(tracks, startAt = i, name = station) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridSongRow(track: Track, action: () -> Unit) {
    val current = track.uri.isNotEmpty() && Engine.displayItem?.uri == track.uri
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(58.dp).combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { Haptics.tap(); action() },
            onLongClick = { Haptics.firm(); menu = true },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            Artwork(track.artMid, Modifier.fillMaxSize(), px = 150, corner = 8.dp)
            if (current) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    EqualizerBars(playing = Engine.now.isPlaying, color = Color.White)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title, color = if (current) Theme.accent else Color.White, fontSize = 15.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(track.artistLine, color = Theme.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Box(Modifier.width(30.dp).height(40.dp).tap { menu = true }, contentAlignment = Alignment.Center) {
                Icon(sym("ellipsis"), contentDescription = "More", tint = Theme.text2, modifier = Modifier.size(18.dp))
            }
            TrackMenu(track, menu, { menu = false })
        }
    }
}

/** Cara's station card on Home. */
@Composable
fun CaraHeroCard() {
    val running = Engine.running
    val glow by animateFloatAsState(if (running) 0.38f else 0.24f, tween(350), label = "glow")
    Column(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            // her colours, glowing faintly through the glass
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Theme.accent.copy(alpha = glow), Color.Transparent), center = Offset(size.width, 0f), radius = 280.dp.toPx()))
                drawRect(Brush.radialGradient(listOf(Theme.caraPurple.copy(alpha = 0.3f), Color.Transparent), center = Offset(0f, size.height), radius = 260.dp.toPx()))
            }
            .glass(26.dp, 0.06f)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = Theme.accent, spotColor = Theme.accent)
                    .clip(RoundedCornerShape(16.dp)).background(Theme.caraGradient),
                contentAlignment = Alignment.Center,
            ) { StationLogo(active = running, modifier = Modifier.width(32.dp).height(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Engine.stationFull.uppercase(), color = Theme.text2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Cara", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(Engine.statusLine, color = Theme.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnimatedVisibility(running, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                LiveBadge(if (Engine.speaking) "ON AIR" else "LIVE")
            }
        }
        if (Engine.line.isNotEmpty()) {
            Text(
                "“" + Engine.line.replace(Regex("""\[[^\]]*]"""), "").trim() + "”",
                color = Color.White.copy(alpha = 0.86f), fontSize = 15.sp, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                "Your own radio host. She talks between your songs about your town, the news, the music, and you.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillLabel(
                if (running) "End Show" else "Go Live", if (running) "stop.fill" else "dot.radiowaves.left.and.right",
                primary = !running, height = 44.dp, fill = false,
                modifier = Modifier.press { Haptics.firm(); if (running) Engine.stop() else Engine.start() },
            )
            PillLabel("Her Settings", primary = false, height = 44.dp, fill = false, modifier = Modifier.press { Haptics.tap(); Router.select(AppTab.CARA) })
        }
    }
}

/** Shown when Spotify isn't connected yet. */
@Composable
fun ConnectCard() {
    val scope = rememberCoroutineScope()
    val noId = Config.clientID.isEmpty()
    Column(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(24.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Connect Spotify", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (noId) "Add your Spotify Client ID in Settings, then connect. Your music plays through the Spotify app."
            else "Log in once and your library, playlists and the player all show up here.",
            color = Theme.text2, fontSize = 15.sp,
        )
        PillLabel(
            if (noId) "Open Settings" else "Connect Spotify", primary = true, height = 46.dp,
            modifier = Modifier.padding(top = 4.dp).press {
                Haptics.tap()
                if (noId) Router.showSettings = true else scope.launch { Engine.connect(forceLogin = true) }
            },
        )
    }
}

/** Older logins don't include the library permissions; one quick reconnect fixes that. */
@Composable
fun ReconnectBanner() {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).glass(20.dp, 0.1f), contentAlignment = Alignment.Center) {
            Icon(sym("sparkles"), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Unlock your library", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Reconnect Spotify once so Cara DJ can show your playlists, albums and liked songs.", color = Theme.text2, fontSize = 13.sp)
        }
        Spacer(Modifier.width(4.dp))
        PillLabel("Reconnect", primary = true, height = 34.dp, fill = false, modifier = Modifier.press {
            Haptics.tap(); scope.launch { Engine.connect(forceLogin = true) }
        })
    }
}

/** Logged in, but Spotify won't play along (wrong account, expired login, no internet). */
@Composable
fun ProblemBanner() {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(sym("exclamationmark.triangle.fill"), contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
            Text("Spotify isn't connecting", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(Engine.problem, color = Theme.text2, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillLabel("Try Again", primary = true, height = 36.dp, fill = false, modifier = Modifier.press { Haptics.tap(); scope.launch { Engine.connect() } })
            PillLabel("Reconnect", primary = false, height = 36.dp, fill = false, modifier = Modifier.press { Haptics.tap(); scope.launch { Engine.connect(forceLogin = true) } })
            PillLabel("Settings", primary = false, height = 36.dp, fill = false, modifier = Modifier.press { Router.showSettings = true })
        }
    }
}

/** Opens the Spotify app (so it has somewhere to play). */
fun openSpotifyApp() {
    try {
        val i = Engine.app.packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i != null) Engine.app.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) else openUrl("spotify:")
    } catch (e: Exception) { openUrl("spotify:") }
}

/** Spotify had nowhere to play (the Spotify app is closed). */
@Composable
fun OpenSpotifyBanner() {
    Row(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sym("exclamationmark.triangle.fill"), contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text("Spotify isn't open on this phone. Open it once, then come back.", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        PillLabel("Open", primary = true, height = 34.dp, fill = false, modifier = Modifier.press { openSpotifyApp() })
    }
}
