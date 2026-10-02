package com.cara.dj

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val tagBits = Regex("""\[[^\]]*\]""")

/** Cara's tab: go live, set her mood, line up the next transition, fine-tune her, and read her log. */
@Composable
fun CaraScreen() {
    val scope = rememberCoroutineScope()
    var showLog by remember { mutableStateOf(false) }
    Page(title = "Cara", actions = { AvatarButton() }) {
        item { Spacer(Modifier.height(6.dp)) }
        item { Hero() }
        if (Engine.line.isNotEmpty()) item { Spacer(Modifier.height(26.dp)); LastLine() }
        item {
            Gap()
            TitledCard("Next Transition", "Lines up how she comes in at the end of this song.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyleButton("Talk Over", "waveform", "talkover", Modifier.weight(1f))
                    StyleButton("Over Intro", "forward.end.fill", "intro", Modifier.weight(1f))
                    StyleButton("Silent", "pause.fill", "silent", Modifier.weight(1f))
                }
            }
        }
        item {
            Gap()
            TitledCard("Right Now") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("Talk Now", "mic.fill", Modifier.weight(1f)) { Engine.testBreak() }
                    ActionTile("Pop In", "sparkles", Modifier.weight(1f)) { Engine.testPopin() }
                    ActionTile("Stinger", "bolt.fill", Modifier.weight(1f)) { scope.launch { Engine.testStinger() } }
                    ActionTile("With Scratch", "person.2.fill", Modifier.weight(1f)) { Engine.testDuo() }
                }
            }
        }
        item {
            Gap()
            TitledCard("Her Mood", moodFooter()) {
                Segmented(listOf("chill" to "Chill", "normal" to "Normal", "unhinged" to "Unhinged", "mixed" to "Mixed"), Config.mood) { Config.mood = it }
            }
        }
        item {
            Gap()
            TitledCard("How Much She Says", chatFooter()) {
                Segmented(listOf("quick" to "Quick", "normal" to "Normal", "chatty" to "Chatty"), Config.chattiness) { Config.chattiness = it }
            }
        }
        item {
            Gap()
            TitledCard("How Often She Talks", "A random number of songs in between, every time.") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepperRow("At least every", songs(Config.breakMin), Config.breakMin, 1..10) {
                        Config.breakMin = it
                        if (it > Config.breakMax) Config.breakMax = it
                    }
                    Hairline()
                    StepperRow("At most every", songs(Config.breakMax), Config.breakMax, 1..10) {
                        Config.breakMax = it
                        if (it < Config.breakMin) Config.breakMin = it
                    }
                }
            }
        }
        item {
            Gap()
            TitledCard("Pop-Ins", "After a talk-over or intro break she can pop back in a few seconds into the next song, over the music.") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow("Pop back in", Config.popinEnabled) { Config.popinEnabled = it }
                    Hairline()
                    StepperRow("Chance", "${Config.popinChance}%", Config.popinChance, 0..100, 5, enabled = Config.popinEnabled) { Config.popinChance = it }
                    Hairline()
                    StepperRow("Seconds into the song", "about ${Config.popinSeconds}", Config.popinSeconds, 5..120, 5, enabled = Config.popinEnabled) { Config.popinSeconds = it }
                    Hairline()
                    ToggleRow("Test mode (after every break)", Config.popinTest, enabled = Config.popinEnabled) { Config.popinTest = it }
                }
            }
        }
        item {
            Gap()
            TitledCard(
                "Co-Host",
                if (Config.coHost) "MC Scratch, Cara's West Coast co-host, joins this share of her breaks for a back-and-forth. His voice is in Settings."
                else "Turn on MC Scratch, Cara's West Coast co-host, for back-and-forth breaks.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ToggleRow("MC Scratch", Config.coHost) { Config.coHost = it }
                    if (Config.coHost) {
                        Hairline()
                        StepperRow("Together", "${Config.coHostChance}% of breaks", Config.coHostChance, 10..100, 10) { Config.coHostChance = it }
                    }
                }
            }
        }
        item {
            Gap()
            TitledCard(
                "Sound",
                if (Config.stationStingers) "Silent breaks start with a stinger this often. Station stingers are your stingers word for word, with the name of whatever's playing in place of Non-Stop-Pop, read by the station voice (Settings)."
                else "Silent breaks start with one of your stingers this often.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SliderRow("Cara's volume", Config.djVolume) { Config.djVolume = it }
                    SliderRow("Stinger volume", Config.stingerVolume) { Config.stingerVolume = it }
                    Hairline()
                    StepperRow("Stinger chance", "${Config.stingerChance}%", Config.stingerChance, 0..100, 5) { Config.stingerChance = it }
                    Hairline()
                    ToggleRow("Station stingers", Config.stationStingers) { Config.stationStingers = it }
                    if (Config.stationStingers) {
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 32.dp).tap {
                                Haptics.tap()
                                StationStingers.clearAll()
                                Toasts.show("New takes on the way", "bolt.fill")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Re-record stingers", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Icon(sym("arrow.clockwise"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        item {
            Gap()
            TitledCard("Her Town", "Local news and weather come from here.") {
                Row(Modifier.fillMaxWidth().tap { Router.showSettings = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(sym("mappin.and.ellipse"), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(Config.city, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Change", color = Theme.text2, fontSize = 16.sp)
                    Icon(sym("chevron.right"), contentDescription = null, tint = Theme.text3, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            Gap()
            Column(
                Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val turn by animateFloatAsState(if (showLog) 180f else 0f, tween(250), label = "log")
                Row(Modifier.fillMaxWidth().tap { showLog = !showLog }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Activity", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(sym("chevron.down"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(24.dp).rotate(turn))
                }
                AnimatedVisibility(showLog) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionContainer {
                            Column(
                                Modifier.fillMaxWidth().height(260.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for (line in Engine.log.reversed()) {
                                    Text(line, color = Color(0.6f, 0.88f, 0.68f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Text("Newest at the top. If something goes wrong, a screenshot of this helps.", color = Theme.text2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Gap() = Spacer(Modifier.height(26.dp))

private fun songs(n: Int) = "$n song${if (n == 1) "" else "s"}"

private fun chatFooter(): String = when (Config.chattiness) {
    "quick" -> "Short drop-ins, in and out."
    "normal" -> "A few lines each time."
    else -> "Proper segments: stories, games, news and nonsense. Silent breaks are her longest."
}

private fun moodFooter(): String = when (Config.mood) {
    "chill" -> "Laid-back and dry, a bit quieter."
    "unhinged" -> "Maximum drama. Still clean, still short."
    "mixed" -> "A different mood every break."
    else -> "Her usual cheeky self."
}

private fun styleName(s: String): String = when (s) {
    "talkover" -> "talk-over"
    "intro" -> "over the intro"
    "silent" -> "silent break"
    else -> s
}

@Composable
private fun Hero() {
    val live = Engine.running
    val glow by animateFloatAsState(if (live) 0.55f else 0.3f, tween(350), label = "glow")
    Column(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(28.dp, 0.05f).padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.height(120.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp)) {
                drawCircle(Brush.radialGradient(listOf(Theme.accent.copy(alpha = glow), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = 90.dp.toPx()))
            }
            StationLogo(active = live, modifier = Modifier.size(width = 110.dp, height = 76.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(Engine.stationFull.uppercase(), color = Theme.text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            Text(if (live) (if (Engine.speaking) "Cara's on the mic" else "Cara is live") else "Cara is off air", color = Color.White,
                fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(Engine.statusLine, color = Theme.text2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        PillLabel(
            if (live) "End Show" else "Go Live", icon = if (live) "stop.fill" else "dot.radiowaves.left.and.right", primary = !live, height = 52.dp,
            modifier = Modifier.widthIn(max = 260.dp).press { Haptics.firm(); if (Engine.running) Engine.stop() else Engine.start() },
        )
        if (!Engine.connected) Text("Connect Spotify first (Home tab).", color = Theme.text2, fontSize = 13.sp)
    }
}

@Composable
private fun LastLine() {
    Column(
        Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(22.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(sym("quote.opening"), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(18.dp))
            Text(if (Engine.speaking) "On air now" else "Last on air", color = Theme.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (Engine.lineStyle.isNotEmpty()) Text("· " + styleName(Engine.lineStyle), color = Theme.text2, fontSize = 13.sp)
        }
        Text(Engine.line.replace(tagBits, ""), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic)
    }
}

@Composable
private fun StyleButton(title: String, icon: String, style: String, modifier: Modifier) {
    val on = Engine.queued == style
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = 60.dp)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.08f), shape)
            .border(0.7.dp, Color.White.copy(alpha = if (on) 0f else 0.1f), shape)
            .press { Haptics.tap(); Engine.queue(style) }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val fg = if (on) Color.Black else Color.White
        Icon(sym(icon), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(title, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ActionTile(title: String, icon: String, modifier: Modifier, action: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = 60.dp)
            .background(Color.White.copy(alpha = 0.08f), shape)
            .border(0.7.dp, Color.White.copy(alpha = 0.1f), shape)
            .press { Haptics.tap(); action() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(sym(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
    }
}
