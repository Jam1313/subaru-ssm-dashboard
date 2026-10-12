package kg.forester.sg.service

import kg.forester.sg.data.Bucket
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Trip
import kg.forester.sg.data.TripState
import kg.forester.sg.ssm.EcuSnapshot
import kotlin.math.abs

object FuelCalc {
    private const val GASOLINE_G_PER_L = 745.0

    /**
     * Fuel flow in L/h from MAF. The ECU meters fuel as MAF / target AFR and then applies the
     * A/F correction + learning trims, so the trims are included (they matter on this car: an air
     * leak shows up as large positive trims). [k] is learned from real refills.
     */
    fun litersPerHour(s: EcuSnapshot, k: Double): Double {
        val rpm = s.rpm ?: return 0.0
        val maf = s.mafGs ?: return 0.0
        if (rpm < 300) return 0.0
        val pw = s.injPwMs
        if (pw != null && pw < 0.2f && rpm > 1100) return 0.0 // deceleration fuel cut
        val measured = s.afr?.toDouble()
        val closedLoop = measured == null || measured in 13.5..16.0
        val baseAfr = if (closedLoop) 14.7 else measured!!.coerceIn(10.0, 18.5)
        val trims = if (closedLoop) ((s.afCorrPct ?: 0f) + (s.afLearnPct ?: 0f)) / 100.0
        else (s.afLearnPct ?: 0f) / 100.0
        val fuelGs = maf / baseAfr * (1 + trims.coerceIn(-0.5, 0.6)) * k
        return fuelGs * 3600.0 / GASOLINE_G_PER_L
    }
}

/** Integrates samples into the current trip, buckets, fuel level and learned corrections. */
class TripTracker(ctx: android.content.Context) {
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
    private var sensorLeftL: Double? = null
    private var lhShown = 0.0
    private var lastSpeedKSave = 0L

    fun process(e: EcuSnapshot, gps: GpsFix?) {
        val now = e.timeMs
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
            if (trip == null) trip = Trip(startMs = now, endMs = now, distanceKm = 0.0, fuelL = 0.0)
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
                mixKm = t.mixKm + if (moving && avg in 45.0..75.0) dKm else 0.0,
                mixL = t.mixL + if (moving && avg in 45.0..75.0) dFuel else 0.0,
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
            s.usedSinceRefillL += dFuel
            s.usedSinceFullL += dFuel
        }
        addToBucket(now, dFuel, dKm)
        if (e.fuelSensorV != null || e.fuelSensorOhm != null) {
            val known = if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL).coerceAtLeast(0.0) else null
            sensorLeftL = sensorCal.process(e.fuelSensorV, e.fuelSensorOhm, known, speed < 1, s.tankL, now)
        }

        if (now - lastSaveMs > 30_000) save(now)
        // the number on screen is smoothed over ~1.5 s; the trip totals above use the raw flow
        lhShown += (lh - lhShown) * 0.3
        publish(lhShown, speed)
    }

    /** Called periodically even without ECU data so trips close when the engine stops. */
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

    fun resetTrip() {
        trip?.let { if (it.distanceKm > 0.05) db.upsertTrip(it) }
        trip = trip?.let { Trip(startMs = System.currentTimeMillis(), endMs = System.currentTimeMillis(), distanceKm = 0.0, fuelL = 0.0) }
        s.currentTripId = 0
        Repo.touch()
        publish(0.0, 0.0)
    }

    fun shutdown() {
        save(System.currentTimeMillis())
        flushBucket()
    }

    private fun save(now: Long) {
        lastSaveMs = now
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
            ?: if (s.fuelAtRefillL >= 0) (s.fuelAtRefillL - s.usedSinceRefillL).coerceAtLeast(0.0) else null
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
