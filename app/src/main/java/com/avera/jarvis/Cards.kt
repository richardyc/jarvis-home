package com.avera.jarvis

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.avera.jarvis.tools.Game
import com.avera.jarvis.tools.GuideCard
import com.avera.jarvis.tools.ScoresCard

/* Same visual world as the rest of the app: white glass, ink text, one blue, hairlines. */
private val CardWhite = Color(0xFFFFFFFF)
private val Ink2 = Color(0xFF0D0D0D)
private val Dim2 = Color(0xFF8F8F8F)
private val Faint2 = Color(0xFFB4B4B4)
private val Cream2 = Color(0xFFF7F2E9)
private val LiveRed = Color(0xFFE0483E)

/* ---------- sports: a scoreboard the way the Apple TV app would draw it ---------- */

@Composable
fun ScoresCardView(c: ScoresCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .clip(RoundedCornerShape(28.dp))
            .background(CardWhite)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BasicText(
            c.title.uppercase(),
            style = TextStyle(fontFamily = AppFont, color = Dim2, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        )
        Spacer(Modifier.height(8.dp))
        c.games.forEachIndexed { i, g ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x10000000)))
            GameRow(g)
        }
    }
}

@Composable
private fun GameRow(g: Game) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamCell(g.awayLogo, g.away, alignEnd = false, modifier = Modifier.weight(1f))
        if (g.pre)
            // not played yet — a fixture, not a result (ESPN reports 0-0, don't show it)
            BasicText(
                "vs",
                style = TextStyle(fontFamily = AppFont, color = Dim2, fontSize = 17.sp)
            )
        else BasicText(
            "${g.awayScore.ifEmpty { "–" }}  ·  ${g.homeScore.ifEmpty { "–" }}",
            style = TextStyle(fontFamily = AppFont, color = Ink2, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        )
        TeamCell(g.homeLogo, g.home, alignEnd = true, modifier = Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(128.dp).padding(start = 14.dp)
        ) {
            if (g.live) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.size(6.dp)) { drawCircle(LiveRed) }
                BasicText("LIVE", style = TextStyle(fontFamily = AppFont, color = LiveRed,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
            }
            BasicText(g.detail, style = TextStyle(fontFamily = AppFont,
                color = if (g.live) Ink2 else Faint2, fontSize = 12.sp))
        }
    }
}

@Composable
private fun TeamCell(logo: String, code: String, alignEnd: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        if (!alignEnd) { TeamLogo(logo); Spacer(Modifier.width(10.dp)) }
        BasicText(code, style = TextStyle(fontFamily = AppFont, color = Ink2,
            fontSize = 17.sp, fontWeight = FontWeight.Medium))
        if (alignEnd) { Spacer(Modifier.width(10.dp)); TeamLogo(logo) }
    }
}

@Composable
private fun TeamLogo(url: String, size: Dp = 30.dp) {
    val bmp = if (url.isNotEmpty()) rememberUrlImage(url, maxDim = 120) else null
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(300), label = "logo")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.size(size).alpha(a))
        else Canvas(Modifier.size(size * 0.8f)) {
            drawCircle(Cream2)   // quiet placeholder while the logo loads
        }
    }
}

/* ---------- guide: a recipe/itinerary rendered like a magazine page ---------- */

@Composable
fun GuideCardView(c: GuideCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight(0.94f)
            .clip(RoundedCornerShape(28.dp))
            .background(CardWhite)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (c.heroUrl != null) {
            val bmp = rememberUrlImage(c.heroUrl, maxDim = 900)
            val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(450), label = "hero")
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(21f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Cream2)
            ) {
                if (bmp != null) Image(
                    bmp, null,
                    modifier = Modifier.matchParentSize().alpha(a),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        BasicText(
            c.title,
            style = TextStyle(fontFamily = AppFont, color = Ink2, fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        )
        Spacer(Modifier.height(6.dp))
        c.steps.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0D000000)))
            Row(Modifier.padding(vertical = 14.dp)) {
                BasicText(
                    "${i + 1}",
                    style = TextStyle(fontFamily = AppFont, color = Color(0xFF3E68FF),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.width(30.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(
                        s.heading,
                        style = TextStyle(fontFamily = AppFont, color = Ink2, fontSize = 17.sp,
                            fontWeight = FontWeight.Medium)
                    )
                    if (s.body.isNotEmpty()) BasicText(
                        s.body,
                        style = TextStyle(fontFamily = AppFont, color = Dim2, fontSize = 14.sp,
                            lineHeight = 20.sp)
                    )
                }
            }
        }
    }
}

/* ---------- timer: iOS Clock's ring, shrunk to a bedside panel ---------- */

/** The full countdown card (shown while a card slot is free / on the idle screen). */
@Composable
fun TimerRing(remainingMs: Long, totalMs: Long, label: String, size: Dp = 200.dp) {
    val frac = if (totalMs > 0) (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val sweep by animateFloatAsState(frac, tween(500), label = "timerFrac")
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color(0x14000000), -90f, 360f, false, style = stroke)
            drawArc(Color(0xFF3E68FF), -90f, 360f * sweep, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                clock(remainingMs),
                style = TextStyle(fontFamily = AppFont, color = Ink2, fontSize = 44.sp,
                    fontWeight = FontWeight.Light, letterSpacing = 1.sp)
            )
            if (label.isNotEmpty()) BasicText(
                label,
                style = TextStyle(fontFamily = AppFont, color = Dim2, fontSize = 14.sp)
            )
        }
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
