package com.cara.dj

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color(0xFF0D0D0D)
private val PILL = Color(0x24FFFFFF)
private val GRAY = Color(0xFF9A9A9A)
private val GREEN = Color(0xFF99D1A8)

@Composable
fun CaraApp() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, onPrimary = Color.Black, background = BG, surface = BG)) {
        Surface(modifier = Modifier.fillMaxSize(), color = BG) {
            var showSettings by remember { mutableStateOf(false) }
            val art = rememberArt(Engine.now.track?.art)
            LaunchedEffect(Unit) {
                Engine.startBackgroundPolling()
                if (Spotify.isLoggedIn) Engine.connect()
                else if (Config.clientID.isEmpty()) showSettings = true
            }
            Box(Modifier.fillMaxSize()) {
                CoverBackground(art, dim = if (showSettings) 0.72f else 0f)
                if (showSettings) SettingsScreen(onDone = { showSettings = false })
                else MainScreen(art = art, onSettings = { showSettings = true })
            }
        }
    }
}

// ---------- the album cover fills the whole screen ----------
@Composable
private fun rememberArt(url: String?): Bitmap? {
    val bmp by produceState<Bitmap?>(null, url) {
        if (url.isNullOrEmpty()) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            try { java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        }
    }
    return bmp
}

@Composable
private fun CoverBackground(art: Bitmap?, dim: Float) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1B28), Color.Black)))) {
        // cross-fades when the song changes (the old cover stays until the new one has loaded)
        Crossfade(targetState = art, animationSpec = tween(900), label = "bg") { b ->
            if (b != null) {
                // shrunk tiny and stretched back up = a soft frosted wash of the cover's colours
                val soft = remember(b) { Bitmap.createScaledBitmap(b, 32, 32, true).asImageBitmap() }
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = soft, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(30.dp, BlurredEdgeTreatment.Unbounded),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
            }
        }
        // darkens the bottom so the text and buttons stay readable
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.4f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
            )
        )
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

// ---------- small builders ----------
@Composable
private fun Label(text: String) {
    Text(text, color = GRAY, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PILL, contentColor = Color.White),
        border = if (selected) BorderStroke(2.dp, Color.White) else null,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
    ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun BigButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun Stepper(text: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Pill("-", Modifier.padding(start = 8.dp)) { onChange(maxOf(min, value - step)) }
        Pill("+", Modifier.padding(start = 6.dp)) { onChange(minOf(max, value + step)) }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Label("$title  ${value.toInt()}%")
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = PILL),
        )
    }
}

/** The white icons (play, pause, prev, next, DJ) come from res/drawable-nodpi; the "..." is drawn by hand. */
@Composable
private fun Glyph(kind: String, size: Dp, tint: Color = Color.White, onClick: (() -> Unit)? = null) {
    val base = Modifier.size(size + 24.dp).clip(CircleShape)
    val res = when (kind) {
        "play" -> R.drawable.ic_play
        "pause" -> R.drawable.ic_pause
        "next" -> R.drawable.ic_next
        "prev" -> R.drawable.ic_prev
        "wave" -> R.drawable.ic_dj
        "shuffle" -> R.drawable.ic_shuffle
        "repeat" -> R.drawable.ic_repeat
        "repeat1" -> R.drawable.ic_repeat1
        "dots" -> R.drawable.ic_more
        "share" -> R.drawable.ic_share
        else -> null
    }
    Box(if (onClick != null) base.clickable { onClick() } else base, contentAlignment = Alignment.Center) {
        if (res != null) {
            Image(painterResource(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            Canvas(Modifier.size(size)) {
                val r = this.size.height * 0.11f
                for (i in 0..2) drawCircle(tint, r, Offset(this.size.width * (0.2f + 0.3f * i), this.size.height * 0.5f))
            }
        }
    }
}

@Composable
private fun ModeButton(kind: String, on: Boolean, green: Color, onClick: () -> Unit) {
    Column(Modifier.size(52.dp).clip(CircleShape).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(kind, 24.dp, tint = if (on) green else Color.White.copy(alpha = 0.85f))
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) green else Color.Transparent))
    }
}

/** The cover's average colour, darkened, for the lyrics card. */
private fun avgColor(b: Bitmap): Color {
    val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
    return Color(android.graphics.Color.red(px) / 255f * 0.75f, android.graphics.Color.green(px) / 255f * 0.75f, android.graphics.Color.blue(px) / 255f * 0.75f)
}

private fun clock(ms: Int): String {
    val s = maxOf(0, ms / 1000)
    return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).padding(18.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable
private fun CreditRow(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, maxLines = 2)
        Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun ExploreTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(92.dp).clip(RoundedCornerShape(12.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))))
        .clickable { onClick() }.padding(10.dp), contentAlignment = Alignment.BottomStart) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun compactNum(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

// ---------- main screen ----------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(art: Bitmap?, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tick by produceState(0L) { while (true) { delay(500); value = System.currentTimeMillis() } }
    var showOptions by remember { mutableStateOf(false) }
    val shadowed = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f))

    val now = Engine.now
    val track = now.track
    val lyricsResult by produceState(LyricsResult(message = "Finding lyrics..."), track?.title, track?.artist) {
        value = if (track == null) LyricsResult(message = "Nothing playing.")
        else { value = LyricsResult(message = "Finding lyrics..."); Lyrics.find(track, now.durationMs) }
    }
    val accentTarget = remember(art) { art?.let { avgColor(it) } ?: Color(0xFF402F2F) }
    val accent by animateColorAsState(accentTarget, tween(900), label = "accent")
    val green = Color(0xFF5ED16E)
    var drag by remember { mutableStateOf<Float?>(null) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val inLyr = listState.firstVisibleItemIndex >= 1
    val lyrPos = if (tick >= 0) now.currentProgressMs + 250 else 0
    val curLine = lyricsResult.lines.indexOfLast { it.timeMs <= lyrPos }
    val about by produceState(About(), track?.uri) {
        value = About()
        if (track != null && track.uri.isNotEmpty()) value = try { loadAbout(track) } catch (e: Exception) { About() }
    }
    var bioOpen by remember(track?.uri) { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    val shareTrack = {
        if (track != null && track.uri.startsWith("spotify:")) {
            val p = track.uri.split(":")
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, track.title + " - " + track.artist + "\nhttps://open.spotify.com/" + p[1] + "/" + p[2])
            shareCtx.startActivity(android.content.Intent.createChooser(i, null))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageH = maxHeight - (84.dp + navPad)
    val pagePx = with(density) { pageH.toPx() }
    val miniOn = listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > pagePx * 0.8f
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
      item {
        Column(Modifier.fillMaxWidth().height(pageH).statusBarsPadding()) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 16.dp)) {
            if (!Engine.connected) {
                Spacer(Modifier.height(12.dp))
                BigButton("CONNECT SPOTIFY", Color.White, Color.Black) { scope.launch { Engine.connect() } }
            }

            // the real album cover, sharp, in the middle of the frosted background
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                val side = minOf(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp * 0.78f, maxHeight, 400.dp)
                val coverShape = RoundedCornerShape(10.dp)
                Box(Modifier.size(side).shadow(22.dp, coverShape).clip(coverShape).background(Color.White.copy(alpha = 0.08f))) {
                    Crossfade(targetState = art, animationSpec = tween(900), label = "cover") { b ->
                        if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            // song name + artist, the DJ on/off button and the "..." button that opens every DJ option
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: (if (Engine.connected) "Nothing playing" else "Not connected"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, style = shadowed)
                    Text(track?.artist ?: "Start a playlist in the Spotify app.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, style = shadowed)
                }
                // DJ on / off: white and filled while she is live
                Box(
                    Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape)
                        .background(if (Engine.running) Color.White else Color.White.copy(alpha = 0.22f))
                        .clickable { if (Engine.running) Engine.stop() else Engine.start() },
                    contentAlignment = Alignment.Center,
                ) { Glyph("wave", 15.dp, tint = if (Engine.running) Color.Black else Color.White) }
                Box(Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).clickable { showOptions = true }, contentAlignment = Alignment.Center) {
                    Glyph("dots", 17.dp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // progress bar with a round handle; drag it to seek
            val dur = maxOf(now.durationMs, 1)
            val cur = if (tick >= 0) minOf(now.currentProgressMs, dur) else 0
            val frac = drag ?: (cur.toFloat() / dur).coerceIn(0f, 1f)
            val shownMs = drag?.let { (it * dur).toInt() } ?: cur
            Slider(
                value = frac,
                onValueChange = { drag = it },
                onValueChangeFinished = { val v = drag; drag = null; if (v != null) scope.launch { Engine.seek((v * dur).toInt()) } },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(shownMs), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                Text("-" + clock(dur - shownMs), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            }

            Spacer(Modifier.height(20.dp))

            // shuffle, previous, the big round play/pause, next, repeat
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ModeButton("shuffle", now.shuffle, green) { scope.launch { Engine.toggleShuffle() } } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Glyph("prev", 24.dp) { scope.launch { Engine.previous() } } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White).clickable { scope.launch { Engine.togglePlay() } }, contentAlignment = Alignment.Center) {
                        Glyph(if (now.isPlaying) "pause" else "play", 22.dp, tint = Color.Black)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Glyph("next", 24.dp) { scope.launch { Engine.next() } } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ModeButton(if (now.repeat == "track") "repeat1" else "repeat", now.repeat != "off", green) { scope.launch { Engine.cycleRepeat() } } }
            }

            // which device is playing
            if (Engine.connected) {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_speaker), contentDescription = null, colorFilter = ColorFilter.tint(green), modifier = Modifier.size(17.dp))
                    Text(if (now.deviceName.isEmpty()) "This device" else now.deviceName, color = green, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable { shareTrack() }, contentAlignment = Alignment.Center) { Glyph("share", 16.dp) }
                }
            }
        }

        }
      }
      // compact lyrics card: a short preview that follows the song; tap to open it full screen
      item {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(22.dp)).background(accent)
                .clickable { showFull = true }.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Lyrics", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)).clickable { shareTrack() }, contentAlignment = Alignment.Center) { Glyph("share", 15.dp) }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Text("\u2922", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
            val pv = rememberLazyListState()
            LaunchedEffect(curLine) { if (curLine >= 0) pv.animateScrollToItem(curLine) }
            Box(Modifier.fillMaxWidth().height(190.dp)
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut) }) {
                if (lyricsResult.lines.isNotEmpty()) {
                    LazyColumn(state = pv, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lyricsResult.lines) { i, line ->
                            Text(if (line.text.isEmpty()) "\u266A" else line.text, color = if (i == curLine) Color.White else Color.White.copy(alpha = 0.4f),
                                fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        }
                        item { Spacer(Modifier.height(120.dp)) }
                    }
                } else {
                    Text(if (lyricsResult.plain.isNotEmpty()) lyricsResult.plain else lyricsResult.message.ifEmpty { "Lyrics show up here when the song has them." },
                        color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
      }
      // ---- the info cards under the lyrics ----
      item { Spacer(Modifier.height(14.dp)) }
      if (about.song.isNotEmpty()) item {
        InfoCard("About the song") {
            Text(about.song, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp)
            Text("Wikipedia", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
      }
      if (about.artistImage.isNotEmpty() || about.bio.isNotEmpty()) item {
        val heroArt = rememberArt(about.artistImage.ifEmpty { null })
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth().height(if (heroArt != null) 260.dp else 64.dp)) {
                if (heroArt != null) Image(bitmap = heroArt.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Text("About the artist", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp), style = shadowed)
            }
            Column(Modifier.padding(18.dp)) {
                Text(track?.artist ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                if (about.followers > 0) Text(compactNum(about.followers) + " followers", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                if (about.genres.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    about.genres.take(3).forEach { g -> Text(g.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp)) }
                }
                if (about.bio.isNotEmpty()) {
                    Text(about.bio, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp, maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).animateContentSize())
                    if (about.bio.length > 220) Text(if (bioOpen) "show less" else "see more", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).clickable { bioOpen = !bioOpen })
                }
                if (about.artistUrl.isNotEmpty()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Text("Open in Spotify", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                            .clickable { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(about.artistUrl))) }.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
      }
      if (about.pop.isNotEmpty()) item {
        InfoCard("Popular tracks") {
            about.pop.forEachIndexed { i, p ->
                val img = rememberArt(p.art.ifEmpty { null })
                Row(Modifier.fillMaxWidth().clickable { scope.launch { Spotify.playContext(null, p.uri, now.deviceID) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.1f))) {
                        if (img != null) Image(bitmap = img.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(p.artists, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
                    }
                    Text("${i + 1}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
        }
      }
      if (track != null) item {
        InfoCard("Credits") {
            track.artists.forEachIndexed { i, n -> CreditRow(n, if (i == 0) "Main Artist" else "Featured Artist") }
            if (track.album.isNotEmpty()) CreditRow(track.album, "Album" + (if (track.year.isNotEmpty()) " \u00B7 " + track.year else ""))
            if (about.label.isNotEmpty()) CreditRow(about.label, "Label")
            if (track.release.length > 4) CreditRow(track.release, "Released")
            if (about.copyright.isNotEmpty()) CreditRow(about.copyright, "Copyright")
        }
      }
      if (track != null) item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        InfoCard("Explore") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (track.artistId.isNotEmpty()) ExploreTile("Songs by " + track.artist, Modifier.weight(1f)) { scope.launch { Spotify.playContext("spotify:artist:" + track.artistId, null, now.deviceID) } }
                if (track.albumId.isNotEmpty()) ExploreTile("Play " + track.album.ifEmpty { "album" }, Modifier.weight(1f)) { scope.launch { Spotify.playContext("spotify:album:" + track.albumId, null, now.deviceID) } }
                ExploreTile("Watch on YouTube", Modifier.weight(1f)) {
                    val q = java.net.URLEncoder.encode(track.title + " " + track.artist, "UTF-8")
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$q")))
                }
            }
        }
      }
      item { Spacer(Modifier.height(40.dp + navPad)) }
    }

    // small player that slides in once you scroll into the cards
    androidx.compose.animation.AnimatedVisibility(
        visible = miniOn,
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).background(Color(0xF0101014)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((track?.title ?: "") + "  \u00B7 " + (track?.artist ?: ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(now.deviceName, color = green, fontSize = 11.sp, maxLines = 1)
                }
                Glyph(if (now.isPlaying) "pause" else "play", 20.dp) { scope.launch { Engine.togglePlay() } }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction = if (now.durationMs > 0) (now.currentProgressMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f).height(2.dp).background(Color.White))
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = showFull,
        enter = androidx.compose.animation.scaleIn(initialScale = 0.88f, animationSpec = tween(420)) + androidx.compose.animation.fadeIn(tween(300)),
        exit = androidx.compose.animation.scaleOut(targetScale = 0.88f, animationSpec = tween(300)) + androidx.compose.animation.fadeOut(tween(250)),
    ) {
        LyricsFull(lyricsResult, curLine, accent, track, now, onClose = { showFull = false }, onShare = { shareTrack() }, onDots = { showFull = false; showOptions = true })
    }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }, containerColor = Color(0xF2141418), contentColor = Color.White) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DJ OPTIONS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Pill("SETTINGS", Modifier.size(width = 100.dp, height = 44.dp)) { showOptions = false; onSettings() }
                }

                // start / stop the DJ
                if (Engine.running) BigButton("STOP DJ", Color(0xFFD32F2F), Color.White) { Engine.stop() }
                else BigButton("START DJ", Color.White, Color.Black) { Engine.start() }
                Label(if (Engine.busy) "DJ IS TALKING..." else if (Engine.running) "DJ IS LIVE" else "DJ IS OFF")
                if (Engine.line.isNotEmpty()) Text("“${Engine.line}”", color = Color.White, fontSize = 13.sp, fontStyle = FontStyle.Italic)

                Stepper("Fewest songs between DJ breaks: ${Config.breakMin}", Config.breakMin, 1, 10) {
                    Config.breakMin = it
                    if (it > Config.breakMax) Config.breakMax = it
                }
                Stepper("Most songs between DJ breaks: ${Config.breakMax}", Config.breakMax, 1, 10) {
                    Config.breakMax = it
                    if (it < Config.breakMin) Config.breakMin = it
                }
                Stepper("Stinger chance: ${Config.stingerChance}% of silent breaks", Config.stingerChance, 0, 100, 5) { Config.stingerChance = it }

                Pill(if (Config.popinEnabled) "CARA POPS BACK IN: ON" else "CARA POPS BACK IN: OFF", Modifier.fillMaxWidth(), selected = Config.popinEnabled) { Config.popinEnabled = !Config.popinEnabled }
                Stepper("Pop-in chance: ${Config.popinChance}% of talk-over / intro breaks", Config.popinChance, 0, 100, 5) { Config.popinChance = it }
                Stepper("Pop-in about ${Config.popinSeconds} seconds into the song (a random 5 either way)", Config.popinSeconds, 5, 120, 5) { Config.popinSeconds = it }
                Pill(if (Config.popinTest) "POP-IN TEST MODE: ON (after every non-silent break)" else "POP-IN TEST MODE: OFF", Modifier.fillMaxWidth(), selected = Config.popinTest) { Config.popinTest = !Config.popinTest }

                SliderRow("DJ VOLUME", Config.djVolume) { Config.djVolume = it }
                SliderRow("STINGER VOLUME", Config.stingerVolume) { Config.stingerVolume = it }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("DJ MOOD")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((tag, name) in listOf("chill" to "CHILL", "normal" to "NORMAL", "unhinged" to "UNHINGED", "mixed" to "MIXED"))
                            Pill(name, Modifier.weight(1f), selected = Config.mood == tag) { Config.mood = tag }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("QUEUE NEXT (AT THE END OF THIS SONG)")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("TALK OVER", Modifier.weight(1f), selected = Engine.queued == "talkover") { Engine.queue("talkover") }
                        Pill("OVER INTRO", Modifier.weight(1f), selected = Engine.queued == "intro") { Engine.queue("intro") }
                        Pill("SILENT", Modifier.weight(1f), selected = Engine.queued == "silent") { Engine.queue("silent") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("TEST DJ NOW", Modifier.weight(1f)) { Engine.testBreak() }
                    Pill("TEST STINGER", Modifier.weight(1f)) { scope.launch { Engine.testStinger() } }
                    Pill("TEST POP-IN", Modifier.weight(1f)) { Engine.testPopin() }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("ACTIVITY")
                    Column(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.4f)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                        for (m in Engine.log.reversed())
                            Text(m, color = GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
    }
}

// ---------- full-screen lyrics ----------
@Composable
private fun LyricsFull(result: LyricsResult, cur: Int, accent: Color, track: Track?, now: Playback, onClose: () -> Unit, onShare: () -> Unit, onDots: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tick by produceState(0L) { while (true) { delay(500); value = System.currentTimeMillis() } }
    val listState = rememberLazyListState()
    LaunchedEffect(cur) {
        if (cur >= 0) listState.animateScrollToItem(cur + 1, -(listState.layoutInfo.viewportSize.height / 4))
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = maxOf(now.durationMs, 1)
    val pos = if (tick >= 0) minOf(now.currentProgressMs, dur) else 0
    val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val shownMs = drag?.let { (it * dur).toInt() } ?: pos
    Box(Modifier.fillMaxSize().background(accent).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(width = 20.dp, height = 12.dp)) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.05f, size.height * 0.1f); lineTo(size.width * 0.5f, size.height * 0.9f); lineTo(size.width * 0.95f, size.height * 0.1f)
                        }
                        drawPath(p, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track?.title ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(track?.artist ?: "", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1)
                }
                Spacer(Modifier.size(40.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black, 0.04f to Color.Transparent, 0.9f to Color.Transparent, 1f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
                }) {
                when {
                    result.lines.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                        item { Spacer(Modifier.height(18.dp)) }
                        itemsIndexed(result.lines) { i, line ->
                            Text(
                                if (line.text.isEmpty()) "♪" else line.text,
                                color = if (i == cur) Color.White else Color.White.copy(alpha = 0.38f),
                                fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp,
                                modifier = Modifier.fillMaxWidth().clickable { scope.launch { Engine.seek(line.timeMs) } }.padding(bottom = 22.dp),
                            )
                        }
                        item { Spacer(Modifier.height(260.dp)) }
                    }
                    result.plain.isNotEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text(result.plain, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> Text(result.message, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart).padding(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Glyph("share", 24.dp) { onShare() }
                Glyph("dots", 22.dp) { onDots() }
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                Slider(
                    value = frac,
                    onValueChange = { drag = it },
                    onValueChangeFinished = { val v = drag; drag = null; if (v != null) scope.launch { Engine.seek((v * dur).toInt()) } },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(shownMs), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                    Text("-" + clock(dur - shownMs), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable { scope.launch { Engine.togglePlay() } }, contentAlignment = Alignment.Center) {
                    Glyph(if (now.isPlaying) "pause" else "play", 26.dp, tint = Color.Black)
                }
            }
        }
    }
}

// ---------- settings ----------
@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cityText by remember { mutableStateOf(Config.city) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

        Label("SPOTIFY")
        OutlinedTextField(value = Config.clientID, onValueChange = { Config.clientID = it.trim() }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("In your Spotify app settings, add this Redirect URI:  caradj://callback", color = GRAY, fontSize = 12.sp)
        if (Spotify.isLoggedIn) Pill("LOG OUT OF SPOTIFY", Modifier.fillMaxWidth()) { Spotify.logout(); Engine.connected = false }
        else Pill("CONNECT SPOTIFY", Modifier.fillMaxWidth()) { onDone(); scope.launch { Engine.connect() } }

        Label("ELEVENLABS (YOUR VOICE)")
        OutlinedTextField(
            value = Config.elevenKey, onValueChange = { Config.elevenKey = it.trim() }, label = { Text("API key (starts with sk_)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = Config.elevenVoice, onValueChange = { Config.elevenVoice = it.trim() }, label = { Text("Voice ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Label("MODEL")
        Pill("ELEVEN V4 (MOST EXPRESSIVE)", Modifier.fillMaxWidth(), selected = Config.elevenModel == "eleven_v4") { Config.elevenModel = "eleven_v4" }
        Pill("ELEVEN V4 TURBO (FASTER)", Modifier.fillMaxWidth(), selected = Config.elevenModel == "eleven_v4_turbo") { Config.elevenModel = "eleven_v4_turbo" }
        Pill("MULTILINGUAL V2 (OLDER)", Modifier.fillMaxWidth(), selected = Config.elevenModel == "eleven_multilingual_v2") { Config.elevenModel = "eleven_multilingual_v2" }

        Label("GEMINI (WRITES HER LINES)")
        OutlinedTextField(
            value = Config.geminiKey, onValueChange = { Config.geminiKey = it.trim() }, label = { Text("API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )

        Label("TOWN")
        OutlinedTextField(value = cityText, onValueChange = { cityText = it }, label = { Text("Yakima, Washington") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text(
            "Tip: in Android Settings > Apps > Cara DJ > Battery, choose Unrestricted so the DJ keeps going with the screen off.",
            color = GRAY, fontSize = 12.sp,
        )
        BigButton("DONE", Color.White, Color.Black) { scope.launch { Engine.changeCity(cityText) }; onDone() }
    }
}
