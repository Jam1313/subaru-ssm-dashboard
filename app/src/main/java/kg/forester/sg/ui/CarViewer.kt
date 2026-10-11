package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Pre-rendered 360° turntable frames in assets/car/car_NNN.webp. */
private class FrameSource(private val ctx: Context) {
    val count: Int = ctx.assets.list("car")?.count { it.startsWith("car_") } ?: 0
    // All frames are decoded once in the background and kept in memory: on-the-fly decoding made the
    // rotation stutter. ~1.3 MB per frame, needs largeHeap.
    private val frames = arrayOfNulls<Bitmap>(count)
    @Volatile var loaded = 0
        private set

    fun preload() {
        for (i in 0 until count) {
            if (frames[i] == null) frames[i] = decode(i)
            loaded = i + 1
        }
    }

    private fun decode(i: Int): Bitmap? = runCatching {
        ctx.assets.open("car/car_%03d.webp".format(i)).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Per-frame plate corners (front/rear), normalised 0..1, TL TR BR BL. */
    val plates: List<Map<String, FloatArray>> = runCatching {
        val arr = org.json.JSONArray(ctx.assets.open("car/plates.json").bufferedReader().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.keys().asSequence().associateWith { k ->
                val q = o.getJSONArray(k)
                FloatArray(8) { j -> q.getJSONArray(j / 2).getDouble(j % 2).toFloat() }
            }
        }
    }.getOrDefault(emptyList())

    fun index(i: Int) = if (count == 0) 0 else ((i % count) + count) % count

    fun cached(i: Int): Bitmap? = if (count == 0) null else frames[index(i)]

    fun get(i: Int): Bitmap? {
        if (count == 0) return null
        val idx = index(i)
        return frames[idx] ?: decode(idx)?.also { frames[idx] = it }
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
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameIdx by remember { mutableStateOf(0) }
    val plate by kg.forester.sg.data.Plate.bitmap.collectAsState()
    LaunchedEffect(Unit) { if (kg.forester.sg.data.Plate.bitmap.value == null) kg.forester.sg.data.Plate.load(ctx) }

    // Slow auto-rotation when the user is not touching it.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { src.preload() } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(110) // one 5° frame per tick ≈ 8 s per revolution
            if (System.currentTimeMillis() - lastTouch > 4000) angle += 1f
        }
    }
    LaunchedEffect(angle.roundToInt()) {
        val i = angle.roundToInt()
        val b = src.cached(i) ?: withContext(Dispatchers.IO) { src.get(i) }
        if (b != null) { frame = b; frameIdx = src.index(i) }
    }

    val glow by animateFloatAsState(((rpm ?: 0) / 7000f).coerceIn(0f, 1f), tween(300), label = "glow")

    Box(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, drag ->
                lastTouch = System.currentTimeMillis()
                angle -= drag / 9f
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
        val f = frame
        if (f != null) {
            Canvas(Modifier.fillMaxSize()) {
                // fit the frame into the box (like ContentScale.Fit)
                val scale = minOf(size.width / f.width, size.height / f.height)
                val dw = f.width * scale
                val dh = f.height * scale
                val ox = (size.width - dw) / 2
                val oy = (size.height - dh) / 2
                drawIntoCanvas { cnv ->
                    val nc = cnv.nativeCanvas
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                    nc.drawBitmap(f, null, android.graphics.RectF(ox, oy, ox + dw, oy + dh), paint)
                    val pb = plate
                    val quads = src.plates.getOrNull(frameIdx)
                    if (pb != null && quads != null) {
                        val srcPts = floatArrayOf(0f, 0f, pb.width.toFloat(), 0f, pb.width.toFloat(), pb.height.toFloat(), 0f, pb.height.toFloat())
                        quads.values.forEach { q ->
                            val dst = FloatArray(8) { j -> if (j % 2 == 0) ox + q[j] * dw else oy + q[j] * dh }
                            val m = android.graphics.Matrix()
                            if (m.setPolyToPoly(srcPts, 0, dst, 0, 4)) nc.drawBitmap(pb, m, paint)
                        }
                    }
                }
            }
        }
    }
}
