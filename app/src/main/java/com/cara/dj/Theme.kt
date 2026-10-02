package com.cara.dj

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameMillis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The iPhone app's look, colour for colour. */
object Theme {
    /** The Apple Music pink-red, which also happens to be very Non Stop Pop. Used sparingly. */
    val accent = Color(0.98f, 0.18f, 0.29f)
    val caraPurple = Color(0.47f, 0.16f, 0.62f)
    val caraNight = Color(0.10f, 0.05f, 0.20f)
    /** The deep near-black every page starts from. */
    val ink = Color(0.035f, 0.035f, 0.05f)
    /** Secondary and tertiary text on glass. */
    val text2 = Color.White.copy(alpha = 0.62f)
    val text3 = Color.White.copy(alpha = 0.38f)
    /** Hairlines between rows. */
    val line = Color.White.copy(alpha = 0.08f)
    /** The player's green (shuffle on, the device you're playing on). */
    val green = Color(0.37f, 0.82f, 0.43f)
    /** The frosted fill of the floating bars and sheets (Android can't blur what's behind them, so it's a dark glass). */
    val frost = Color(0xF41C1C22)          // dense enough that songs scrolling underneath stay unreadable (there is no blur behind it on Android)
    val hPad = 20.dp
    /** The floating tab bar and mini player. */
    val tabBarHeight = 62.dp
    val miniHeight = 60.dp
    val caraGradient: Brush get() = Brush.linearGradient(listOf(accent, caraPurple, caraNight))
}

// ---------------------------------------------------------------- frosted glass
/** A frosted glass card: a faint white wash with a hairline edge, over the page's soft colour. */
fun Modifier.glass(corner: Dp = 20.dp, tint: Float = 0.06f): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = tint + 0.035f), Color.White.copy(alpha = tint))), shape)
        .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.05f))), shape)
}

/** The floating bars and toasts: a darker glass that reads over anything. */
fun Modifier.frosted(corner: Dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(Theme.frost, shape)
        .border(0.7.dp, Color.White.copy(alpha = 0.12f), shape)
}

/** Buttons that squish a little when you press them. */
@Composable
fun Modifier.press(scale: Float = 0.96f, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed) 0.8f else 1f }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** A tap target with no ripple (rows, cards). */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

// ---------------------------------------------------------------- the colour behind every page
/** The backdrop every page sits on: a soft, blurred copy of what's playing (or of the page's own cover),
 *  deepened towards the bottom so text always reads. Cara's colours glow before anything has played. */
@Composable
fun AmbientBackdrop(art: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    val own by produceState<Pair<Bitmap, Float>?>(null, art) {
        value = if (art.isNullOrEmpty()) null else Ambience.make(art)
    }
    val img: Bitmap? = own?.first ?: Ambience.image
    val bright: Float = own?.second ?: Ambience.brightness
    // bright covers get a little extra shade, so white text always reads
    val shade = min(0.42f, max(0f, bright - 0.3f) * 0.8f)
    Box(modifier.background(Theme.ink)) {
        Crossfade(targetState = img, animationSpec = tween(1200), label = "ambient") { b ->
            if (b != null) {
                Box(Modifier.fillMaxSize()) {
                    val ib = remember(b) { b.asImageBitmap() }
                    Image(
                        ib, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)))
                }
            } else {
                CaraGlow()
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.28f),
                    0.42f to Color.Black.copy(alpha = 0.52f),
                    1f to Color.Black.copy(alpha = 0.80f),
                )
            )
        )
    }
}

/** Cara's colours, softly glowing (before anything has played). */
@Composable
fun CaraGlow(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Theme.accent.copy(alpha = 0.42f), Color.Transparent), center = Offset(w * 0.12f, h * 0.08f), radius = 430.dp.toPx()))
        drawRect(Brush.radialGradient(listOf(Theme.caraPurple.copy(alpha = 0.62f), Color.Transparent), center = Offset(w * 0.92f, h * 0.38f), radius = 470.dp.toPx()))
        drawRect(Brush.radialGradient(listOf(Color(0.14f, 0.2f, 0.56f).copy(alpha = 0.5f), Color.Transparent), center = Offset(w * 0.25f, h * 0.96f), radius = 430.dp.toPx()))
    }
}

// ---------------------------------------------------------------- buttons and labels
/** A white pill (the main action) or a glass pill (everything else). */
@Composable
fun PillLabel(title: String, icon: String? = null, primary: Boolean = true, height: Dp = 48.dp, fill: Boolean = true, modifier: Modifier = Modifier) {
    val shape = CircleShape
    val fg = if (primary) Color.Black else Color.White
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = height)
            .clip(shape)
            .then(
                if (primary) Modifier.background(Color.White, shape)
                else Modifier.background(Color.White.copy(alpha = 0.1f), shape).border(0.7.dp, Color.White.copy(alpha = 0.14f), shape)
            )
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(sym(icon), contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A section title on a page, with a chevron when it opens something. */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
            .padding(horizontal = Theme.hPad),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(sym("chevron.right"), contentDescription = null, tint = Theme.text3, modifier = Modifier.size(20.dp))
            }
        }
        if (subtitle != null) Text(subtitle, color = Theme.text2, fontSize = 14.sp)
    }
}

/** "Play" and "Shuffle", side by side. */
@Composable
fun PlayShuffleButtons(play: () -> Unit, shuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Theme.hPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillLabel("Play", icon = "play.fill", primary = true, modifier = Modifier.weight(1f).press { Haptics.tap(); play() })
        PillLabel("Shuffle", icon = "shuffle", primary = false, modifier = Modifier.weight(1f).press { Haptics.tap(); shuffle() })
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier.size(13.dp).background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(2.5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black, lineHeight = 9.sp)
    }
}

/** A clock that ticks every frame (or not at all when [running] is false), for the little animations. */
@Composable
private fun frameClock(running: Boolean, everyMs: Long = 0L): Long {
    val t by produceState(System.currentTimeMillis(), running) {
        var last = 0L
        while (running && isActive) {
            withFrameMillis { }
            val n = System.currentTimeMillis()
            if (n - last >= everyMs) { value = n; last = n }
        }
    }
    return t
}

/** The little bouncing bars next to the song that's playing. */
@Composable
fun EqualizerBars(playing: Boolean, color: Color = Theme.accent, height: Dp = 13.dp) {
    val t = frameClock(playing, 100L) / 1000.0
    Canvas(Modifier.width(18.dp).height(height)) {
        val bw = 3.dp.toPx()
        val gap = 2.dp.toPx()
        for (i in 0 until 4) {
            val lvl = if (!playing) 0.3f else {
                val speed = 2.1 + i * 0.65
                val phase = i * 1.7
                (0.25 + 0.75 * abs(sin(t * speed + phase))).toFloat()
            }
            val bh = size.height * lvl
            drawRoundRect(color, topLeft = Offset(i * (bw + gap), size.height - bh), size = Size(bw, bh), cornerRadius = CornerRadius(1.dp.toPx()))
        }
    }
}

/** The Non Stop Pop waveform logo (the app icon), which dances while Cara is live. */
@Composable
fun StationLogo(active: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    val base = floatArrayOf(0.35f, 0.65f, 1.0f, 0.55f, 0.85f, 0.45f, 0.7f)
    val t = frameClock(active, 50L) / 1000.0
    Canvas(modifier) {
        val count = base.size
        val gap = size.width / (count * 2 - 1)
        for (i in 0 until count) {
            val b = base[i]
            val lvl = if (!active) b else (b * (0.72 + 0.28 * sin(t * (2.6 + i * 0.9) + i * 1.3))).toFloat()
            val h = size.height * lvl
            drawRoundRect(color, topLeft = Offset(i * gap * 2, (size.height - h) / 2), size = Size(gap, h), cornerRadius = CornerRadius(gap / 2))
        }
    }
}

/** A capsule that reads "LIVE" with a pulsing dot. */
@Composable
fun LiveBadge(text: String = "LIVE") {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.background(Theme.accent, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).alpha(pulse).background(Color.White, CircleShape))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

/** A friendly message for empty or not-yet-loaded pages. */
@Composable
fun EmptyNote(symbol: String, title: String, message: String, buttonTitle: String? = null, action: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).glass(36.dp), contentAlignment = Alignment.Center) {
            Icon(sym(symbol), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, color = Theme.text2, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (buttonTitle != null && action != null) {
            PillLabel(buttonTitle, primary = true, height = 44.dp, fill = false, modifier = Modifier.padding(top = 6.dp).press { action() })
        }
    }
}

/** A tiny progress spinner row for lists that load more as you scroll. */
@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

/** A two-tone gradient from a hue, for the browse tiles. */
fun tileBrush(hue: Float): Brush {
    val h2 = (hue + 0.06f).let { if (it > 1f) it - 1f else it }
    return Brush.linearGradient(listOf(Color.hsv(hue * 360f, 0.62f, 0.78f), Color.hsv(h2 * 360f, 0.78f, 0.42f)))
}

// ---------------------------------------------------------------- form pieces (the iPhone app's cards, toggles and steppers)
/** A thin line between rows inside a card. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(Theme.line))
}

/** "Label ........ value" */
@Composable
fun ValueRow(left: String, right: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(right, color = Theme.text2, fontSize = 16.sp)
    }
}

/** An on/off switch with its label, in Cara's pink. */
@Composable
fun ToggleRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = { Haptics.tap(); onChange(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Theme.accent, checkedBorderColor = Theme.accent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.16f), uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = Theme.accent.copy(alpha = 0.5f), disabledUncheckedTrackColor = Color.White.copy(alpha = 0.1f),
            ),
        )
    }
}

/** A row with − and + buttons, like the iPhone's stepper. */
@Composable
fun StepperRow(left: String, right: String, value: Int, range: IntRange, step: Int = 1, enabled: Boolean = true, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(left, color = Color.White, fontSize = 16.sp)
            Text(right, color = Theme.text2, fontSize = 14.sp)
        }
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.12f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canDown = enabled && value - step >= range.first
            val canUp = enabled && value + step <= range.last
            Box(Modifier.width(46.dp).fillMaxSize().tap(enabled = canDown) { Haptics.tap(); onChange(max(range.first, value - step)) }, contentAlignment = Alignment.Center) {
                Text("−", color = if (canDown) Color.White else Theme.text3, fontSize = 20.sp)
            }
            Box(Modifier.width(0.5.dp).height(18.dp).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.width(46.dp).fillMaxSize().tap(enabled = canUp) { Haptics.tap(); onChange(min(range.last, value + step)) }, contentAlignment = Alignment.Center) {
                Text("+", color = if (canUp) Color.White else Theme.text3, fontSize = 20.sp)
            }
        }
    }
}

/** A volume-style slider with its label and percentage. */
@Composable
fun SliderRow(title: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${value.toInt()}%", color = Theme.text2, fontSize = 16.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
    }
}

/** The iPhone's segmented picker. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.1f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((tag, title) in options) {
            val on = tag == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(7.dp))
                    .background(if (on) Color.White.copy(alpha = 0.26f) else Color.Transparent)
                    .tap { if (!on) { Haptics.tap(); onSelect(tag) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** A titled glass card with an optional footnote, like the iPhone app's Cara page. */
@Composable
fun TitledCard(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Theme.hPad + 4.dp))
        Box(Modifier.padding(horizontal = Theme.hPad).fillMaxWidth().glass(20.dp).padding(16.dp)) {
            content()
        }
        if (footer != null) Text(footer, color = Theme.text2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = Theme.hPad + 4.dp))
    }
}

/** Small uppercase label. */
@Composable
fun SmallLabel(t: String, color: Color = Color.White.copy(alpha = 0.6f), size: TextUnit = 10.sp) {
    Text(t, color = color, fontSize = size, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** Keeps a text box from being shorter than a comfy tap. */
fun Modifier.minTap(): Modifier = this.defaultMinSize(minHeight = 44.dp)
