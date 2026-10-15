package kg.forester.sg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.X
import kg.forester.sg.data.Repo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * «3D мотор» (test page): a boxer EJ built from boxes and prisms right here (no model file), turned by a finger
 * and slowly on its own, flat-shaded and sorted back to front on a Canvas. Every part the app has a reading for
 * carries a callout with the live value in the colour of its state; a tap explains what it is and what is normal.
 */

private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
}

private class Face(val pts: List<V3>, val color: Color)

/** The parts, in model units (a decimetre or so): x across (the cylinder banks), y up, z from the front (−) back. */
private fun engineMesh(turbo: Boolean, accent: Color): List<Face> {
    val out = ArrayList<Face>()
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, c: Color) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2; val y0 = cy - sy / 2; val y1 = cy + sy / 2; val z0 = cz - sz / 2; val z1 = cz + sz / 2
        fun f(vararg p: V3) { out += Face(p.toList(), c) }
        f(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0))
        f(V3(x0, y0, z1), V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y0, z1))
        f(V3(x0, y0, z0), V3(x0, y1, z0), V3(x0, y1, z1), V3(x0, y0, z1))
        f(V3(x1, y0, z0), V3(x1, y0, z1), V3(x1, y1, z1), V3(x1, y1, z0))
        f(V3(x0, y1, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(x0, y1, z1))
        f(V3(x0, y0, z0), V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y0, z0))
    }
    /** A round part as a 14-sided prism along [axis] ('x', 'y' or 'z'). */
    fun prism(cx: Float, cy: Float, cz: Float, r: Float, len: Float, axis: Char, c: Color, n: Int = 14) {
        fun at(a: Float, t: Float): V3 {
            val u = r * cos(a); val v = r * sin(a)
            return when (axis) {
                'x' -> V3(cx + t, cy + u, cz + v)
                'y' -> V3(cx + u, cy + t, cz + v)
                else -> V3(cx + u, cy + v, cz + t)
            }
        }
        val h = len / 2
        val ring = List(n) { (it.toFloat() / n) * 2 * Math.PI.toFloat() }
        for (i in 0 until n) {
            val a = ring[i]; val b = ring[(i + 1) % n]
            out += Face(listOf(at(a, -h), at(b, -h), at(b, h), at(a, h)), c)
        }
        out += Face(ring.map { at(it, -h) }, c)
        out += Face(ring.map { at(it, h) }.reversed(), c)
    }
    val metal = Color(0xFF8C929C); val dark = Color(0xFF2E323A); val alu = Color(0xFFB4BAC4); val rust = Color(0xFF8A5F44)
    box(0f, 0f, 0f, 1.3f, 1.0f, 2.2f, metal)                       // crankcase
    for (s in listOf(-1f, 1f)) {
        for (z in listOf(-0.55f, 0.55f)) prism(s * 0.95f, 0.05f, z, 0.38f, 0.65f, 'x', alu)   // cylinders
        box(s * 1.52f, 0.05f, 0f, 0.42f, 0.95f, 2.1f, Color(0xFF7C828C))                    // heads
        box(s * 1.83f, 0.05f, 0f, 0.2f, 0.76f, 1.9f, accent)                                // valve covers
        for (z in listOf(-0.55f, 0.55f)) box(s * 0.92f, 0.58f, z, 0.95f, 0.16f, 0.2f, Color(0xFF606775)) // runners
        for (z in listOf(-0.55f, 0.55f)) prism(s * 0.9f, -0.58f, z, 0.09f, 0.95f, 'x', rust) // exhaust
        box(s * 1.5f, 0.1f, -1.12f, 0.46f, 0.9f, 0.1f, dark)                                  // belt covers
    }
    box(0f, -0.75f, 0.1f, 1.1f, 0.42f, 1.8f, Color(0xFF3B3F47))     // oil pan
    box(0f, 0.75f, 0f, 0.95f, 0.34f, 1.5f, Color(0xFF5B6170))       // intake plenum
    prism(0f, 0.75f, -0.95f, 0.17f, 0.3f, 'z', alu)                 // throttle body
    prism(-0.55f, 0.88f, -1.35f, 0.15f, 0.6f, 'z', Color(0xFF3A3F48)) // intake pipe with the MAF
    box(0f, 0.05f, -1.17f, 1.2f, 0.7f, 0.08f, dark)                 // centre belt cover
    prism(0f, -0.25f, -1.3f, 0.32f, 0.12f, 'z', Color(0xFF30343C))  // crank pulley
    prism(-0.78f, 0.62f, -1.15f, 0.26f, 0.45f, 'z', alu)            // alternator
    prism(-0.8f, -0.45f, -0.9f, 0.11f, 0.25f, 'x', Color(0xFF9AA0AA)) // thermostat housing
    prism(0.15f, 0.55f, 0.45f, 0.07f, 0.12f, 'y', Color(0xFF444850))   // knock sensor
    prism(0.25f, -0.78f, 0.95f, 0.06f, 0.2f, 'y', Color(0xFFD9DDE3))    // A/F sensor
    if (turbo) {
        box(0f, 1.24f, -0.1f, 1.55f, 0.2f, 1.2f, Color(0xFFC3C9D2))     // intercooler
        prism(1.1f, -0.2f, 1.25f, 0.32f, 0.42f, 'x', Color(0xFFB9BEC6)) // compressor
        prism(1.48f, -0.2f, 1.25f, 0.27f, 0.34f, 'x', Color(0xFFB07A4A)) // turbine (hot side)
    }
    return out
}

/** A part with a reading: where its callout points ([anchor]), the metric ids to show, what to say about it. */
private class Spot(val id: String, val title: String, val anchor: V3, val metrics: List<String>, val about: String, val turboOnly: Boolean = false)

private val SPOTS = listOf(
    Spot("coolant", "Охлаждающая жидкость", V3(-0.8f, -0.45f, -0.9f), listOf("coolant"),
        "Датчик температуры ОЖ у термостата. Прогретый мотор держит примерно 85–95°. Выше 100° повод присмотреться, 105° и выше перегрев. Долго ниже 75° в движении: подклинил открытым термостат."),
    Spot("maf", "Расходомер (MAF)", V3(-0.55f, 0.88f, -1.55f), listOf("maf"),
        "Сколько воздуха идёт в мотор, г/с. На прогретых холостых у EJ20 обычно около 2,5–4 г/с, растёт с оборотами и нагрузкой. Заниженный при той же нагрузке: грязный датчик или подсос воздуха после него."),
    Spot("throttle", "Дроссель", V3(0f, 0.75f, -1.1f), listOf("throttle"),
        "Насколько открыта заслонка. На холостых почти закрыта, в пол около 100%."),
    Spot("intake", "Воздух на впуске", V3(0f, 1.0f, 0.55f), listOf("intake"),
        "Температура воздуха во впуске. Обычно выше уличной на 10–30°. Сильно горячий воздух забирает мощность и повышает риск детонации."),
    Spot("turbo", "Турбина", V3(1.3f, -0.2f, 1.25f), listOf("boost", "wgdc"),
        "Наддув над атмосферой и открытие вестгейта. Сток EJ20T обычно до 0,7–0,9 бар (зависит от версии). Не добирает: утечка во впуске или вестгейт. Перебирает: вестгейт или прошивка.", turboOnly = true),
    Spot("inj", "Форсунки", V3(1.25f, 0.62f, -0.55f), listOf("inj", "injDuty"),
        "Время впрыска и загрузка форсунок. На холостых около 2–3 мс. Загрузка выше 85% на пике значит, форсунки на пределе."),
    Spot("knock", "Датчик детонации", V3(0.15f, 0.62f, 0.45f), listOf("fbkc", "flkc", "iamV"),
        "Слышит детонацию, а ЭБУ убирает угол. Откат FBKC и выученная FLKC в норме 0. IAM 1,0 значит мозги довольны бензином и мотором."),
    Spot("afr", "Лямбда (A/F)", V3(0.25f, -0.86f, 0.95f), listOf("afr", "afcorr", "aflearn"),
        "Смесь и коррекции. На прогретых холостых смесь около 14,7. Коррекция плюс обучение в сумме ±5% норма, больше +10% похоже на подсос воздуха или слабое топливо."),
    Spot("alt", "Генератор", V3(-0.78f, 0.62f, -1.4f), listOf("battery", "alt"),
        "Напряжение бортсети. На работающем моторе 13,5–14,5 В. Ниже 13 генератор не заряжает, выше 15 перезаряд."),
    Spot("avcs", "Распредвал (AVCS)", V3(-1.55f, 0.45f, -1.1f), listOf("avcs"),
        "Насколько муфта AVCS повернула впускной распредвал. На холостых около нуля, в нагрузке до 20–35°."),
    Spot("oil", "Масло", V3(0f, -0.95f, 0.3f), listOf("oil"),
        "Температура масла, на большинстве EJ это оценка по ОЖ и времени работы. До 60° не крутите выше 3000–3500 оборотов."),
    Spot("crank", "Коленвал", V3(0f, -0.25f, -1.38f), listOf("rpm", "load"),
        "Обороты и нагрузка (воздух за оборот, г/об). Прогретые холостые EJ около 650–800 об/мин."),
)

private data class Proj(val x: Float, val y: Float, val z: Float)

@Composable
internal fun Engine3DTab() {
    val turbo = Repo.guard.collectAsState().value.turbo || "turbo" in Repo.settings.carSub.lowercase()
    val accent = Color(0xFF9B1C22)
    val mesh = remember(turbo) { engineMesh(turbo, accent) }
    val avail by Repo.rrAvailable.collectAsState()
    val ctx = rememberMetricCtx()
    var yaw by remember { mutableFloatStateOf(0.75f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }
    var touchedAt by remember { mutableStateOf(0L) }
    var auto by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Spot?>(null) }
    // a slow turn on its own, paused for a while after the finger
    LaunchedEffect(auto) {
        var last = 0L
        while (auto) withFrameNanos { t ->
            if (last != 0L && System.currentTimeMillis() - touchedAt > 4000) yaw += (t - last) / 1e9f * 0.18f
            last = t
        }
    }
    val spots = SPOTS.filter { (!it.turboOnly || turbo) && it.metrics.any { id -> Metrics.of(id, avail)?.read(ctx) != null } }
        .ifEmpty { SPOTS.filter { !it.turboOnly || turbo } }

    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF1C2233), C.Bg)))
            .border(1.dp, C.Border, RoundedCornerShape(18.dp)),
    ) {
        val d = LocalDensity.current
        val wPx = with(d) { maxWidth.toPx() }
        val hPx = with(d) { maxHeight.toPx() }
        val labelW = with(d) { 168.dp.toPx() }
        val labelH = with(d) { 58.dp.toPx() }
        val margin = with(d) { 14.dp.toPx() }
        val cy = hPx * 0.52f
        val cx = wPx * 0.5f
        val focal = minOf(wPx, hPx) * 1.25f

        fun project(p: V3): Proj {
            // turn about y (yaw) then x (pitch), then the camera 6 units back
            val x1 = p.x * cos(yaw) + p.z * sin(yaw)
            val z1 = -p.x * sin(yaw) + p.z * cos(yaw)
            // pitch > 0: the camera above, looking down on the engine
            val y2 = p.y * cos(pitch) + z1 * sin(pitch)
            val z2 = -p.y * sin(pitch) + z1 * cos(pitch) + 6.2f
            return Proj(cx + focal * x1 / z2, cy - focal * y2 / z2, z2)
        }

        // callouts: left or right of the engine by where their point is, spread so they don't overlap
        val anchors = spots.associateWith { project(it.anchor) }
        val placed = HashMap<Spot, Offset>()
        for (left in listOf(true, false)) {
            val side = spots.filter { (anchors[it]!!.x < cx) == left }.sortedBy { anchors[it]!!.y }
            val gap = margin * 0.6f
            val ys = FloatArray(side.size)
            var start = margin
            side.forEachIndexed { i, s -> ys[i] = maxOf(start, anchors[s]!!.y - labelH / 2); start = ys[i] + labelH + gap }
            // too low at the bottom: move the column up as much as needed (the top stays inside)
            val over = (ys.lastOrNull() ?: 0f) + labelH + margin - hPx
            if (over > 0) for (i in ys.indices.reversed()) {
                val limit = if (i == ys.lastIndex) hPx - labelH - margin else ys[i + 1] - labelH - gap
                ys[i] = minOf(ys[i], limit).coerceAtLeast(margin + i * (labelH + gap))
            }
            side.forEachIndexed { i, s -> placed[s] = Offset(if (left) margin else wPx - labelW - margin, ys[i]) }
        }

        val violet = C.Violet
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragStart = { touchedAt = System.currentTimeMillis() }) { change, drag ->
                    touchedAt = System.currentTimeMillis()
                    yaw += drag.x * 0.008f
                    pitch = (pitch + drag.y * 0.006f).coerceIn(-0.3f, 1.3f)
                    change.consume()
                }
            },
        ) {
            // a soft floor shadow
            drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), Offset(cx, cy + focal * 1.2f / 6.2f + 30f), focal * 0.42f),
                Offset(cx - focal * 0.42f, cy + focal * 1.2f / 6.2f), Size(focal * 0.84f, focal * 0.14f))
            // faces back to front, lit from the upper left front
            val light = V3(-0.45f, 0.75f, -0.5f).let { val l = it.len(); V3(it.x / l, it.y / l, it.z / l) }
            val drawn = mesh.map { f ->
                val p = f.pts.map { project(it) }
                val cam = f.pts.map { v ->
                    val x1 = v.x * cos(yaw) + v.z * sin(yaw); val z1 = -v.x * sin(yaw) + v.z * cos(yaw)
                    V3(x1, v.y * cos(pitch) + z1 * sin(pitch), -v.y * sin(pitch) + z1 * cos(pitch))
                }
                val n = (cam[1] - cam[0]).cross(cam[2] - cam[0]).let { val l = it.len().coerceAtLeast(1e-6f); V3(it.x / l, it.y / l, it.z / l) }
                Triple(p, p.map { it.z }.average().toFloat(), abs(n.x * light.x + n.y * light.y + n.z * light.z) to f.color)
            }.sortedByDescending { it.second }
            for ((p, _, shade) in drawn) {
                val (lit, base) = shade
                val k = 0.32f + 0.68f * lit
                val col = Color(base.red * k, base.green * k, base.blue * k, 1f)
                val path = Path().apply { moveTo(p[0].x, p[0].y); for (i in 1 until p.size) lineTo(p[i].x, p[i].y); close() }
                drawPath(path, col)
                drawPath(path, Color.White.copy(alpha = 0.05f + 0.07f * lit), style = Stroke(1.2f))
            }
            // leader lines: from the part to its callout, with a glowing dot on the part
            for (s in spots) {
                val a = anchors[s] ?: continue
                val o = placed[s] ?: continue
                val left = o.x < cx
                val end = Offset(if (left) o.x + labelW else o.x, o.y + labelH / 2)
                val mid = Offset(end.x + if (left) 18f else -18f, end.y)
                val col = if (selected == s) Color.White else violet
                drawLine(col.copy(alpha = 0.7f), Offset(a.x, a.y), mid, strokeWidth = 2f)
                drawLine(col.copy(alpha = 0.7f), mid, end, strokeWidth = 2f)
                drawCircle(col.copy(alpha = 0.25f), 12f, Offset(a.x, a.y))
                drawCircle(col, 5f, Offset(a.x, a.y))
            }
        }

        for (s in spots) {
            val o = placed[s] ?: continue
            Callout(s, avail, ctx, selected == s, Modifier.offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }) {
                selected = if (selected == s) null else s
            }
        }

        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
            T("Тестовая страница · крутите пальцем", 12.sp, C.Sub)
            Spacer(Modifier.width(10.dp))
            Icon(Lucide.RotateCw, "Вращение", tint = if (auto) C.Violet else C.Sub,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { auto = !auto })
        }

        AnimatedVisibility(selected != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn() + slideInHorizontally { it / 4 }, exit = fadeOut() + slideOutHorizontally { it / 4 }) {
            val s = selected ?: return@AnimatedVisibility
            Column(
                Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(C.Card.copy(alpha = 0.97f))
                    .border(1.dp, C.Violet.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(s.title, 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Lucide.X, "Закрыть", tint = C.Sub, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { selected = null })
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    s.metrics.forEach { id ->
                        val m = Metrics.of(id, avail) ?: return@forEach
                        val v = m.read(ctx)
                        Column(Modifier.padding(end = 18.dp)) {
                            T(m.short, 11.sp, C.Sub)
                            T(valueText(m, v), 18.sp, if (m.warn(v)) C.Orange else C.Text, FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                T(s.about, 13.sp, C.Text.copy(alpha = 0.85f))
            }
        }
    }
}

private fun valueText(m: Indicator, v: Float?): String =
    m.text(v) + if (m.unit.isEmpty() || v == null) "" else if (m.unit.startsWith("°")) m.unit else " ${m.unit}"

/** A callout card: the part, its main value large in the colour of its state, a second value small. */
@Composable
private fun Callout(s: Spot, avail: List<kg.forester.sg.ssm.RrParam>, ctx: MetricCtx, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val main = s.metrics.firstNotNullOfOrNull { Metrics.of(it, avail) }
    val v = main?.read(ctx)
    val second = s.metrics.drop(1).firstNotNullOfOrNull { id -> Metrics.of(id, avail)?.let { m -> m.read(ctx)?.let { m to it } } }
    val state = when {
        v == null -> C.Gray
        main.warn(v) -> C.Orange
        else -> C.Green
    }
    Row(
        modifier.width(168.dp).height(58.dp).clip(RoundedCornerShape(14.dp))
            .background(C.Card.copy(alpha = if (selected) 0.98f else 0.86f))
            .border(1.dp, if (selected) Color.White else state.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 10.dp).clip(RoundedCornerShape(2.dp)).background(state))
        Spacer(Modifier.width(8.dp))
        Column {
            T(s.title, 11.sp, C.Sub, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                T(main?.let { valueText(it, v) } ?: "—", 17.sp, C.Text, FontWeight.Bold, maxLines = 1)
                if (second != null) T("  " + valueText(second.first, second.second), 11.sp, C.Sub, maxLines = 1)
            }
        }
    }
}
