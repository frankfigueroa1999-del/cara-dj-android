package com.cara.dj

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The whole app: four tabs, the floating mini player and tab bar, and the big player that slides up. */
@Composable
fun CaraApp() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black, background = Theme.ink, surface = Color(0xFF1C1C21),
            onSurface = Color.White, secondary = Theme.accent, surfaceContainer = Color(0xFF26262C),
        )
    ) {
        // the app rises into view once the welcome setup is put away
        val reveal by animateFloatAsState(if (Config.welcomed) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 60f), label = "reveal")
        LaunchedEffect(Unit) {
            Engine.boot()
            CrashLog.lastCrash()?.let { why ->
                Engine.addLog("Cara DJ closed unexpectedly last time: $why")
                Toasts.show("Cara DJ closed unexpectedly last time. The reason is in Cara, Activity", "exclamationmark.triangle.fill")
            }
            if (Spotify.isLoggedIn) Engine.connect()
        }
        // every page takes its colour from the cover of what's playing
        val artMid = Engine.displayItem?.artMid ?: ""
        LaunchedEffect(artMid) { Ambience.follow(artMid) }

        Box(Modifier.fillMaxSize().background(Theme.ink)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 0.94f + 0.06f * reveal
                    scaleX = s; scaleY = s; alpha = reveal
                }
            ) {
                TabHost()
                BottomChrome(Modifier.align(Alignment.BottomCenter))
                AnimatedVisibility(
                    visible = Router.showPlayer,
                    enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) { it },
                    exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) { it },
                ) {
                    NowPlayingScreen()
                }
            }
            ToastOverlay()
            AnimatedVisibility(visible = !Config.welcomed, enter = fadeIn(tween(400)), exit = fadeOut(tween(500))) {
                WelcomeScreen()
            }
        }
        if (Router.showSettings) SettingsSheet(onDismiss = { Router.showSettings = false })
        Router.addToPlaylist?.let { t -> AddToPlaylistSheet(t, onDismiss = { Router.addToPlaylist = null }) }

        // Android's back gesture: close the player, then go back a page, then back to Home
        BackHandler(enabled = !Router.showPlayer && Router.tab != AppTab.HOME && Router.stack().isEmpty()) { Router.select(AppTab.HOME) }
        BackHandler(enabled = !Router.showPlayer && Router.stack().isNotEmpty()) { Router.pop() }
        BackHandler(enabled = Router.showPlayer) { Router.closePlayer() }
    }
}

/** The tab that's showing, and whatever page is open on top of it (pages slide in and out like the iPhone's). */
@Composable
fun TabHost() {
    val holder = rememberSaveableStateHolder()
    val tab = Router.tab
    val top: Entry? = Router.stack(tab).lastOrNull()
    AnimatedContent(
        targetState = Pair(tab, top),
        transitionSpec = {
            if (initialState.first != targetState.first) {
                fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            } else if (Router.wentBack) {
                (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(260)))
            } else {
                (slideInHorizontally(tween(320)) { it } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(320)))
            }
        },
        label = "pages",
    ) { (t, e) ->
        holder.SaveableStateProvider(e?.key ?: -(t.ordinal + 1)) {
            if (e == null) RootPage(t) else RouteScreen(e)
        }
    }
}

@Composable
private fun RootPage(t: AppTab) {
    when (t) {
        AppTab.HOME -> HomeScreen()
        AppTab.CARA -> CaraScreen()
        AppTab.LIBRARY -> LibraryScreen()
        AppTab.SEARCH -> SearchScreen()
    }
}

/** Any page that opens on top of a tab: albums, artists, playlists and the rest. */
@Composable
private fun RouteScreen(e: Entry) {
    when (val r = e.route) {
        is Route.AlbumPage -> AlbumScreen(e, r.album)
        is Route.ArtistPage -> ArtistScreen(e, r.artist)
        is Route.PlaylistPage -> PlaylistScreen(e, r.playlist)
        Route.Liked -> LikedSongsScreen()
        Route.Playlists -> PlaylistsListScreen()
        Route.Albums -> AlbumsGridScreen()
        Route.Artists -> ArtistsListScreen()
        is Route.GenrePage -> GenreScreen(e, r.genre)
    }
}

// ---------------------------------------------------------------- the floating mini player + tab bar
@Composable
fun BottomChrome(modifier: Modifier = Modifier) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxWidth()) {
        // the page softly dissolves under the floating bars
        Box(
            Modifier.matchParentSizeFix(180.dp + bottom).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.5f))))
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = bottom + 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = Engine.displayItem != null || Engine.speaking,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer()
            }
            TabBar()
        }
    }
}

/** A box of a fixed height across the full width (for the fade behind the bars). */
private fun Modifier.matchParentSizeFix(h: androidx.compose.ui.unit.Dp): Modifier = this.fillMaxWidth().height(h)

@Composable
fun TabBar() {
    Box(
        Modifier.fillMaxWidth().height(Theme.tabBarHeight)
            .shadow(18.dp, RoundedCornerShape(Theme.tabBarHeight / 2))
            .frosted(Theme.tabBarHeight / 2)
            .padding(5.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gap = 2.dp
            val w = (maxWidth - gap * 3) / 4
            // the pill glides over to the tab you pick
            val x by animateDpAsState((w + gap) * Router.tab.ordinal, spring(dampingRatio = 0.86f, stiffness = 195f), label = "pill")
            Box(
                Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)).border(0.6.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                TabItem(AppTab.HOME, "Home", "house.fill", Modifier.weight(1f))
                TabItem(AppTab.CARA, "Cara", "dot.radiowaves.left.and.right", Modifier.weight(1f))
                TabItem(AppTab.LIBRARY, "Library", "square.stack.fill", Modifier.weight(1f))
                TabItem(AppTab.SEARCH, "Search", "magnifyingglass", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabItem(t: AppTab, title: String, icon: String, modifier: Modifier) {
    val on = Router.tab == t
    val tint by animateColorAsState(if (on) Color.White else Theme.text2, tween(180), label = "tab")
    Column(
        modifier.fillMaxSize().clip(CircleShape)
            .tap {
                if (!on) Haptics.soft()
                Router.select(t)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(sym(icon), contentDescription = title, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(title, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MiniPlayer() {
    val item = Engine.displayItem
    val cara = Silence.isSilence(item?.uri ?: "")
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().height(Theme.miniHeight)
            .shadow(16.dp, RoundedCornerShape(22.dp))
            .frosted(22.dp)
            .tap { Router.openPlayer() }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val up = -24.dp.toPx()
                        val side = 60.dp.toPx()
                        when {
                            dy < up -> Router.openPlayer()
                            dx < -side -> { Haptics.tap(); scope.launch { Engine.next() } }
                            dx > side -> { Haptics.tap(); scope.launch { Engine.previous() } }
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item?.artMid, Modifier.size(44.dp).shadow(6.dp, RoundedCornerShape(11.dp)), px = 150, corner = 11.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (Engine.speaking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EqualizerBars(playing = true, color = Color.White, height = 10.dp)
                    Text("Cara is on the mic", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(if (cara) Engine.stationFull else (item?.title ?: Engine.stationName), color = Theme.text2, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(item?.title ?: "Not Playing", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val a = item?.artistLine ?: ""
                if (a.isNotEmpty()) Text(a, color = Theme.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        // play / pause, ringed by how far into the song you are
        val frac by produceState(0f, Engine.now, Engine.pendingItem) {
            while (true) {
                value = if (Engine.pendingItem != null) 0f else {
                    val dur = maxOf(Engine.now.durationMs, 1).toFloat()
                    (Engine.now.currentProgressMs / dur).coerceIn(0f, 1f)
                }
                delay(1000)
            }
        }
        Box(Modifier.size(44.dp).press(0.85f) { Haptics.tap(); scope.launch { Engine.togglePlay() } }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                val sw = 2.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 - sw / 2, style = Stroke(sw))
                drawArc(Color.White, -90f, 360f * frac, false, topLeft = Offset(sw / 2, sw / 2),
                    size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            }
            Icon(sym(if (Engine.now.isPlaying) "pause.fill" else "play.fill"), contentDescription = if (Engine.now.isPlaying) "Pause" else "Play",
                tint = Color.White, modifier = Modifier.size(18.dp).offset(x = if (Engine.now.isPlaying) 0.dp else 1.dp))
        }
        Box(Modifier.width(40.dp).height(44.dp).press(0.85f) { Haptics.tap(); scope.launch { Engine.next() } }, contentAlignment = Alignment.Center) {
            Icon(sym("forward.fill"), contentDescription = "Next", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
