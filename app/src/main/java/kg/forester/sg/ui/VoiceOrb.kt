package kg.forester.sg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.service.Voice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * While the assistant listens (its name heard, until the command is done): neon loops in the middle of the
 * screen, each a ribbon of thin lines, slowly turning and swelling with the voice, what is being said under them.
 */
@Composable
fun VoiceOrb() {
    val on by Voice.listening.collectAsState()
    AnimatedVisibility(on, enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.85f), exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.9f)) {
        val level by Voice.level.collectAsState()
        val heard by Voice.partial.collectAsState()
        val loud by animateFloatAsState(level, tween(120), label = "voice")
        val t = rememberInfiniteTransition(label = "orb")
        val time by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "time")
        val colors = listOf(Color(0xFF22D3EE), Color(0xFFE040FB), C.Violet, Color(0xFF7C4DFF), Color(0xFF22D3EE))
        Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.86f), Color.Black.copy(alpha = 0.62f)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Canvas(Modifier.size(320.dp)) {
                    val c = center
                    val r0 = size.minDimension * 0.30f
                    // four loops, each its own shape, speed and turn
                    for (k in 0 until 4) {
                        val amp = 0.10f + loud * 0.22f + k * 0.015f
                        val speed = 1f + k * 0.35f
                        val p1 = time * speed + k * 1.7f
                        val p2 = -time * (speed * 0.8f) + k * 0.9f
                        val p3 = time * 1.3f + k * 2.4f
                        val base = r0 * (1f + k * 0.07f + loud * 0.12f)
                        fun loop(scale: Float): Path = Path().apply {
                            val n = 140
                            for (i in 0..n) {
                                val a = (i.toFloat() / n) * 2 * PI.toFloat()
                                val r = base * scale * (1f + amp * (0.55f * sin(3 * a + p1) + 0.30f * sin(5 * a + p2) + 0.18f * sin(2 * a + p3)))
                                val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            close()
                        }
                        val brush = Brush.sweepGradient(colors, c)
                        rotate(time * 18f * (if (k % 2 == 0) 1 else -1) + k * 40f, c) {
                            // the ribbon: thin lines a little apart, fading outwards
                            for (j in 0 until 12) {
                                drawPath(loop(1f + j * 0.018f), brush, alpha = (0.55f - j * 0.04f).coerceAtLeast(0.06f), style = Stroke(1.2f))
                            }
                            // its glowing edge
                            drawPath(loop(1f), brush, alpha = 0.22f + loud * 0.3f, style = Stroke(16f))
                            drawPath(loop(1f), brush, alpha = 0.9f, style = Stroke(4f))
                        }
                    }
                    drawCircle(Brush.radialGradient(listOf(C.Violet.copy(alpha = 0.35f + loud * 0.3f), Color.Transparent), c, r0 * 0.9f), r0 * 0.9f, c)
                }
                Spacer(Modifier.height(10.dp))
                T(heard.ifBlank { "Слушаю…" }, 26.sp, Color.White, FontWeight.SemiBold, Modifier.widthIn(max = 700.dp), maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}
