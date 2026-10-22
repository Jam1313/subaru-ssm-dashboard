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
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * 3D models available for the home screen: assets/<dir>/car_NNN.webp (+ plates.json, meta.json).
 * meta.json may set driveFrame (straight rear view), carX (car centre in that frame) and the exhaust spot.
 */
val CarModels3d = listOf(
    "SG" to "Forester SG5",
    "SGNA" to "Forester SG5 (атмо)",
    "SH" to "Forester SH",
    "SHNA" to "Forester SH (атмо)",
    "SK" to "Forester SK 2020",
    "GRC" to "WRX STI GRC",
    "BM" to "Legacy 2.5GT tS (BM9)",
    "VA" to "WRX STI 2018 (VA)",
    "GG" to "Impreza WRX Wagon (GG)",
    "GD" to "Impreza WRX STI (GD)",
    "GV" to "Impreza WRX STI (GV)",
    "22B" to "Impreza 22B STi",
    "GC" to "Impreza Coupe WRX Type R (GC)",
)

/** True when the model has white-body frames, so it can take any colour (incl. white and silver). */
fun car3dAnyColour(ctx: Context, id: String): Boolean =
    car3dDir(ctx, id)?.let { d -> ctx.assets.list(d)?.any { it.startsWith("white_") } } ?: false

/** Asset folder of a 3D model: "car" for the SG5, "car_<id>" for the others (car_sh, car_sk, car_22b, ...). */
fun car3dDir(ctx: Context, id: String): String? {
    val dir = if (id == "SG" || CarModels3d.none { it.first == id }) "car" else "car_" + id.lowercase()
    return dir.takeIf { (ctx.assets.list(it)?.any { f -> f.startsWith("car_") } ?: false) }
}

/**
 * One still of the chosen 3D car in its paint, cropped to the car: the straight rear view, or ([side]) the side
 * view with the nose to the left. For the tilt card; decodes one frame, call it off the main thread.
 */
fun carStill(ctx: Context, model: String, paintId: String, side: Boolean): Bitmap? {
    val src = FrameSource(ctx.applicationContext, car3dDir(ctx, model) ?: "car", carPaint(paintId))
    if (src.count == 0) return null
    val b = src.get(if (side) src.driveFrame - src.count / 4 else src.driveFrame) ?: return null
    var x0 = b.width; var y0 = b.height; var x1 = 0; var y1 = 0
    for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
        if ((b.getPixel(x, y) ushr 24) > 16) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
    }
    if (x1 <= x0 || y1 <= y0) return b
    return Bitmap.createBitmap(b, x0, y0, (x1 - x0 + 2).coerceAtMost(b.width - x0), (y1 - y0 + 2).coerceAtMost(b.height - y0))
}

/**
 * The decoded frames of the car on screen, kept while the app runs: going to another screen and back does
 * not decode them again (that took a few seconds). Only the last model/paint is kept (~1.3 MB per frame).
 */
private object CarFrames {
    private var key = ""
    private var src: FrameSource? = null
    /** Where the car was turned to, in frames. */
    var angle: Float? = null

    @Synchronized
    fun source(ctx: Context, model: String, paint: String): FrameSource {
        val k = "$model|$paint"
        src?.takeIf { key == k }?.let { return it }
        return FrameSource(ctx.applicationContext, car3dDir(ctx, model) ?: "car", carPaint(paint)).also { src = it; key = k }
    }
}

/** Pre-rendered 360° turntable frames in assets/car/car_NNN.webp. */
private class FrameSource(private val ctx: Context, private val dir: String, private val paint: CarPaint) {
    val count: Int = ctx.assets.list(dir)?.count { it.startsWith("car_") } ?: 0

    private val meta = runCatching {
        org.json.JSONObject(ctx.assets.open("$dir/meta.json").bufferedReader().readText())
    }.getOrNull()
    /** Straight rear view used while driving, and where things are in it (SG5 values by default). */
    val driveFrame = meta?.optInt("driveFrame", 43) ?: 43
    val carX = meta?.optDouble("carX", 0.464)?.toFloat() ?: 0.464f // the car is not centred in its frame
    val exhaustX = meta?.optDouble("exhaustX", 0.366)?.toFloat() ?: 0.366f
    val exhaustY = meta?.optDouble("exhaustY", 0.86)?.toFloat() ?: 0.86f
    /** Exhaust tips in the rear view (the SH XT has two). */
    val exhausts: List<Pair<Float, Float>> = listOfNotNull(
        exhaustX to exhaustY,
        meta?.takeIf { it.has("exhaust2X") }?.let { it.getDouble("exhaust2X").toFloat() to it.getDouble("exhaust2Y").toFloat() },
    )
    // All frames are decoded once in the background and kept in memory: on-the-fly decoding made the
    // rotation stutter. ~1.3 MB per frame, needs largeHeap.
    private val frames = arrayOfNulls<Bitmap>(count)
    @Volatile var loaded = 0
        private set

    /** Decodes every frame, nearest to [first] (the view on screen / the driving view) first. */
    fun preload(first: Int = 0) {
        val order = (0 until count).sortedBy { i -> minOf((i - first + count) % count, (first - i + count) % count) }
        for ((k, i) in order.withIndex()) {
            if (frames[i] == null) get(i)
            loaded = k + 1
        }
    }

    private fun decode(i: Int): Bitmap? = runCatching {
        fun load(name: String) = ctx.assets.open("$dir/%s_%03d.webp".format(name, i)).use { BitmapFactory.decodeStream(it) }
        when {
            paint.id == "chameleon" -> load("car")
            hasWhite -> tint(load("white"), load("mask"), paint)
            paint.needsWhite -> load("car") // no white frames for this model: light colours are not possible
            else -> load("car")?.let { recolor(it, paint) }
        }
    }.getOrNull()

    private val hasWhite = ctx.assets.list(dir)?.any { it.startsWith("white_") } ?: false

    /** Per-frame plate corners (front/rear), normalised 0..1, TL TR BR BL. */
    val plates: List<Map<String, FloatArray>> = runCatching {
        val arr = org.json.JSONArray(ctx.assets.open("$dir/plates.json").bufferedReader().readText())
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

    /** Width of the car itself in each frame, as a part of the frame width (the rear view is narrow). */
    private val carW = FloatArray(count) { 1f }
    fun carWidth(i: Int) = if (count == 0) 1f else carW[index(i)]

    private fun measure(idx: Int, b: Bitmap) {
        var mn = b.width; var mx = 0
        for (x in 0 until b.width step 3) for (y in 0 until b.height step 6) {
            if ((b.getPixel(x, y) ushr 24) > 8) { if (x < mn) mn = x; if (x > mx) mx = x }
        }
        if (mx > mn) carW[idx] = (mx - mn).toFloat() / b.width
    }

    @Synchronized
    fun get(i: Int): Bitmap? {
        if (count == 0) return null
        val idx = index(i)
        return frames[idx] ?: decode(idx)?.also { frames[idx] = it; measure(idx, it) }
    }
}

/**
 * Rotatable car: drag horizontally to spin, idles with a slow auto-rotation.
 * The neon floor glow pulses with engine speed.
 */
@Composable
fun CarViewer(
    rpm: Int?, speedKmh: Int? = null, modifier: Modifier = Modifier,
    throttlePct: Float? = null, boostBar: Float? = null,
    /** Part of the width the car itself may take (the dials sit left and right). */
    carShare: Float = 1f,
    /** Car size relative to the largest that fits; a smaller car sits lower, on the road. */
    carScale: Float = 1f,
) {
    val ctx = LocalContext.current
    val model by kg.forester.sg.data.Repo.car3d.collectAsState()
    val paintId by kg.forester.sg.data.Repo.carPaint.collectAsState()
    val src = remember(model, paintId) { CarFrames.source(ctx, model, paintId) }
    // back on the home screen the car is where it was; on the move straight away in the rear view, no spin
    var angle by remember(src) {
        mutableFloatStateOf(if ((speedKmh ?: 0) >= 3) src.driveFrame.toFloat() else CarFrames.angle ?: 0f)
    } // in frames
    LaunchedEffect(angle) { CarFrames.angle = angle }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameIdx by remember { mutableStateOf(0) }
    val plate by kg.forester.sg.data.Plate.bitmap.collectAsState()
    LaunchedEffect(Unit) { if (kg.forester.sg.data.Plate.bitmap.value == null) kg.forester.sg.data.Plate.load(ctx) }

    // Parked: the car stands still, a finger turns it. Driving: turn to the straight rear view (like the Teyes
    // launcher), let the road and speed streaks run, follow GPS turns, the camera lag behind on a pull and the
    // exhaust spit flames. Only a parked car can be turned by hand.
    // Turn rate from the GPS course (°/s, + = right), only on the move where the course is reliable.
    val gps by kg.forester.sg.data.Repo.gps.collectAsState()
    var yaw by remember { mutableFloatStateOf(0f) }
    var lastFix by remember { mutableStateOf<kg.forester.sg.data.GpsFix?>(null) }
    LaunchedEffect(gps) {
        val g = gps
        val prev = lastFix
        lastFix = g
        if (g?.bearing == null || g.speedKmh < 10f || prev?.bearing == null) { yaw *= 0.5f; return@LaunchedEffect }
        val dt = ((g.timeMs - prev.timeMs) / 1000f).takeIf { it in 0.2f..3f } ?: return@LaunchedEffect
        var d = g.bearing - prev.bearing
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        yaw += ((d / dt).coerceIn(-30f, 30f) - yaw) * 0.45f
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); if ((gps?.let { System.currentTimeMillis() - it.timeMs } ?: 99_999) > 3000) yaw *= 0.5f } }
    val turn by animateFloatAsState((yaw / 22f).coerceIn(-1f, 1f), tween(700), label = "turn")
    val turnNow by rememberUpdatedState(turn)
    val speed by rememberUpdatedState(speedKmh ?: 0)
    var road by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(src) { withContext(Dispatchers.Default) { src.preload(angle.roundToInt()) } }
    LaunchedEffect(src) {
        while (true) {
            val driving = speed >= 3
            delay(35)
            // parked: the car stays where it is (turn it with a finger); driving: chase view, eased into turns
            if (!driving) continue
            val n = src.count.coerceAtLeast(1)
            val cur = angle.roundToInt()
            // in a right turn the nose swings right and the chase view sees the car's right flank:
            // higher frame numbers (the camera moves to the car's right), up to 3 frames (15°)
            val target = src.driveFrame + (turnNow * 3f).roundToInt()
            var diff = ((target - cur) % n + n) % n
            if (diff > n / 2) diff -= n
            if (diff != 0) angle = (cur + if (diff > 0) 1 else -1).toFloat()
        }
    }
    val rpmNow by rememberUpdatedState(rpm ?: 0)
    val throttle by rememberUpdatedState(throttlePct ?: 0f)
    var accel by remember { mutableFloatStateOf(0f) } // smoothed, km/h per second
    var flame by remember { mutableFloatStateOf(0f) } // 1 = fresh backfire, fades to 0
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var lastV = speed.toFloat()
        var vT = 0L
        val recent = ArrayDeque<Triple<Long, Int, Float>>() // time, rpm, throttle over the last ~0.4 s
        while (true) {
            // parked with nothing left to play (no flame, the camera settled): no work on every frame, only a look
            // ten times a second for the car to move off; dt starts again from zero, so nothing jumps
            if (speed < 3 && flame == 0f && accel == 0f) { delay(100); last = 0L; continue }
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 0f else (t - last) / 1e9f
                last = t
                clock += dt
                if (speed >= 3) {
                    // ~2 road dashes per second at 30 km/h, faster with speed
                    road = (road + dt * speed / 14f) % 1f
                }
                // speed only changes 4–12 times a second (plain or fast poll): measure acceleration between updates,
                // smoothed by time so the fast poll's more frequent steps don't make the camera twitchier
                if (speed.toFloat() != lastV) {
                    if (vT != 0L) {
                        val span = ((t - vT) / 1e9f).coerceAtLeast(0.1f)
                        val a = (speed - lastV) / span
                        accel += (a - accel) * (span / 0.7f).coerceAtMost(1f)
                    }
                    lastV = speed.toFloat(); vT = t
                } else if (vT != 0L && t - vT > 1_500_000_000L) {
                    // decays to an exact zero (a leftover 0.01 km/h/s changes nothing on screen but kept every frame busy)
                    accel *= 0.95f
                    if (kotlin.math.abs(accel) < 0.05f) accel = 0f
                }
                // backfire: throttle snapped shut at high rpm, or an upshift under load
                val ms = t / 1_000_000L
                recent.addLast(Triple(ms, rpmNow, throttle))
                while (recent.size > 1 && ms - recent.first().first > 400) recent.removeFirst()
                val old = recent.first()
                val lift = old.third - throttle > 25f && old.second > 2800
                val shift = rpmNow < old.second - 600 && throttle > 50f && old.second > 2500
                if ((lift || shift) && flame < 0.2f && speed >= 3) flame = 1f
                flame = (flame - dt * 2.8f).coerceAtLeast(0f)
            }
        }
    }
    LaunchedEffect(angle.roundToInt(), src) {
        val i = angle.roundToInt()
        // a decode (with re-colouring) takes longer than one rotation step: finish it even if the angle moves on,
        // otherwise a turning car never gets a picture
        val b = src.cached(i) ?: withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { src.get(i) }
        if (b != null) { frame = b; frameIdx = src.index(i) }
    }

    val roadAlpha by animateFloatAsState(if ((speedKmh ?: 0) >= 3) 1f else 0f, tween(800), label = "road")
    // camera lags on a pull (car gets a bit smaller), catches up when braking
    val zoom by animateFloatAsState(1f - (accel / 12f).coerceIn(-0.6f, 1f) * 0.07f * roadAlpha, tween(500), label = "zoom")
    // "nitro": boost plus a hard pull, like the NFS blur when the bottle opens
    val nitro by animateFloatAsState(
        (((boostBar ?: 0f) - 0.35f) / 0.5f + (accel - 6f) / 10f).coerceIn(0f, 1f) * roadAlpha, tween(350), label = "nitro",
    )
    val turbo by animateFloatAsState(((boostBar ?: 0f) / 0.9f).coerceIn(0f, 1f) * roadAlpha, tween(250), label = "turbo")
    val glow by animateFloatAsState(((rpm ?: 0) / 7000f).coerceIn(0f, 1f), tween(300), label = "glow")

    Box(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, drag ->
                // on the move the car always shows its rear (road, exhaust flames): no turning it by hand
                if (speed >= 3) return@detectHorizontalDragGestures
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
            // The frames are rendered from slightly above the roof: on the move the vanishing point goes up
            // to roof level, otherwise the car looks nose-up on the road.
            val horizon = h * (0.62f - 0.38f * roadAlpha)
            for (k in 0..9) {
                val t = (k + road) / 9f // the lines drift towards the viewer while driving
                if (t <= 0f || t > 1f) continue
                val y = horizon + (h - horizon) * t * t
                drawLine(C.Violet.copy(alpha = 0.10f + t * 9f * 0.015f), Offset(0f, y), Offset(w, y), 1.2f)
            }
            for (k in -10..10) {
                drawLine(
                    C.Violet.copy(alpha = 0.12f),
                    Offset(w / 2 + k * w * 0.02f, horizon), Offset(w / 2 + k * w * 0.16f, h), 1.2f,
                )
            }
            // the road: appears while driving, its dashes run towards the viewer with the speed
            // in a turn the far end of the road swings to the side (more far away, none at the car)
            val bend = turn * roadAlpha * w * 0.15f
            // t runs from the horizon (0) to the bottom (1); t² is the perspective scale for x and y alike
            fun bendAt(t: Float) = bend * (1f - t * t) * (1f - t * t)
            if (roadAlpha > 0.01f) {
                val cx = w / 2
                fun xAt(t: Float, side: Float) = cx + side * (h * 0.02f + t * t * h * 0.95f) + bendAt(t)
                fun yAt(t: Float) = horizon + (h - horizon) * t * t
                val steps = 16
                val asphalt = androidx.compose.ui.graphics.Path().apply {
                    moveTo(xAt(0f, -1f), yAt(0f))
                    for (i in 1..steps) { val t = i / steps.toFloat(); lineTo(xAt(t, -1f), yAt(t)) }
                    for (i in steps downTo 0) { val t = i / steps.toFloat(); lineTo(xAt(t, 1f), yAt(t)) }
                    close()
                }
                drawPath(asphalt, Brush.verticalGradient(listOf(Color(0x00101626), Color(0xCC0E1322)), horizon, h), alpha = roadAlpha)
                for (side in listOf(-1f, 1f)) {
                    for (i in 0 until steps) {
                        val t0 = i / steps.toFloat()
                        val t1 = (i + 1) / steps.toFloat()
                        drawLine(C.Violet.copy(alpha = 0.55f * roadAlpha), Offset(xAt(t0, side), yAt(t0)), Offset(xAt(t1, side), yAt(t1)), 2.5f)
                    }
                }
                val n = 7
                for (k in -1..n) {
                    val t0 = ((k + road) / n).coerceIn(0f, 1f)
                    val t1 = ((k + road + 0.45f) / n).coerceIn(0f, 1f)
                    if (t1 <= t0) continue
                    drawLine(
                        Color.White.copy(alpha = (0.25f + 0.6f * t1) * roadAlpha),
                        Offset(cx + bendAt(t0), yAt(t0)), Offset(cx + bendAt(t1), yAt(t1)), 1.5f + t1 * 7f,
                    )
                }
            }
            // speed streaks from the vanishing point: motion blur, stronger with speed and when pulling
            val streak = (roadAlpha * ((speedKmh ?: 0) / 110f + accel.coerceAtLeast(0f) / 15f + nitro * 0.6f)).coerceIn(0f, 1f)
            if (streak > 0.02f) {
                val vx = w / 2 + bend
                for (i in 0 until (26 + (nitro * 40).toInt())) {
                    // fixed pseudo-random rays, each with its own phase
                    val ang = (i * 137.508f) % 360f
                    val rad = Math.toRadians(ang.toDouble())
                    val dx = kotlin.math.cos(rad).toFloat()
                    val dy = kotlin.math.sin(rad).toFloat() * 0.55f
                    val ph = (road * 2f + i * 0.37f) % 1f
                    val r0 = w * (0.10f + ph * 0.55f)
                    val r1 = r0 + w * (0.05f + 0.18f * streak + 0.35f * nitro) * (0.4f + ph)
                    val tint = if ((turbo > 0.3f || nitro > 0.2f) && i % 3 == 0) Color(0xFF7DD3FC) else Color.White
                    drawLine(
                        tint.copy(alpha = streak * 0.35f * (1f - ph) * (0.5f + 0.5f * ph * 2f).coerceAtMost(1f)),
                        Offset(vx + dx * r0, horizon + dy * r0), Offset(vx + dx * r1, horizon + dy * r1),
                        1f + 2.5f * ph,
                    )
                }
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
        val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG) }
        if (f != null) {
            Canvas(Modifier.fillMaxSize()) {
                // fit the frame into the box (like ContentScale.Fit)
                // the car itself (not the frame with its empty margins) fits the room between the dials
                val scale = minOf(size.width * carShare / (f.width * src.carWidth(frameIdx).coerceAtLeast(0.35f)), size.height / f.height) * zoom * carScale
                val dw = f.width * scale
                val dh = f.height * scale
                // while driving, put the car (not its frame) on the road's centre line
                val ox = (size.width - dw) / 2 + (0.5f - src.carX) * dw * roadAlpha
                val oy = (size.height - dh) / 2 + (1f - zoom) * size.height * 0.25f + (1f - carScale) * size.height * 0.32f
                drawIntoCanvas { cnv ->
                    val nc = cnv.nativeCanvas
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
                // exhaust: orange backfire, blue turbo flicker under boost (only in the rear view)
                if (frameIdx == src.driveFrame && roadAlpha > 0.5f && (flame > 0f || turbo > 0.15f)) src.exhausts.forEach { (exX, exY) ->
                    val ex = Offset(ox + exX * dw, oy + exY * dh)
                    val flick = 0.75f + 0.25f * kotlin.math.sin(clock * 47f) * kotlin.math.sin(clock * 31f)
                    if (turbo > 0.15f) {
                        val r = dw * 0.035f * (0.6f + turbo) * flick
                        drawCircle(
                            Brush.radialGradient(listOf(Color(0xFFE0F2FE), Color(0xFF38BDF8).copy(alpha = 0.8f), Color.Transparent), ex, r),
                            r, ex, alpha = turbo,
                        )
                    }
                    if (flame > 0f) {
                        val r = dw * (0.05f + 0.11f * flame) * flick
                        val c = Offset(ex.x, ex.y + r * 0.15f)
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color(0xFFFFF7D6), Color(0xFFFFC233), Color(0xFFFF6A00).copy(alpha = 0.7f), Color.Transparent),
                                c, r,
                            ),
                            r, c, alpha = flame.coerceAtMost(1f),
                        )
                    }
                }
            }
        }
        // NFS-style speed blur in front of everything: long streaks along the edges and a dark tinted vignette
        if (nitro > 0.02f) Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w / 2 + turn * w * 0.15f * roadAlpha, h * 0.24f)
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent,
                    0.85f to Color(0xFF0B1A3A).copy(alpha = 0.55f * nitro), 1f to Color(0xFF020617).copy(alpha = 0.85f * nitro),
                    center = c, radius = w * 0.62f,
                )
            )
            for (i in 0 until 46) {
                val ang = (i * 97.31f + 13f) % 360f
                val rad = Math.toRadians(ang.toDouble())
                val dx = kotlin.math.cos(rad).toFloat()
                val dy = kotlin.math.sin(rad).toFloat() * 0.6f
                val ph = (road * 3f + i * 0.21f) % 1f
                val r0 = w * (0.30f + ph * 0.30f)
                val r1 = r0 + w * (0.12f + 0.30f * nitro)
                drawLine(
                    (if (i % 4 == 0) Color(0xFF93C5FD) else Color.White).copy(alpha = nitro * 0.45f * (1f - ph)),
                    Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * r1, c.y + dy * r1), 1.5f + 3f * ph,
                )
            }
        }
    }
}
