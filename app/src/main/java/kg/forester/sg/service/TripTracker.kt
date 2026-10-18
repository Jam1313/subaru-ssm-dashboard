package kg.forester.sg.service

import kg.forester.sg.data.Bucket
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Trip
import kg.forester.sg.data.TripState
import kg.forester.sg.ssm.EcuSnapshot
import kotlin.math.abs

object VirtualAfr {
    /**
     * Вычисляет реальный AFR на основе MAF и скважности форсунок.
     * Если MAF сломан или данные некорректны, возвращает null.
     */
    fun calculate(s: EcuSnapshot, injectorCc: Double): Float? {
        val maf = s.mafGs ?: return null
        val rpm = s.rpm ?: return null
        val pw = s.injPwMs ?: return null
        
        if (rpm < 300 || injectorCc <= 0.0 || pw < 0.3f) return null

        // 1. Считаем массу топлива по форсункам
        val openMs = (pw - 0.6f).coerceAtLeast(0f)
        val fuelLh = rpm * openMs * injectorCc * 2e-6
        val fuelGs = fuelLh * 745.0 / 3600.0
        
        if (fuelGs < 0.01) return null // Двигатель не льёт топливо (ПХХ)

        // 2. Считаем виртуальный AFR
        val afr = maf / fuelGs
        
        // 3. Защита от мусора (AFR вне разумных пределов — это ошибка)
        return if (afr in 9.0f..18.0f) afr else null
    }
}
object FuelCalc {
    private const val GASOLINE_G_PER_L = 745.0

    //**
     * Fuel flow in L/h from MAF. The ECU meters fuel as MAF / target AFR and then applies the
     * A/F correction + learning trims, so the trims are included (they matter on this car: an air
     * leak shows up as large positive trims). Outside closed loop the measured AFR is used as is.
     * [k] is learned from real refills.
    fun litersPerHour (s: EcuSnapshot, k: Double): Double {
    val rpm = s.rpm ?: return 0.0
    if (rpm < 300) return 0.0
    val pw = s.injPwMs
    if (pw != null && pw < 0.2f && rpm > 1100) return 0.0 // deceleration fuel cut

    val maf = s.mafGs ?: return byInjectors(pw, rpm, k)
    source = "maf"
    
    // Берем AFR из датчика, а если он молчит — вычисляем виртуально
    val measured = s.afr?.toDouble() ?: VirtualAfr.calculate(s)?.toDouble()
    val closedLoop = measured == null || measured in 13.5..16.0
    
    val fuelGs = if (closedLoop) {
        val trims = ((s.afCorrPct ?: 0f) + (s.afLearnPct ?: 0f)) / 100.0
        maf / 14.7 * (1 + trims.coerceIn(-0.35, 0.35))
    } else {
        maf / measured!!.coerceIn(10.0, 18.5)
    } * k
    
    return fuelGs * 3600.0 / GASOLINE_G_PER_L
}

    /** How the last [litersPerHour] got its number: "maf", "inj" (no MAF, by the injectors) or "" (neither). */
    @Volatile var source = ""
        private set

    /**
     * For an ECU without a usable MAF (some early 2000s ones answer 0xFFFF there): four injectors, each firing
     * once per two turns, open for the pulse width less the time they take to open, at a typical flow for the
     * engine. The refills calibrate [k] as they do for the MAF way.
     */
    private fun byInjectors(pw: Float?, rpm: Int, k: Double): Double {
        if (pw == null || pw !in 0.3f..40f) { source = ""; return 0.0 }
        source = "inj"
        val turbo = runCatching {
            kg.forester.sg.data.Repo.guard.value.turboCar
        }.getOrDefault(false)
        // the driver's injectors when set (a Link car's are rarely stock), else typical for the engine
        val ccPerMin = runCatching { kg.forester.sg.data.Repo.settings.injectorCc }.getOrDefault(0).takeIf { it in 100..3000 }?.toDouble()
            ?: if (turbo) 490.0 else 260.0
        val openMs = (pw - 0.6).coerceAtLeast(0.0)
        return rpm * openMs * ccPerMin * 2e-6 * k
    }
}

/** The SensorBox fuel line's learning: points ready, share of the tank covered (null: no such input). */
val boxProgress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Double>?>(null)

/** Integrates samples into the current trip, buckets, fuel level and learned corrections. */
/** [onNewTrip] gets the start time of every new trip. */
class TripTracker(ctx: android.content.Context, private val onNewTrip: (Long) -> Unit = {}) {
    private val s get() = Repo.settings
    private val db get() = Repo.db

    private var trip: Trip? = null
    private var lastSampleMs = 0L
    private var lastEngineOnMs = 0L
    private var lastSpeed = 0.0
    private var lastHardEventMs = 0L
    private var lastSaveMs = 0L

    private var bucketStart = 0L
    private var bucketFuel = 0.0
    private var bucketKm = 0.0

    private val speedWindow = ArrayDeque<Pair<Long, Double>>()
    private var speedK = s.speedK
    private val sensorCal = FuelSensorCal(ctx)
    /** The fuel gauge wire read by the SensorBox (SG and older: the ECU has no fuel level), its line volts 0–15. */
    private val boxCal = FuelSensorCal(ctx, "fuelcal_box", 15.0)
    private var standMs = 0L
    private var standSum = 0f
    private var standN = 0
    private var standMarked = false

    init {
        // the trip in memory is gone after a restart; its saved row stays as it is and a new trip starts
        s.currentTripId = 0
    }
    private var sensorLeftL: Double? = null
    private var lhShown = 0.0
    private var pendingFuel = 0.0

    private fun flushFuel() {
        if (pendingFuel <= 0.0) return
        s.usedSinceRefillL += pendingFuel
        s.usedSinceFullL += pendingFuel
        pendingFuel = 0.0
    }
    private var lastSpeedKSave = 0L

    @Synchronized
    fun process(e: EcuSnapshot, gps: GpsFix?) {
        val now = e.timeMs
        // a garbled answer (bad line, an address this ECU maps elsewhere) must not end up in the trip and fuel totals
        if ((e.rpm ?: 0) > 10_000 || (e.speedKmh ?: 0) > 300 || (e.mafGs ?: 0f) > 500f) return
        tick(now) // closes the trip after 2 min with the engine off, even while the ECU keeps answering
        val dt = if (lastSampleMs == 0L) 0.0 else ((now - lastSampleMs) / 1000.0).coerceIn(0.0, 2.0)
        lastSampleMs = now
        val engineOn = (e.rpm ?: 0) > 300

        val gpsFresh = gps != null && now - gps.timeMs < 3000 && gps.accuracyM < 15
        learnSpeed(e, gps, gpsFresh, now)
        val speed = when {
            e.speedKmh != null -> e.speedKmh * speedK
            gpsFresh -> gps!!.speedKmh.toDouble()
            else -> 0.0
        }

        val lh = if (engineOn) FuelCalc.litersPerHour(e, s.fuelK) else 0.0
        val dFuel = lh * dt / 3600.0
        val dKm = speed * dt / 3600.0

        if (engineOn) {
            lastEngineOnMs = now
            if (trip == null) {
                trip = Trip(startMs = now, endMs = now, distanceKm = 0.0, fuelL = 0.0)
                onNewTrip(now)
            }
        }

        var t = trip
        if (t != null && dt > 0) {
            speedWindow.addLast(now to speed)
            while (speedWindow.isNotEmpty() && now - speedWindow.first().first > 120_000) speedWindow.removeFirst()
            val avg = speedWindow.sumOf { it.second } / speedWindow.size
            val moving = speed >= 2
            val accel = (speed - lastSpeed) / 3.6 / dt // m/s^2
            var hardA = 0
            var hardB = 0
            if (now - lastHardEventMs > 3000) {
                if (accel > 3.0) { hardA = 1; lastHardEventMs = now }
                if (accel < -3.5) { hardB = 1; lastHardEventMs = now }
            }
            t = t.copy(
                endMs = now,
                distanceKm = t.distanceKm + dKm,
                fuelL = t.fuelL + dFuel,
                cityKm = t.cityKm + if (moving && avg < 45) dKm else 0.0,
                cityL = t.cityL + if (moving && avg < 45) dFuel else 0.0,
                hwyKm = t.hwyKm + if (moving && avg >= 75) dKm else 0.0,
                hwyL = t.hwyL + if (moving && avg >= 75) dFuel else 0.0,
                mixKm = t.mixKm + if (moving && avg >= 45.0 && avg < 75.0) dKm else 0.0,
                mixL = t.mixL + if (moving && avg >= 45.0 && avg < 75.0) dFuel else 0.0,
                idleS = t.idleS + if (engineOn && !moving) dt else 0.0,
                idleL = t.idleL + if (!moving) dFuel else 0.0,
                hardAccel = t.hardAccel + hardA,
                hardBrake = t.hardBrake + hardB,
                maxBoostKpa = maxOf(t.maxBoostKpa, (e.gaugeKpa ?: 0f).toDouble()),
                maxRpm = maxOf(t.maxRpm, e.rpm ?: 0),
            )
            trip = t
        }
        lastSpeed = speed

        if (dFuel > 0) {
            pendingFuel += dFuel // written to the settings every 30 s, not 4 times a second (flash wear)
        }
        addToBucket(now, dFuel, dKm)
        if (e.fuelLevelPct != null && s.tankL > 0) {
            // OBD-II gives the level in percent already
            sensorLeftL = e.fuelLevelPct / 100.0 * s.tankL
        } else if (e.fuelSensorV != null || e.fuelSensorOhm != null) {
            val known = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
            sensorLeftL = sensorCal.process(e.fuelSensorV, e.fuelSensorOhm, known, speed < 1, s.tankL, now)
        } else {
            // no level from the ECU: the gauge wire on the SensorBox, learnt the same way, if one is wired
            val line = kg.forester.sg.data.SensorBox.role("fuelLevel")
            sensorLeftL = if (line != null && line in 0.05f..15f) {
                val known = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
                val learnt = boxCal.process(line, null, known, speed < 1, s.tankL, now)
                // standing 15 s (the fuel in the tank settles) with the litres known from a full tank: a point by itself
                if (speed < 1 && engineOn) {
                    standMs += (dt * 1000).toLong()
                    if (standMs >= 5_000) { standSum += line; standN++ } // the first seconds the fuel still sloshes
                    if (standMs >= 15_000 && !standMarked && s.fuelLevelKnown && known != null && standN > 0) {
                        standMarked = true
                        kg.forester.sg.data.FuelLine.learn(standSum / standN, known.toFloat())
                    }
                } else { standMs = 0; standSum = 0f; standN = 0; standMarked = false }
                // marked by hand (tank filled step by step): those points win over what the refills taught
                kg.forester.sg.data.FuelLine.covered(line, s.tankL.toFloat())?.toDouble()?.let { if (s.tankL > 0) it.coerceIn(0.0, s.tankL) else it } ?: learnt
            } else null
            boxProgress.value = if (line == null) null else boxCal.progress(s.tankL)
        }

        if (now - lastSaveMs > 30_000) save(now)
        // the number on screen is smoothed over ~1.5 s; the trip totals above use the raw flow
        lhShown += (lh - lhShown) * 0.3
        publish(lhShown, speed)
    }

    /** Called periodically even without ECU data so trips close when the engine stops. */
    @Synchronized
    fun tick(now: Long) {
        val t = trip ?: return
        if (now - lastEngineOnMs > 120_000) {
            if (t.distanceKm > 0.05 || t.fuelL > 0.02) {
                val id = db.upsertTrip(t)
                trip = null
                s.currentTripId = 0
                Repo.touch()
                if (id < 0) return
            } else {
                trip = null
            }
            flushBucket()
            publish(0.0, 0.0)
        }
    }

    @Synchronized
    fun resetTrip() {
        trip?.let { if (it.distanceKm > 0.05) db.upsertTrip(it) }
        trip = trip?.let { Trip(startMs = System.currentTimeMillis(), endMs = System.currentTimeMillis(), distanceKm = 0.0, fuelL = 0.0) }
        s.currentTripId = 0
        onNewTrip(System.currentTimeMillis())
        Repo.touch()
        publish(0.0, 0.0)
    }

    @Synchronized
    fun shutdown() {
        flushFuel()
        save(System.currentTimeMillis())
        flushBucket()
    }

    private fun save(now: Long) {
        lastSaveMs = now
        flushFuel()
        val t = trip ?: return
        if (t.distanceKm < 0.05 && t.fuelL < 0.02) return
        val id = db.upsertTrip(t.copy(id = s.currentTripId))
        if (s.currentTripId == 0L) {
            s.currentTripId = id
        }
        trip = t.copy(id = s.currentTripId)
        Repo.touch()
    }

    private fun learnSpeed(e: EcuSnapshot, gps: GpsFix?, gpsFresh: Boolean, now: Long) {
        val ecu = e.speedKmh ?: return
        if (!gpsFresh || gps == null) return
        if (ecu < 40 || gps.speedKmh < 40) return
        if (abs(ecu * speedK - lastSpeed) > 3) return // only steady driving
        val ratio = (gps.speedKmh / ecu).toDouble()
        if (ratio !in 0.85..1.15) return
        speedK = speedK * 0.995 + ratio * 0.005
        if (now - lastSpeedKSave > 60_000) {
            s.speedK = speedK; lastSpeedKSave = now
        }
    }

    private fun addToBucket(now: Long, dFuel: Double, dKm: Double) {
        val slot = now / 10_000 * 10_000
        if (bucketStart != 0L && slot != bucketStart) flushBucket()
        if (bucketStart == 0L) bucketStart = slot
        bucketFuel += dFuel
        bucketKm += dKm
    }

    private fun flushBucket() {
        if (bucketStart != 0L && (bucketFuel > 0 || bucketKm > 0)) {
            db.addBucket(Bucket(bucketStart, bucketFuel, bucketKm))
        }
        bucketStart = 0L; bucketFuel = 0.0; bucketKm = 0.0
    }

    private fun publish(lh: Double, speed: Double) {
        // the learned fuel sender wins when the ECU has one; otherwise count from the refills (SG)
        val left = sensorLeftL
            ?: if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL - pendingFuel).coerceAtLeast(0.0) else null
        val (totKm, totL) = db.totals()
        val t = trip
        val avg = when {
            t?.per100 != null && t.distanceKm > 10 -> t.per100!!
            totKm > 20 -> totL / totKm * 100
            else -> 10.0
        }
        Repo.trip.value = TripState(
            trip = t,
            instLh = lh,
            // below 20 km/h litres per 100 km shoot past 100 when pulling away: show litres per hour there
            instPer100 = if (speed >= 20) lh / speed * 100 else null,
            fuelLeftL = left,
            fuelFromSensor = sensorLeftL != null,
            rangeKm = left?.let { it / avg * 100 },
        )
    }
}
