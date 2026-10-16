package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * SensorBox outputs: relays, MOSFETs, LEDs on the box's free pins, switched by the driver's rules
 * (docs/knowledge/sensorbox-relays.md). The rules are evaluated in the app (it has the ECU data); the box keeps the
 * hardware safe by itself (guard times, a maximum on time, a failsafe when the app goes silent).
 */

enum class LoadKind(val title: String, val sub: String) {
    RELAY("Модуль реле", "Щёлкает реле, оно включает автомобильное реле нагрузки"),
    COIL12("Реле 12 В через транзистор", "Обычное автомобильное реле: плата включает его катушку через транзистор или MOSFET"),
    MOSFET("MOSFET-модуль", "Без щелчков, можно плавно (ШИМ): вентилятор, насос"),
    SSR("Твердотельное реле DC", "Без щелчков, только постоянный ток"),
    LED("Светодиод", "Прямо от пина через резистор: шифтлайт, индикатор"),
}

/** What the box does with an output when the app stops talking to it (frozen head unit, cable out). */
enum class LostState(val code: Char, val title: String) {
    OFF('0', "выключить"), ON('1', "включить"), KEEP('K', "оставить как было"), HOLD('T', "подержать и выключить"),
}

enum class Manual { AUTO, ON, OFF }

enum class RuleKind { LEVEL, BLOCK, EVENT }

enum class OutEvent(val title: String) { WOKE("магнитола проснулась"), ENGINE_ON("мотор завёлся") }

/** "[src] above [on]; back off below [off]" (or the other way round with [above] false). */
data class Threshold(val src: String, val above: Boolean, val on: Float, val off: Float) {
    fun toJson(): JSONObject = JSONObject().put("src", src).put("above", above).put("on", on.toDouble()).put("off", off.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Threshold(o.getString("src"), o.optBoolean("above", true), o.getDouble("on").toFloat(), o.getDouble("off").toFloat())
    }
}

/**
 * One rule of an output. LEVEL: on while [main] (and every [also]) holds, for [onDelayS] first, staying [offDelayS] after.
 * BLOCK: never on while [main] holds. EVENT: on for [holdS] (0 = until the head unit sleeps) after [event].
 */
data class OutRule(
    val id: String,
    val kind: RuleKind,
    val enabled: Boolean = true,
    val main: Threshold? = null,
    val also: List<Threshold> = emptyList(),
    val onDelayS: Float = 0f,
    val offDelayS: Float = 0f,
    val event: OutEvent? = null,
    val holdS: Int = 0,
    /** LEVEL on an LED: blink instead of steady (the shift light's second stage). */
    val blink: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("en", enabled).put("onD", onDelayS.toDouble())
        .put("offD", offDelayS.toDouble()).put("hold", holdS).put("blink", blink)
        .put("also", JSONArray().also { a -> also.forEach { a.put(it.toJson()) } })
        .apply { main?.let { put("main", it.toJson()) }; event?.let { put("event", it.name) } }

    companion object {
        fun fromJson(o: JSONObject) = OutRule(
            o.getString("id"), RuleKind.valueOf(o.getString("kind")), o.optBoolean("en", true),
            o.optJSONObject("main")?.let(Threshold::fromJson),
            o.optJSONArray("also")?.let { a -> List(a.length()) { Threshold.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optDouble("onD", 0.0).toFloat(), o.optDouble("offD", 0.0).toFloat(),
            o.optString("event").takeIf { it.isNotEmpty() }?.let(OutEvent::valueOf), o.optInt("hold", 0), o.optBoolean("blink", false),
        )
    }
}

/** A physical output: which pin, what hangs on it, its guards and failsafe, the driver's override, its rules. */
data class OutChannel(
    val ch: Int,
    val pin: String,
    val name: String,
    val icon: String,
    val load: LoadKind,
    val activeLow: Boolean,
    val minOnS: Int,
    val minOffS: Int,
    val maxOnS: Int,
    val lost: LostState,
    val lostHoldS: Int = 0,
    val manual: Manual = Manual.AUTO,
    val rules: List<OutRule> = emptyList(),
    /** Which template it came from, for the help texts. */
    val template: String = "",
    /** Its widget for the screens: "button" (on/off), "slider" (a level 0–100 %, PWM; MOSFET or LED on a PWM pin). */
    val widget: String = "button",
    /** The level the slider set, % (100 = fully on). */
    val level: Int = 100,
    // how its button looks: the text on it ("" = the name), the words and colours for on and off
    val label: String = "",
    val onText: String = "ВКЛ",
    val offText: String = "выкл",
    val onColor: Long = 0xFF22C55E,
    val offColor: Long = 0xFF64748B,
    /** Where a PWM output's level comes from: "" = its widget on the screen, "box_A3" = a potentiometer on the box. */
    val levelSrc: String = "",
) {
    /** A level below 100 % is PWM: only a MOSFET or an LED, and only on a pin with a timer. */
    fun pwm(board: BoxBoard) = widget != "button" && (load == LoadKind.MOSFET || load == LoadKind.LED) && pin in board.pwmPins

    /** The box's set-up command (firmware 3, see the sketch). */
    fun setup() = "C $ch ${pin.removePrefix("D")} ${if (activeLow) "L" else "H"} ${minOnS * 1000} ${minOffS * 1000} $maxOnS ${lost.code}" +
        if (lost == LostState.HOLD) " $lostHoldS" else ""

    fun toJson(): JSONObject = JSONObject().put("ch", ch).put("pin", pin).put("name", name).put("icon", icon).put("load", load.name)
        .put("low", activeLow).put("minOn", minOnS).put("minOff", minOffS).put("maxOn", maxOnS).put("lost", lost.name)
        .put("lostHold", lostHoldS).put("manual", manual.name).put("tpl", template).put("widget", widget).put("level", level)
        .put("label", label).put("onText", onText).put("offText", offText).put("onColor", onColor).put("offColor", offColor).put("levelSrc", levelSrc)
        .put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = OutChannel(
            o.getInt("ch"), o.getString("pin"), o.optString("name"), o.optString("icon", "relay"),
            runCatching { LoadKind.valueOf(o.getString("load")) }.getOrDefault(LoadKind.RELAY), o.optBoolean("low", true),
            o.optInt("minOn"), o.optInt("minOff"), o.optInt("maxOn"),
            runCatching { LostState.valueOf(o.getString("lost")) }.getOrDefault(LostState.OFF), o.optInt("lostHold"),
            runCatching { Manual.valueOf(o.getString("manual")) }.getOrDefault(Manual.AUTO),
            o.optJSONArray("rules")?.let { a -> List(a.length()) { OutRule.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optString("tpl"), o.optString("widget", "button"), o.optInt("level", 100),
            o.optString("label"), o.optString("onText", "ВКЛ"), o.optString("offText", "выкл"),
            o.optLong("onColor", 0xFF22C55E), o.optLong("offColor", 0xFF64748B), o.optString("levelSrc"),
        )
    }
}

/** A value a rule can watch: the app's own (ECU, transmission, guard) or a SensorBox input ("box_A0"). */
data class RuleSource(val id: String, val title: String, val unit: String, val min: Float, val max: Float, val step: Float, val decimals: Int,
                      /** On or off rather than a number: another output ("out_3"). */
                      val bool: Boolean = false)

object RuleSources {
    val builtIn = listOf(
        RuleSource("coolant", "Антифриз", "°C", 40f, 120f, 1f, 0),
        RuleSource("oil", "Масло", "°C", 40f, 150f, 1f, 0),
        RuleSource("intake", "Воздух на впуске", "°C", -20f, 80f, 1f, 0),
        RuleSource("atf", "ATF (коробка)", "°C", 20f, 130f, 1f, 0),
        RuleSource("rpm", "Обороты", "об/мин", 0f, 7500f, 100f, 0),
        RuleSource("speed", "Скорость", "км/ч", 0f, 200f, 5f, 0),
        RuleSource("boost", "Наддув", "бар", -0.8f, 2.0f, 0.05f, 2),
        RuleSource("afr", "AFR (смесь)", "", 10f, 20f, 0.1f, 1),
        RuleSource("throttle", "Дроссель", "%", 0f, 100f, 5f, 0),
        RuleSource("battery", "Напряжение", "В", 10f, 15f, 0.1f, 1),
    )

    /** The built-in ones, the SensorBox inputs set up now (a potentiometer too) and the other outputs, on or off. */
    fun all(): List<RuleSource> = builtIn + SensorBox.slots.value.map { s ->
        RuleSource("box_${s.input}", s.name, s.unit, s.p.min, s.p.max, if (s.p.decimals >= 2) 0.05f else if (s.p.decimals == 1) 0.1f else 1f, s.p.decimals)
    } + Relays.channels.value.map { c -> RuleSource("out_${c.ch}", "Выход ${c.ch}: ${c.name}", "", 0f, 1f, 1f, 0, bool = true) }

    fun of(id: String) = all().firstOrNull { it.id == id }
}

/** Ready outputs for Subaru owners: the numbers are starting points the driver sees and changes. */
object OutTemplates {
    data class Tpl(val id: String, val title: String, val sub: String, val icon: String, val load: LoadKind, val make: (ch: Int, pin: String) -> OutChannel)

    private var n = 0
    private fun id() = "r${System.currentTimeMillis()}${n++}"

    val all = listOf(
        Tpl("fan", "Доп. вентилятор радиатора", "Включать выше 98 °C, выключать ниже 94 °C", "fan", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Доп. вентилятор", "fan", LoadKind.RELAY, true, 30, 10, 0, LostState.ON, template = "fan",
                rules = listOf(OutRule(id(), RuleKind.LEVEL, main = Threshold("coolant", true, 98f, 94f))))
        },
        Tpl("oilfan", "Вентилятор масляного радиатора", "По доп датчику масла: выше 110 °C, ниже 100 °C", "fan", LoadKind.RELAY) { ch, pin ->
            val oil = SensorBox.slots.value.firstOrNull { it.role == "oil" }?.let { "box_${it.input}" } ?: "oil"
            OutChannel(ch, pin, "Вентилятор масла", "fan", LoadKind.RELAY, true, 30, 10, 0, LostState.ON, template = "oilfan",
                rules = listOf(OutRule(id(), RuleKind.LEVEL, main = Threshold(oil, true, 110f, 100f), also = listOf(Threshold("rpm", true, 400f, 300f)))))
        },
        Tpl("meth", "Водо-метанол", "Наддув выше 1,0 бар и смесь беднее 12; не ниже 2500 об/мин", "pump", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Водо-метанол", "pump", LoadKind.RELAY, true, 0, 1, 60, LostState.OFF, template = "meth", rules = listOf(
                OutRule(id(), RuleKind.LEVEL, main = Threshold("boost", true, 1.0f, 0.8f), also = listOf(Threshold("afr", true, 12f, 11.8f)), onDelayS = 0.3f),
                OutRule(id(), RuleKind.BLOCK, main = Threshold("rpm", false, 2500f, 2500f)),
            ))
        },
        Tpl("shift", "Шифтлайт", "Горит с 6300 об/мин, мигает с 6800", "light", LoadKind.LED) { ch, pin ->
            OutChannel(ch, pin, "Шифтлайт", "light", LoadKind.LED, false, 0, 0, 0, LostState.OFF, template = "shift", rules = listOf(
                OutRule(id(), RuleKind.LEVEL, main = Threshold("rpm", true, 6300f, 6100f)),
                OutRule(id(), RuleKind.LEVEL, main = Threshold("rpm", true, 6800f, 6600f), blink = true),
            ))
        },
        Tpl("light", "Свет при пробуждении", "Включается, когда магнитола проснулась", "light", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Свет", "light", LoadKind.RELAY, true, 0, 0, 0, LostState.OFF, template = "light",
                rules = listOf(OutRule(id(), RuleKind.EVENT, event = OutEvent.WOKE)))
        },
        Tpl("atf", "Вентилятор АКПП", "Включать выше 100 °C ATF, выключать ниже 92 °C", "fan", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Вентилятор АКПП", "fan", LoadKind.RELAY, true, 30, 10, 0, LostState.ON, template = "atf",
                rules = listOf(OutRule(id(), RuleKind.LEVEL, main = Threshold("atf", true, 100f, 92f))))
        },
        Tpl("button", "Кнопка на экране", "Доп. фары, компрессор: включаете сами; не включится при напряжении ниже 12,0 В", "button", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Доп. фары", "button", LoadKind.RELAY, true, 0, 0, 0, LostState.OFF, manual = Manual.OFF, template = "button",
                rules = listOf(OutRule(id(), RuleKind.BLOCK, main = Threshold("battery", false, 12.0f, 12.3f))))
        },
        Tpl("empty", "Своё правило", "Пустой выход: условие составите сами", "relay", LoadKind.RELAY) { ch, pin ->
            OutChannel(ch, pin, "Выход $ch", "relay", LoadKind.RELAY, true, 5, 5, 0, LostState.OFF, template = "empty",
                rules = listOf(OutRule(id(), RuleKind.LEVEL, main = Threshold("coolant", true, 95f, 91f))))
        },
    )

    fun newRuleId() = id()
}

/** What an output is doing and why, for the lamp and the line under the card. */
data class OutStatus(val want: Boolean, val blink: Boolean, val why: String, val box: Char? = null)

object Relays {
    val channels = MutableStateFlow<List<OutChannel>>(emptyList())
    /** «Всё выключить»: every output off until the driver turns them back on. */
    val allOff = MutableStateFlow(false)
    /** What each output is doing now (by channel), from the rule engine and the box's frames. */
    val status = MutableStateFlow<Map<Int, OutStatus>>(emptyMap())
    /** The box's own report: one character per channel (see the sketch), and whether it is in failsafe. */
    val boxStates = MutableStateFlow("")
    val boxFailsafe = MutableStateFlow(false)

    fun load() {
        channels.value = runCatching {
            val a = JSONArray(Repo.settings.relays)
            List(a.length()) { OutChannel.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        Repo.settings.relays = JSONArray().also { a -> channels.value.forEach { a.put(it.toJson()) } }.toString()
    }

    fun put(c: OutChannel) { channels.value = (channels.value.filter { it.ch != c.ch } + c).sortedBy { it.ch }; save() }
    fun remove(ch: Int) { channels.value = channels.value.filter { it.ch != ch }; save() }
    fun channel(ch: Int) = channels.value.firstOrNull { it.ch == ch }

    /** A widget button press: on and off by hand. */
    fun toggle(ch: Int) {
        val c = channel(ch) ?: return
        val on = status.value[ch]?.want == true
        put(c.copy(manual = if (on) Manual.OFF else Manual.ON))
    }

    /** Back to the rules (a long press on the widget). */
    fun auto(ch: Int) { channel(ch)?.let { put(it.copy(manual = Manual.AUTO)) } }

    /** The slider: a level, 0 = off. */
    fun setLevel(ch: Int, level: Int) {
        val c = channel(ch) ?: return
        put(c.copy(level = level.coerceIn(0, 100), manual = if (level <= 0) Manual.OFF else Manual.ON))
    }

    /** Puts output [ch]'s widget on «Мой экран» (switched on there); true if it was added. */
    fun showOnMine(ch: Int): Boolean {
        val s = Repo.settings
        val raw = s.board("myTiles")
        val tiles = MyBoard.parse(raw)
        if (tiles.any { it.id == "outw_$ch" && it.on }) return false
        val l = tiles.toMutableList()
        val i = l.indexOfFirst { it.id == "outw_$ch" }
        if (i < 0) return false
        val t = l.removeAt(i).copy(on = true)
        l.add(l.indexOfLast { it.on } + 1, t)
        s.setBoard("myTiles", MyBoard.format(l, MyBoard.isFree(raw)))
        return true
    }

    /** The next free channel number and a free output pin of [board], or null when there is none. */
    fun free(board: BoxBoard): Pair<Int, String>? {
        val ch = (1..8).firstOrNull { c -> channels.value.none { it.ch == c } } ?: return null
        val pin = SensorBox.freeOutPins(board).firstOrNull() ?: return null
        return ch to pin
    }
}
