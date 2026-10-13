package kg.forester.sg.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** G-forces and tilt for the G-meter and the inclinometer. */
data class MotionState(
    /** Sideways (+ = to the right) and along the car (+ = speeding up), in g. */
    val latG: Float? = null,
    val lonG: Float? = null,
    /** Where the numbers come from: "датчик магнитолы", "VDC", "GPS и скорость". */
    val source: String = "",
    /** Nose up (+) / down and roll to the right (+) / left, degrees; needs the head unit's sensor. */
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    /** Road grade from GPS height over the last ~150 m, %. */
    val gradePct: Float? = null,
    val hasSensor: Boolean = false,
    val calibrated: Boolean = false,
    val maxLat: Float = 0f,
    val maxAccel: Float = 0f,
    val maxBrake: Float = 0f,
)

/**
 * The head unit's accelerometer when it has one (many FYT units do not): zeroed on level ground with the car at
 * rest ([calibrate]), it gives pitch, roll and both G axes. Without it: lateral G from VDC's sensor (SH and
 * later, via RomRaider P172) or from speed × GPS turn rate, longitudinal G from the ECU speed.
 */
object Motion : SensorEventListener {
    private var sm: SensorManager? = null
    @Volatile private var grav: FloatArray? = null
    @Volatile private var lin = FloatArray(3)
    private var zero: FloatArray? = null
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var lastGps: GpsFix? = null
    private var gpsLat: Float? = null
    private val climb = ArrayDeque<Pair<Double, Double>>() // (distance m, altitude m)
    private var distM = 0.0
    private var lastSnapMs = 0L

    fun start(ctx: Context) {
        if (sm != null) return
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sm = m
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        m.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        zero = Repo.settings.tiltZero.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 }?.toFloatArray()
        Repo.motion.value = Repo.motion.value.copy(hasSensor = true, calibrated = zero != null)
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val g = grav?.copyOf() ?: ev.values.copyOf()
        // slow low-pass = gravity (tilt), the rest = the car's own acceleration
        for (i in 0..2) g[i] = g[i] * 0.95f + ev.values[i] * 0.05f
        grav = g
        lin = FloatArray(3) { ev.values[it] - g[it] }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Car at rest on level ground: remember which way "down" is for this mounting. */
    fun calibrate() {
        val g = grav ?: return
        zero = g.copyOf()
        Repo.settings.tiltZero = g.joinToString(",")
        Repo.motion.value = Repo.motion.value.copy(calibrated = true)
    }

    fun resetMax() {
        Repo.motion.value = Repo.motion.value.copy(maxLat = 0f, maxAccel = 0f, maxBrake = 0f)
    }

    /** The car's axes in the sensor's frame from the calibration: up, forward (out of the screen's back), right. */
    private fun axes(): Triple<FloatArray, FloatArray, FloatArray>? {
        val z = zero ?: return null
        val up = norm(z)
        // the screen faces the driver, so "forward" is the device's -Z, made level
        val f0 = floatArrayOf(0f, 0f, -1f)
        val d = dot(f0, up)
        val fwd = norm(FloatArray(3) { f0[it] - d * up[it] })
        val right = cross(fwd, up)
        return Triple(up, fwd, right)
    }

    fun process(e: EcuSnapshot, gps: GpsFix?, now: Long) {
        val st = Repo.motion.value
        // longitudinal from the ECU speed: slope of the last ~0.8 s
        e.speedKmh?.let { v ->
            speeds.addLast(now to v / 3.6f)
            while (speeds.isNotEmpty() && now - speeds.first().first > 800) speeds.removeFirst()
        }
        val lonEcu = slope(speeds)?.let { it / 9.81f }
        // lateral from GPS: speed × turn rate
        if (gps != null && gps !== lastGps) {
            val prev = lastGps
            lastGps = gps
            if (prev?.bearing != null && gps.bearing != null && gps.speedKmh > 10) {
                val dt = (gps.timeMs - prev.timeMs) / 1000f
                if (dt in 0.3f..3f) {
                    var db = gps.bearing - prev.bearing
                    if (db > 180) db -= 360f
                    if (db < -180) db += 360f
                    gpsLat = (gps.speedKmh / 3.6f) * Math.toRadians((db / dt).toDouble()).toFloat() / 9.81f
                }
            } else if (gps.speedKmh < 5) gpsLat = 0f
            // grade: height change over distance
            if (prev != null && prev.lat != null && gps.lat != null && prev.lon != null && gps.lon != null) {
                distM += metres(prev.lat, prev.lon, gps.lat, gps.lon)
                climb.addLast(distM to gps.altitudeM)
                while (climb.size > 2 && distM - climb.first().first > 150) climb.removeFirst()
            }
        }
        val grade = if (climb.size >= 2 && climb.last().first - climb.first().first > 60)
            ((climb.last().second - climb.first().second) / (climb.last().first - climb.first().first) * 100).toFloat()
                .takeIf { abs(it) < 40 } else null
        // VDC lateral G (m/s²) when the transmission/VDC unit gives it
        val vdc = Repo.rrValues.value.let { rr -> rr["P172"]?.toFloat()?.let { it / 9.81f } }

        var lat: Float?
        var lon: Float?
        var pitch: Float? = null
        var roll: Float? = null
        var source: String
        val ax = axes()
        val gNow = grav
        if (ax != null && gNow != null) {
            val (up, fwd, right) = ax
            val gu = dot(gNow, up); val gf = dot(gNow, fwd); val gr = dot(gNow, right)
            pitch = Math.toDegrees(atan2(gf.toDouble(), gu.toDouble())).toFloat() // nose up: "up" leans forward
            roll = Math.toDegrees(atan2(-gr.toDouble(), gu.toDouble())).toFloat() // right side down: "up" leans left
            val l = lin
            lat = dot(l, right) / 9.81f
            lon = dot(l, fwd) / 9.81f
            source = "датчик магнитолы"
        } else {
            lat = vdc ?: gpsLat
            lon = lonEcu
            source = if (vdc != null) "VDC и скорость" else "GPS и скорость"
        }
        if (lat != null && abs(lat) > 2.5f) lat = null
        if (lon != null && abs(lon) > 2.5f) lon = null
        lastSnapMs = now
        Repo.motion.value = st.copy(
            latG = lat, lonG = lon, source = source, pitchDeg = pitch, rollDeg = roll, gradePct = grade,
            maxLat = maxOf(st.maxLat, abs(lat ?: 0f)),
            maxAccel = maxOf(st.maxAccel, lon ?: 0f),
            maxBrake = maxOf(st.maxBrake, -(lon ?: 0f)),
        )
    }

    private fun slope(p: ArrayDeque<Pair<Long, Float>>): Float? {
        if (p.size < 3) return null
        val t0 = p.first().first
        val n = p.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((t, v) in p) { val x = (t - t0) / 1000.0; sx += x; sy += v; sxx += x * x; sxy += x * v }
        val den = n * sxx - sx * sx
        return if (abs(den) < 1e-6) null else ((n * sxy - sx * sy) / den).toFloat()
    }

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = 111_320.0
        val dx = (lon2 - lon1) * k * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * k
        return sqrt(dx * dx + dy * dy)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: FloatArray): FloatArray { val l = sqrt(dot(a, a)).takeIf { it > 1e-6f } ?: 1f; return FloatArray(3) { a[it] / l } }
}
