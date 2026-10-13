package kg.forester.sg.service

import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import java.util.Locale

/** What one CSV line is made of. */
class LogRow(val s: EcuSnapshot, val g: GpsFix?, val lh: Double, val t: TcuSnapshot?, val guard: GuardState)

/** One column of the CSV log; [key] is the header (kept as before, so old scripts still read the files). */
class LogColumn(val key: String, val title: String, val group: String, val get: (LogRow) -> String)

/**
 * The CSV log's columns, each can be switched off on the «Лог» screen (e.g. the transmission ones, which only
 * matter when hunting a gearbox problem). New columns are on until switched off.
 */
object LogColumns {
    private fun f(x: Float?, d: Int = 2) = x?.let { "%.${d}f".format(Locale.US, it) } ?: ""
    private fun i(x: Int?) = x?.toString() ?: ""

    val all: List<LogColumn> = listOf(
        LogColumn("rpm", "Обороты", "Двигатель") { i(it.s.rpm) },
        LogColumn("speed_kmh", "Скорость", "Двигатель") { i(it.s.speedKmh) },
        LogColumn("coolant_c", "Темп. ОЖ", "Двигатель") { i(it.s.coolantC) },
        LogColumn("intake_c", "Темп. впуска", "Двигатель") { i(it.s.intakeC) },
        LogColumn("battery_v", "Напряжение", "Двигатель") { f(it.s.batteryV) },
        LogColumn("throttle_pct", "Дроссель", "Двигатель") { f(it.s.throttlePct) },
        LogColumn("pedal_pct", "Педаль", "Двигатель") { f(it.s.pedalPct) },
        LogColumn("load_grev", "Нагрузка, г/об", "Двигатель") { f(it.s.loadGRev, 3) },
        LogColumn("avcs_deg", "AVCS", "Двигатель") { f(it.s.avcsDeg) },
        LogColumn("alt_duty_pct", "Генератор", "Двигатель") { f(it.s.altDutyPct) },
        LogColumn("maf_gs", "MAF", "Топливо") { f(it.s.mafGs) },
        LogColumn("inj_pw_ms", "Впрыск, мс", "Топливо") { f(it.s.injPwMs) },
        LogColumn("inj_duty_pct", "Загрузка форсунок", "Топливо") { f(it.s.injDutyPct) },
        LogColumn("af_corr_pct", "Коррекция A/F", "Топливо") { f(it.s.afCorrPct) },
        LogColumn("af_learn_pct", "Обучение A/F", "Топливо") { f(it.s.afLearnPct) },
        LogColumn("afr", "AFR", "Топливо") { f(it.s.afr) },
        LogColumn("fuel_lh", "Расход, л/ч", "Топливо") { "%.3f".format(Locale.US, it.lh) },
        LogColumn("timing_deg", "УОЗ", "Зажигание и детонация") { f(it.s.timingDeg) },
        LogColumn("knock_deg", "Коррекция детонации", "Зажигание и детонация") { f(it.s.knockDeg) },
        LogColumn("iam", "IAM", "Зажигание и детонация") { f(it.guard.iam, 3) },
        LogColumn("fbkc_deg", "FBKC (откат)", "Зажигание и детонация") { f(it.guard.fbkc) },
        LogColumn("flkc_deg", "FLKC (обучение)", "Зажигание и детонация") { f(it.guard.flkc) },
        LogColumn("atm_kpa", "Атм. давление", "Наддув") { f(it.s.atmKpa) },
        LogColumn("map_kpa", "Абс. давление", "Наддув") { f(it.s.mapKpa) },
        LogColumn("boost_kpa", "Наддув (отн.)", "Наддув") { f(it.s.gaugeKpa) },
        LogColumn("target_boost_kpa", "Целевой наддув", "Наддув") { f(it.guard.targetBoostKpa) },
        LogColumn("wgdc_pct", "Wastegate", "Наддув") { f(it.s.wastegatePct) },
        LogColumn("gps_speed_kmh", "Скорость GPS", "GPS") { f(it.g?.speedKmh) },
        LogColumn("gps_alt_m", "Высота GPS", "GPS") { it.g?.altitudeM?.let { a -> "%.0f".format(Locale.US, a) } ?: "" },
        LogColumn("gps_lat", "Широта", "GPS") { it.g?.lat?.let { a -> "%.6f".format(Locale.US, a) } ?: "" },
        LogColumn("gps_lon", "Долгота", "GPS") { it.g?.lon?.let { a -> "%.6f".format(Locale.US, a) } ?: "" },
        LogColumn("gear", "Передача", "АКПП") { i(it.t?.gear) },
        LogColumn("atf_c", "Темп. ATF", "АКПП") { i(it.t?.atfC) },
        LogColumn("turbine_rpm", "Турбинное колесо", "АКПП") { i(it.t?.turbineRpm) },
        LogColumn("lockup_pct", "Блокировка ГДТ", "АКПП") { f(it.t?.lockUpPct) },
        LogColumn("transfer_pct", "Муфта полного привода", "АКПП") { f(it.t?.transferPct) },
        LogColumn("front_wheel_kmh", "Скорость передних колёс", "АКПП") { i(it.t?.frontWheelKmh) },
        LogColumn("rear_wheel_kmh", "Скорость задних колёс", "АКПП") { i(it.t?.rearWheelKmh) },
        LogColumn("fuel_sensor_v", "Датчик топлива, В", "Уровень топлива") { f(it.s.fuelSensorV) },
        LogColumn("fuel_sensor_ohm", "Датчик топлива, Ом", "Уровень топлива") { f(it.s.fuelSensorOhm) },
    )

    fun enabled(): List<LogColumn> {
        val off = Repo.settings.logOff
        return all.filter { it.key !in off }
    }
}
