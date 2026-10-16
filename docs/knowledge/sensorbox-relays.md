# SensorBox relays: outputs, rules, safety, protocol, board models

Scope: the next SensorBox feature. The Arduino (Nano, Uno, Pro Micro, Mega; 5 V) on the head unit's USB drives relay
modules (1/2/4/8 channels, mostly active-low and opto-isolated), MOSFET modules and solid-state relays. The driver sets up
rules in the app: an extra fan on coolant, a water/meth pump on boost and AFR, a shift light, an oil cooler fan on the
SensorBox oil temperature, a turbo timer, lights when the head unit wakes, and buttons on the screen.

Research date: 2026-10. Sources are linked in [Sources](#10-sources). Points marked **(unverified)** were not confirmed
against a primary document. Nothing here was tested on hardware.

Short answers:
- **Rule model:** output-centred, not event-centred. Each output has one or more "level" rules (on while a condition holds,
  with a separate off value, on-delay, off-delay), combined with OR, plus vetoes, a manual override and a failsafe state.
  Event rules (head unit woke, engine stopped, button) only start timed holds. This is how ECU GP outputs work, and it
  cannot "miss" an edge after an app restart.
- **Where rules run:** in the app (it has the ECU data; the box does not). The firmware only enforces per-output guards
  (minimum on/off time, maximum on time, pulse and blink timing, PWM) and a heartbeat failsafe with a per-output "link lost"
  state, optionally a single raw-ADC threshold on the box's own inputs.
- **Editor:** sentence-like cards in Russian, chips for every value, a two-handle slider for on/off values, live lamp with a
  "why" line, a "Проверить" pulse button and a replay on the last drive's log. Eight ready templates.
- **Board for relays:** Pro Micro. A Nano or Uno resets every time the app opens the port (the app raises DTR), so all
  outputs drop for about 2 s on every reconnect.
- **3D boards:** generate them procedurally in Blender from the pin rows the app already has in `BoxBoard`. Use the official
  Arduino EAGLE files (CC BY-SA 4.0) and the SparkFun Pro Micro files (CC BY-SA 4.0) only as a dimensional reference.
  Third-party CC BY models exist on Sketchfab and Printables for Nano, Uno and Pro Micro, but they don't cover the Mega, the
  relay modules or the MAX31855 in a consistent style.

---

## Contents

1. [What other editors do](#1-what-other-editors-do)
2. [Recommended rule model](#2-recommended-rule-model)
3. [Rule editor UI](#3-rule-editor-ui)
4. [Templates for Subaru owners](#4-templates-for-subaru-owners)
5. [Where rules run: app vs firmware](#5-where-rules-run-app-vs-firmware)
6. [Protocol additions](#6-protocol-additions)
7. [Safety checklist](#7-safety-checklist)
8. [3D models of the boards](#8-3d-models-of-the-boards)
9. [Open questions / unverified](#9-open-questions--unverified)
10. [Sources](#10-sources)

---

## 1. What other editors do

### 1.1 Block and node editors (Arduino world)

| Tool | How a rule looks | Take | Avoid |
|---|---|---|---|
| **Blockly** (Google), the base of most block editors | Jigsaw blocks: `if <compare> then <set pin>`; typed sockets only accept matching blocks | Typed slots: a value slot only accepts a value, so nonsense can't be built. Toolbox grouped by colour | Free-form programs. A driver doesn't want `if/else` nesting, and nothing in Blockly expresses hysteresis or "for 3 s" |
| **ArduBlock** (Arduino IDE plug-in, abandoned) and **mBlock** (Scratch 3 based) | Same block style, generate Arduino C++ that is uploaded | Showing the result as plain text next to the blocks (mBlock, Tinkercad) builds trust | Code generation and reflashing per rule change. Our box must never be reflashed for a new rule |
| **Tinkercad Circuits** code blocks | "Blocks", "Blocks + Text", "Text" modes; a **Start Simulation** button runs the circuit on screen | A **simulate** mode: move a virtual value and watch the output react before touching hardware | |
| **XOD** (node-based, for Arduino) | Nodes wired by data links; library nodes `flip-flop`, `debounce`, `delay`, `gate`; watch nodes show live values on the patch | Live values right on the rule. Debounce and delay as first-class parts | Graph wiring on a 7-10" touch screen is fiddly, and abstract for drivers |
| **Node-RED** | Flows of nodes; hysteresis needs contributed nodes (`node-red-contrib-hysteresis`: upper/lower thresholds; `node-red-contrib-threshold-hysteresis`: setpoint ± band, High/Low mode) | The setpoint ± band phrasing for thermostat-like rules | Flows and message passing are a programmer's model |
| **Arduino Cloud** triggers | A variable condition sends an email or push; device online/offline triggers with an optional delay | "Device went offline" as its own trigger: we need "box lost" and "app lost" as states | Only notifications, no output logic |

### 1.2 Home and phone automations

| Tool | How a rule looks | Take | Avoid |
|---|---|---|---|
| **Home Assistant** automations | Editor sections **When** (triggers), **And if** (conditions), **Then do** (actions). Numeric state trigger has Above / Below / **For** (must stay past the threshold for the time). Modes `single`, `restart`, `queued`, `parallel`. Every row can be disabled from its menu; **traces** show which step stopped a run. **Blueprints** are shareable templates | The three-part sentence and the word order. "For N seconds". A per-run trace becomes our "почему" line. Disable a rule without deleting it | HA triggers fire on a **crossing**, "not while something is true": after a restart a value already above the threshold doesn't fire. For relays that is a bug; we use level rules (§2) |
| **Home Assistant** Threshold helper and Generic Thermostat | Threshold: lower/upper with a hysteresis distance. Thermostat: target with `cold_tolerance` / `hot_tolerance`, `min_cycle_duration` (minimum time before switching off), `keep_alive` (resend the state periodically) | `min_cycle_duration` = our minimum on time; `keep_alive` = our heartbeat and periodic state resend | Tolerances around a target are harder to read than two plain numbers |
| **Apple Shortcuts** personal automations | Trigger card ("When I arrive...") then a list of actions; **Run Immediately** vs **Ask Before Running**, **Notify When Run** | One sentence per card; "Notify when run" maps to an optional toast "Вентилятор включён" | Asking for confirmation in the car |
| **Tasker** (Android) | A **profile** is a set of **contexts** (all must be true); it runs an **Enter task** when it becomes active and an **Exit task** when it stops being active. Profile properties include **Cooldown** and **Priority** | Level semantics: "while all contexts are true, the output is on; when not, it is off". Cooldown = minimum time between activations | Variables and task scripting |
| **Torque Pro** alarms | Pick a sensor, a comparison (over / under) and a value; sound, voice or popup **(unverified exact labels)** | Alarm on any value the app shows | A single threshold, no off value: alarms flap near the threshold. That is exactly what hysteresis prevents |

### 1.3 ECU and logger outputs (closest domain)

| System | What its general-purpose output offers |
|---|---|
| **Link G4+/G4X GP output** | Output function "GP Output", a logic function choosing which conditions count, condition 1/2/3 each "parameter, operator, value", a switch-off timer (the tutorial uses 0.1 s on a 7200 rpm shift light to stop flicker). Hysteresis is built from conditions: on the Link forum a moderator says the lower value goes in condition 1 and the higher in condition 3 (fan on above 50 %, off below 40 %) **(the exact logic expression is unverified; it reads like "(cond 1 AND output already on) OR cond 3")**. Newer Link PDM "GP Logic" uses a text expression over up to 4 inputs: `a&b`, `(a&b)|(c&d)` |
| **Haltech NSP** generic outputs | Switched outputs with conditions and on/off delays **(unverified: no primary doc found)** |
| **MegaSquirt MS3Pro** programmable on/off outputs | **Power On Value** (state at ECU power-up), **Active Value** (state when conditions are met), conditions with a hysteresis per condition ("how much the value must move before the condition is false"), conditions chained with AND/OR; once on, the output stays on until one condition turns false |
| **Speeduino** programmable outputs (from `reference/speeduino.ini`) | 8 outputs; per output `outputPin`, `outputInverted` ("Active high" / "Active low"), `outputDelay` (0-25.5 s), `outputTimeLimit` with `kindOfLimiting` "Minimum" or "Maximum" (minimum or maximum on time), two conditions with comparators `==, !=, >, >=, <, <=, &, ^` joined by `bitwise` "Disabled / AND / OR / XOR". No hysteresis field |
| **AiM Race Studio 3** status variables | "is TRUE when **All / Any** of the following conditions are true", each condition "channel, operator, value"; for AND plus OR you nest status variables. Alarm actions (LED, message, **Output Signal** "Closed to Ground") run "**Until**: condition no longer met" |
| **RaceCapture Pro** | No form editor: a Lua script calls `setGpio(channel, state)`; outputs are open drain, about 1 A, use a relay for more. The official example compares one value with no deadband |

What to take from the ECU world:
- **Active low / active high per output** (Speeduino), **power-on state** (MS3Pro), **minimum and maximum on time**
  (Speeduino), **on-delay and off-timer** (Link, Speeduino), **All / Any** (AiM).
- **Hysteresis must be first-class.** Link users have to build it out of three conditions and ask on forums how; Torque has
  none. In our editor it is the second line of every threshold rule.
- **"Until"** (AiM) is the natural way to say how an event rule ends: "пока условие не пропадёт", "N секунд",
  "пока не уснёт магнитола".

---

## 2. Recommended rule model

### 2.1 Principles

1. **Output-centred.** The driver thinks "the extra fan", not "the event coolant crossed 98". Every rule belongs to one
   output, and the output card shows all of its rules. Conflicts become impossible by construction (§2.4).
2. **Level, not edge.** A level rule is re-evaluated on every new sample: "on while coolant ≥ 98, then stays on until ≤ 94".
   After an app restart, a reconnect or a box reset the output state is recomputed from the current values.
3. **Two numbers instead of a hysteresis value.** "Включать выше 98, выключать ниже 94" is understood by anyone; "гистерезис
   4 °C" is not.
4. **The box keeps the hardware safe on its own.** Minimum on/off time, maximum on time and the failsafe live in the
   firmware, so a bug in the app or a frozen head unit can't chatter a relay or leave a pump on.

### 2.2 Data structures (Kotlin sketch)

```kotlin
/** A physical output channel on the box. Saved per box id, like BoxSlot. */
data class OutChannel(
    val ch: Int,                 // 1..8, the number on the card and in the protocol
    val pin: String,             // "D4"; from the board's allowed list (§6.5)
    val name: String,            // "Доп. вентилятор"
    val icon: String,            // fan, pump, light, bulb, timer, button
    val load: LoadKind,          // RELAY_MODULE, MOSFET, SSR_DC, LED
    val activeLow: Boolean,      // most relay modules: true
    val pwmHz: Int? = null,      // null = on/off only; 100 or 25000 (MOSFET/LED only)
    val minOnMs: Int,            // the box won't switch off sooner (relay/pump protection)
    val minOffMs: Int,           // the box won't switch on sooner
    val maxOnSec: Int? = null,   // hard limit, e.g. 60 s for a pump; null = none
    val lost: LostState,         // what the box does when the app is gone (§6.3)
    val manual: Manual = Manual.AUTO,
    val manualUntil: Long? = null,          // auto-return to AUTO (time) or null = until ignition off
)
enum class LoadKind { RELAY_MODULE, MOSFET, SSR_DC, LED }
sealed interface LostState { object Off; object On; object Keep; object LocalRule; data class HoldFor(val sec: Int) }
enum class Manual { AUTO, ON, OFF }

/** One rule. An output is ON when any enabled ON rule is active and no BLOCK rule is active. */
data class OutRule(
    val id: String,
    val ch: Int,
    val enabled: Boolean = true,
    val kind: RuleKind,                       // LEVEL, EVENT, BLOCK
    // LEVEL and BLOCK: "when <src> above <on>, off when below <off>"
    val main: Threshold? = null,
    val also: List<Threshold> = emptyList(), // "и если": all must hold (AND); OR = another rule
    val onDelayMs: Int = 0,                   // "держится N с" before switching on (HA "for")
    val offDelayMs: Int = 0,                  // "и ещё N с после" before switching off
    // EVENT: "when <event> then on for <hold>"
    val event: OutEvent? = null,
    val hold: Hold? = null,
    // what to do when a source has no data (sensor unplugged, ECU silent > 2 s)
    val noData: NoData = NoData.LIKE_LINK_LOST,
    val action: OutAction = OutAction.On,     // On, Pwm(duty), Blink(onMs, offMs)
)
enum class RuleKind { LEVEL, EVENT, BLOCK }
data class Threshold(
    val src: String,          // "coolant", "boost", "afr", "rpm", "atf", "box_A0" (SensorBox.values() keys)
    val above: Boolean,       // true: on above `on`; false: on below `on`
    val on: Float,
    val off: Float,           // above: off < on; below: off > on. Equal = no hysteresis (allowed only for BLOCK)
)
enum class OutEvent { HEAD_UNIT_WOKE, ENGINE_STARTED, ENGINE_STOPPED, BUTTON }
sealed interface Hold { data class Seconds(val s: Int); object UntilSleep; object UntilEngineStop; data class Cooldown(val maxSec: Int) }
enum class NoData { ON, OFF, LIKE_LINK_LOST }
```

Stored as JSON next to `SensorBox` slots (one array per box id), exported in `Backup`.

### 2.3 Evaluation (app side, every new ECU or box sample, at least every 250 ms)

For each output channel, in this order; the first line that decides wins:

| # | Layer | Result |
|---|---|---|
| 1 | Box guards (firmware): min on/off, max on, failsafe | the box may delay or refuse a change; the app shows it ("ждёт 3 с", "лимит 60 с") |
| 2 | "Всё выключить" (panic button on the relay screen) | OFF until the driver re-enables |
| 3 | Manual override ON / OFF (expires at `manualUntil` or at ignition off) | ON / OFF |
| 4 | Any active BLOCK rule ("никогда не включать, если напряжение ниже 11,5 В") | OFF |
| 5 | Any active LEVEL rule, or a running EVENT hold | ON (PWM: the highest duty requested; Blink if any rule asks to blink) |
| 6 | nothing active | OFF |

Level rule state machine (per rule): `OFF → (all conditions true for onDelay) → ON → (main crosses its off value, or any
"also" condition false, for offDelay) → OFF`. The "also" conditions get their own small hysteresis automatically (a tenth of
the main gap, or a per-parameter default: 0.05 bar, 0.2 AFR, 100 rpm, 1 °C) so a second condition hovering at its value
doesn't flap either.

Data freshness: a source older than 2 s counts as "no data"; the rule then follows `noData` (default: what the output does
when the link is lost: a fan goes ON, a pump goes OFF).

The app sends `O` only when the computed state changes, and a heartbeat every 500 ms (§6). The output state goes into the
log as a column (`LogColumns`) so the drive log shows when the fan or pump ran.

### 2.4 Conflicts

With OR semantics, two ON rules on one output never conflict: "Реле 1 включено, если сработало любое из 2 правил". The
editor still checks:
- **Event OFF vs level ON** (only if we ever allow an OFF action besides BLOCK): disallowed; an OFF intention is a BLOCK.
- **Pin clash:** an output pin that is a sensor input, the thermocouple CS or SPI pins, the frequency input, or D13 (§6.5).
- **Action vs load:** PWM or Blink on a relay channel is refused ("реле не для мигания: поставьте MOSFET или светодиод").
- **Gap too small:** off value within sensor noise of the on value (temperature < 1 °C, boost < 0.05 bar, AFR < 0.2) or
  the replay on the last drive shows more than 20 switches per 10 minutes: "реле будет щёлкать, увеличьте разницу".
- **Wrong side:** "above" with off ≥ on is swapped automatically with a note.
- **Source missing:** the rule references a SensorBox input that isn't set up, or a parameter this ECU/ROM doesn't report:
  the card turns grey with "нет данных: подключите датчик масла".
- **Risky failsafe:** a pump or ignition-type output with `lost = On` gets a red warning; a cooling fan with `lost = Off`
  gets a yellow one.

---

## 3. Rule editor UI

Head unit screens are 1024×600 or 1280×720 landscape, used at arm's length, often while parked. Rules:
touch targets ≥ 48 dp, values changed with large −/+ steppers and sliders (no keyboard unless asked), one card per output,
Russian everyday words (no "гистерезис", "триггер", "логика").

### 3.1 Screen layout

```
┌ Настройки, Доп датчики, Реле ───────────────────────────────────────────────────────────────┐
│ ┌ Выходы ─────────────────┐  ┌ Реле 1 · Доп. вентилятор ──────────────────────── ● ВКЛ ──┐ │
│ │ ● 1 Доп. вентилятор ВКЛ │  │ Включать, когда  [Антифриз ▾]  выше  [ 98 °C ]            │ │
│ │ ○ 2 Водо-метанол   выкл │  │ Выключать, когда он ниже  [ 94 °C ]                        │ │
│ │ ◐ 3 Шифтлайт  мигает    │  │   88 ────────●══════════|══════════●──────── 105 °C        │ │
│ │ ○ 4 Масляный кулер выкл │  │              94   сейчас 99 ▲      98                         │ │
│ │ ⚠ 5 Свет  нет связи     │  │ + и если ...                                               │ │
│ │ + Добавить выход        │  │ Не выключать раньше чем через  [ 30 с ]                    │ │
│ │                         │  │ Если связи с магнитолой нет:  [ включить ▾ ]               │ │
│ │ [ Всё выключить ]       │  │ ─────────────────────────────────────────────────────────  │ │
│ └─────────────────────────┘  │ Сейчас: включён 2 мин, потому что антифриз 99 °C (≥ 98)    │ │
│                              │ Коробка подтвердила: вкл                                    │ │
│                              │ [ Проверить 1 с ]  [ Как было бы в прошлой поездке ]       │ │
│                              │ Ручное: ( Авто | Вкл | Выкл )                              │ │
│                              └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Card grammar (all rules read as one Russian sentence)

| Rule | Sentence |
|---|---|
| Level | **Включать**, когда `[параметр]` `[выше/ниже]` `[значение]`. **Выключать**, когда он `[ниже/выше]` `[значение]`. |
| Also | **и если** `[параметр]` `[выше/ниже]` `[значение]` (each as a chip row; "+ и если ..." adds one) |
| On-delay | ... **и это держится** `[2 с]` |
| Off-delay | **после этого ещё** `[30 с]` |
| Event | **Когда** `[магнитола проснулась ▾]`, **включить** `[на 10 мин / пока не уснёт / пока не остынет турбина]` |
| Block | **Никогда не включать**, если `[напряжение]` `[ниже]` `[11,5 В]` |
| Button | **Кнопка на главном экране**: `[нажал и держит / вкл-выкл]`, **выключить сама через** `[10 мин]` |
| PWM | **Скорость** `[40 %]` при `[95 °C]`, `[100 %]` при `[102 °C]` (two points, a straight line between) |
| Blink | **Мигать** при `[обороты]` выше `[6800]` |

Two or more rules on one output are shown stacked with an **"или"** divider and the line "Включено, если сработало
любое".

### 3.3 Patterns

- **Chips with live values.** Every parameter chip shows its current value under the name ("Антифриз · 91 °C"). The picker
  groups parameters as in the app: Мотор, Наддув и смесь, Коробка, Доп датчики (SensorBox inputs by their names),
  Машина (скорость, напряжение, магнитола).
- **Two-handle slider for on/off values** on the parameter's own scale, with the live value as a moving marker and the band
  between the handles shaded and captioned "здесь реле остаётся как было". Default gap per parameter (temperature 4 °C,
  boost 0.2 bar, rpm 200, AFR 0.3, voltage 0.5 V).
- **Live lamp** per output: green ВКЛ, grey выкл, orange "ждёт 3 с" (on-delay or the box's minimum time), blue "вручную",
  red "нет связи с коробкой" or "авария: коробка в защите". The lamp shows what the **box** reports in its `D` frames, not
  what the app wanted; a mismatch for more than 1 s is a warning.
- **"Почему" line** under the card (HA traces, simplified): "Включён, потому что антифриз 99 °C ≥ 98" / "Выключен: ждём,
  пока наддув продержится 0,5 с" / "Не включается: запрет «напряжение ниже 11,5 В»".
- **Проверить 1 с:** a confirmation dialog ("Мотор заглушен? Насос будет качать 1 секунду") then `P ch 1000`. Shows the click
  in the lamp and asks "Щёлкнуло?". Pumps and turbo timer only with the engine off.
- **Simulate (Tinkercad):** "Попробовать" turns the live marker into a draggable one; the lamp reacts, nothing is sent to the
  box.
- **Replay on the last drive (unique to us, we have the logs):** "В прошлой поездке вентилятор включился бы 6 раз, всего
  4 мин 10 с, самый короткий раз 35 с". The same run feeds the chatter check of §2.4. A small strip chart shows the value and
  the on periods.
- **Templates first.** "Добавить выход" opens the template gallery (§4). The steps are: what is it (template) → where it is
  wired (channel, pin, module type, with the wiring picture we already draw for sensors) → the sentence card with the
  template's numbers → Проверить.
- **Home screen button:** like "Показать на главном экране" for sensors: a tile with the output's icon and state; tap toggles
  (or hold for momentary), long press opens the card.
- **Disable without deleting** (HA): a switch on every rule row.
- **Notifications:** optional "Сообщить, когда включится" (Shortcuts "Notify When Run") through `Alerts`, off by default.

---

## 4. Templates for Subaru owners

Numbers are starting points the driver sees and can change. Coolant, boost, rpm, intake and ATF come from the ECU/TCU through
SSM; oil temperature and wideband AFR come from SensorBox inputs with those roles.

| # | Template (card title) | Sentence (as the card shows it) | Load | Box settings |
|---|---|---|---|---|
| 1 | **Доп. вентилятор радиатора** | Включать, когда антифриз выше 98 °C. Выключать, когда ниже 94 °C. Не выключать раньше чем через 30 с. | relay → 30/40 A automotive relay, extra fan only (stock fan stays on the ECU) | min on 30 s, min off 10 s, lost = ON, no data = ON |
| 2 | **Вентилятор масляного радиатора** | Включать, когда масло (доп датчик) выше 110 °C, и если мотор работает. Выключать ниже 100 °C. | relay or MOSFET (small fan) | min on 30 s, lost = local rule on the oil input (raw threshold), no data = ON |
| 3 | **Водо-метанол** | Включать, когда наддув выше 1,0 бар и AFR беднее 12,0 (больше 12,0), и это держится 0,3 с. Выключать, когда наддув ниже 0,8 бар. Никогда не включать, если обороты ниже 2500. | relay → automotive relay, or a DC SSR / MOSFET; PWM for progressive kits | max on 60 s, min off 1 s, lost = OFF, no data = OFF |
| 4 | **Шифтлайт** | Включать, когда обороты выше 6300; мигать выше 6800. Выключать ниже 6100. | LED from the pin (≤ 15 mA) or MOSFET for a 12 V lamp; never a relay | no min times, lost = OFF |
| 5 | **Турботаймер** (phase 2, §7.4) | Когда мотор заглушён, держать зажигание, пока не остынет турбина (по расчёту приложения, не дольше 3 мин), только если затянут ручник. | relay → automotive relay across the ignition switch | lost = hold for the cooldown sent by the app, handbrake input required |
| 6 | **Свет при пробуждении** | Когда магнитола проснулась, включить, пока не уснёт. (or the DRL form: Включать, когда обороты выше 400 и это держится 3 с) | relay → automotive relay | lost = OFF; powered from USB, so it goes off with ACC |
| 7 | **Вентилятор АКПП** (4EAT) | Включать, когда ATF выше 100 °C. Выключать ниже 92 °C. | relay | min on 30 s, lost = ON |
| 8 | **Кнопка на экране** (доп. фары, компрессор, «люстра») | Кнопка на главном экране: вкл-выкл. Выключить сама через 30 мин. Никогда не включать, если напряжение ниже 12,0 В. | relay | lost = OFF |

Extra ideas for later: intercooler sprayer (pulse 2 s when boost > 0.9 bar and intake > 50 °C, not more often than every
20 s: uses `P` and a cooldown), a buzzer output for any app alarm, a 2-speed fan (two channels with 96/100 °C).

---

## 5. Where rules run: app vs firmware

| | App evaluates (recommended) | Firmware evaluates |
|---|---|---|
| Data | Everything: ECU and TCU over SSM, SensorBox inputs converted to °C/bar/AFR, head unit state, EngineGuard (turbo heat, cooldown) | Only the box's own raw inputs (ADC counts, TC0, F0). ECU values would have to be forwarded by the app, which brings the app back into the loop |
| Units and profiles | Already in the app (`SensorBox.read`) | The box would need every sensor profile; contradicts "the box is dumb" |
| Changing a rule | Instant, no reflash, no EEPROM writes | A protocol for rule upload, EEPROM wear, versioning |
| UI features (why line, replay, simulate) | Same code as the live evaluation | Would need a second implementation in Kotlin anyway |
| Head unit freezes / app crashes / USB drops | Outputs would stay in their last state: **unacceptable alone**, hence the heartbeat failsafe in firmware | Keeps working |
| ACC off (head unit sleeps) | Box usually loses USB power too, so everything is off anyway **(unit-dependent, unverified)** | Same, unless the box has permanent power |
| Latency | ECU poll (≈ 10-20 Hz on SSM) + app + USB: ≈ 100-250 ms. Fine for fans, pumps, lights; acceptable for a shift light | ≈ 50 ms (one frame) |

**Recommendation:** the app evaluates all rules. The firmware adds:
1. per-output **guards** (min on, min off, max on, max toggles per minute),
2. **pulse, blink and PWM** timing, so the app sends one command instead of a stream,
3. a **heartbeat failsafe** with a per-output state when the app is gone: off, on, keep, hold N s, or
4. an optional **local threshold** on one raw input (`L`, §6.2) used only while the app is gone. The app computes the raw
   counts from the driver's °C with `SensorBox.rawFor()`, so the box stays unit-free. This covers the oil cooler fan from
   the SensorBox NTC with the head unit frozen.

---

## 6. Protocol additions

Current protocol (README): text at 115200 8N1, `?`, `R <hz>`, `S 0|1`, frames `I ...` and `D ...`. The additions keep the
same style: one letter, space-separated numbers, every line ends with `\n`. The firmware's line buffer (`char line[24]`)
grows to 48 bytes.

### 6.1 Identification

```
box → I SENSORBOX 2 board=promicro id=1A2B rate=20 ch=A0,A1,A2,A3,TC0,F0 out=D0,D1,D2,D3,D4,D5,D6,D8,D9 pwm=D5,D9
```
`out=` lists the pins this board allows as outputs (§6.5), `pwm=` those with a settable PWM frequency. Version 2 means the
output commands exist; a version 1 box simply has no relay screen. `parseInfo` ignores unknown keys already.

### 6.2 Commands (host → box)

| Command | Meaning | Reply |
|---|---|---|
| `C <ch> <pin> <L\|H> <minOn ms> <minOff ms> <maxOn s> <lost> [f=<Hz>]` | Configure channel 1..8: pin, active Low/High, guards (0 = none), state when the link is lost: `0` off, `1` on, `K` keep, `R` local rule, `T<s>` hold s seconds then off; optional PWM frequency. Saved in EEPROM | `K C <ch>` or `E C <ch> <reason>` (pin not allowed, pin in use) |
| `O <ch> <0\|1>` | Set output (subject to guards) | state shows in the next `D` frame; `E O <ch> <reason>` if refused |
| `P <ch> <ms>` | Pulse: on for ms (1..60000), then off | `D` frame shows `p` |
| `B <ch> <onMs> <offMs>` | Blink until `O <ch> 0` (shift light) | `D` shows `b` |
| `W <ch> <0..255>` | PWM duty (only on a `pwm=` pin of a channel configured with `f=`) | `D` shows the duty |
| `L <ch> <input> <gt\|lt> <on> <off>` | Local rule in raw counts, e.g. `L 4 A0 lt 210.0 260.0` (NTC: lower counts = hotter). Used only while lost = `R` | `K L <ch>` |
| `H <ms>` | Arm the heartbeat with a timeout (500..30000 ms). `H` alone: keep-alive. `H 0`: disarm (bench tests) | none |
| `X` | Everything off now, blink/PWM/pulses cancelled | `K X` |

Any received line counts as a heartbeat. The app sends `H` every 500 ms with a 3000 ms timeout: long enough for a GC pause
or a USB hiccup, short enough that a pump doesn't run on for long.

### 6.3 Failsafe behaviour

- **Power-up and reset:** every output is set **OFF** (inactive level) as the very first thing in `setup()`, before
  `Serial.begin`. Not the `lost` state: a box that powers up with the head unit and no app yet must not start a fan or pump.
- **Armed:** after the first `H <ms>`. Only an armed box can enter the failsafe.
- **Link lost** (no line for the timeout): each channel goes to its `lost` state; the box prints `F 1` and the `D` frames carry
  `fs=1`. `T<s>` holds the output for s seconds then turns it off; `R` runs the `L` rule.
- **Link back:** the box stays in failsafe until the app sends `H` again and then the states it wants (`O`). The box never
  restores old states by itself. It prints `F 0`.
- **Guards always apply**, also in failsafe and to manual commands: a change before `minOn`/`minOff` is postponed (the
  frame shows `w`), `maxOn` turns the output off and blocks it until the app sends `O <ch> 0` (acknowledgement), more than 30
  changes per minute locks the channel off with `E O <ch> chatter`.
- **Watchdog:** optional. **Do not enable it on Nanos with the old bootloader:** ATmegaBOOT doesn't clear the watchdog, so a
  watchdog reset loops forever (board "bricked" until reflashed with Optiboot). Optiboot (new Nano, Uno), Caterina (Pro Micro)
  are fine **(Mega 2560 stk500v2 bootloader: unverified)**. The app knows the bootloader from flashing (57 600 baud = old).

### 6.4 Frames (box → host)

```
D t=81234 vcc=4987 A0=512.3 A1=88.0 A2=1023.0 A3=0.0 TC0=745.25 F0=0.0 Q=10wb W5=128 fs=0
```
- `Q=` one character per configured channel in channel order: `0` off, `1` on, `w` waiting for a guard time (wanted state
  differs), `p` pulsing, `b` blinking, `~` PWM (duty in `W<ch>=`), `x` locked (max on or chatter), `-` channel not configured.
- `fs=` 1 while in failsafe.
- **Do not start new keys with `A`:** `SensorBox.parseFrame` treats every key starting with `A` as an analog input.
- Events: `K ...` acknowledgements, `E <cmd> <ch> <reason>` errors, `F 1` / `F 0` failsafe entered / left.

### 6.5 Allowed output pins

Pins the current sketch already uses are excluded: analog inputs, the frequency input, MAX31855 SCK/MISO/CS, SPI MOSI
(driven by the SPI hardware), the USB serial pins on boards with a USB bridge, and D13 (the bootloader blinks the LED on it at
every reset, a relay there clicks).

| Board | Output pins | PWM with a settable frequency | Notes |
|---|---|---|---|
| Nano | D3, D4, D5, D6, D7, D8, D9 | D9 (Timer1 OC1A: 25 kHz or 100 Hz), D3 (Timer2 OC2B, OCR2A as top) | D5/D6 are Timer0 (millis): fixed ≈ 980 Hz. A4/A5 also usable as digital if no I2C |
| Uno | D3, D4, D5, D6, D7, D8, D9 | D9, D3 as Nano | A4/A5 are analog inputs in this sketch |
| Pro Micro | D0 (TX0), D1 (RX1), D2, D3, D4, D5, D6, D8, D9 | D9 (Timer1), D5 (Timer3) | Native USB, so D0/D1 are free; **no reset when the app opens the port** |
| Mega | D3-D9, D11, D12, D22-D29 | D11/D12 (Timer1), D5/D3 (Timer3), D6-D8 (Timer4) | D2 is the frequency input; D50-D52 SPI; D22+ are on the end header (the wiring picture needs it) |

### 6.6 Firmware size

All of the above is about 250 lines of C++ and well under 2 KB of flash; EEPROM holds 8 × 12 bytes of channel config after
the existing 4 bytes. The `F()` strings keep RAM use low on the 328P.

---

## 7. Safety checklist

### 7.1 Wiring the box side

- [ ] **No load on an Arduino pin.** A pin drives an opto input (2-5 mA), a MOSFET gate or an LED (≤ 15 mA). ATmega328P: 40 mA
      absolute maximum per pin, 20 mA recommended, 200 mA through the VCC/GND pins in total. A relay coil directly on a pin is
      never OK.
- [ ] **Opto-isolated relay module, JD-VCC jumper removed**, coils fed from a separate 5 V buck converter on fused 12 V (2 A
      fuse), not from the head unit's USB. A Songle SRD-05VDC coil draws about 71 mA (70 Ω): an 8-channel module with all
      relays on is about 0.57 A, more than a USB port gives, and a dip resets the Arduino.
- [ ] **Active-low modules:** the firmware writes HIGH before `pinMode(OUTPUT)`; otherwise the pin goes LOW for a moment and
      the relay clicks on at every start. During reset and in the bootloader the pins are inputs (high impedance), which keeps
      an active-low opto module off.
- [ ] **Active-high outputs (MOSFET, DC SSR):** a 10 kΩ pull-down from the input to ground, so the load stays off while the pin
      floats during reset, plus 100-220 Ω in series with a MOSFET gate.
- [ ] **Pins:** not D13 (bootloader blink), not D0/D1 on Nano/Uno, not the SPI/thermocouple pins, not the frequency input
      (§6.5). The app enforces this.
- [ ] **Nano/Uno reset on every port open.** The app raises DTR when it opens the port (`SensorBoxLink`: "a Nano restarts on
      it"), which resets the board through the DTR capacitor: every reconnect drops all outputs for about 2 s and the box comes
      back with everything off. Use a **Pro Micro** (ATmega32U4 resets only on the 1200-baud touch) for relays. Don't fit the
      usual "10 µF from RESET to GND" hack: it also breaks flashing from the app.
- [ ] One ground point for the box, the modules and the sensors (as in the README); relay and load wires routed away from
      sensor wires.

### 7.2 Wiring the load side

- [ ] **Module relays only switch the coil of an automotive relay** (ISO mini 30/40 A, Bosch type), never the fan or pump
      directly. The SRD contact is rated 10 A (28-30 VDC, resistive; listings disagree), and motor inrush is several times the
      running current.
- [ ] **Flyback diode or resistor across every relay coil** the module switches (or a relay with a built-in diode: mind its
      polarity). Modules have a diode across their own coils already.
- [ ] **Fuse every new power wire at the battery or fuse-box end**, sized to protect the wire and a little above the load's
      running current. Rough guide **(check the fan/pump instructions)**: one 12-14" extra fan, 10-15 A running → 25-30 A fuse,
      2.5-4 mm² wire; water/meth pump, 5-10 A → 15 A fuse, 1.5-2.5 mm²; lights per their rating.
- [ ] **SSR: only DC-output types** ("DD", MOSFET output). An AC-output SSR ("DA") switches on a DC load and **never switches
      it off**. Cheap "Fotek" SSRs are often counterfeit and rated far below the label.
- [ ] **MOSFET modules:** logic-level MOSFET (Rds(on) given at Vgs = 4.5 V), ≥ 40 V (load dump), a freewheel diode across
      motors and solenoids rated for the load current. The popular IRF520 module is not logic-level and runs hot at 5 V gate
      drive; dual-AOD4184 style modules are logic-level **(check the part on the board)**.

### 7.3 PWM

- **Big radiator fans: on/off through relays**, possibly two stages (two channels). PWM of a 15 A brushed fan from an Arduino
  pin needs a gate driver and heat sinking.
- **Brushed motors via MOSFET:** 100-300 Hz is easy to drive from a pin but audible; 20-25 kHz is silent but the pin's
  20-40 mA gate current makes slow edges and heat, so it needs a driver chip. Use `f=100` by default.
- **Fans and pumps with their own controller and a PWM input** (brushless motorsport/OEM fans, 4-wire PC fans) take a signal,
  not power: PC fans per Intel's 4-wire spec use 25 kHz (21-28 kHz); one aftermarket brushless radiator fan lists a 50-200 Hz
  input and **runs at full speed if the signal is lost**, a good failsafe; OEM fan modules commonly use about 100 Hz
  **(unverified, check the fan's documentation)**. For these, `W` drives the signal pin directly through a resistor or an
  open-collector transistor.
- The default `analogWrite()` frequency is about 490 Hz (980 Hz on D5/D6 of a Nano/Uno); D9 and D3 can be re-timed without
  touching `millis()`.

### 7.4 What not to switch

- **Never:** ignition and starter, fuel pump, ECU or TCU power, the stock radiator fan circuit (add a parallel extra fan with
  its own relay, the ECU keeps the stock one), brakes and ABS, power steering, airbag, immobiliser, headlights needed to drive
  at night, horn. The editor offers no templates for these and the safety screen says so.
- **Turbo timer** bridges the ignition switch: the car runs with no key in it. It needs (a) permanent power for the box (the
  head unit and its USB go off with ACC), (b) a handbrake input that must be on, (c) a hard limit (3 min) in firmware and (d)
  cancel on handbrake release or brake pedal. Phase 2 only; until then the template shows the cooldown countdown on screen
  (EngineGuard already computes `cooldownSec`).
- **Water/meth:** fail OFF (no data, link lost, max on time). Running the pump with the injector closed or the tank empty can
  damage it; a level switch input is a good later addition.

### 7.5 Toggle rate and minimum times

- Small relays are rated about 10⁷ operations mechanically and about 10⁵ electrically at rated load (typical SRD datasheet
  figures, **unverified for this part**). At one click per second, 10⁵ operations is 28 hours of driving.
- Defaults: fans min on 30 s / min off 10 s; pumps min off 1-2 s and max on 60 s; lights 2 s / 2 s; LEDs and shift lights no
  limits; any channel above 30 changes per minute is locked off with an error.
- Hysteresis gaps per §3.3 defaults; the replay of the last drive warns before the driver saves.

### 7.6 Commissioning (shown as a checklist in the app)

1. Engine off, ACC on. Box connected, module powered from its own 5 V.
2. "Проверить 1 с" on every channel: the right relay clicks, the lamp in the app matches the `Q=` the box reports.
3. Pull the USB cable while an output is on: within 3 s every output goes to its "нет связи" state.
4. Replug: everything stays in that state until the app sends new states; the lamps match again.
5. Start the engine, watch the "почему" lines on a short drive; check the replay.

---

## 8. 3D models of the boards

### 8.1 Candidates

Downloading from Sketchfab needs a free account; Printables and Thingiverse downloads **(login requirement unverified)**.
Face counts are what the Sketchfab API reports.

| Board | Source, author | Licence | Format | Faces | URL |
|---|---|---|---|---|---|
| Nano | Sketchfab, **hackerman_den** "Arduino Nano" (2022) | CC BY 4.0 | glTF/original via Sketchfab | 26 612 | https://sketchfab.com/3d-models/fa0e94f0c4b44b9e88295346b6cef068 |
| Nano | Sketchfab, **rom35076** "Arduino Nano V3 Mini USB" (2019) | CC BY 4.0 | via Sketchfab | 10 440 | https://sketchfab.com/3d-models/c32b69f84f874565b9f8ac9a86944ac0 |
| Nano | Sketchfab, **misa.matejku** "Arduino Nano" (2023) | CC BY 4.0 | via Sketchfab | 45 022 | https://sketchfab.com/3d-models/fb76a83e9b1d44cdb04be7150c6e63d6 |
| Nano | Printables, **Jakob Kerber** "Arduino Nano" | CC0 | STL/STEP (see page) | n/a | https://www.printables.com/model/154503 |
| Nano | Printables, **Cyberbyte** "Basic Arduino Nano for 3D Design" (SolidWorks) | CC BY 4.0 | see page | n/a | https://www.printables.com/model/758155 |
| Uno | Sketchfab, **kakaeka** "Arduino Uno" (2020) | CC BY 4.0 | via Sketchfab | 8 136 | https://sketchfab.com/3d-models/775a2bf901a74fd190f175f5f0dcf2d0 |
| Uno | Sketchfab, **spark-games** "Arduino UNO - Low Poly" (2021) | CC BY 4.0 | via Sketchfab | 2 196 | https://sketchfab.com/3d-models/43cebeb50dc14ab490b553f0a0077eb9 |
| Uno | Sketchfab, **thesidekick** "Arduino Uno R3, Elegoo" (2023) | CC BY 4.0 | via Sketchfab | 57 540 | https://sketchfab.com/3d-models/69d58a27ae814b429e39b2c19d648cdd |
| Uno | Printables, **Nilabh Yadav** "Arduino uno Model" | CC0 | see page | n/a | https://www.printables.com/model/1260827 |
| Uno | Printables, **Ludvig Broomé** "Arduino UNO 3D Model (STP)" | CC BY-NC: **not usable** (NC is not GPL-compatible) | STEP | n/a | https://www.printables.com/model/358867 |
| Pro Micro | Sketchfab, **moshivuu** "Sparkfun Pro Micro" (2020) | CC BY 4.0 | via Sketchfab | 17 654 | https://sketchfab.com/3d-models/690cb433868b4e86be49ca3f55c05647 |
| Pro Micro | Printables, **Logomode** "Arduino Pro Micro dummy" | CC BY-SA 4.0 | STL | n/a | https://www.printables.com/model/1197302 |
| Mega 2560 | Printables, **Hack_n_Splice** "Arduino Mega 2560 model (Rexqualis version)" | CC BY 4.0 | see page | n/a | https://www.printables.com/model/435446 |
| Mega 2560 | GrabCAD, **Sergey Torshin** "Arduino Mega 2560 Rev3" (detailed and simplified, STEP) | GrabCAD terms: "non-commercial, internal use", commercial use needs the author's permission: **avoid** | STEP | n/a | https://grabcad.com/library/arduino-mega-2560-rev3-microcontroller-board-1 |
| 1-ch relay module | Sketchfab, **davidg.dev** "Relay 1 Channel to 5V 10A" (2023) | CC BY 4.0 | via Sketchfab | 4 635 | https://sketchfab.com/3d-models/c583b63c86cc4a19b1345ee3eae0d34c |
| 1-ch relay module | Sketchfab, **peddintiudaykiran176** "Single Channel Relay Module" (2026) | CC BY 4.0 | via Sketchfab | 8 101 | https://sketchfab.com/3d-models/50e119a125544c7aa447ac254015594f |
| 2-ch relay module | Sketchfab, **roboticaparana** "Módulo Relé 2 Canais" (2024) | CC BY 4.0 | via Sketchfab | 226 290 (heavy) | https://sketchfab.com/3d-models/488f00dcb5ee437f8c71c59d776afd62 |
| Relay (part) | KiCad `Relay_THT.3dshapes/Relay_SPDT_SANYOU_SRD_Series_Form_C.step` (same body as Songle SRD) | CC BY-SA 4.0 with the KiCad design exception | STEP (+ WRL) | n/a | https://gitlab.com/kicad/libraries/kicad-packages3D |
| MOSFET module | Adafruit CAD Parts "5648 MOSFET Driver" | MIT | STEP, STL, F3D | n/a | https://github.com/adafruit/Adafruit_CAD_Parts |
| MAX31855 module | **nothing found** on Sketchfab or Printables (only cases for MAX31865 boards) | | | | |
| 4/8-ch relay module | **nothing usable found** (Printables has only cases; Sketchfab results were PCB-only or NC) | | | | |

### 8.2 Official sources checked

- **KiCad 3D library (kicad-packages3D):** licence CC BY-SA 4.0 with an exception that waives article 3 for "electronic designs
  ... and any generated files"; redistributing library parts as a collection keeps CC BY-SA. `Module.3dshapes` holds only
  Raspberry Pi Pico, Coral and Sipeed models. The footprints `Arduino_Nano_WithMountingHoles` and `Arduino_UNO_R3` reference
  `Module.3dshapes/Arduino_Nano_WithMountingHoles.step` and `Arduino_UNO_R3.step`, **but those files are not in the
  repository**. No Pro Micro, no Mega. Useful parts: the SRD relay, pin headers, terminal blocks, USB connectors, QFP/DIP
  packages, crystals. A render in our app is not an "electronic design", so treat the renders as CC BY-SA 4.0 derivatives.
- **Arduino docs (docs.arduino.cc):** the product pages for Nano (A000005), Uno R3 (A000066) and Mega 2560 (A000067) offer
  "CAD Files" ZIPs (≈ 200-300 KB). They contain **EAGLE `.brd`/`.sch` files, not STEP**, and a `License.txt`: "This
  project's hardware is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License". The Mega page
  also has a Fritzing part. The `.brd` is XML with exact board outline, hole, header and part positions.
- **SparkFun Pro Micro** (github.com/sparkfun/Pro_Micro): EAGLE `.brd`/`.sch` (and STL) under CC BY-SA 4.0 (SparkFun
  `LICENSE.md`). Chinese clones share the pinout and outline.
- **GrabCAD:** the Terms of Use grant a licence for "non-commercial, internal use"; the help centre allows non-commercial
  public showing with credit; commercial use needs the designer's permission. No per-model open licence: not compatible
  with distributing frames in a GPL app.

Licence compatibility: CC BY 4.0 and CC0 are fine with attribution in README "3D-модели" as for the cars and engines.
CC BY-SA 4.0 is one-way compatible with GPLv3 (declared by Creative Commons in 2015), but the rendered frames would be
CC BY-SA 4.0 works and should be listed like the NC-SA car frames are. CC BY-NC (any NC) is not usable for frames shipped
under GPL.

### 8.3 Procedural boards vs downloaded models

| | Procedural in Blender (recommended) | Downloaded models |
|---|---|---|
| Pin match | **Exact:** generated from `BoxBoard.topRow` / `bottomRow` / `endPins`, the same lists the 2D wiring picture uses; silkscreen labels as printed | Each model has its own variant (Nano clones differ in USB connector, Pro Micro in micro-USB vs USB-C); pins can't be addressed by name |
| Pin anchors | Every pin is a named object: `render_engine.py --anchors` can export each pin's screen position per frame, so the app can draw wires to a rotating board and light the used pins | Would need hand-placed anchors per model |
| Coverage | All four boards, 1/2/4/8-ch relay modules, MOSFET module, MAX31855 module, one style, colour from `BoxBoard.pcb` | Mega, multi-channel relays and MAX31855 missing; mixed styles and detail (2 k to 226 k faces) |
| Licence | Our own work (GPL, or CC0 for the frames); measurements taken from the official files are facts. Credit "размеры по файлам Arduino и SparkFun (CC BY-SA 4.0)" as a courtesy | CC BY attribution per model; CC BY-SA frames for the KiCad relay |
| Effort | ≈ 300-400 lines of `bpy`: rounded PCB slab, header pins and plastic, a QFP-32 / TQFP-44 / TQFP-100 chip, USB connector, crystal, SMD LEDs, mounting holes, text; relay module = PCB + blue SRD block + screw terminals + opto + LED | Download, clean up, scale, re-anchor per model |
| Polygon count | ≈ 5-20 k per board | 2 k to 226 k |

**Recommendation:** procedural. Add `tools/board3d/make_boards.py` that builds a `.glb` per board and module from a small JSON
(dimensions and header positions taken from the official EAGLE files and the SparkFun Pro Micro files, pin names from
`BoxBoard`), then render with the existing `render_engine.py` (36 frames are enough for a board, or two fixed views: top
for wiring, three-quarter for the hero image) and its anchor export for the pins. If a more realistic relay is wanted later,
the KiCad SRD STEP can be imported for that one part and credited as CC BY-SA 4.0.

---

## 9. Open questions / unverified

- Torque Pro alarm dialog labels; Haltech NSP generic output fields; the exact Link GP output logic expression for
  hysteresis (§1.3).
- Whether the user's head units keep USB power during ACC sleep (FYT/Teyes behaviour varies). Decides whether "lights follow
  ACC" works by power alone and whether a USB-powered box can ever run a turbo timer (it can't if power drops).
- Mega 2560 stk500v2 bootloader and the watchdog (safe or loops).
- The SRD relay's contact rating and life (10 A vs 15 A, 28 vs 30 VDC across listings; the 10⁵/10⁷ life figures).
- PWM input frequency of OEM/brushless radiator fans (50-200 Hz from one aftermarket spec, ~100 Hz from a forum report).
- Download login on Printables and Thingiverse; file formats of the Printables candidates.

---

## 10. Sources

Editors and automations
- Blockly: https://developers.google.com/blockly
- ArduBlock: https://github.com/taweili/ardublock ; mBlock: https://mblock.cc
- Tinkercad Circuits: https://www.tinkercad.com/circuits
- XOD: https://xod.io/docs/
- Node-RED hysteresis nodes: https://flows.nodered.org/node/node-red-contrib-hysteresis , https://flows.nodered.org/node/node-red-contrib-threshold-hysteresis
- Arduino Cloud triggers: https://docs.arduino.cc/arduino-cloud/features/triggers
- Home Assistant: automation modes https://www.home-assistant.io/docs/automation/modes/ , editor https://www.home-assistant.io/docs/automation/editor/ , triggers https://www.home-assistant.io/docs/automation/trigger/ , numeric state https://www.home-assistant.io/triggers/numeric_state/ , threshold helper https://www.home-assistant.io/integrations/threshold/ , generic thermostat https://www.home-assistant.io/integrations/generic_thermostat/
- Tasker user guide: https://tasker.joaoapps.com/userguide/en/
- Apple Shortcuts personal automations: https://support.apple.com/guide/shortcuts/welcome/ios

ECU and logger outputs
- Link G4+ GP output tutorial (HP Academy): https://www.hpacademy.com/courses/link-g4-plus-software-tutorial/setting-up-auxiliary-outputs-general-purpose-outputs
- Link forum, hysteresis via conditions 1 and 3: https://forums.linkecu.com/topic/20585-relay-system-for-clutched-supercharger-pulley/ , https://forums.linkecu.com/topic/12743-autotrans-fan/
- Link PDM GP Logic: https://kb.linkecu.com/pdm-kb/gp-logic
- MS3Pro programmable on/off outputs: https://www.diyautotune.com/support/ms3pro-programmable-on-off-outputs-basic/
- MegaSquirt forum on hysteresis vs time delay: https://msefi.com/viewtopic.php?p=248631
- Speeduino `reference/speeduino.ini` (page 13, programmable outputs): https://github.com/speeduino/speeduino/blob/master/reference/speeduino.ini
- AiM RS3 status variables FAQ: https://support.aimshop.com/faq/software/rs3/pdf/Status.pdf
- RaceCapture Lua `setGpio` and GPIO output: https://wiki.autosportlabs.com/RaceCapturePro_LuaScripting , https://wiki.autosportlabs.com/GPIO_Output

Hardware and safety
- ATmega328P pin current limits: https://www.arduino.cc/playground/Main/ArduinoPinCurrentLimitations/
- Songle SRD-05VDC-SL-C ratings: https://lcsc.com/product-detail/Power-Relays_Ningbo-Songle-Relay-SRD-05VDC-SL-C_C35449.html , https://forum.digikey.com/t/songle-relay-srd-05vdc-sl-c/5775
- Relay module active low, JD-VCC: https://forum.arduino.cc/t/relay-module-wiring/78865 , https://basic-starter-kit-for-arduino-uno-r4-wifi.readthedocs.io/en/latest/Components_Kit/component_relay.html , https://www.okaphone.nl/files/RELMOD-2V.pdf
- Bootloader blinks pin 13: https://forum.arduino.cc/t/bootloader-flashing-pins-0-1-13/367311
- Old Nano bootloader and the watchdog loop: https://forum.arduino.cc/t/infinite-watchdog-reset-loop-with-pro-mini-and-atmegaboot-bootloader/451259 , https://github.com/arduino/Arduino/issues/4492
- Brushless fan PWM input 50-200 Hz, full speed on signal loss: https://www.turbozentrum.de/High-Performance-Motorsport-Brushless-Fan-30cm-12-Puller-1470-CFM-PWM-Control ; OEM fan ~100 Hz (forum): https://lateral-g.net/forums/showthread.php?p=686596
- DTR reset in this app: `app/src/main/java/kg/forester/sg/service/SensorBoxLink.kt` (`port.dtr = true`); flashing and bootloaders: `docs/knowledge/arduino-flashing.md`

3D models and licences
- KiCad packages3D and licence: https://gitlab.com/kicad/libraries/kicad-packages3D (LICENSE.md); footprints: https://gitlab.com/kicad/libraries/kicad-footprints (Module.pretty)
- Arduino product pages with CAD ZIPs: https://docs.arduino.cc/hardware/nano/ , https://docs.arduino.cc/hardware/uno-rev3/ , https://docs.arduino.cc/hardware/mega-2560/
- SparkFun Pro Micro: https://github.com/sparkfun/Pro_Micro
- Adafruit CAD Parts (MIT): https://github.com/adafruit/Adafruit_CAD_Parts
- GrabCAD terms: https://grabcad.com/terms , https://help.grabcad.com/article/246-how-can-models-be-used-and-shared
- CC BY-SA 4.0 and GPLv3 compatibility: https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses
- Sketchfab and Printables model pages: see the table in §8.1
