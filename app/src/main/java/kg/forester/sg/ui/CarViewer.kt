package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Pre-rendered 360° turntable frames in assets/car/car_NNN.webp. */
private class FrameSource(private val ctx: Context) {
    val count: Int = ctx.assets.list("car")?.count { it.startsWith("car_") } ?: 0
    // ~20 decoded frames in memory; neighbours are decoded on demand.
    private val cache = object : LruCache<Int, Bitmap>(20) {}

    fun get(i: Int): Bitmap? {
        if (count == 0) return null
        val idx = ((i % count) + count) % count
        cache.get(idx)?.let { return it }
        val bmp = runCatching {
            ctx.assets.open("car/car_%03d.webp".format(idx)).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        cache.put(idx, bmp)
        return bmp
    }
}

/**
 * Rotatable car: drag horizontally to spin, idles with a slow auto-rotation.
 * The neon floor glow pulses with engine speed.
 */
@Composable
fun CarViewer(rpm: Int?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val src = remember { FrameSource(ctx.applicationContext) }
    var angle by remember { mutableFloatStateOf(0f) } // in frames
    var lastTouch by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    // Slow auto-rotation when the user is not touching it.
    LaunchedEffect(Unit) {
        while (true) {
            delay(66)
            if (System.currentTimeMillis() - lastTouch > 4000) angle += 0.12f
        }
    }
    LaunchedEffect(angle.roundToInt()) {
        val i = angle.roundToInt()
        frame = withContext(Dispatchers.IO) { src.get(i)?.asImageBitmap() } ?: frame
    }

    val glow by animateFloatAsState(((rpm ?: 0) / 7000f).coerceIn(0f, 1f), tween(300), label = "glow")

    Box(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, drag ->
                lastTouch = System.currentTimeMillis()
                angle -= drag / 12f
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // horizon glow
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to C.Purple.copy(alpha = 0.18f + glow * 0.25f),
                    0.62f to C.Violet.copy(alpha = 0.35f + glow * 0.4f),
                    0.70f to C.Purple.copy(alpha = 0.10f),
                    1f to Color.Transparent,
                )
            )
            // perspective floor grid
            val horizon = h * 0.62f
            for (k in 1..9) {
                val y = horizon + (h - horizon) * (k / 9f) * (k / 9f)
                drawLine(C.Violet.copy(alpha = 0.10f + k * 0.015f), Offset(0f, y), Offset(w, y), 1.2f)
            }
            for (k in -10..10) {
                drawLine(
                    C.Violet.copy(alpha = 0.12f),
                    Offset(w / 2 + k * w * 0.02f, horizon), Offset(w / 2 + k * w * 0.16f, h), 1.2f,
                )
            }
            // neon under-glow ellipse, brighter with rpm
            drawOval(
                Brush.radialGradient(
                    listOf(C.Violet.copy(alpha = 0.55f + glow * 0.45f), Color.Transparent),
                    center = Offset(w / 2, h * 0.80f), radius = w * 0.34f,
                ),
                topLeft = Offset(w * 0.16f, h * 0.70f), size = Size(w * 0.68f, h * 0.2f),
            )
        }
        frame?.let {
            Image(it, "Forester SG5", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
