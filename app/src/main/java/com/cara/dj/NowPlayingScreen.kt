package com.cara.dj

import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.DropdownMenuItem
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

private val npTags = Regex("""\[[^\]]*\]""")

/** The drawables that came with the app (the same icons as the iPhone app's ai_*.png). */
@Composable
private fun AppIcon(res: Int, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Image(painterResource(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), contentScale = ContentScale.Fit, modifier = modifier.size(size))
}

/**
 * The big player: the frosted, full-screen look, with lyrics, Playing Next and the song's story on cards underneath.
 * Pull down from the top (or tap the little handle) to close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen() {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val item = Engine.displayItem
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // the cover on screen: it only changes once the next one has loaded, then cross-fades
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var accentTarget by remember { mutableStateOf(Color(0.25f, 0.2f, 0.2f)) }
    val accent by animateColorAsState(accentTarget, tween(900), label = "accent")
    LaunchedEffect(item?.art) {
        val a = item?.art ?: ""
        if (a.isEmpty()) return@LaunchedEffect
        val img = ImageCache.load(a, 900) ?: return@LaunchedEffect
        shown = img
        accentTarget = ArtColors.average(img)
    }
    val lyrics by produceState(LyricsResult(message = "Finding lyrics..."), Engine.now.item?.uri) {
        val t = Engine.now.item
        value = when {
            t == null -> LyricsResult(message = "Nothing playing.")
            Silence.isSilence(t.uri) -> LyricsResult(message = "Cara's on the air.")
            !t.isMusic -> LyricsResult(message = "No lyrics for this one.")
            else -> { value = LyricsResult(message = "Finding lyrics..."); Lyrics.find(t, Engine.now.durationMs) }
        }
    }
    val about by produceState(About(), Engine.now.item?.uri) {
        val t = Engine.now.item
        value = About()
        if (t != null) value = try { loadAbout(t) } catch (e: Exception) { About() }
    }
    var popular by remember { mutableStateOf(listOf<Track>()) }
    var popularFor by remember { mutableStateOf("") }
    var popularName by remember { mutableStateOf("") }
    LaunchedEffect(Engine.now.item?.uri) {
        val t = Engine.now.item ?: return@LaunchedEffect
        if (t.isMusic && t.artistId.isNotEmpty() && popularFor != t.artistId) {
            val found = Spotify.artistTopTracks(t.artistRef)
            popular = found
            popularFor = t.artistId
            popularName = t.artists.firstOrNull() ?: t.artist
        }
    }

    var showFullLyrics by remember { mutableStateOf(false) }
    var showFullQueue by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showCara by remember { mutableStateOf(false) }
    var heartPop by remember { mutableStateOf(false) }

    // pull down from the top to close
    var pullPx by remember { mutableFloatStateOf(0f) }
    val closeAt = with(density) { 110.dp.toPx() }
    val pullDown = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pullPx > 0f && available.y < 0f) {
                    val used = max(available.y, -pullPx)
                    pullPx += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    pullPx += available.y * 0.5f
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullPx > 0f) {
                    if (pullPx > closeAt) {
                        Haptics.soft()
                        Router.closePlayer()
                    } else {
                        animate(pullPx, 0f) { v, _ -> pullPx = v }
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(Router.showPlayer) { if (Router.showPlayer) pullPx = 0f }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationY = pullPx }) {
        val fullH = maxHeight
        val pageH = fullH - (84.dp + bottom)
        val side = minOf(maxWidth * 0.84f, fullH * 0.46f, 420.dp)
        val listState = rememberLazyListState()
        val pagePx = with(density) { pageH.toPx() }
        val inLyr by remember { derivedStateOf { listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > pagePx * 0.8f } }

        NowPlayingBackground(shown)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(pullDown)) {
            item(key = "player") {
                Column(Modifier.fillMaxWidth().height(pageH).padding(horizontal = 22.dp).padding(top = top)) {
                    // the little handle: tap it to close
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 120.dp, height = 22.dp).tap { Router.closePlayer() }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = 36.dp, height = 5.dp).background(Color.White.copy(alpha = 0.35f), CircleShape))
                        }
                    }
                    if (!Engine.connected) {
                        Box(
                            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .tap { scope.launch { Engine.connect(forceLogin = !Engine.loggedIn) } }.padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (Engine.loggedIn) "RECONNECT SPOTIFY" else "CONNECT SPOTIFY", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // the cover: double-tap to like
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(side).shadow(22.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = {
                                        if (Engine.pendingItem == null && Engine.displayItem != null) {
                                            Haptics.tap()
                                            if (Engine.currentLiked != true) scope.launch { Engine.toggleLikeCurrent() }
                                            heartPop = true
                                            scope.launch { delay(700); heartPop = false }
                                        }
                                    })
                                },
                        ) {
                            Crossfade(targetState = shown, animationSpec = tween(900), label = "cover") { b ->
                                if (b != null) {
                                    val ib = remember(b) { b.asImageBitmap() }
                                    Image(ib, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = Engine.speaking && Engine.line.isNotEmpty(),
                                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) { CaraCaption(Engine.line) }
                            val pop by animateFloatAsState(if (heartPop) 1f else 0f, spring(dampingRatio = 0.55f), label = "heart")
                            Icon(
                                sym("heart.fill"), contentDescription = null, tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(84.dp).scale(0.4f + 0.6f * pop).alpha(0.95f * pop),
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    PlayerPanel(onDevices = { showDevices = true }, onCara = { showCara = true })
                    Spacer(Modifier.height(12.dp))
                }
            }
            item(key = "lyrics") {
                LyricsCard(lyrics, accent, item) { showFullLyrics = true }
            }
            item(key = "queue") {
                Spacer(Modifier.height(14.dp))
                QueueCard { showFullQueue = true }
            }
            item(key = "info") {
                InfoCards(about, popular, popularName)
                Spacer(Modifier.height(40.dp + bottom))
            }
        }

        // small player that slides in once you scroll past the player page
        AnimatedVisibility(
            visible = inLyr, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MiniBar(top)
        }

        AnimatedVisibility(
            visible = showFullLyrics,
            enter = scaleIn(initialScale = 0.88f, animationSpec = tween(420)) + fadeIn(tween(300)),
            exit = scaleOut(targetScale = 0.88f, animationSpec = tween(300)) + fadeOut(tween(250)),
        ) {
            FullPanel(accent, top, bottom, onClose = { showFullLyrics = false }, onCara = { showCara = true }) {
                LyricsScroller(lyrics, size = 29, spacing = 22, tappable = true, modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 18.dp))
            }
        }
        AnimatedVisibility(
            visible = showFullQueue,
            enter = scaleIn(initialScale = 0.88f, animationSpec = tween(420)) + fadeIn(tween(300)),
            exit = scaleOut(targetScale = 0.88f, animationSpec = tween(300)) + fadeOut(tween(250)),
        ) {
            FullPanel(accent, top, bottom, onClose = { showFullQueue = false }, onCara = { showCara = true }) {
                FullQueue()
            }
        }
    }
    BackHandler(enabled = showFullLyrics) { showFullLyrics = false }
    BackHandler(enabled = showFullQueue) { showFullQueue = false }
    if (showDevices) DevicesSheet { showDevices = false }
    if (showCara) CaraOptionsSheet(onDismiss = { showCara = false }, onSettings = {
        showCara = false
        Engine.scope.launch { delay(450); Router.showSettings = true }
    })
}

// ---------------------------------------------------------------- the frosted album-art background
@Composable
private fun NowPlayingBackground(shown: Bitmap?) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0.12f, 0.12f, 0.18f), Color.Black)))) {
        Crossfade(targetState = shown, animationSpec = tween(900), label = "bg") { b ->
            if (b != null) {
                val soft = remember(b) { Bitmap.createScaledBitmap(b, 40, 40, true).asImageBitmap() }
                Box(Modifier.fillMaxSize()) {
                    Image(
                        soft, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(30.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
            }
        }
        // darkens the top and bottom so the text and buttons stay readable
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.35f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
            )
        )
    }
}

// ---------------------------------------------------------------- song name, the DJ button, "...", progress, controls, device
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerPanel(onDevices: () -> Unit, onCara: () -> Unit) {
    val scope = rememberCoroutineScope()
    val item = Engine.displayItem
    var djMenu by remember { mutableStateOf(false) }
    var transitionMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item?.title ?: (if (Engine.connected) "Nothing playing" else "Not connected"), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item?.artistLine ?: "Start a playlist in the Spotify app.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.tap { if (item != null && item.artistId.isNotEmpty()) Router.open(Route.ArtistPage(item.artistRef)) },
                )
            }
            Spacer(Modifier.width(8.dp))
            // Cara on / off: white and filled while she's live. Press and hold for her quick actions.
            Box {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (Engine.running) Color.White else Color.White.copy(alpha = 0.22f))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { Haptics.firm(); if (Engine.running) Engine.stop() else Engine.start() },
                            onLongClick = { Haptics.firm(); djMenu = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(R.drawable.ic_dj, 15.dp, if (Engine.running) Color.Black else Color.White)
                }
                DarkMenu(djMenu, { djMenu = false }) {
                    MenuItem("Talk Now", "mic.fill") { djMenu = false; Engine.testBreak() }
                    MenuItem("Pop In Now", "sparkles") { djMenu = false; Engine.testPopin() }
                    MenuItem("Play a Stinger", "bolt.fill") { djMenu = false; scope.launch { Engine.testStinger() } }
                    MenuItem("Next Transition", "forward.end") { transitionMenu = true }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    MenuItem("Cara Options…", "slider.horizontal.3") { djMenu = false; onCara() }
                }
                // the "Next Transition" submenu
                DarkMenu(transitionMenu, { transitionMenu = false }) {
                    for ((title, style) in listOf("Talk Over" to "talkover", "Over the Intro" to "intro", "Silent" to "silent")) {
                        DropdownMenuItem(
                            text = { Text(title, fontSize = 16.sp) },
                            onClick = { transitionMenu = false; djMenu = false; Engine.queue(style) },
                            trailingIcon = { if (Engine.queued == style) Icon(sym("checkmark"), contentDescription = null, modifier = Modifier.size(20.dp)) },
                            colors = MenuDefaults.itemColors(textColor = Color.White, trailingIconColor = Color.White),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).tap { moreMenu = true },
                    contentAlignment = Alignment.Center,
                ) { AppIcon(R.drawable.ic_more, 17.dp, Color.White) }
                MoreMenu(moreMenu, { moreMenu = false }, onCara)
            }
        }
        Spacer(Modifier.height(26.dp))
        ProgressBar()
        Spacer(Modifier.height(26.dp))
        Controls()
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().alpha(if (Engine.connected) 1f else 0f), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).tap { Haptics.tap(); onDevices() }, verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_speaker, 17.dp, Theme.green)
                Spacer(Modifier.width(10.dp))
                Text(Engine.now.deviceName.ifEmpty { "This device" }, color = Theme.green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item?.shareURL?.let { u ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).tap { shareLink(u) }, contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_share, 16.dp, Color.White)
                }
            }
        }
    }
}

/** The "..." menu on the player (also at the bottom of the full-screen lyrics and queue). */
@Composable
private fun MoreMenu(expanded: Boolean, onDismiss: () -> Unit, onCara: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = Engine.displayItem
    var sleepMenu by remember { mutableStateOf(false) }
    DarkMenu(expanded, onDismiss) {
        if (t != null) {
            val liked = Engine.currentLiked ?: false
            MenuItem(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", if (liked) "heart.slash" else "heart", enabled = Engine.pendingItem == null) {
                onDismiss(); scope.launch { Engine.toggleLikeCurrent() }
            }
            MenuItem("Add to a Playlist…", "text.badge.plus") { onDismiss(); Router.addToPlaylist = t }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            if (t.albumId.isNotEmpty()) MenuItem("Go to Album", "square.stack") { onDismiss(); Router.open(Route.AlbumPage(t.albumRef)) }
            if (t.artistId.isNotEmpty()) MenuItem("Go to Artist", "music.mic") { onDismiss(); Router.open(Route.ArtistPage(t.artistRef)) }
        }
        MenuItem("Cara Options…", "dot.radiowaves.left.and.right") { onDismiss(); onCara() }
        MenuItem(sleepLabel(), "moon.zzz") { sleepMenu = true }
        t?.shareURL?.let { u -> MenuItem("Share Song", "square.and.arrow.up") { onDismiss(); shareLink(u) } }
    }
    DarkMenu(sleepMenu, { sleepMenu = false }) {
        for ((title, mins) in listOf("Off" to null, "15 Minutes" to 15, "30 Minutes" to 30, "45 Minutes" to 45, "1 Hour" to 60)) {
            MenuItem(title, "moon.zzz") { sleepMenu = false; onDismiss(); Engine.setSleep(mins) }
        }
        MenuItem("End of Song", "moon.zzz") { sleepMenu = false; onDismiss(); Engine.setSleepAtEndOfSong() }
    }
}

private fun sleepLabel(): String {
    if (Engine.sleepAtTrackEnd) return "Sleep Timer: End of Song"
    val s = Engine.sleepAt ?: return "Sleep Timer"
    val mins = max(1, ((s - System.currentTimeMillis()) / 60000.0 + 0.5).toInt())
    return "Sleep Timer: $mins min left"
}

// ---------------------------------------------------------------- progress bar with a round handle; drag it to seek
@Composable
private fun liveFraction(): Float {
    val f by produceState(0f, Engine.now, Engine.pendingItem) {
        while (true) {
            value = if (Engine.pendingItem != null) 0f else {
                val dur = max(Engine.now.durationMs, 1)
                min(Engine.now.currentProgressMs, dur).toFloat() / dur
            }
            delay(500)
        }
    }
    return f
}

@Composable
private fun ProgressBar() {
    val scope = rememberCoroutineScope()
    val dur = max(Engine.now.durationMs, 1)
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val live = liveFraction()
    val frac = dragFrac ?: live
    val shownMs = (frac * dur).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(dur) {
                    detectTapGestures { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        scope.launch { Engine.seek((f * dur).toInt()) }
                    }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            val f = dragFrac
                            dragFrac = null
                            if (f != null) scope.launch { Engine.seek((f * dur).toInt()) }
                        },
                        onDragCancel = { dragFrac = null },
                    ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f), CircleShape))
            Box(Modifier.width(w * frac).height(3.dp).background(Color.White, CircleShape))
            Box(Modifier.offset(x = w * frac - 6.dp).size(12.dp).background(Color.White, CircleShape))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shownMs), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("-" + formatClock(max(0, dur - shownMs)), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------- shuffle, previous, the big round play/pause, next, repeat
@Composable
private fun Controls() {
    val scope = rememberCoroutineScope()
    val repeatOn = Engine.now.repeatMode != "off"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ModeButton(R.drawable.ic_shuffle, Engine.now.shuffle) { scope.launch { Engine.toggleShuffle() } }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(46.dp).press(0.85f) { Haptics.tap(); scope.launch { Engine.previous() } }, contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_prev, 24.dp, Color.White)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PlayButton(60.dp, 22.dp) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(46.dp).press(0.85f) { Haptics.tap(); scope.launch { Engine.next() } }, contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_next, 24.dp, Color.White)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ModeButton(if (Engine.now.repeatMode == "track") R.drawable.ic_repeat1 else R.drawable.ic_repeat, repeatOn) { scope.launch { Engine.cycleRepeat() } }
        }
    }
}

@Composable
private fun PlayButton(size: Dp, icon: Dp) {
    val scope = rememberCoroutineScope()
    Box(Modifier.size(size).clip(CircleShape).background(Color.White).press(0.92f) { Haptics.firm(); scope.launch { Engine.togglePlay() } },
        contentAlignment = Alignment.Center) {
        AppIcon(if (Engine.now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, icon, Color.Black,
            Modifier.offset(x = if (Engine.now.isPlaying) 0.dp else 2.dp))
    }
}

@Composable
private fun ModeButton(res: Int, on: Boolean, action: () -> Unit) {
    Box(Modifier.size(52.dp).tap { Haptics.tap(); action() }, contentAlignment = Alignment.Center) {
        AppIcon(res, 24.dp, if (on) Theme.green else Color.White.copy(alpha = 0.85f))
        Box(Modifier.offset(y = 19.dp).size(4.dp).background(if (on) Theme.green else Color.Transparent, CircleShape))
    }
}

// ---------------------------------------------------------------- lyrics card (a short preview that follows the song; tap for full screen)
@Composable
private fun LyricsCard(lyrics: LyricsResult, accent: Color, item: Track?, onOpen: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(accent)
            .tap { onOpen() }.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lyrics", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            item?.shareURL?.let { u ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)).tap { shareLink(u) }, contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_share, 15.dp, Color.White)
                }
            }
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(sym("arrow.up.left.and.arrow.down.right"), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(190.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstOut)
                },
        ) {
            LyricsScroller(lyrics, size = 22, spacing = 14, tappable = false, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Lyrics that follow the song. */
@Composable
private fun LyricsScroller(lyrics: LyricsResult, size: Int, spacing: Int, tappable: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    if (lyrics.lines.isNotEmpty()) {
        val state = rememberLazyListState()
        val pos by produceState(0, Engine.now) {
            while (true) { value = Engine.now.currentProgressMs + 250; delay(250) }
        }
        val cur = lyrics.lines.indexOfLast { it.timeMs <= pos }
        LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem(cur, if (tappable) -200 else 0) }
        LazyColumn(state = state, userScrollEnabled = tappable, modifier = modifier) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(lyrics.lines) { i, line ->
                Text(
                    line.text.ifEmpty { "♪" }, color = if (i == cur) Color.White else Color.White.copy(alpha = 0.38f),
                    fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size + 5).sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = spacing.dp)
                        .then(if (tappable) Modifier.tap { Haptics.tap(); scope.launch { Engine.seek(line.timeMs) } } else Modifier),
                )
            }
            item { Spacer(Modifier.height(if (tappable) 260.dp else 100.dp)) }
        }
    } else if (lyrics.plain.isNotEmpty()) {
        Column(modifier.then(if (tappable) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Text(lyrics.plain, color = Color.White, fontSize = if (tappable) 22.sp else 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
        }
    } else {
        Text(lyrics.message.ifEmpty { "No lyrics for this one." }, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp,
            modifier = modifier.padding(vertical = 6.dp))
    }
}

// ---------------------------------------------------------------- Playing Next (with where Cara will talk)
@Composable
private fun QueueCard(onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f))
            .tap { onOpen() }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Playing Next", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (Engine.playingFrom.isNotEmpty()) Text("From " + Engine.playingFrom, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(sym("arrow.up.left.and.arrow.down.right"), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (Engine.upNext.isEmpty()) {
            Text("Nothing's lined up. Spotify carries on from " + (if (Engine.playingFrom.isEmpty()) "your music." else Engine.playingFrom + "."),
                color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            if (Engine.breakSlot != null) CaraMarker()
        }
        for ((i, t) in Engine.upNext.take(6).withIndex()) {
            if (Engine.breakSlot == i) CaraMarker()
            if (!Silence.isSilence(t.uri)) QueueRow(t) { scope.launch { Engine.skip(i) } }
        }
    }
}

@Composable
private fun FullQueue() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Engine.refreshQueue() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Playing Next", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (Engine.playingFrom.isNotEmpty()) Text("From " + Engine.playingFrom, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                QueueToggle(R.drawable.ic_shuffle, Engine.now.shuffle) { scope.launch { Engine.toggleShuffle() } }
                Spacer(Modifier.width(8.dp))
                QueueToggle(if (Engine.now.repeatMode == "track") R.drawable.ic_repeat1 else R.drawable.ic_repeat, Engine.now.repeatMode != "off") {
                    scope.launch { Engine.cycleRepeat() }
                }
            }
        }
        if (Engine.upNext.isEmpty()) {
            item {
                Text("Nothing's lined up next.", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
                if (Engine.breakSlot != null) CaraMarker()
            }
        }
        itemsIndexed(Engine.upNext) { i, t ->
            if (Engine.breakSlot == i) CaraMarker()
            if (!Silence.isSilence(t.uri)) QueueRow(t) { scope.launch { Engine.skip(i) } }
        }
        item {
            Text("Spotify doesn't let apps reorder or remove songs in the queue. Press and hold a song for more.",
                color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun QueueToggle(res: Int, on: Boolean, action: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 32.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.16f))
            .press(0.9f) { Haptics.tap(); action() },
        contentAlignment = Alignment.Center,
    ) { AppIcon(res, 18.dp, if (on) Color.Black else Color.White) }
}

/** A song in "Playing Next". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(track: Track, onTap: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { Haptics.tap(); onTap() }, onLongClick = { Haptics.firm(); menu = true },
            ).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artMid, Modifier.size(44.dp), px = 150, corner = 5.dp)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistLine, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TrackMenu(track, menu, { menu = false }, onAddToPlaylist = { Router.addToPlaylist = it })
    }
}

/** Where Cara will come in, shown between the songs in "Playing Next". */
@Composable
private fun CaraMarker() {
    val s = Engine.queued ?: Engine.prepared?.style ?: ""
    val styleText = when (s) {
        "talkover" -> "Talking over the end of the song"
        "intro" -> "Over the start of the next song"
        "silent" -> "The music stops while she talks"
        else -> "She picks the style when she's ready"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 44.dp, height = 30.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(R.drawable.ic_dj, 15.dp, Color.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Cara talks here", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(styleText, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- full-screen lyrics / queue
@Composable
private fun FullPanel(accent: Color, top: Dp, bottom: Dp, onClose: () -> Unit, onCara: () -> Unit, content: @Composable () -> Unit) {
    val item = Engine.displayItem
    var more by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(accent).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = top + 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).tap { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(sym("chevron.down"), contentDescription = "Close", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item?.title ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item?.artistLine ?: "", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(40.dp))
            }
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Black, 0.04f to Color.Transparent, 0.9f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstOut)
                    },
            ) { content() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val u = item?.shareURL
                Box(Modifier.size(44.dp).then(if (u != null) Modifier.tap { shareLink(u) } else Modifier), contentAlignment = Alignment.Center) {
                    if (u != null) AppIcon(R.drawable.ic_share, 24.dp, Color.White)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).tap { more = true }, contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_more, 17.dp, Color.White)
                    }
                    MoreMenu(more, { more = false }, onCara)
                }
            }
            Box(Modifier.padding(horizontal = 24.dp).padding(top = 6.dp)) { ProgressBar() }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = bottom + 22.dp), contentAlignment = Alignment.Center) { PlayButton(72.dp, 26.dp) }
        }
    }
}

// ---------------------------------------------------------------- the cards under Playing Next
@Composable
private fun InfoCards(about: About, popular: List<Track>, popularName: String) {
    val scope = rememberCoroutineScope()
    var bioOpen by remember(about.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (about.song.isNotEmpty()) {
            InfoCard("About the song") {
                Text(about.song, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp)
                Text("Wikipedia", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (about.artistImage.isNotEmpty() || about.bio.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth().height(if (about.artistImage.isNotEmpty()) 260.dp else 70.dp)) {
                    if (about.artistImage.isNotEmpty()) {
                        Artwork(about.artistImage, Modifier.fillMaxSize(), px = 900, corner = 0.dp)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.5f to Color.Transparent)))
                    } else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)))
                    Text("About the artist", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
                }
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Engine.now.item?.artist ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    if (about.genres.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (g in about.genres.take(3)) {
                            Text(g.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    if (about.bio.isNotEmpty()) {
                        Text(about.bio, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp,
                            maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        if (about.bio.length > 220) Text(if (bioOpen) "show less" else "see more", color = Color.White, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.tap { bioOpen = !bioOpen })
                    }
                    val t = Engine.now.item
                    if (t != null && t.artistId.isNotEmpty()) {
                        Text("See artist page", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                .tap { Router.open(Route.ArtistPage(t.artistRef)) }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (popular.isNotEmpty()) {
            InfoCard("Popular tracks") {
                for ((i, t) in popular.take(5).withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().tap {
                            Haptics.tap()
                            scope.launch { Engine.playTracks(popular, i, name = popularName.ifEmpty { null }) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(t.artMid, Modifier.size(44.dp), px = 150, corner = 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artistLine, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${i + 1}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
            }
        }
        val t = Engine.now.item
        if (t != null && !Silence.isSilence(t.uri)) {
            InfoCard("Credits") {
                for ((i, n) in t.artists.withIndex()) CreditRow(n, if (i == 0) "Main Artist" else "Featured Artist")
                if (t.album.isNotEmpty()) CreditRow(t.album, "Album" + (if (t.year.isEmpty()) "" else " · " + t.year))
                if (t.release.length > 4) CreditRow(prettyDate(t.release), "Released")
                if (about.copyright.isNotEmpty()) CreditRow(about.copyright, "Copyright")
            }
            InfoCard("Explore") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (t.artistId.isNotEmpty()) ExploreTile("Songs by " + t.artist, Modifier.weight(1f)) {
                        scope.launch { Engine.playContext("spotify:artist:" + t.artistId, name = t.artists.firstOrNull() ?: t.artist) }
                    }
                    if (t.albumId.isNotEmpty()) ExploreTile("Play " + t.album.ifEmpty { "album" }, Modifier.weight(1f)) {
                        scope.launch { Engine.playContext("spotify:album:" + t.albumId, name = t.album.ifEmpty { null }) }
                    }
                    ExploreTile("Watch on YouTube", Modifier.weight(1f)) {
                        openUrl("https://www.youtube.com/results?search_query=" + URLEncoder.encode(t.title + " " + t.artist, "UTF-8"))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditRow(title: String, sub: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun ExploreTile(label: String, modifier: Modifier, action: () -> Unit) {
    Box(
        modifier.heightIn(min = 92.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))))
            .tap { Haptics.tap(); action() }.padding(10.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniBar(top: Dp) {
    val scope = rememberCoroutineScope()
    val item = Engine.displayItem
    val f = liveFraction()
    Box(Modifier.fillMaxWidth().background(Color(0xF0101014))) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = top + 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((item?.title ?: "") + "  · " + (item?.artist ?: ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Engine.now.deviceName, color = Theme.green, fontSize = 11.sp, maxLines = 1)
            }
            Box(Modifier.size(40.dp).tap { Haptics.tap(); scope.launch { Engine.togglePlay() } }, contentAlignment = Alignment.Center) {
                AppIcon(if (Engine.now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, 20.dp, Color.White)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White))
        }
    }
}

/** Her line, shown on the cover while she's talking. */
@Composable
private fun CaraCaption(text: String) {
    Row(
        Modifier.padding(10.dp).fillMaxWidth().frosted(14.dp).padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveBadge("CARA")
        Text(text.replace(npTags, ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- everything Cara can do (the "Cara Options…" sheet)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaraOptionsSheet(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xF2141418), contentColor = Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DJ OPTIONS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("SETTINGS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (Engine.running) Color(0xFFD32F2F) else Color.White)
                    .tap { Haptics.firm(); if (Engine.running) Engine.stop() else Engine.start() }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (Engine.running) "STOP DJ" else "START DJ", color = if (Engine.running) Color.White else Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
            SmallLabel(
                when {
                    Engine.speaking -> "DJ IS TALKING..."
                    Engine.running -> "DJ IS LIVE · " + Engine.statusLine.uppercase()
                    else -> "DJ IS OFF"
                }
            )
            if (Engine.line.isNotEmpty()) Text("“" + Engine.line.replace(npTags, "") + "”", color = Color.White, fontSize = 13.sp, fontStyle = FontStyle.Italic)

            StepperRow("DJ talks at least every", "${Config.breakMin} song${if (Config.breakMin == 1) "" else "s"}", Config.breakMin, 1..10) {
                Config.breakMin = it
                if (it > Config.breakMax) Config.breakMax = it
            }
            StepperRow("...and at most every", "${Config.breakMax} (random in between)", Config.breakMax, 1..10) {
                Config.breakMax = it
                if (it < Config.breakMin) Config.breakMin = it
            }
            StepperRow("Stinger chance", "${Config.stingerChance}% of silent breaks", Config.stingerChance, 0..100, 5) { Config.stingerChance = it }
            ToggleRow("Cara pops back in a few seconds into the song", Config.popinEnabled) { Config.popinEnabled = it }
            StepperRow("Pop-in chance", "${Config.popinChance}% of talk-over / intro breaks", Config.popinChance, 0..100, 5) { Config.popinChance = it }
            StepperRow("Pop-in timing", "about ${Config.popinSeconds} seconds into the song", Config.popinSeconds, 5..120, 5) { Config.popinSeconds = it }
            ToggleRow("Pop-in test mode (after every non-silent break)", Config.popinTest) { Config.popinTest = it }

            SheetSlider("DJ VOLUME", Config.djVolume) { Config.djVolume = it }
            SheetSlider("STINGER VOLUME", Config.stingerVolume) { Config.stingerVolume = it }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallLabel("HOW MUCH SHE SAYS")
                Segmented(listOf("quick" to "Quick", "normal" to "Normal", "chatty" to "Chatty"), Config.chattiness) { Config.chattiness = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallLabel("DJ MOOD")
                Segmented(listOf("chill" to "Chill", "normal" to "Normal", "unhinged" to "Unhinged", "mixed" to "Mixed"), Config.mood) { Config.mood = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallLabel("QUEUE NEXT (AT THE END OF THIS SONG)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((title, style) in listOf("TALK OVER" to "talkover", "OVER INTRO" to "intro", "SILENT" to "silent")) {
                        OptionPill(title, Modifier.weight(1f), selected = Engine.queued == style) { Engine.queue(style) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionPill("TEST DJ NOW", Modifier.weight(1f)) { Engine.testBreak() }
                OptionPill("TEST STINGER", Modifier.weight(1f)) { scope.launch { Engine.testStinger() } }
                OptionPill("TEST POP-IN", Modifier.weight(1f)) { Engine.testPopin() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallLabel("ACTIVITY")
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f))
                            .verticalScroll(rememberScrollState()).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (line in Engine.log.reversed()) {
                            Text(line, color = Color(0.6f, 0.82f, 0.66f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

/** A slider under a small caps label, like the iPhone's DJ options. */
@Composable
private fun SheetSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SmallLabel(title)
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
    }
}

@Composable
private fun OptionPill(title: String, modifier: Modifier, selected: Boolean = false, action: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.clip(shape).background(Color.White.copy(alpha = 0.14f))
            .border(2.dp, if (selected) Color.White else Color.Transparent, shape)
            .tap { Haptics.tap(); action() }.padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- speakers and other devices
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Engine.loadDevices() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Color(0xF21A1A1F), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("Play On", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f))) {
                if (Engine.devices.isEmpty()) {
                    Text("No Spotify devices found. Open Spotify on the phone, computer or speaker you want to use.", color = Theme.text2, fontSize = 16.sp,
                        modifier = Modifier.padding(16.dp))
                }
                for (d in Engine.devices) {
                    Row(
                        Modifier.fillMaxWidth().alpha(if (d.isRestricted) 0.4f else 1f)
                            .tap(enabled = !d.isRestricted) {
                                Haptics.tap()
                                scope.launch { Engine.transfer(d); onDismiss() }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(sym(d.symbol), contentDescription = null, tint = if (d.isActive) Theme.green else Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Color.White, fontSize = 17.sp)
                            if (d.isActive) Text("Playing now", color = Theme.green, fontSize = 12.sp)
                        }
                        if (d.isActive) Icon(sym("checkmark"), contentDescription = null, tint = Theme.green, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Text("Cara's voice always comes out of this phone, so she sounds best when the music plays here too.", color = Theme.text3, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}
