package com.cara.dj

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

private fun clock(ms: Int): String {
    val s = maxOf(0, ms / 1000)
    return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
}

// ---------- main screen ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(art: Bitmap?, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tick by produceState(0L) { while (true) { delay(500); value = System.currentTimeMillis() } }
    var showOptions by remember { mutableStateOf(false) }
    val shadowed = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f))

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
        // top
        Text("NON STOP POP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, style = shadowed)
        Text("LIVE FROM ${Config.city.uppercase()}", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, letterSpacing = 1.sp, style = shadowed)
        if (!Engine.connected) {
            Spacer(Modifier.height(12.dp))
            BigButton("CONNECT SPOTIFY", Color.White, Color.Black) { scope.launch { Engine.connect() } }
        }

        Spacer(Modifier.weight(1f))

        // the real album cover, sharp, in the middle of the frosted background
        val coverShape = RoundedCornerShape(12.dp)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp).aspectRatio(1f)
                .shadow(24.dp, coverShape).clip(coverShape).background(Color.White.copy(alpha = 0.08f))
        ) {
            Crossfade(targetState = art, animationSpec = tween(900), label = "cover") { b ->
                if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.weight(1f))

        // song name + artist, and the "..." button that opens every DJ option
        val now = Engine.now
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(now.track?.title ?: (if (Engine.connected) "Nothing playing" else "Not connected"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, style = shadowed)
                Text(now.track?.artist ?: "Start a playlist in the Spotify app.", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp, maxLines = 1, style = shadowed)
            }
            Box(Modifier.padding(start = 8.dp).size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).clickable { showOptions = true }, contentAlignment = Alignment.Center) {
                Glyph("dots", 20.dp)
            }
        }

        Spacer(Modifier.height(22.dp))

        // progress
        val dur = maxOf(now.durationMs, 1)
        val cur = if (tick >= 0) minOf(now.currentProgressMs, dur) else 0
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth((cur.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(cur), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            Text("-" + clock(dur - cur), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
        }

        Spacer(Modifier.height(16.dp))

        // transport
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Glyph("prev", 30.dp) { scope.launch { Engine.previous() } }
            Glyph(if (now.isPlaying) "pause" else "play", 40.dp) { scope.launch { Engine.togglePlay() } }
            Glyph("next", 30.dp) { scope.launch { Engine.next() } }
        }

        Spacer(Modifier.height(20.dp))

        // bottom centre: starts and stops the DJ
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val live = Engine.running
            Box(
                Modifier.size(54.dp).clip(CircleShape)
                    .background(if (live) Color.White else Color.White.copy(alpha = 0.22f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { if (live) Engine.stop() else Engine.start() },
                contentAlignment = Alignment.Center,
            ) { Glyph("wave", 22.dp, tint = if (live) Color.Black else Color.White) }
            Spacer(Modifier.height(6.dp))
            Text(
                if (Engine.busy) "DJ IS TALKING..." else if (live) "DJ IS LIVE - TAP TO STOP" else "START DJ",
                color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )
            if (Engine.line.isNotEmpty())
                Text("“${Engine.line}”", color = Color.White, fontSize = 12.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(top = 4.dp), style = shadowed)
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }, containerColor = Color(0xF2141418), contentColor = Color.White) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DJ OPTIONS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Pill("SETTINGS", Modifier.size(width = 100.dp, height = 44.dp)) { showOptions = false; onSettings() }
                }

                Stepper("Fewest songs between DJ breaks: ${Config.breakMin}", Config.breakMin, 1, 10) {
                    Config.breakMin = it
                    if (it > Config.breakMax) Config.breakMax = it
                }
                Stepper("Most songs between DJ breaks: ${Config.breakMax}", Config.breakMax, 1, 10) {
                    Config.breakMax = it
                    if (it < Config.breakMin) Config.breakMin = it
                }
                Stepper("Stinger chance: ${Config.stingerChance}% of silent breaks", Config.stingerChance, 0, 100, 5) { Config.stingerChance = it }

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
