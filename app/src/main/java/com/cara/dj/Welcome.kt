package com.cara.dj

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val HELLO = 0
private const val SPOTIFY = 1
private const val VOICE = 2
private const val WORDS = 3
private const val TOWN = 4
private const val DONE = 5

/**
 * The first launch, as an unboxing: a matte black lid you pull up, Cara waiting inside,
 * four calm setup steps on frosted glass, a checkmark that draws itself, and the app rising into view.
 */
@Composable
fun WelcomeScreen() {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    var stage by remember { mutableStateOf(HELLO) }
    var forward by remember { mutableStateOf(true) }
    var turning by remember { mutableStateOf(false) }
    // the box
    val lift = remember { Animatable(0f) }
    var lidGone by remember { mutableStateOf(false) }
    var lifting by remember { mutableStateOf(false) }
    // inside
    var awake by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // setup
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf("") }
    var cityText by remember { mutableStateOf(Config.city) }

    fun go(s: Int) {
        // one page turn at a time, however fast the taps come
        if (turning || s == stage) return
        turning = true
        focus.clearFocus()
        Haptics.tap()
        forward = s > stage
        stage = s
        scope.launch { delay(500); turning = false }
    }

    BackHandler(enabled = stage in SPOTIFY..TOWN) { go(stage - 1) }

    val leave by animateFloatAsState(if (leaving) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "leave")
    val breatheT = rememberInfiniteTransition(label = "breathe")
    val breathe by breatheT.animateFloat(0f, 1f, infiniteRepeatable(tween(4500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val b = if (awake) breathe else 0f

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Theme.ink).graphicsLayer {
            val s = 1f + 0.07f * leave
            scaleX = s; scaleY = s; alpha = 1f - leave
        }.blur((16 * leave).dp)
    ) {
        val boxHeight = with(density) { maxHeight.toPx() }
        val openness = min(1f, max(0f, lift.value / max(boxHeight, 1f)))

        fun openLid() {
            if (lifting) return
            lifting = true
            Haptics.firm()
            // slow, like a lid sliding off a snug box
            scope.launch { lift.animateTo(boxHeight + with(density) { 140.dp.toPx() }, tween(1250, easing = CubicBezierEasing(0.55f, 0f, 0.2f, 1f))) }
            scope.launch { delay(450); awake = true }
            scope.launch { delay(1300); lidGone = true }
        }

        // ---------------------------------------------------------------- inside the box
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = if (lidGone) 1f else 0.9f + 0.1f * openness
                scaleX = s; scaleY = s
                alpha = if (lidGone) 1f else 0.25f + 0.75f * openness
            }
        ) {
            CaraGlow(Modifier.fillMaxSize().graphicsLayer { val s = 1f + 0.12f * b; scaleX = s; scaleY = s; alpha = 0.72f + 0.28f * b })
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    val d = if (forward) 1 else -1
                    val inS = spring<Float>(dampingRatio = 0.9f, stiffness = 130f)
                    (fadeIn(inS) + slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 130f)) { with(density) { (36.dp.toPx() * d).roundToInt() } } + scaleIn(inS, 1.03f)) togetherWith
                        (fadeOut(tween(250)) + slideOutHorizontally(tween(300)) { with(density) { (-36.dp.toPx() * d).roundToInt() } } + scaleOut(tween(300), 0.97f))
                },
                label = "stage",
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
            ) { s ->
                when (s) {
                    HELLO -> Hello(awake) { go(SPOTIFY) }
                    SPOTIFY -> {
                        val connected = Engine.loggedIn && Engine.connected
                        Step(
                            "music.note", "Spotify",
                            "Make a free app in the Spotify developer dashboard, set its Redirect URI to caradj://callback, add your Spotify email under User Management, then paste its Client ID here.",
                            primary = if (connecting) "Connecting…" else if (connected) "Continue" else "Connect Spotify",
                            busy = connecting,
                            onSkip = { go(VOICE) },
                            action = {
                                if (connected) { go(VOICE); return@Step }
                                if (Config.clientID.isBlank()) { connectError = "Paste the Client ID first."; return@Step }
                                focus.clearFocus()
                                connecting = true
                                connectError = ""
                                scope.launch {
                                    Engine.connect(forceLogin = true)
                                    connecting = false
                                    if (Engine.connected) {
                                        Haptics.success()
                                        delay(700)
                                        go(VOICE)
                                    } else {
                                        connectError = Engine.problem.ifEmpty { "That didn't work. Check the Client ID and that the Redirect URI is caradj://callback, then try again." }
                                    }
                                }
                            },
                        ) {
                            SetupField("Client ID", "Paste it here", Config.clientID, secure = false) { Config.clientID = it }
                            AnimatedVisibility(connected || connectError.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                                if (connected) {
                                    val n = Library.me?.name.orEmpty()
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(sym("checkmark.circle.fill"), contentDescription = null, tint = Theme.green, modifier = Modifier.size(18.dp))
                                        Text(if (n.isNotEmpty()) "Connected as $n" else "Connected", color = Theme.green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    Text(connectError, color = Color(0xFFFF9F0A), fontSize = 13.sp)
                                }
                            }
                            LinkLine("Open the developer dashboard") { openUrl("https://developer.spotify.com/dashboard") }
                        }
                    }
                    VOICE -> Step(
                        "waveform", "Her Voice",
                        "Cara speaks with an ElevenLabs voice. Paste your API key (the secret one that starts with sk_) and the Voice ID.",
                        primary = "Continue", onSkip = { go(WORDS) }, action = { go(WORDS) },
                    ) {
                        SetupField("API Key", "sk_…", Config.elevenKey, secure = true) { Config.elevenKey = it }
                        SetupField("Voice ID", "Voice ID", Config.elevenVoice, secure = false) { Config.elevenVoice = it }
                    }
                    WORDS -> Step(
                        "text.bubble", "Her Words",
                        "Gemini writes what she says. A free key from Google AI Studio is plenty. Without one she still talks, with simpler lines.",
                        primary = "Continue", onSkip = { go(TOWN) }, action = { go(TOWN) },
                    ) {
                        SetupField("Gemini API Key", "Paste it here", Config.geminiKey, secure = true) { Config.geminiKey = it }
                        LinkLine("Get a free key") { openUrl("https://aistudio.google.com/apikey") }
                    }
                    TOWN -> Step(
                        "mappin.and.ellipse", "Your Town",
                        "She talks about your local news and weather. Town, State works best.",
                        primary = "Continue", onSkip = { go(DONE) },
                        action = {
                            val c = cityText
                            Engine.scope.launch { Engine.changeCity(c) }
                            go(DONE)
                        },
                    ) {
                        SetupField("Town", "Yakima, Washington", cityText, secure = false) { cityText = it }
                    }
                    else -> Finale {
                        Haptics.firm()
                        focus.clearFocus()
                        leaving = true
                        scope.launch { delay(180); Config.welcomed = true }
                    }
                }
            }
            AnimatedVisibility(stage in SPOTIFY..TOWN, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.systemBarsPadding()) {
                TopBar(stage) { go(stage - 1) }
            }
        }

        // ---------------------------------------------------------------- the lid
        if (!lidGone) Lid(
            lift = lift.value,
            modifier = Modifier.offset { IntOffset(0, -lift.value.roundToInt()) },
            onDrag = { pulled -> if (!lifting) scope.launch { lift.snapTo(max(0f, pulled) * 0.72f) } },
            onRelease = { pulled, flung ->
                if (!lifting) {
                    if (pulled > with(density) { 110.dp.toPx() } || flung > with(density) { 340.dp.toPx() }) openLid()
                    else scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 160f)) }
                }
            },
            onTap = { openLid() },
        )
    }
}

@Composable
private fun Lid(lift: Float, modifier: Modifier, onDrag: (Float) -> Unit, onRelease: (Float, Float) -> Unit, onTap: () -> Unit) {
    val density = LocalDensity.current
    val hintT = rememberInfiniteTransition(label = "hint")
    val hint by hintT.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h")
    val tabAlpha = 1f - min(1f, lift / with(density) { 120.dp.toPx() })
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val tapped by rememberUpdatedState(onTap)
    Box(
        modifier.fillMaxSize()
            .graphicsLayer {
                shadowElevation = if (lift > 1f) 36.dp.toPx() else 0f
            }
            .background(Brush.verticalGradient(listOf(Color(0xFF181818), Color(0xFF090909))))
            .pointerInput(Unit) {
                var total = 0f
                var touched = false
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { total = 0f; tracker.resetTracking(); if (!touched) { touched = true; Haptics.soft() } },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        // a flick carries the lid on a little way, like iOS's predicted end
                        release(-total, -total - v * 0.25f)
                        touched = false
                    },
                    onDragCancel = { release(-total, -total); touched = false },
                ) { change, dy ->
                    total += dy
                    tracker.addPosition(change.uptimeMillis, change.position)
                    drag(-total)
                }
            }
            .pointerInput(Unit) { detectTapGestures { tapped() } },
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), center = Offset.Unspecified, radius = with(density) { 400.dp.toPx() })))
        // the logo, pressed into the lid
        Column(
            Modifier.align(Alignment.Center).offset(y = (-24).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Box {
                StationLogo(false, Modifier.width(96.dp).height(66.dp).offset(y = (-1).dp), Color.Black.copy(alpha = 0.9f))
                StationLogo(false, Modifier.width(96.dp).height(66.dp).offset(y = 1.dp), Color.White.copy(alpha = 0.09f))
                StationLogo(false, Modifier.width(96.dp).height(66.dp), Color(0xFF1E1E1E))
            }
            Text("CARA DJ", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 7.sp)
        }
        // the pull tab
        Column(
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 46.dp).graphicsLayer { alpha = tabAlpha },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(sym("chevron.up"), contentDescription = null, tint = Color.White.copy(alpha = 0.42f), modifier = Modifier.size(20.dp).offset(y = (1f - 6f * hint).dp))
            Text("PULL UP TO OPEN", color = Color.White.copy(alpha = 0.32f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
            Box(Modifier.padding(top = 8.dp).width(44.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.24f)))
        }
        // the edge of the lid catching the light as it lifts
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.14f)))
    }
}

/** Text and buttons that drift up into focus, one after another. */
@Composable
private fun Modifier.reveal(on: Boolean, delayMs: Int): Modifier {
    val p by animateFloatAsState(if (on) 1f else 0f, tween(900, delayMs, LinearOutSlowInEasing), label = "reveal")
    return this.graphicsLayer { alpha = p; translationY = (1f - p) * 14.dp.toPx() }.blur((10f * (1f - p)).dp)
}

@Composable
private fun Hello(awake: Boolean, setUp: () -> Unit) {
    val glow by animateFloatAsState(if (awake) 1f else 0f, tween(1600, easing = LinearOutSlowInEasing), label = "glow")
    val logo by animateFloatAsState(if (awake) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 35f), label = "logo")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(280.dp).graphicsLayer { alpha = glow }.blur(18.dp).background(Brush.radialGradient(listOf(Theme.accent.copy(alpha = 0.55f), Color.Transparent))))
            StationLogo(awake, Modifier.width(124.dp).height(86.dp).graphicsLayer { val s = 0.86f + 0.14f * logo; scaleX = s; scaleY = s; alpha = logo.coerceIn(0f, 1f) })
        }
        Text("Hi, I'm Cara.", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp).reveal(awake, 350))
        Text("Your music, now a radio station.", color = Theme.text2, fontSize = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).reveal(awake, 650))
        Spacer(Modifier.weight(1f))
        PillLabel("Set Up", primary = true, height = 54.dp, modifier = Modifier.padding(horizontal = 32.dp).reveal(awake, 1150).press(onClick = setUp))
        Text("Takes about a minute.", color = Theme.text3, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp).reveal(awake, 1300))
    }
}

@Composable
private fun TopBar(stage: Int, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).tap(onClick = back), contentAlignment = Alignment.Center) {
            Icon(sym("chevron.left"), contentDescription = "Back", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..4) {
                val w by animateDpAsState(if (i == stage) 22.dp else 7.dp, spring(dampingRatio = 0.8f, stiffness = 300f), label = "dot")
                Box(Modifier.width(w).height(7.dp).clip(CircleShape).background(if (i <= stage) Color.White else Color.White.copy(alpha = 0.22f)))
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(44.dp))
    }
}

/** One setup step: a frosted card, centred when there's room and scrolling when the keyboard is up. */
@Composable
private fun Step(
    icon: String, title: String, text: String, primary: String, busy: Boolean = false,
    onSkip: () -> Unit, action: () -> Unit, fields: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val room = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = room)
                    .padding(top = 56.dp, bottom = 12.dp),          // room for the back button and the dots
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().glass(30.dp, 0.055f).padding(24.dp)) {
                    Box(Modifier.padding(bottom = 20.dp).size(56.dp).glass(28.dp, 0.1f), contentAlignment = Alignment.Center) {
                        Icon(sym(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(text, color = Theme.text2, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = fields)
                }
            }
        }
        Column(Modifier.padding(horizontal = 32.dp).padding(top = 8.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PillLabel(primary, primary = true, height = 54.dp, modifier = Modifier.graphicsLayer { alpha = if (busy) 0.6f else 1f }.press(enabled = !busy, onClick = action))
            Box(Modifier.height(40.dp).tap(onClick = onSkip), contentAlignment = Alignment.Center) {
                Text("Skip for now", color = Theme.text2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SetupField(label: String, placeholder: String, value: String, secure: Boolean, onChange: (String) -> Unit) {
    val requester = remember { FocusRequester() }
    val clip = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var on by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label.uppercase(), color = Theme.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.28f))
                .border(if (on) 1.dp else 0.7.dp, Color.White.copy(alpha = if (on) 0.5f else 0.13f), RoundedCornerShape(14.dp))
                // tapping anywhere in the box puts the cursor in the field
                .tap { requester.requestFocus() }
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = Theme.text3, fontSize = 17.sp, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(Color.White),
                    visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secure) KeyboardType.Password else KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { on = it.isFocused },
                )
            }
            if (value.isEmpty()) {
                Box(
                    Modifier.height(30.dp).clip(CircleShape).background(Color.White)
                        .press { clip.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { onChange(it); Haptics.tap() } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Paste", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun LinkLine(title: String, onClick: () -> Unit) {
    Row(Modifier.tap(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Theme.text2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Icon(sym("arrow.up.right"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Finale(finish: () -> Unit) {
    val ring = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch { delay(300); ring.animateTo(1f, tween(850, easing = FastOutSlowInEasing)) }
        launch { delay(1050); shown = true; tick.animateTo(1f, tween(400, easing = LinearOutSlowInEasing)) }
        launch { delay(1400); Haptics.success() }
    }
    val glow by animateFloatAsState(if (shown) 1f else 0f, tween(800), label = "glow")
    val first = Library.me?.name?.split(" ")?.firstOrNull().orEmpty()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp).graphicsLayer { alpha = glow }.background(Brush.radialGradient(listOf(Theme.accent.copy(alpha = 0.45f), Color.Transparent))))
            Canvas(Modifier.size(128.dp)) {
                val r = size.minDimension / 2
                drawCircle(Color.White.copy(alpha = 0.06f), r)
                drawCircle(Color.White.copy(alpha = 0.1f), r - 0.35.dp.toPx(), style = Stroke(0.7.dp.toPx()))
                val inset = 1.5.dp.toPx()
                drawArc(
                    Color.White, -90f, 360f * ring.value, false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
                // the tick, drawn in
                val w = 50.dp.toPx()
                val h = 38.dp.toPx()
                val left = (size.width - w) / 2 + 2.dp.toPx()
                val top = (size.height - h) / 2 + 2.dp.toPx()
                val check = Path().apply {
                    moveTo(left, top + h * 0.55f)
                    lineTo(left + w * 0.36f, top + h)
                    lineTo(left + w, top)
                }
                if (tick.value > 0f) {
                    val pm = PathMeasure()
                    pm.setPath(check, false)
                    val part = Path()
                    pm.getSegment(0f, pm.length * tick.value, part, true)
                    drawPath(part, Color.White, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Text(
            if (first.isNotEmpty()) "You're all set, $first." else "You're all set.",
            color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 40.dp, start = 20.dp, end = 20.dp).reveal(shown, 250),
        )
        Text("Enjoy the show.", color = Theme.text2, fontSize = 19.sp, modifier = Modifier.padding(top = 10.dp).reveal(shown, 500))
        Spacer(Modifier.weight(1f))
        PillLabel("Start Listening", primary = true, height = 54.dp, modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 24.dp).reveal(shown, 850).press(onClick = finish))
    }
}
