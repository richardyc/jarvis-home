package com.avera.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/** Everything on screen, scaled for a 7" panel viewed from across the room. */
const val UI_SCALE = 1.3f

class MainActivity : ComponentActivity() {
    private var session: RealtimeSession? = null
    private var wake: WakeWordDetector? = null
    private var barge: WakeWordDetector? = null
    private var panel: Panel? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Volatile private var latestLux = -1f
    private val luxHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val cfg = Config.load(this)
        Env.init(this)      // runtime key overrides from files/.env, before anything needs a secret
        Memory.init(this)
        // idle-screen weather — session client reused (IPv4 pin + timeouts), started after session below

        // Volume is applied as a per-track gain in RealtimeSession; the rocker is caught in onKeyDown.
        android.util.Log.i("Jarvis", "Jarvis onCreate (hardware echo cancellation)")

        // The app's ONE microphone stream — opened now, never closed. Every consumer (wake word,
        // session, timer ring listener) taps this instead of opening its own AudioRecord: the
        // DSP's ~2.25s convergence mute is paid once here at boot instead of on every handoff.
        MicHub.start()

        // Persisted brightness must be applied before the first frame (no full-bright flash at night).
        val p = Panel(this); panel = p
        window.attributes = window.attributes.apply { screenBrightness = p.initialBrightness }

        // WiFi is left entirely to the OS — standard AOSP reconnects on its own. (The old app watchdog
        // existed only because the Ivy debug ROM had no Settings/SystemUI to do it.)
        val s = RealtimeSession(cfg); session = s
        s.audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        s.tapDir = filesDir      // upstream-audio debug tap → files/upstream.pcm
        s.wakeFireFrame = { wake?.lastFireFrame ?: -1L }
        s.onMicAboutToOpen = {   // the handoff moment: the session seeds itself from the hub
            wake?.stop()         // ring, so stopping the detector costs nothing anymore
        }
        s.onClosed = { runOnUiThread { wake?.start() } }        // session ended → re-arm wake word
        s.onVolumeChange = { pct -> runOnUiThread { p.chooseVolume(pct); p.volumeShownAt = android.os.SystemClock.elapsedRealtime() } }
        s.setOutputGain(p.volumePercent / 100f)                 // apply the saved volume to playback
        s.modelProvider = { p.modelChoice.ifEmpty { cfg.model } }   // Settings override config.yaml
        s.voiceProvider = { p.voiceChoice.ifEmpty { cfg.voice } }
        Ambient.start(s.client, cfg.homeCity)                       // clock+weather idle screen
        com.avera.jarvis.tools.TimerManager.gainProvider = { p.volumePercent / 100f }   // chime follows the volume
        // While the timer rings, the wake detector sleeps — the chime false-triggers "Hey Jarvis"
        // and the resulting session start would silently dismiss the ring.
        com.avera.jarvis.tools.TimerManager.onRingChange = { ringing ->
            runOnUiThread {
                if (ringing) wake?.stop()
                else if (session?.active != true && !p.micMuted) wake?.start()
            }
        }
        if (cfg.wakeWord) {
            wake = WakeWordDetector(this, cfg.wakeThreshold) { runOnUiThread { startConversation() } }
            wake?.start()
            // A second detector, fed from the session's own mic, so "Hey Jarvis" can cut him off
            // mid-answer. It never touches the microphone itself — the session owns that.
            barge = WakeWordDetector(this, cfg.wakeThreshold) {}.also { it.openForFeeding() }
            s.bargeCheck = { chunk -> barge?.feed(chunk) ?: false }
        }
        if (intent.getStringExtra("demo") == "weather")
            s.showDemoWeather(intent.getStringExtra("place") ?: "San Francisco, California")
        if (intent.getStringExtra("nomic") != null) s.debugNoUpstream = true
        intent.getStringExtra("ask")?.let { q -> s.pendingAsk = q; startConversation() }
        if (intent.getStringExtra("diag") != null) {
            wake?.stop()                 // the probe needs the microphone to itself
            Diag.probeCapture()
        }
        if (intent.getStringExtra("aec3test") != null) {
            wake?.stop()
            Diag.aec3Test(this)
        }
        if (intent.getStringExtra("echotest") != null) {
            wake?.stop()
            Diag.echoTest(this)
        }

        // Long-lived TLS streams die when the WiFi radio naps or goes off-channel — hold the link.
        wifiLock = (applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis")
            .also { it.acquire() }

        startPeripherals(p, s)
        setContent {
            // It's a 7" panel read from across a room, not a phone held at 30cm. Scale the whole
            // composition — every dp and sp, so proportions stay exactly as designed, just bigger.
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(d.density * UI_SCALE, d.fontScale)
            ) {
                PanelRoot(s, p, cfg.model, cfg.voice, cfg.animate, onBrightness = { b ->
                    window.attributes = window.attributes.apply { screenBrightness = b }
                }) { onTap() }
            }
        }
    }

    /** Sensors + system stats → UI state. Callbacks arrive off the main thread. */
    private fun startPeripherals(p: Panel, s: RealtimeSession) {
        // Ambient light via the standard SensorManager — the VCNL4200 ALS is a normal
        // android.sensor.light on this device. Drives auto-brightness + night mode.
        val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val light = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
        if (light != null) {
            // The listener just records the newest value (cheap). It's an ON-CHANGE sensor, so it only
            // fires when the light actually changes — a throttle that DROPS an event can strand us on a
            // stale value forever (covering then uncovering left brightness stuck). Instead a slow timer
            // applies the *latest* reading, so it's never twitchy, saves CPU, and always catches up.
            sm.registerListener(object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) { latestLux = e.values[0] }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }, light, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            val tick = object : Runnable {
                override fun run() {
                    if (latestLux >= 0f) p.updateLux(latestLux)
                    luxHandler.postDelayed(this, 4_000)   // apply latest every 4s
                }
            }
            luxHandler.post(tick)
            android.util.Log.i("Jarvis", "ambient light: ${light.name} (4s sampling)")
        } else android.util.Log.w("Jarvis", "ambient light: no TYPE_LIGHT sensor")

        SysMon.start(object : SysMon.Listener {
            override fun onSample(cpuPercent: Int, cores: Int, governor: String) = runOnUiThread {
                p.cpuPercent = cpuPercent
                p.cpuCores = cores
                p.cpuGovernor = governor
            }
        })
    }

    /** Volume as a direct gain on the playback track (deterministic — stream volume didn't move it) + HUD. */
    private fun adjustVolume(up: Boolean) {
        val p = panel ?: return
        val target = (p.volumePercent + if (up) 10 else -10).coerceIn(0, 100)
        p.chooseVolume(target)
        p.volumeShownAt = android.os.SystemClock.elapsedRealtime()
        session?.setOutputGain(target / 100f)
        Sfx.blip(target / 100f, rising = up)   // soft two-note tick at the new level
    }

    private fun startConversation() {
        com.avera.jarvis.tools.TimerManager.dismissRing()   // "hey jarvis" over a ringing timer silences it
        barge?.reset()        // no stale audio history from the last answer
        // NOTE: the wake mic is NOT stopped here — it keeps recording through session setup and
        // is released by the session's mic thread at the last moment (onMicAboutToOpen), which
        // also splices its recording into the upstream. See RealtimeSession.startMic.
        session?.start()
    }

    private var lastTap = 0L
    private fun onTap() {
        if (com.avera.jarvis.tools.TimerManager.ringing) {   // a ringing timer owns the screen
            com.avera.jarvis.tools.TimerManager.dismissRing()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTap < 900) return    // debounce: rapid toggling races the session start/stop
        lastTap = now
        val s = session ?: return
        when {
            s.speaking -> s.interrupt()    // tap while he's talking = cut him off, keep listening
            s.active -> s.stop()
            else -> startConversation()
        }
    }

    // The hardware volume rocker arrives as normal key events here; apply the change to the music
    // stream (what Jarvis plays) and show our own HUD.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(up = true)
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(up = false)
            // The mic-mute SLIDER is momentary: each flip sends ONE pulse, and the two positions send
            // DIFFERENT keycodes (mic-off → KEY_HIRAGANA, remapped by gpio-keys.kl to MUTE; mic-on →
            // KEY_F3). So each keycode sets the absolute state — no toggling, robust to a missed event.
            android.view.KeyEvent.KEYCODE_MUTE -> if (event.repeatCount == 0) setMuted(true)
            android.view.KeyEvent.KEYCODE_F3 -> if (event.repeatCount == 0) setMuted(false)
            else -> {
                android.util.Log.i("Jarvis", "unhandled key $keyCode")
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    // Swallow the matching key-up so the slider's release edge can't leak to the system.
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MUTE, android.view.KeyEvent.KEYCODE_F3 -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    /**
     * Mirror the physical mute slider: UI state, stop streaming upstream, park the wake detector.
     * Playback is left alone — muting mid-answer shouldn't cut the answer off. Deliberately NOT
     * AudioManager.isMicrophoneMute: a missed key event would strand a software mute the switch
     * can't clear. Same-state calls are dropped so a stray repeat doesn't re-toast.
     */
    private fun setMuted(muted: Boolean) {
        val p = panel ?: return
        if (p.micMuted == muted) return
        p.micMuted = muted
        p.muteShownAt = android.os.SystemClock.elapsedRealtime()
        session?.hardMuted = muted
        if (muted) wake?.stop()
        else if (session?.active != true) wake?.start()
    }

    // singleTask: a repeat launch (adb `am start --es ask`, HOME re-press) lands here instead of
    // stacking a second instance. Two instances = two wake detectors, and this HAL allows only ONE
    // active capture stream — the stale detector starves the session mic (startInput status -38).
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("ask")?.let { q ->
            session?.ask(q)                                      // live session → speak up right now
            if (session?.active != true) startConversation()     // idle → open one (ask is queued)
        }
    }

    override fun onDestroy() {
        luxHandler.removeCallbacksAndMessages(null)
        wifiLock?.let { runCatching { it.release() } }
        wake?.stop(); session?.stop()
        super.onDestroy()
    }
}

private val Ground = Color(0xFFFFFFFF)
private val TextMain = Color(0xFF0D0D0D)
private val TextDim = Color(0xFF8F8F8F)
private val Blue = Color(0xFF3E68FF)
private val Cream = Color(0xFFF7F2E9)
private val Faint = Color(0xFFB4B4B4)
private val OrbTop = Color(0xFF7D84F8)
private val OrbMid = Color(0xFF96A1FA)
private val OrbLow = Color(0xFFE9EDFE)

// Bundle our own font so text never depends on the system default typeface —
// some firmwares (e.g. the factory build) resolve the default to null and crash Compose.
internal val AppFont = FontFamily(
    Font(R.font.app_regular, FontWeight.Normal),
    Font(R.font.app_medium, FontWeight.Medium)
)

@Composable
fun JarvisScreen(session: RealtimeSession, model: String, animate: Boolean, onTap: () -> Unit) {
    // Drive the orb at ~24fps rather than letting an infinite transition redraw this fairly
    // expensive Canvas every vsync — at 60fps it was over half the panel's CPU, all day, forever.
    // The motion is a slow 8s drift; nobody can tell, and the device runs much cooler.
    var phase by remember { mutableStateOf(0f) }
    if (animate) LaunchedEffect(Unit) {
        val twoPi = (2f * Math.PI).toFloat()
        val step = twoPi / (8000f / 42f)          // 8s period at ~24fps
        while (true) {
            phase = (phase + step) % twoPi
            kotlinx.coroutines.delay(42)
        }
    }
    val t = if (animate) phase else 0f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground)
            .clickable(              // no grey ripple flash on tap
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        // The scene — a widget card, the resting orb, or the chat — switches with the Material
        // fade-through (out fast, in soft with a touch of scale): the iOS-feel state transition.
        // A new card of the SAME kind is also a new state, so weather → scores animates too.
        val scene: Any = session.card ?: if (session.messages.isEmpty()) "rest" else "chat"
        androidx.compose.animation.AnimatedContent(
            targetState = scene,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 90)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(300, delayMillis = 90)))
                    .togetherWith(fadeOut(tween(90)))
            },
            contentAlignment = Alignment.Center,
            label = "scene"
        ) { s ->
            when (s) {
                is Card -> SwipeToClose(
                    // swipe = dismiss EVERYTHING: cut the answer, end the session, home screen
                    onClose = { if (session.active) session.stop() else session.clearCard() }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (s) {
                            is WeatherCard -> WeatherCardView(s.data)
                            is com.avera.jarvis.tools.ScoresCard -> ScoresCardView(s)
                            is com.avera.jarvis.tools.GuideCard -> GuideCardView(s)
                        }
                        if (session.assistantText.isNotEmpty() && s !is com.avera.jarvis.tools.GuideCard) {
                            // a whisper, not a transcript: the voice carries the detail, the card
                            // carries the data — the caption just anchors what he's saying
                            BasicText(
                                session.assistantText,
                                modifier = Modifier.padding(horizontal = 110.dp),
                                style = TextStyle(fontFamily = AppFont,color = TextDim, fontSize = 15.sp, textAlign = TextAlign.Center),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                "rest" ->
                    if (session.active)
                        // Listening: the orb and its one line of status.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(26.dp)
                        ) {
                            CloudOrb(t, 1f, session.level)
                            BasicText(
                                text = session.status,
                                style = TextStyle(fontFamily = AppFont,color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
                            )
                            BasicText("Tap anywhere or say “stop” to end", style = TextStyle(fontFamily = AppFont, color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center))
                        }
                    else AmbientIdle(t, session.level)
                else -> SwipeToClose(onClose = { session.stop() }) {
                    // In conversation: the running exchange as a scrollable chat, newest pinned at the
                    // bottom. The orb shrinks beside the status line so the words get the room.
                    val listState = rememberLazyListState()
                    val msgs = session.messages
                    val lastLen = msgs.lastOrNull()?.text?.length ?: 0
                    LaunchedEffect(msgs.size, lastLen) {
                        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(34.dp)) { CloudOrb(t, 1f, session.level, 34.dp) }
                            BasicText(
                                session.status,
                                style = TextStyle(fontFamily = AppFont, color = TextDim, fontSize = 14.sp)
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(msgs) { m -> Bubble(m.text, mine = m.mine) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * iOS-grade swipe-to-close: content is LOCKED to the finger while dragging (snapTo — no chasing
 * spring), flies off-screen and closes past the threshold, springs back home under it.
 */
@Composable
private fun SwipeToClose(onClose: () -> Unit, content: @Composable () -> Unit) {
    val offX = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(offX.value.toInt(), 0) }
            .alpha(1f - (kotlin.math.abs(offX.value) / 1300f).coerceIn(0f, 0.7f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (closing) return@detectHorizontalDragGestures
                        if (kotlin.math.abs(offX.value) > size.width * 0.16f) {
                            closing = true
                            val exit = if (offX.value > 0) size.width * 1.1f else -size.width * 1.1f
                            scope.launch {
                                offX.animateTo(exit, tween(150))   // finish the throw…
                                onClose()                          // …then land on the home screen
                            }
                        } else scope.launch {
                            offX.animateTo(0f, androidx.compose.animation.core.spring(
                                dampingRatio = 0.75f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
                        }
                    },
                    onDragCancel = { scope.launch { offX.animateTo(0f) } },
                    onHorizontalDrag = { _, delta ->
                        if (!closing) scope.launch { offX.snapTo(offX.value + delta) }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
        // The affordance matches the gesture: a slim VERTICAL pill on the edge (the Android
        // back-gesture idiom, "this view slides sideways") — a horizontal bar at the bottom
        // would promise swipe-UP, which this view doesn't do.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Faint.copy(alpha = 0.5f))
        )
    }
}

/** Idle: an ambient clock the way a bedside device should rest — time, day, sky, one hint. */
@Composable
private fun AmbientIdle(t: Float, level: Float) {
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) { while (true) { now = java.util.Date(); kotlinx.coroutines.delay(1000) } }
    val time = remember(now) { java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(now) }
    val date = remember(now) { java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(now) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CloudOrb(t, 0.5f, level, 108.dp)
        Spacer(Modifier.height(10.dp))
        BasicText(
            time,
            style = TextStyle(
                fontFamily = AppFont, color = TextMain, fontSize = 84.sp,
                fontWeight = FontWeight.Light, letterSpacing = 1.sp
            )
        )
        BasicText(date, style = TextStyle(fontFamily = AppFont, color = TextDim, fontSize = 18.sp))
        Ambient.weather?.let { w ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherGlyph(w.days.firstOrNull()?.code ?: 0, 24.dp)
                BasicText(
                    "${w.currentTemp}°  ${w.description.replaceFirstChar { it.uppercase() }}",
                    style = TextStyle(fontFamily = AppFont, color = TextMain, fontSize = 18.sp)
                )
                BasicText(
                    w.location.substringBefore(","),
                    style = TextStyle(fontFamily = AppFont, color = Faint, fontSize = 15.sp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        BasicText("Say “Hey Jarvis”", style = TextStyle(fontFamily = AppFont, color = Faint, fontSize = 14.sp))
    }
}

/** One turn of the conversation. Yours sits right and blue; his sits left and quiet. */
@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (mine) 20.dp else 6.dp,
                        bottomEnd = if (mine) 6.dp else 20.dp
                    )
                )
                .background(if (mine) Blue else Cream)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            BasicText(
                text,
                style = TextStyle(
                    fontFamily = AppFont,
                    color = if (mine) Color.White else TextMain,
                    fontSize = 17.sp,
                    lineHeight = 23.sp
                )
            )
        }
    }
}

@Composable
fun WeatherCardView(w: WeatherData) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText(w.location, style = TextStyle(fontFamily = AppFont,color = TextDim, fontSize = 17.sp))
        Row {
            BasicText("${w.currentTemp}°", style = TextStyle(fontFamily = AppFont,color = TextMain, fontSize = 58.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp))
            Spacer(Modifier.width(6.dp))
            BasicText("F", style = TextStyle(fontFamily = AppFont,color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        BasicText(w.description.replaceFirstChar { it.uppercase() }, style = TextStyle(fontFamily = AppFont,color = TextMain, fontSize = 17.sp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.days.take(7).forEachIndexed { i, d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == 0) Cream else Color.Transparent)
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    BasicText(d.day, style = TextStyle(fontFamily = AppFont,color = TextDim, fontSize = 13.sp))
                    WeatherGlyph(d.code, 24.dp)
                    BasicText("${d.hi}°", style = TextStyle(fontFamily = AppFont,color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                    BasicText("${d.lo}°", style = TextStyle(fontFamily = AppFont,color = Faint, fontSize = 13.sp))
                }
            }
        }
        TempCurve(w.curve)
    }
}

/** Simple drawn weather icons (no emoji font dependency — the factory ROM can't render emoji). */
@Composable
private fun WeatherGlyph(code: Int, size: Dp) {
    val cSun = Color(0xFFF4A62A); val cCloud = Color(0xFF9AA0A6)
    val cRain = Color(0xFF5B8DEF); val cSnow = Color(0xFFBFD3F2)
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        fun sun(cx: Float, cy: Float, r: Float) {
            for (k in 0 until 8) {
                val a = (k * 45f) * (Math.PI.toFloat() / 180f)
                drawLine(cSun, Offset(cx + cos(a) * r * 1.4f, cy + sin(a) * r * 1.4f),
                    Offset(cx + cos(a) * r * 1.9f, cy + sin(a) * r * 1.9f), strokeWidth = w * 0.055f, cap = StrokeCap.Round)
            }
            drawCircle(cSun, r, Offset(cx, cy))
        }
        fun cloud(cx: Float, cy: Float, s: Float) {
            drawCircle(cCloud, s * 0.30f, Offset(cx - s * 0.32f, cy + s * 0.06f))
            drawCircle(cCloud, s * 0.42f, Offset(cx, cy - s * 0.04f))
            drawCircle(cCloud, s * 0.30f, Offset(cx + s * 0.34f, cy + s * 0.06f))
            drawCircle(cCloud, s * 0.30f, Offset(cx + s * 0.02f, cy + s * 0.16f))
        }
        when (code) {
            0, 1 -> sun(w * 0.5f, h * 0.5f, w * 0.22f)
            2 -> { sun(w * 0.36f, h * 0.38f, w * 0.15f); cloud(w * 0.56f, h * 0.58f, w * 0.5f) }
            in 51..67, in 80..82 -> { cloud(w * 0.5f, h * 0.4f, w * 0.52f)
                for (k in 0..2) drawLine(cRain, Offset(w * (0.34f + k * 0.16f), h * 0.66f),
                    Offset(w * (0.30f + k * 0.16f), h * 0.86f), strokeWidth = w * 0.05f, cap = StrokeCap.Round) }
            in 71..77, 85, 86 -> { cloud(w * 0.5f, h * 0.4f, w * 0.52f)
                for (k in 0..2) drawCircle(cSnow, w * 0.045f, Offset(w * (0.34f + k * 0.16f), h * 0.76f)) }
            in 95..99 -> { cloud(w * 0.5f, h * 0.4f, w * 0.52f)
                drawPath(Path().apply { moveTo(w * 0.52f, h * 0.58f); lineTo(w * 0.42f, h * 0.78f)
                    lineTo(w * 0.52f, h * 0.78f); lineTo(w * 0.44f, h * 0.96f) },
                    cSun, style = Stroke(width = w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            else -> cloud(w * 0.5f, h * 0.5f, w * 0.52f)   // overcast / fog / unknown
        }
    }
}

@Composable
private fun TempCurve(points: List<Pair<String, Int>>) {
    if (points.size < 2) return
    val temps = points.map { it.second }
    val minT = temps.min(); val maxT = temps.max()
    val range = (maxT - minT).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(92.dp)) {
        val n = points.size
        val padX = size.width * 0.05f
        val plotW = size.width - padX * 2
        val topPad = 22.dp.toPx(); val botPad = 18.dp.toPx()
        val plotH = size.height - topPad - botPad
        fun px(i: Int) = padX + plotW * i / (n - 1)
        fun py(t: Int) = topPad + plotH * (1f - (t - minT).toFloat() / range)
        val fill = Path().apply {
            moveTo(px(0), py(temps[0]))
            for (i in 1 until n) lineTo(px(i), py(temps[i]))
            lineTo(px(n - 1), size.height - botPad); lineTo(px(0), size.height - botPad); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Cream.copy(alpha = 0.9f), Cream.copy(alpha = 0f))))
        val line = Path().apply {
            moveTo(px(0), py(temps[0])); for (i in 1 until n) lineTo(px(i), py(temps[i]))
        }
        drawPath(line, TextMain, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in 0 until n) drawCircle(TextMain, radius = 3.5.dp.toPx(), center = Offset(px(i), py(temps[i])))
        val hi = android.graphics.Paint().apply {
            color = 0xFF0D0D0D.toInt(); textAlign = android.graphics.Paint.Align.CENTER
            textSize = 12.dp.toPx(); isFakeBoldText = true; isAntiAlias = true
        }
        val lo = android.graphics.Paint().apply {
            color = 0xFF8F8F8F.toInt(); textAlign = android.graphics.Paint.Align.CENTER
            textSize = 11.dp.toPx(); isAntiAlias = true
        }
        drawIntoCanvas { c ->
            for (i in 0 until n) {
                c.nativeCanvas.drawText("${temps[i]}°", px(i), py(temps[i]) - 8.dp.toPx(), hi)
                c.nativeCanvas.drawText(points[i].first, px(i), size.height - 3.dp.toPx(), lo)
            }
        }
    }
}

@Composable
private fun CloudOrb(phase: Float, alpha: Float, level: Float, dim: Dp = 180.dp) {
    // gentle breathing at rest; the whole orb swells a touch with the voice
    val pulse = 1f + 0.022f * sin(phase * 2f) + 0.045f * level
    Canvas(modifier = Modifier.size(dim)) {
        val c = center
        val r = size.minDimension * 0.48f * pulse
        val left = c.x - r; val right = c.x + r; val bottom = c.y + r
        val sphere = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
        clipPath(sphere) {
            // periwinkle sphere
            drawRect(
                brush = Brush.linearGradient(
                    listOf(OrbTop.copy(alpha = alpha), OrbMid.copy(alpha = alpha), OrbLow.copy(alpha = alpha)),
                    start = Offset(c.x, c.y - r), end = Offset(c.x, c.y + r)
                ),
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f)
            )
            // The cloud: soft white fog filling the lower half, whose UPPER EDGE is a gentle
            // rolling wave. A few overlapping layers (each transparent at its wavy crest, ramping
            // to white at the bottom) blend into diffuse fog — no crisp bands. The wave amplitude
            // and boundary height rise with the live audio `level`, so the seam breathes with speech.
            val layers = 3
            for (b in 0 until layers) {
                val t = b / (layers - 1f)                          // 0 = low/opaque … 1 = high/wispy
                val edgeY = c.y + r * (0.12f - t * 0.36f) - r * 0.12f * level  // seam rises gently with voice
                val amp = r * (0.05f + 0.05f * level) * (0.85f + 0.4f * t)     // gentle broad roll
                val freq = 0.8f + b * 0.35f                        // <1.5 cycles → soft billow, not spikes
                val ph = phase * (0.4f + b * 0.28f) + b * 2.2f
                val wave = Path().apply {
                    moveTo(left, bottom)
                    var x = left
                    val step = r / 22f
                    while (x <= right) {
                        val y = edgeY + amp * sin((x - left) / r * freq * Math.PI.toFloat() + ph)
                        lineTo(x, y); x += step
                    }
                    lineTo(right, bottom); close()
                }
                val botA = (0.44f - 0.11f * t) * alpha
                drawPath(wave, Brush.verticalGradient(          // long feathered top → soft fog, no crisp crest
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = botA)),
                    startY = edgeY - amp * 3.2f, endY = bottom
                ))
            }
            // soft billowy puffs drifting along the cloud top for texture
            for (i in 0 until 3) {
                val px = c.x + sin(phase * (0.3f + i * 0.21f) + i * 2.1f) * r * 0.45f
                val py = c.y + r * (0.06f + 0.1f * sin(phase * 0.4f + i * 1.6f)) - r * 0.14f * level
                val pr = r * (0.55f + 0.12f * sin(i * 1.9f + phase * 0.5f))
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.26f * alpha), Color.White.copy(alpha = 0f)),
                        center = Offset(px, py), radius = pr
                    ), radius = pr, center = Offset(px, py)
                )
            }
        }
    }
}
