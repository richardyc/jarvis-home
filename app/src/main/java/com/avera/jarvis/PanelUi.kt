package com.avera.jarvis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Palette — same world as the main screen */
private val Ink = Color(0xFF0D0D0D)
private val Dim = Color(0xFF8F8F8F)
private val Blue = Color(0xFF3E68FF)
private val Glass = Color(0xF2FFFFFF)
private val HairLine = Color(0x14000000)
private val MuteRed = Color(0xFFD8574E)
private val Candle = Color(0xFFB59A76)      // night-clock digits: warm, dim, not blue-white
private val WarmAmber = Color(0xFFFF9240)

/** Root: the existing Jarvis screen plus every panel overlay, in one place. */
@Composable
fun PanelRoot(
    session: RealtimeSession,
    panel: Panel,
    model: String,
    voice: String,
    animate: Boolean,
    onBrightness: (Float) -> Unit,
    onTap: () -> Unit
) {
    // ease brightness changes over a second — a step change is very visible in a dark room
    val smoothBrightness by animateFloatAsState(
        targetValue = panel.targetBrightness,
        animationSpec = tween(1200), label = "brightness"
    )
    LaunchedEffect(smoothBrightness) { onBrightness(smoothBrightness) }

    // Launch: the whole screen arrives in one soft breath — fade with a hint of scale, once.
    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { launched = true }
    val launch by animateFloatAsState(
        if (launched) 1f else 0f,
        tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "launch"
    )

    Box(Modifier.fillMaxSize()) {
        val night = panel.nightMode && !session.active && !panel.settingsOpen
        if (night) {
            NightClock(panel, onTap)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(launch)
                    .scale(0.97f + 0.03f * launch)
            ) {
                JarvisScreen(session, model, animate, onTap)
            }
            SettingsDots(panel)
            if (!panel.online) OfflineTag()
        }
        MuteChip(panel.micMuted && !night)   // night mode has its own dim indicator
        VolumeHud(panel)
        MuteToast(panel)                     // transient confirmation on the physical mute key
        TimerHud(night)                      // countdown pill + full-screen ring when it fires
        if (panel.settingsOpen) SettingsSheet(panel, model, voice)
        // warmth veil: pixels are the room's only light source at night — pull them amber.
        // Not in night-clock mode: its palette is already warm, and a veil over black just muddies it.
        val w = panel.warmth
        if (w > 0.01f && !night) Box(
            Modifier
                .fillMaxSize()
                .background(WarmAmber.copy(alpha = 0.22f * w))
        )
    }
}

/* ---------- night clock: the whole point of a bedroom panel ---------- */

@Composable
private fun NightClock(panel: Panel, onTap: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    // drift a few pixels every couple of minutes — kind to a panel lit all night, invisible to a person
    var drift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) { delay(120_000); i++; drift = ((i * 7) % 17 - 8) to ((i * 5) % 13 - 6) }
    }
    val time = remember(now) { SimpleDateFormat("h:mm", Locale.US).format(now) }
    val day = remember(now) { SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(drift.first.dp, drift.second.dp)
        ) {
            BasicText(
                time,
                style = TextStyle(
                    fontFamily = AppFont, color = Candle,
                    fontSize = 112.sp, fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp, textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                day,
                style = TextStyle(
                    fontFamily = AppFont, color = Candle.copy(alpha = 0.45f),
                    fontSize = 15.sp, textAlign = TextAlign.Center
                )
            )
            // tonight's sky, whispered — the bedside glance that saves picking up a phone
            Ambient.weather?.let { w ->
                Spacer(Modifier.height(10.dp))
                BasicText(
                    "${w.currentTemp}° ${w.description}",
                    style = TextStyle(
                        fontFamily = AppFont, color = Candle.copy(alpha = 0.45f), fontSize = 15.sp
                    )
                )
            }
            // a running timer stays readable in the dark — candle-dim, under the date
            if (com.avera.jarvis.tools.TimerManager.endsAt > 0L) {
                Spacer(Modifier.height(16.dp))
                BasicText(
                    clock(com.avera.jarvis.tools.TimerManager.remainingMs()),
                    style = TextStyle(
                        fontFamily = AppFont, color = Candle.copy(alpha = 0.55f),
                        fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp
                    )
                )
            }
            // quiet, dim status line — enough to explain why "Hey Jarvis" won't answer
            if (panel.micMuted || !panel.online) {
                Spacer(Modifier.height(18.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (panel.micMuted) {
                        MicOffGlyph(Candle.copy(alpha = 0.5f), 13.dp)
                        BasicText(
                            "mic off",
                            style = TextStyle(fontFamily = AppFont, color = Candle.copy(alpha = 0.4f), fontSize = 12.sp)
                        )
                    }
                    if (panel.micMuted && !panel.online) Spacer(Modifier.width(8.dp))
                    if (!panel.online) BasicText(
                        "offline",
                        style = TextStyle(fontFamily = AppFont, color = Candle.copy(alpha = 0.4f), fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

/** Idle-screen tag: why the assistant won't respond right now. */
@Composable
private fun OfflineTag() {
    Box(Modifier.fillMaxSize()) {
        BasicText(
            "Offline — reconnecting…",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 14.dp),
            style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 12.sp)
        )
    }
}

/* ---------- mic mute: always visible when the physical switch is engaged ---------- */

@Composable
private fun MuteChip(muted: Boolean) {
    AnimatedVisibility(
        visible = muted,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MuteRed)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MicOffGlyph(Color.White, 15.dp)
                BasicText(
                    "Mic is off",
                    style = TextStyle(fontFamily = AppFont, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

/* ---------- mute toast: a center-screen confirmation when the physical mute key toggles ---------- */

@Composable
private fun MuteToast(panel: Panel) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(panel.muteShownAt) {
        if (panel.muteShownAt == 0L) return@LaunchedEffect
        visible = true
        delay(1600)
        visible = false
    }
    // Freeze the label/icon while fading out — the state at the moment of the press, not live state.
    var muted by remember { mutableStateOf(false) }
    if (visible) muted = panel.micMuted
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)) + scaleIn(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                initialScale = 0.55f
            ),
            exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.9f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(if (muted) MuteRed else Glass),
                    contentAlignment = Alignment.Center
                ) {
                    if (muted) MicOffGlyph(Color.White, 46.dp) else MicGlyph(Ink, 46.dp)
                }
                BasicText(
                    if (muted) "Microphone off" else "Microphone on",
                    style = TextStyle(
                        fontFamily = AppFont, color = Ink,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

/* Official Google Material Symbols (rounded), embedded as path data — the icon library isn't in
 * the offline gradle cache, and this ROM can't render emoji. Symbols use a 960-unit viewport with
 * y in [-960, 0], hence the +960 group translation. */
private const val MIC_PATH =
    "M480-400q-50 0-85-35t-35-85v-240q0-50 35-85t85-35q50 0 85 35t35 85v240q0 50-35 85t-85 35Z" +
    "m0-240Zm-40 480v-83q-92-13-157.5-78T203-479q-2-17 9-29t28-12q17 0 28.5 11.5T284-480q14 70 " +
    "69.5 115T480-320q72 0 127-45.5T676-480q4-17 15.5-28.5T720-520q17 0 28 12t9 29q-14 91-79 " +
    "157t-158 79v83q0 17-11.5 28.5T480-120q-17 0-28.5-11.5T440-160Zm40-320q17 0 28.5-11.5T520-520" +
    "v-240q0-17-11.5-28.5T480-800q-17 0-28.5 11.5T440-760v240q0 17 11.5 28.5T480-480Z"
private const val MIC_OFF_PATH =
    "M672-377q-14-8-18-24.5t4-30.5q7-11 11.5-23.5T676-481q4-17 15.5-28t28.5-11q17 0 28 12t9 29" +
    "q-3 23-10.5 45T727-392q-8 14-24.5 18.5T672-377ZM480-594Zm0-286q50 0 85 35t35 85v190q0 20-12.5 " +
    "30T560-530q-15 0-27.5-10.5T520-571v-189q0-17-11.5-28.5T480-800q-17 0-28.5 11.5T440-760v30" +
    "q0 20-12.5 30T400-690q-15 0-27.5-10.5T360-731v-29q0-50 35-85t85-35Zm-40 720v-83q-92-12-157.5" +
    "-77.5T203-479q-2-17 9-29t28-12q17 0 29 11.5t15 28.5q14 71 69 115.5T480-320q34 0 64.5-10.5" +
    "T600-360l57 57q-29 23-63.5 39T520-243v83q0 17-11.5 28.5T480-120q-17 0-28.5-11.5T440-160Z" +
    "m324 76L84-764q-11-11-11-28t11-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11Z"

@Composable
private fun MaterialGlyph(pathData: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val vector = remember(pathData, tint) {
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f
        ).apply {
            addGroup(translationY = 960f)
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(tint))
            clearGroup()
        }.build()
    }
    Image(rememberVectorPainter(vector), null, Modifier.size(size))
}

@Composable
private fun MicGlyph(tint: Color, size: androidx.compose.ui.unit.Dp) =
    MaterialGlyph(MIC_PATH, tint, size)

@Composable
private fun MicOffGlyph(tint: Color, size: androidx.compose.ui.unit.Dp) =
    MaterialGlyph(MIC_OFF_PATH, tint, size)

/* ---------- timer: a quiet countdown pill; the whole screen breathes when it fires ---------- */

@Composable
private fun TimerHud(night: Boolean) {
    val tm = com.avera.jarvis.tools.TimerManager
    if (tm.endsAt == 0L) return
    // tick 4×/s while a timer is visible — cheap, and the seconds read smoothly
    var remaining by remember { mutableStateOf(tm.remainingMs()) }
    LaunchedEffect(tm.endsAt) {
        while (true) { remaining = tm.remainingMs(); delay(250) }
    }
    if (tm.ringing) {
        // Done: the panel's one loud moment. Soft white wash + the ring at full breath.
        val pulse by rememberPulse()
        Box(
            Modifier
                .fillMaxSize()
                .background(if (night) Color.Black else Color.White.copy(alpha = 0.96f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { tm.dismissRing() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.scale(pulse)) { TimerRing(0L, 1L, tm.label, 210.dp) }
                BasicText(
                    if (tm.label.isEmpty()) "Timer done" else "${tm.label} — done",
                    style = TextStyle(fontFamily = AppFont, color = if (night) Candle else Ink,
                        fontSize = 24.sp, fontWeight = FontWeight.Medium)
                )
                BasicText(
                    "Say “stop” — or tap",
                    style = TextStyle(fontFamily = AppFont,
                        color = if (night) Candle.copy(alpha = 0.4f) else Dim, fontSize = 13.sp)
                )
            }
        }
    } else if (!night) {
        // Counting: an iOS-style pill in the top corner, out of the conversation's way.
        Box(Modifier.fillMaxSize().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Glass)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                MiniRing(remaining, tm.totalMs)
                BasicText(
                    clock(remaining) + if (tm.label.isEmpty()) "" else "  ·  ${tm.label}",
                    style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
private fun MiniRing(remaining: Long, total: Long) {
    val frac = if (total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.size(14.dp)) {
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(HairLine, -90f, 360f, false, style = s)
        drawArc(Blue, -90f, 360f * frac, false, style = s)
    }
}

/** A slow confident breath, ~1.1s per cycle — for the fired timer. */
@Composable
private fun rememberPulse(): androidx.compose.runtime.State<Float> {
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        1f, 1.05f,
        infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pulseF"
    )
}

/* ---------- volume HUD: appears on a physical key press, then slips away ---------- */

@Composable
private fun VolumeHud(panel: Panel) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(panel.volumeShownAt) {
        if (panel.volumeShownAt == 0L) return@LaunchedEffect
        visible = true
        delay(2200)
        visible = false
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(350)) + slideOutVertically(tween(400)) { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Glass)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SpeakerGlyph(if (panel.volumePercent == 0) Dim else Ink, 16.dp, panel.volumePercent)
                // track
                Canvas(Modifier.width(180.dp).height(5.dp)) {
                    drawRoundRect(HairLine, cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(
                        if (panel.volumePercent == 0) Dim else Blue,
                        size = Size(size.width * panel.volumePercent / 100f, size.height),
                        cornerRadius = CornerRadius(size.height / 2)
                    )
                }
                BasicText(
                    "${panel.volumePercent}",
                    style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
private fun SpeakerGlyph(tint: Color, size: androidx.compose.ui.unit.Dp, percent: Int) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.3f, h * 0.36f)
            lineTo(w * 0.54f, h * 0.14f); lineTo(w * 0.54f, h * 0.86f)
            lineTo(w * 0.3f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
        }
        drawPath(body, tint)
        val s = w * 0.1f
        if (percent > 0) {
            val arc = Path().apply {
                moveTo(w * 0.68f, h * 0.32f)
                cubicTo(w * 0.82f, h * 0.42f, w * 0.82f, h * 0.58f, w * 0.68f, h * 0.68f)
            }
            drawPath(arc, tint, style = Stroke(width = s, cap = StrokeCap.Round))
        }
        if (percent > 55) {
            val arc2 = Path().apply {
                moveTo(w * 0.78f, h * 0.18f)
                cubicTo(w * 1.0f, h * 0.38f, w * 1.0f, h * 0.62f, w * 0.78f, h * 0.82f)
            }
            drawPath(arc2, tint, style = Stroke(width = s, cap = StrokeCap.Round))
        }
    }
}

/* ---------- settings: three quiet dots, then a sheet in the card language ---------- */

@Composable
private fun SettingsDots(panel: Panel) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(CircleShape)
                .clickable { panel.settingsOpen = true }
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            repeat(3) { Box(Modifier.size(4.dp).clip(CircleShape).background(Color(0xFFC9C9C9))) }
        }
    }
}

@Composable
private fun SettingsSheet(panel: Panel, defaultModel: String, defaultVoice: String) {
    // scrim
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                panel.settingsOpen = false
            }
    )
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                // scrollable: a bottom-aligned Column that outgrows the screen otherwise starves
                // its trailing children of height — text squeezed to nothing, rows pushed out
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            BasicText("Display", style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium))

            SettingRow("Auto brightness", "Follows the room's light") {
                Toggle(panel.autoBrightness) { panel.chooseAutoBrightness(it) }
            }
            if (!panel.autoBrightness) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("Brightness", style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 14.sp))
                    BrightnessSlider(panel.manualBrightness) { panel.chooseManualBrightness(it) }
                }
            }
            SettingRow("Night warmth", "Amber tint when the room is dark") {
                Toggle(panel.warmthEnabled) { panel.chooseWarmth(it) }
            }

            BasicText("Assistant", style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("Model — takes effect next conversation", style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 12.sp))
                Chips(
                    options = listOf("2.1" to "gpt-realtime-2.1", "2.1 mini" to "gpt-realtime-2.1-mini"),
                    selected = panel.modelChoice.ifEmpty { defaultModel }
                ) { panel.chooseModel(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("Voice", style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 12.sp))
                Chips(
                    options = listOf("Verse" to "verse", "Cedar" to "cedar", "Marin" to "marin",
                        "Alloy" to "alloy", "Sage" to "sage"),
                    selected = panel.voiceChoice.ifEmpty { defaultVoice }
                ) { panel.chooseVoice(it) }
            }

            BasicText("Memory", style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium))
            MemoryRow()

            BasicText("About", style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium))
            BasicText(
                "Jarvis — made by Richard He",
                style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 13.sp)
            )

            val luxLabel = if (panel.lux < 0f) "no reading" else "%.0f lux".format(panel.lux)
            val netLabel = when {
                !panel.online -> "offline"
                panel.wifiChannel > 0 -> "channel ${panel.wifiChannel}"
                else -> "connected"
            }
            val cpuLabel = when {
                panel.cpuCores < 0 -> "—"
                panel.cpuPercent < 0 -> "${panel.cpuCores} cores"
                else -> "${panel.cpuPercent}% · ${panel.cpuCores} cores"
            }
            BasicText(
                "Room light: $luxLabel   ·   Wi-Fi: $netLabel   ·   CPU: $cpuLabel",
                style = TextStyle(fontFamily = AppFont, color = Color(0xFFB4B4B4), fontSize = 12.sp)
            )
            if (panel.wifiIsDfs) BasicText(
                "This is a DFS channel — the radio must go quiet when the router detects radar, " +
                "which cuts Jarvis off mid-sentence. Set the router to a fixed channel: 36–48 or 149–165.",
                style = TextStyle(fontFamily = AppFont, color = MuteRed, fontSize = 12.sp, lineHeight = 16.sp)
            )
            if (panel.cpuCores in 1..3) BasicText(
                "Only ${panel.cpuCores} of 4 CPU cores are running — the power-saving governor has " +
                "parked them. That starves the audio and network threads and drops conversations.",
                style = TextStyle(fontFamily = AppFont, color = MuteRed, fontSize = 12.sp, lineHeight = 16.sp)
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 16.sp))
            BasicText(hint, style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 12.sp))
        }
        control()
    }
}

/** What Jarvis has remembered, and the debugging escape hatch: wipe it all. */
@Composable
private fun MemoryRow() {
    var count by remember { mutableStateOf(Memory.factCount()) }
    var armed by remember { mutableStateOf(false) }   // first tap arms, second wipes
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                if (count == 0) "Nothing remembered yet" else "$count remembered fact${if (count == 1) "" else "s"}",
                style = TextStyle(fontFamily = AppFont, color = Ink, fontSize = 16.sp)
            )
            BasicText(
                "Say “remember …” to add — plain files on the device",
                style = TextStyle(fontFamily = AppFont, color = Dim, fontSize = 12.sp)
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (armed) MuteRed else Color(0xFFF0F0F0))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (armed) { Memory.wipe(); count = 0; armed = false }
                    else armed = true
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            BasicText(
                if (armed) "Tap again to wipe" else "Wipe memory",
                style = TextStyle(fontFamily = AppFont, fontSize = 14.sp,
                    color = if (armed) Color.White else MuteRed, fontWeight = FontWeight.Medium)
            )
        }
    }
}

/** iOS-style segmented pills — one always selected. */
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Blue else Color(0xFFF0F0F0))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BasicText(
                    label,
                    style = TextStyle(
                        fontFamily = AppFont, fontSize = 14.sp,
                        color = if (on) Color.White else Ink,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Blue else Color(0xFFDADADA))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onChange(!on) }
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun BrightnessSlider(value: Float, onChange: (Float) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        v = (change.position.x / size.width).coerceIn(0.02f, 1f)
                        onChange(v)
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.firstOrNull()?.let {
                            if (it.pressed) { v = (it.position.x / size.width).coerceIn(0.02f, 1f); onChange(v) }
                        }
                    }
                }
            }
    ) {
        val cy = size.height / 2
        drawRoundRect(
            HairLine, topLeft = Offset(0f, cy - 3.dp.toPx()),
            size = Size(size.width, 6.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx())
        )
        drawRoundRect(
            Blue, topLeft = Offset(0f, cy - 3.dp.toPx()),
            size = Size(size.width * v, 6.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx())
        )
        drawCircle(Color.White, 13.dp.toPx(), Offset(size.width * v, cy))
        drawCircle(Blue, 11.dp.toPx(), Offset(size.width * v, cy))
    }
}
