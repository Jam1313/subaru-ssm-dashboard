package kg.forester.sg.service

import kg.forester.sg.ssm.EngineInfo

/**
 * Warm idle against the factory Select Monitor tables ("Note (at idling)": warm engine, N/P, A/C and loads off),
 * docs/knowledge/factory-manuals.md §2.2 and engine-ej.md §1.1. [lo]..[hi] is what we judge by: the factory range,
 * a little wider where owners' logs of healthy cars sit just outside it; [norm] is the factory text shown.
 */
object IdleRef {
    data class Row(val key: String, val title: String, val unit: String, val norm: String, val lo: Float, val hi: Float, val decimals: Int = 0)

    data class Table(val source: String, val rows: List<Row>)

    /** OK: inside the factory range; NEAR: just outside it, where healthy cars are often seen too; LOW/HIGH: out. */
    enum class Verdict { OK, NEAR, LOW, HIGH, NONE }

    data class Line(val row: Row, val value: Float?, val verdict: Verdict)

    private fun common(rpm: Row, timing: Row, maf: Row?, inj: Row, map: Row, battery: Row, extra: List<Row> = emptyList()) = listOfNotNull(
        rpm,
        Row("coolant", "Температура ОЖ", "°", "80–100°", 78f, 102f),
        timing,
        maf,
        inj,
        map,
        Row("afcorr", "Коррекция смеси", "%", "−10…+10 %", -10f, 10f, 1),
        Row("aflearn", "Обучение смеси", "%", "−15…+15 % (лучше ±5)", -15f, 15f, 1),
        Row("knock", "Откат по детонации", "°", "0°", -0.5f, 0.5f, 1),
        battery,
    ) + extra

    private val NA_BATT = Row("battery", "Напряжение", "В", "13,5–14,6 В (с умной зарядкой 12,5–13 В тоже норма)", 12.4f, 14.8f, 1)
    private val T_BATT = Row("battery", "Напряжение", "В", "12–15 В, обычно 13,5–14,6", 12.4f, 15.0f, 1)

    /** The table for [e]'s engine, null when there is none (FA/FB/EZ, diesel) or the ECU hasn't said which. */
    fun forEngine(e: EngineInfo): Table? = when {
        // a tuned engine on a sports ECU (cams, its own idle) is not held to the factory numbers
        e.source == "нет" || e.source == "Link" -> null
        // an EJ whose size the ROM ID doesn't give: what both the 2.0 and the 2.5 allow
        e.family == "EJ" && e.turbo -> Table(
            "Общие заводские значения EJ турбо (объём ЭБУ не сообщил, границы для 2,0 и 2,5)",
            common(
                Row("rpm", "Обороты", "об/мин", "630–770", 600f, 800f),
                Row("timing", "Угол зажигания", "°", "10–17°", 8f, 20f, 1),
                Row("maf", "Расход воздуха", "г/с", "2,1–3,6 г/с", 1.9f, 4.2f, 1),
                Row("inj", "Впрыск", "мс", "1,2–2,2 мс", 1.1f, 2.5f, 2),
                Row("map", "Давление во впуске", "кПа", "29,5–37 кПа", 27f, 40f),
                T_BATT,
                listOf(Row("avcs", "AVCS", "°", "около 0–5°", -5f, 10f, 1)),
            ),
        )
        e.family == "EJ" -> Table(
            "Общие заводские значения EJ атмо (объём ЭБУ не сообщил, границы для 2,0 и 2,5)",
            common(
                Row("rpm", "Обороты", "об/мин", "600–800", 600f, 850f),
                Row("timing", "Угол зажигания", "°", "10–16°", 8f, 18f, 1),
                Row("maf", "Расход воздуха", "г/с", "2,5–3,2 г/с", 2.0f, 5.0f, 1),
                Row("inj", "Впрыск", "мс", "2–3,3 мс", 2.0f, 3.6f, 2),
                Row("map", "Давление во впуске", "кПа", "26,7–40 кПа", 25f, 42f),
                NA_BATT,
            ),
        )
        e.family == "EJ20" && e.turbo -> Table(
            "Заводская таблица Select Monitor, Legacy BL 2004 EJ20 турбо (у SG5 EJ205 близкие значения)",
            common(
                Row("rpm", "Обороты", "об/мин", "630–770", 600f, 800f),
                Row("timing", "Угол зажигания", "°", "10–15°", 8f, 17f, 1),
                Row("maf", "Расход воздуха", "г/с", "2,1–3,1 г/с", 1.9f, 3.6f, 1),
                Row("inj", "Впрыск", "мс", "1,2–2,2 мс", 1.1f, 2.5f, 2),
                Row("map", "Давление во впуске", "кПа", "29,5–37 кПа", 27f, 40f),
                T_BATT,
                listOf(Row("avcs", "AVCS", "°", "−5…+5° (около 0)", -5f, 5f, 1)),
            ),
        )
        e.code == "EJ257" -> Table(
            "Заводская таблица Select Monitor, WRX STI 2008 EJ257",
            common(
                Row("rpm", "Обороты", "об/мин", "630–770", 600f, 800f),
                Row("timing", "Угол зажигания", "°", "10–15° (пример у исправной 16°)", 8f, 18f, 1),
                Row("maf", "Расход воздуха", "г/с", "2,1–3,1 г/с", 1.9f, 3.6f, 1),
                Row("inj", "Впрыск", "мс", "1,2–2,2 мс", 1.1f, 2.5f, 2),
                Row("map", "Давление во впуске", "кПа", "29,5–37 кПа", 27f, 40f),
                T_BATT,
                listOf(Row("avcs", "AVCS", "°", "около 5°", -2f, 10f, 1)),
            ),
        )
        e.family == "EJ25" && e.turbo -> Table(
            "Заводская таблица Select Monitor, Impreza WRX 2006 EJ255 (Forester XT близко)",
            common(
                Row("rpm", "Обороты", "об/мин", "около 700", 630f, 800f),
                Row("timing", "Угол зажигания", "°", "около 17°", 10f, 20f, 1),
                Row("maf", "Расход воздуха", "г/с", "около 3,6 г/с (2,1–3,1 у LGT)", 2.0f, 4.2f, 1),
                Row("inj", "Впрыск", "мс", "1,2–2,2 мс", 1.1f, 2.5f, 2),
                Row("map", "Давление во впуске", "кПа", "29,5–37 кПа", 27f, 40f),
                T_BATT,
                listOf(Row("avcs", "AVCS", "°", "около 0°, до ±5°", -5f, 5f, 1)),
            ),
        )
        e.family == "EJ25" -> Table(
            "Заводские таблицы Select Monitor, Legacy BL 2004 EJ25 и Impreza 2006 EJ253",
            common(
                Row("rpm", "Обороты", "об/мин", "600–800", 600f, 850f),
                Row("timing", "Угол зажигания", "°", "12,5–16°", 10f, 18f, 1),
                Row("maf", "Расход воздуха", "г/с", "2,8–3,2 г/с (у исправных до 4,8)", 2.4f, 5.0f, 1),
                Row("inj", "Впрыск", "мс", "2,56–3,3 мс", 2.2f, 3.6f, 2),
                Row("map", "Давление во впуске", "кПа", "26,7–40 кПа", 25f, 42f),
                NA_BATT,
            ),
        )
        e.family == "EJ20" || e.family == "EJ22" -> Table(
            "Заводская таблица Select Monitor, Legacy BL 2004 EJ20 атмо; расход воздуха по таблицам EJ25 и логам SG",
            common(
                Row("rpm", "Обороты", "об/мин", "600–800 (около 700)", 600f, 850f),
                Row("timing", "Угол зажигания", "°", "10–15°", 8f, 17f, 1),
                Row("maf", "Расход воздуха", "г/с", "около 2,5 г/с", 2.0f, 4.0f, 1),
                Row("inj", "Впрыск", "мс", "2–4 мс, около 2,8", 2.0f, 4.0f, 2),
                Row("map", "Давление во впуске", "кПа", "26,7–40 кПа", 25f, 42f),
                NA_BATT,
            ),
        )
        else -> null
    }

    /** The factory range written in [norm] ("29,5–37 кПа", "−10…+10 %", "±5°"); null for "около 700". */
    fun factory(norm: String): Pair<Float, Float>? {
        if (norm.startsWith("около")) return null
        Regex("^±(\\d+(?:,\\d+)?)").find(norm)?.let { val n = it.groupValues[1].replace(',', '.').toFloat(); return -n to n }
        val nums = Regex("([−+-]?\\d+(?:,\\d+)?)").findAll(norm).take(2).map { it.value.replace('−', '-').replace(',', '.').toFloat() }.toList()
        return when (nums.size) { 0 -> null; 1 -> nums[0] to nums[0]; else -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1]) }
    }

    /** Each row of [t] with the measured value and how it compares. */
    fun judge(t: Table, values: Map<String, Float>): List<Line> = t.rows.map { r ->
        val v = values[r.key]
        val f = factory(r.norm)
        // the shown number is rounded: judge what the driver sees
        val shown = v?.let { "%.${r.decimals}f".format(java.util.Locale.US, it).toFloat() }
        Line(r, v, when {
            shown == null -> Verdict.NONE
            shown < r.lo -> Verdict.LOW
            shown > r.hi -> Verdict.HIGH
            f != null && (shown < f.first || shown > f.second) -> Verdict.NEAR
            else -> Verdict.OK
        })
    }

    /** One plain sentence about what a value out of range usually means, for the line under the table. */
    fun hint(l: Line): String? = if (l.verdict == Verdict.NEAR) null else when (l.row.key to l.verdict) {
        "rpm" to Verdict.HIGH -> "Высокие обороты: подсос воздуха, грязный дроссель или клапан ХХ, датчик ОЖ врёт на холод"
        "rpm" to Verdict.LOW -> "Низкие обороты: грязный дроссель или клапан ХХ, после сброса ЭБУ не прошло обучение ХХ"
        "maf" to Verdict.LOW -> "Мало воздуха по датчику: подсос после датчика расхода или он грязный"
        "maf" to Verdict.HIGH -> "Много воздуха: нагрузка (кондиционер, генератор) или датчик расхода завышает"
        "aflearn" to Verdict.HIGH, "afcorr" to Verdict.HIGH -> "ЭБУ добавляет топливо: подсос воздуха, грязный датчик расхода, слабый бензонасос"
        "aflearn" to Verdict.LOW, "afcorr" to Verdict.LOW -> "ЭБУ убирает топливо: клапан продувки бензобака, форсунка, давление топлива"
        "map" to Verdict.HIGH -> "Слабое разрежение: подсос, низкая компрессия, сбитые фазы"
        "timing" to Verdict.LOW -> "Малый угол: ЭБУ сдерживает обороты, мотору достаётся лишний воздух"
        "timing" to Verdict.HIGH -> "Большой угол: ЭБУ не даёт заглохнуть, возможно забит дроссель"
        "knock" to Verdict.LOW -> "Откат на холостых: стук датчика или настоящая детонация, проверьте бензин"
        "avcs" to Verdict.HIGH, "avcs" to Verdict.LOW -> "AVCS не возвращается: масло, сетки клапанов AVCS"
        "coolant" to Verdict.LOW -> "Мотор ещё не прогрет до рабочей температуры или открыт термостат"
        "coolant" to Verdict.HIGH -> "Горячо для холостых: проверьте вентиляторы и уровень антифриза"
        "battery" to Verdict.LOW -> "Низкое напряжение: генератор, ремень, клеммы"
        "battery" to Verdict.HIGH -> "Высокое напряжение: регулятор генератора"
        "inj" to Verdict.HIGH -> "Длинный впрыск: нагрузка на мотор или подсос"
        else -> null
    }
}
