# SensorBox UX review: «Доп датчики» tab, wiring diagrams, outputs

Scope: Настройки → «Доп датчики» (`ui/SensorBoxUi.kt`, `ui/RelaysUi.kt`) on a landscape head unit, 1280x720 or 1024x600
at about 1.0 density (the 430 dp left panel is 430 px wide in the screenshots). It is used at arm's length, mostly while
parked, by drivers who have never soldered. The review looks at the current screenshots and code and borrows from the
tools beginners already use for hardware setup.

Review date: 2026-10. Screenshots 16 (flash guide) and 17 (VDO detail) come from an older build. 16 has no
«Прошить прямо с магнитолы» card and 17 has a drawn board instead of the real top view. Where a screenshot and the
current code disagree, the critique follows the code. Effort estimates: **S** is up to 2 hours, **M** is half a day to a
day, **L** is 2 to 3 days.

Short answers:
- **The biggest problem is the left column.** It holds the status, board choice, a 3D model, flashing, 10 inputs, an
  example button, a stray hint, outputs and a red panic button. That is about 1500 dp in a viewport of about 510 dp, so
  three screens. In every screenshot except 16 it is scrolled to the bottom, and the connection status, the board and
  the step numbers are out of view.
- **There are two numbering systems on one screen.** The left column has «1. Какая у вас плата … 4. Выходы». The intro
  on the right has «Три шага» (Купите, Залейте, Подключите). They mean different things.
- **The wiring picture is the best part.** It shows the real top view, coloured wires and nested lanes, which is better
  than most hobby guides. The diagram is still read-only, though. It is not linked to the numbered steps, it does not
  show the sensor faults the app already detects (обрыв, замыкание), and its labels are 8 to 9.5 sp.
- **Text and touch targets are too small for a car.** Subtitles are 11 sp, canvas labels 8 to 9.5 sp, chips about 40
  dp tall, the rule trash icon 20 dp and the colour dots 34 dp. Android for Cars asks for 76 dp targets and 24 dp
  secondary text. Not all of that fits a settings screen, but the current values are far below it.
- **Some destructive actions happen without a confirmation or undo.** These are «Заполнить примером», changing the
  board (it drops the sensors on pins the new board lacks), «Убрать» on an input or output, and «Всё выключить», which
  sits right under «Добавить выход».
- **Recommended flow:** a non-linear stepper «Плата → Прошивка → Датчики → Выходы → Проверка» in the left column, with
  a status line on each step. It works as a wizard on the first visit and as tabs after that. Add a new «Проверка»
  step: every input with its live value and fault, and every output with a test pulse.

---

## Contents

1. [Critique of the current screens](#1-critique-of-the-current-screens)
2. [Prioritised improvements](#2-prioritised-improvements)
3. [Proposed overall flow](#3-proposed-overall-flow)
4. [Best practices from other tools](#4-best-practices-from-other-tools)
5. [Naming: one word per thing](#5-naming-one-word-per-thing)
6. [Sources](#6-sources)

---

## 1. Critique of the current screens

### 1.1 Left column (`SensorBoxSettings`, `BoxLeft`, `InputRow`, `OutputsSection`): screenshots 01–06, 16

| # | Problem | Where |
|---|---------|-------|
| L1 | **Too long, with no sense of where you are.** It contains: status card, switch, «1. Какая у вас плата» with 4 chips, a 150 dp 3D model and a description, «2. Залить прошивку», «3. Что подключено к входам» with 10 rows on a Nano, «Заполнить примером», a hint, «4. Выходы» with its rows, «Добавить выход» and «Всё выключить». On a Nano the first input row sits at y≈550 of 640 (16). Every other screenshot is scrolled to the bottom, so the user never sees the status and the step headings together with the inputs. | `BoxLeft`, `Panel(...verticalScroll(rememberScrollState()))` in `SensorBoxSettings` |
| L2 | **The connection status scrolls away.** «Демо: плата придумана» / «Плата на связи» / «Ищу плату на USB…» is the most important line on the tab, and it is visible only in 16. When a sensor shows «—», the user cannot see why. | status `Row` at the top of `BoxLeft` |
| L3 | **The switch contradicts the status.** In 16 the status says «Демо: плата придумана, значения идут…» while «Доп датчики включены» is off. A beginner reads that as "it is off, but it works". The unchecked switch is also a bright white track (only `checkedTrackColor` is set), the brightest element in the panel. | `Switch(on, …)` in `BoxLeft` |
| L4 | **Board choice takes 260 dp after it has been made.** It is chosen once and rarely changed, yet it keeps 4 chips, a 150 dp model and a description at the top of the column. | `FlowRow(BoxBoard…)`, `Model3D(board3d(board)…)` |
| L5 | **Changing the board is silently destructive.** `SensorBox.setBoard` drops every slot whose input the new board lacks (Mega → Nano loses A8–A15) with no warning. | `Chip(b.title…) { SensorBox.setBoard(b) }` |
| L6 | **The numbering does not match.** The column has «1. … 2. … 3. … 4.», and `BoxIntro` on the right has «Три шага» 1–3 with different meanings. «2. Залить прошивку» is a navigation row, while 1, 3 and 4 are headings. | `BoxLeft`, `BoxIntro` |
| L7 | **«Заполнить примером» is in the wrong place and is destructive.** It sits between the inputs and the outputs, is styled like a primary action, and `fillExample()` overwrites A0, A1, A2 and TC0 with no confirmation, including on a real board. It is useful only in demo or before anything is set up. | the `Sparkles` row in `BoxLeft` |
| L8 | **A stray hint.** «Значения доп датчиков можно вывести на главный экран (плитки «Датчик 1–6»)…» sits at 11 sp between two sections and belongs to nothing. The same action already exists as a chip in `SlotSettings`. | `T("Значения доп датчиков можно вывести…")` |
| L9 | **The input row hides that some sensors use several pins.** A thermocouple shows only «D10» but uses D10, D12 and D13. Free rows show «свободен / аналоговый вход 0–5 В» at 13 and 11 sp in grey, so 4–6 near-identical grey rows add up to 300 dp of noise. | `InputRow` |
| L10 | **Faults are hard to see.** `r.problem` is drawn at 11 sp orange in a 110 dp box at the far right of the row. The row has no icon and no tinted border, so an open-circuit sensor looks almost like a healthy one at arm's length. | `InputRow`, the `r?.problem != null` branch |
| L11 | **«Всё выключить» is a red, full-width toggle directly under «Добавить выход».** It is easy to hit by mistake, and its label changes to a long sentence when active. A panic control belongs in a fixed, predictable place, or on the outputs step header. | `OutputsSection` |
| L12 | **«4. Выходы» repeats the firmware warning far from the fix.** «Для выходов нужна прошивка платы версии 3: обновите её в пункте 2» refers to step 2, which is scrolled out of view. | `OutputsSection` |

### 1.2 Right panel, nothing selected (`BoxIntro`, `BoardOverview`, `PinTable`): screenshots 01–04

| # | Problem | Where |
|---|---------|-------|
| R1 | **Experts and novices get the same page, in the wrong order for both.** With slots set up, the page opens with «Ваша плата целиком» and the pin table, then «Как это работает» and «Три шага». A configured user scrolls past the explanation every time. A brand-new user with demo on (01) sees a dense diagram before learning what the box is. | `BoxIntro` |
| R2 | **The overview labels are 9 sp on a busy picture.** In 01, «Температура выхлопа · SO/CS/SCK», «Выход 1: Доп. вентилятор» and the terminal block rows are readable only up close. The `+5V: клеммник` / `GND: клеммник` titles are 9 sp bold. | `drawOverview`, `text(…, 9f, …)` |
| R3 | **The pin table rows for 5V and GND are paragraphs.** In 02: «в клеммник +5V, а к нему: питание Давление масла 0–10 бар; питание Потенциометр (крутилка); питание Температура выхлопа; резистор 3,3 кОм на D2 (Датчик этанола (flex fuel))». These should be lists, and the names already appear in the picture. | `PinTable` |
| R4 | **The +12 V feed is in the table but not in the picture.** The «+12 В» row (purple) has no counterpart in `drawOverview`, so the ethanol sensor's power is invisible on the drawing. | `PinTable` vs `drawOverview` |
| R5 | **The flow diagram overflows on 1024x600.** 4 × 118 dp boxes plus 3 arrows is about 560 dp in a plain `Row`, but the right panel on 1024 wide is about 420 dp of content. | `BoxIntro` `Row(FlowBox…FlowArrow…)` |
| R6 | **The demo hint gives a path instead of a button.** «Включите демо (Настройки, Авто и адаптер, Демо-данные)» makes the user leave the tab, find a switch and come back. | `Hint(Lucide.Lightbulb, …)` in `BoxIntro` |
| R7 | **Small, low-contrast text.** `FlowBox` subtitles are 10 sp, `FlowArrow` labels 9 sp, and hints 12 sp `C.Sub` (#8E95AB on #111522, contrast ≈ 5.6:1, fine for contrast but small). | `FlowBox`, `FlowArrow`, `Hint` |

### 1.3 Choosing a sensor (`PresetGallery`): screenshot 05

| # | Problem | Where |
|---|---------|-------|
| G1 | **39 cards in one long scroll with no way to jump.** The group headings are 12 sp grey and look like captions. There is no search, no "most common first" and no "I do not know mine". | `PresetGallery` |
| G2 | **Fixed 250 dp cards leave a dead third column.** At 1280 the cards fill two columns and about 170 dp stays empty. At 1024 they fall to one column. | `Modifier.width(250.dp)` |
| G3 | **The scroll is not reset after choosing.** The right panel's scroll is keyed on `selected`, but `choosing` is local state inside `InputDetails`. A user who picks a sensor from the «Выхлоп» group near the bottom lands in the middle of the new details page: the wiring or settings, not the header and live value. | `SensorBoxSettings` `key(selected, flashGuide)` plus `InputDetails` `choosing` |
| G4 | **No path back once a free input is tapped.** The only way out is to tap something else on the left. «Оставить …» appears only when there is already a sensor. | `InputDetails` |
| G5 | **Titles mix brand and function.** «Температура масла, Bosch M12-H» is inside the group «Температура масла», which repeats the group name. The card could lead with «Bosch M12-H». | preset titles in the catalogue |

### 1.4 One input (`InputDetails`, `LiveValue`, `WiringPicture`, `WiringSteps`, `SlotSettings`): screenshots 14, 17

| # | Problem | Where |
|---|---------|-------|
| D1 | **The picture and the steps are not linked.** `WireStep` numbers its rows (1–6, with the wire colour), but the wires in the picture have no numbers. Tapping a step or a wire does nothing. With 5 wires (thermocouple, 14) the user has to match colours by eye. | `WiringPicture` / `WiringSteps` |
| D2 | **Faults the app detects never reach the drawing.** `SensorBox.read` knows «обрыв: нет сигнала», «замыкание на 5 В», «замыкание на массу», «обрыв термопары» and «нет модуля MAX31855». `LiveValue` says «Проверьте провод сигнала и массу по схеме ниже», but the diagram shows nothing different. | `LiveValue`, `drawWiring` |
| D3 | **Violet has five meanings.** It is the app accent and selection colour (`C.Purple`/`C.Violet`), "pin taken by another sensor" (`TAKEN = 0xFFA78BFA`), one of the `SENSOR_COLORS` (the same 0xFFA78BFA), the +12 V wire (`WIRE_PURPLE`) and the outline of the «Выход 1» tag. In 14, violet "taken" pins sit next to green and blue signal wires and read as more wires. | `TAKEN`, `SENSOR_COLORS`, `WIRE_PURPLE` |
| D4 | **The GND wire is grey, not black.** `WIRE_BLACK = 0xFF9CA3AF` is needed on a dark background, but the real wire is black, and the legend never says «на схеме серый». | `WIRE_BLACK` |
| D5 | **Canvas text is 8–9.5 sp.** This covers «фиолетовые пины уже заняты…», «USB к магнитоле», «резистор 220 Ом, 0,5 Вт», terminal labels and «1/8 NPT». | `text(...)` / `vtext(...)` calls in `drawBoard`, `drawPins`, `drawWiring` |
| D6 | **Step text is long and repeats.** For example: «GND модуля → клеммник GND (общий с: Давление масла 0–10 бар, Датчик этанола (flex fuel), Свой датчик 0–5 В, Потенциометр (крутилка))», then step 6 explains the terminal blocks again in three lines. | `WiringSteps` |
| D7 | **The page is very tall, and the order is "wire it, then what to buy, then settings".** Header, 3D module (170 dp for TC), live value, picture (280–336 dp), up to 7 steps, tips, «Что купить», then «Настройка» with warn thresholds and the role chips. The role chips matter: oil guard and the main screen read them. A novice still buying parts has to scroll past the wiring to «Что купить». | `InputDetails` |
| D8 | **The header actions are adjacent.** «Другой датчик» and «Убрать» are chips of equal weight. «Убрать» deletes immediately with no undo. | `InputDetails` header `Row` |
| D9 | **Little room on 1024x600.** The board gets 52% of about 420 dp, roughly 220 dp wide, so the header pitch is about 12 dp and the 9.5 sp rotated labels collide. The module image takes another 36%. | `drawBoard(…, 0.52f, …)`, `mw = w * 0.36f` |
| D10 | **«Сейчас» has no "is it moving?" test.** The value is live, but nothing tells the user to warm the sensor or press on it and watch the number change, which is the quickest way to check the wiring. | `LiveValue` |

### 1.5 Outputs (`OutputNew`, `OutputDetails`, `RuleCard`, `ThresholdRow`): screenshots 10–12, 15

| # | Problem | Where |
|---|---------|-------|
| O1 | **The output page mixes four jobs in one scroll.** Status and manual control, rules, wiring, then name, widget, button look, «Мой экран», pin, load type, active-low, three timers and link-lost behaviour. In 12 the 3D relay is cut off at the bottom, and the safety settings (minimum and maximum times, «Если связи с магнитолой нет…») are after the button colours. | `OutputDetails` |
| O2 | **The «выше/ниже» chip does not look clickable.** It is a `Chip(selected = false)` that flips on tap, so it reads as an unselected option next to the selected source chip. | `ThresholdRow` |
| O3 | **The delay wording is cryptic.** «и это держится 0 с» and «после этого ещё 0 с» (10) are hard to parse. The first is an on-delay, the second an off-delay. | `RuleCard` `StepperF` labels |
| O4 | **Bad defaults are allowed, then flagged.** In 10 the rule is "on above 86, off below 86" and only a small orange «слишком близко: будет щёлкать» at 11 sp warns about it. | `ThresholdRow` |
| O5 | **Manual «Включить» is sticky with no reminder.** It shows only as «· вручную» at 11 sp in the left row. A fan or pump left on by hand can stay on for days. | `OutputDetails` «Управление», `OutputsSection` |
| O6 | **Invalid choices are allowed, then explained.** «Ползунок» and «Крутилка» can be picked for a relay, and then an orange line says «Плавно умеет только MOSFET-модуль…» (12). On a non-PWM pin the same happens. | `OutputDetails` widget chips |
| O7 | **Taken pins fail silently.** «D4 (занят)» is a normal chip that does nothing on tap. | «Пин платы» `FlowRow` |
| O8 | **Jargon.** «Модуль включается низким уровнем» with «вход IN, на плате оптрон» means little to a non-solderer. «Твердотельное реле DC» is not explained. | `OutputDetails` |
| O9 | **Only the control side of the relay is drawn.** In 11 the module's IN/VCC/GND are wired, but the load side (COM/NO → car relay 86/85 or the fan, fuse, +12 V) exists only in a 4-line tip. That side carries 20–40 A and is where mistakes are dangerous. The wires also overlap at the module terminals, and the module image is clipped by the floating assistant button. | `outPreset(LoadKind.RELAY)`, `drawWiring` |
| O10 | **Small targets in rule cards.** The trash icon is a bare 20 dp `Icon.clickable` next to a `Switch`. «Убрать условие» is 22 dp. The `Stepper` buttons are 40 dp. | `RuleCard`, `ThresholdRow`, `Stepper` |
| O11 | **Templates.** The cards (15) are good, but the dead third column (260 dp cards), the «Займёт выход 1 на пине D3» note and the Nano reset warning sit under the cards at 12 sp, after the decision is already made. | `OutputNew` |

### 1.6 Button look, screen widgets (`ButtonLookDialog`, `OutputWidget`, `Knob`): screenshots 13, 18, 19

| # | Problem | Where |
|---|---------|-------|
| W1 | **Two rows of 19 colour dots at 34 dp** take half the dialog (13). Most users change one colour. | `ColorRow` ×2 |
| W2 | **Icon tiles have 11 sp labels** and no search. 8 groups × 8 icons is fine as it is. | `ButtonLookDialog` |
| W3 | **The widget mode label is 11 sp** («авто» / «вручную») and says «авто», while the output page says «По правилам». | `OutputWidget` |
| W4 | **The knob relies on a precise drag on a 270° arc.** Tapping the centre toggles power, but there is no hint and no ±10 % buttons, which are easier to hit at arm's length. In 19 the AFR gauge's glow spills over the knob card (a layout issue on «Мой экран»). | `Knob` |

### 1.7 Flashing (`FlashGuide`, `FlashHere`): screenshot 16 (old), current code

| # | Problem | Where |
|---|---------|-------|
| F1 | **The main action is a `Chip`.** «Прошить Nano» has the same weight as «Сохранить прошивку sensorbox.ino». It should be the one big primary button on the page. | `FlashHere` |
| F2 | **There is no pre-check.** The page does not say whether a board is on USB right now, which board was detected, or whether the selected board matches. That warning lives in the left column (L5). | `FlashHere` |
| F3 | **No next step after «Готово».** On success the green text just sits there. There is no «Дальше: подключить датчики». | `FlashState.Done` branch |
| F4 | **The PC route is as prominent as the head-unit route.** It is 7 steps plus troubleshooting at full size, but it is a fallback. It should be collapsed under «Не получается с магнитолы? Прошить с компьютера». | `FlashGuide` |

### 1.8 Cross-cutting

- **Text sizes.** 11 sp text appears 27 times across the two files, plus 9–10 sp in `FlowBox`, `FlowArrow` and
  the `Knob` «потенциометр» label. At about 1 dp = 1 px on a 9–10" 1280x720 panel (about 145 ppi, so 1 dp ≈ 0.175 mm),
  11 sp is a 1.4 mm cap height. At 70 cm that is under 7 arcmin. In-car legibility standards (ISO 15008) are written
  for a moving car and ask for much more. Even parked, 13 sp should be the floor.
- **Touch targets.** `Chip` is about 40 dp tall (10 + 20 + 10), `ColorRow` dots 34 dp, the trash icon 20 dp, and the
  `BuyInfo` links are 11 sp text with 2 dp padding. Android for Cars specifies 76 × 76 dp. For a parked-use settings
  screen, 48 dp minimum and 56–64 dp for primary actions is a realistic target.
- **No undo anywhere.** This covers input «Убрать», output «Убрать», rule trash, «Заполнить примером» and board change.
- **Saves are instant and invisible.** Every `BoxField` keystroke calls `save()`, which is fine, but nothing ever says
  «Сохранено». Users used to a «Сохранить» button may look for one.

---

## 2. Prioritised improvements

Each item names the composable, the change, the exact Russian wording and the effort. They are ordered by
value ÷ effort within each priority.

### P1: do first (biggest effect, fits the current structure)

**P1-1. Pin the status to the top of the left column (S).**
In `SensorBoxSettings`, change the left `Panel` to a `Column` with a non-scrolling header followed by the scrolling
body (`Modifier.weight(1f).verticalScroll(...)`). Move the status `Row` and the switch into the header as one line:
- found: «● Плата на связи · Arduino Nano · прошивка 3»
- demo: «● Демо: плата придумана» with a small chip «Выйти из демо»
- looking: «● Ищу плату на USB…»
- off: «○ Выключено» plus the switch labelled «Искать плату на USB»

Hide the switch row while demo is on, or label it «Настоящая плата: искать на USB». Set the unchecked switch colours
(`uncheckedTrackColor = C.CardHi`, `uncheckedThumbColor = C.Sub`).

**P1-2. Collapse the board section once a board is chosen or detected (S).**
In `BoxLeft`, show one row «Плата: Arduino Nano» with a «Сменить» chip, expanded only on tap or when nothing has been
chosen yet. Show `Model3D(board3d(board))` only in the expanded state. This saves about 260 dp.
When `info.board != board.id`, make the row orange: «Подключена Pro Micro, а выбрана Nano. [Выбрать Pro Micro]».

**P1-3. Confirm or undo destructive actions (S–M).**
- `setBoard` when some slots would be dropped: an `AlertDialog` titled «Сменить плату на Arduino Nano?» with the text
  «На Nano нет входов A8, A9, A12. Эти датчики будут убраны: Давление топлива, Наддув.» and the buttons «Сменить» /
  «Отмена».
- «Заполнить примером» when any of A0–A2/TC0 is set: «Заменить датчики на A0, A1, A2 и выхлоп примером? Ваши настройки
  на этих входах пропадут.» with «Заменить» / «Отмена». Better still, show the button only when `slots` is empty or demo
  is on (see P1-6).
- Input and output «Убрать», and the rule trash: no dialog, but an undo snackbar for 6 s: «Датчик «Давление масла» убран
  · Вернуть», «Выход «Доп. вентилятор» убран · Вернуть», «Правило удалено · Вернуть». Keep the removed `BoxSlot` /
  `OutChannel` / `OutRule` in a `remember` and put it back on tap.

**P1-4. Reset the right panel's scroll after choosing a sensor (S).**
In `SensorBoxSettings`, hoist the scroll state: `val rs = rememberScrollState()` inside the `key`, pass it to
`InputDetails`, and call `LaunchedEffect(slot?.preset) { rs.scrollTo(0) }`. Or key the `Panel` on
`selected to SensorBox.slot(sel)?.preset`. After picking «Термопара K» the user should land on the header and «Сейчас».

**P1-5. Show faults on the diagram and in the row (M).**
- `InputRow`: when `r.problem != null`, give the row an orange border (`C.Orange`), a `Lucide.TriangleAlert` icon in
  place of the value, and the problem in 13 sp, two lines. Use the short texts «Обрыв?», «Замыкание на массу»,
  «Замыкание на +5 В», «Нет модуля».
- `WiringPicture` gets the reading as a new parameter, `r: BoxReading?`. In `drawWiring`:
  - «обрыв…»: draw the signal wire (and for NTC/RES the GND wire) dashed (`PathEffect.dashPathEffect`) in `C.Orange`, put
    a tag at the wire's middle «Обрыв? Проверьте этот провод», and pulse the pin ring.
  - «замыкание на массу»: signal and GND wires orange, tag «Сигнал касается массы».
  - «замыкание на 5 В»: signal and +5V wires orange, tag «Сигнал касается +5 В».
  - TC faults: tag the probe «Термопара: обрыв» or «Термопара касается массы (MAX31855 так не умеет)».
- Draw the live value at the sensor end, for example «1,80 В · 123 Ом», so that the picture itself is the test.

**P1-6. Reorder `BoxIntro` and tidy the left column (S).**
- Nothing set up (`mine.isEmpty() && outs.isEmpty()`): «Как это работает» → «Три шага» → hints. The demo hint becomes a
  button: «Посмотреть на примере» (turns demo on with `Repo` and calls `fillExample()`).
- Something set up: «Ваша плата целиком» → pin table, with «Как это работает» collapsed under a row «Как это
  работает ›».
- Remove the stray hint L8 from `BoxLeft`. The «Показать на главном экране» chip in `SlotSettings` already covers it.
- Move «Заполнить примером» out of the left column into `BoxIntro` (as above), shown only while nothing is set up.
- Replace `Row(FlowBox…)` with a `FlowRow` so it wraps on 1024x600.
- Rename «Три шага» → «С чего начать», so its numbers do not clash with the left column's steps.

**P1-7. Raise text and target sizes (S).**
- `T` calls at 11 sp → 13 sp, and 10 / 9 sp → 12 sp (in `InputRow`, `FlowBox`, `FlowArrow`, `OutputsSection`,
  `RuleCard`, `Knob`, `OutputWidget`).
- Canvas labels: the `text()` and `vtext()` minimum goes from 8–9.5 to 11 sp, pin labels to 11 sp bold, and terminal
  block titles to 12 sp. Increase the `BoardOverview` row `pitch` from 18 to 22 dp to fit.
- `Chip`: `padding(vertical = 14.dp)` gives about 48 dp. `RuleCard` trash and `ThresholdRow` X go inside
  `Box(Modifier.size(48.dp))`. `Stepper`/`StepperF` boxes go from 40 to 48 dp. `ColorRow` dots go from 34 to 44 dp.
  The `BuyInfo` links get `padding(vertical = 10.dp)`.

**P1-8. Make the colours mean one thing each (S).**
- Reserve **red = +5 V**, **black = GND**, **purple = +12 V of the car**. Take these out of `SENSOR_COLORS`
  (0xFFA78BFA violet, 0xFFFB7185 rose) and replace them with sky 0xFF38BDF8 and amber 0xFFFBBF24 or similar.
- Draw GND as a **black core with a light 1 dp outline** (`drawPath` black 3 dp over `0xFF9CA3AF` 5 dp), so it looks like
  the real black wire and stays visible.
- "Taken" pins: change from violet to **a grey ring with a diagonal strike** (`0xFF64748B`). Legend:
  «серые зачёркнутые пины заняты другими датчиками».
- Mention the thermocouple colour pitfall in `p.tips` for TC: «У провода термопары K красный это минус, жёлтый плюс:
  не путайте с красным +5 В. Если температура падает при нагреве, поменяйте провода местами».

**P1-9. Make flashing a single, obvious action (S).**
In `FlashHere`, replace the `Chip` with a full-width 56 dp button «Прошить плату с магнитолы» (accent fill). Above it,
add a pre-check line:
- «Плата на USB: Arduino Nano, прошивка 2. Будет записана версия 3.»
- or, orange, «Плата не видна на USB. Вставьте её в USB магнитолы и включите «Искать плату на USB».» with the button
  disabled.

On `FlashState.Done`, show a green card «Готово: плата прошита, версия 3» and a button «Дальше: подключить датчики»,
which sets `flashGuide = false` and opens the first free input or the sensors step. In `FlashGuide`, collapse «Или с
компьютера» and its 7 steps under a row «Не получается с магнитолы? Прошить с компьютера ›».

### P2: next (structure and novice flow)

**P2-1. Left column as a non-linear stepper (L).** See [section 3](#3-proposed-overall-flow). This replaces P1-2/P1-6
partially. Build it on the P1-1 header.

**P2-2. Link the steps and the picture (M).**
- In `drawWiring`, draw a 16 dp numbered badge in the wire colour at each wire's middle or at its turn (`turnX`,
  `laneY`) with the same `n` as `WireStep`.
- Make `WireStep` a checkable row (48 dp): «☐ 1. Сигнал датчика (жёлтый) → пин A6». Tapping it highlights that wire
  (others drawn at 35% alpha) and marks it done. Store the done set per input in `remember(input)`; persisting it can
  be P3.
- When all are checked: «Всё подключено? Проверьте: значение «Сейчас» должно быть похоже на правду» and scroll to
  `LiveValue`.

**P2-3. Make the «Проверка» screen a real step (M–L).**
Add a new `BoxCheck()` composable for the right panel. Its left entry is «Проверка». It has three blocks:
1. «Связь»: board, firmware version, «данные идут: 10 раз в секунду» (from frame rate), USB path.
2. «Датчики»: one row per slot with the pin, name, value, raw value («1,80 В») and status: «✓ в порядке», «⚠ обрыв?»,
   «⚠ замыкание», «— жду данные». Each row carries a hint for the live test:
   - NTC: «Зажмите датчик в руке: температура должна подняться на 5–10 °C.»
   - pressure: «Заведите мотор: давление масла должно вырасти до 1–4 бар.»
   - TC: «Поднесите зажигалку к щупу: значение должно расти.»
   - potentiometer: «Покрутите ручку: проценты должны меняться от 0 до 100.»

   Optionally, a «Изменилось ✓» badge appears when the value moves by more than 3 steps in 20 s.
3. «Выходы»: per output, «Проверить 1 с». After the pulse, ask «Щёлкнуло реле и включилась нагрузка?» with «Да» / «Нет,
   не щёлкнуло» / «Щёлкнуло, но нагрузка не включилась». Each «Нет» answer leads to a short check list, for example «Не
   щёлкнуло: проверьте VCC и GND модуля и переключатель «Модуль включается низким уровнем»».

**P2-4. Sensor gallery: jump, sort, and "don't know" (M).**
In `PresetGallery`:
- Add a row of chips at the top with the groups present («Масло t», «Масло давл.», «Антифриз/АКПП», «Впуск»,
  «Наддув», «Топливо», «ШДК», «Выхлоп», «Ток», «Тормоза», «Своё»). Tapping one scrolls to the group header
  (`bringIntoViewRequester`).
- Make group headers 15 sp semibold `C.Text`.
- Card width: `Modifier.fillMaxWidth(0.5f)` minus the gap, in a `FlowRow` with `maxItemsInEachRow = 2` (or 3 at
  ≥ 900 dp), so nothing is left dead.
- Card title without the group prefix: «Bosch M12-H», with the subtitle «Bosch 0 281 002 170, M12×1,5, до 150 °C».
- Put a «Не знаю, какой у меня» card first in each group. It selects the most common one for that group and shows
  «Мы выбрали самый частый вариант. Если показания странные, нажмите «Другой датчик».»
- Add a back chip at the top: «‹ Назад» (sets `selected = null`).

**P2-5. Split `OutputDetails` into collapsible sections (M).**
Use a small `Section(title, open)` composable (title row 48 dp with a chevron). The order:
1. Status card and «Управление» (always open).
2. «Когда включать» (open).
3. «Как подключить» (open until the user checks all wires, then collapsed).
4. «На экране»: name, widget, button look, «Показать на «Мой экран»» (collapsed).
5. «Пин и модуль»: pin, load type, active-low (collapsed).
6. «Защита»: min/max times, «Если связи с магнитолой нет» (collapsed, with an orange dot when a dangerous combination
   is set, for example pump + «включить»).

**P2-6. Rule sentence wording (S).**
In `ThresholdRow` and `RuleCard`:
- «выше/ниже» becomes a two-segment control «[выше | ниже]», using two `Chip`s with `selected` set.
- «и это держится N с» → «если держится дольше N с».
- «после этого ещё N с» → «и не выключать ещё N с».
- «Выключать, когда он ниже» → «Выключать, когда станет ниже».
- When the gap is too small, the `−` on the off stepper stops at `on − 2·step`, and the label reads «Нужен зазор хотя
  бы 2 °C, иначе реле будет щёлкать».
- «+ ещё условие (или)» → «+ Ещё случай, когда включать»; «+ и если…» → «+ И ещё условие»; «+ никогда не включать,
  если» → «+ Запрет (никогда не включать, если…)».

**P2-7. A reminder for manual mode (S).**
When `c.manual != AUTO`, show an orange banner above the rules: «Включено вручную 12 мин назад. Правила не действуют.
[Вернуть правила]». In `OutputsSection`, the lamp text becomes «ВКЛ · вручную» in orange. The widget label «авто»
becomes «по правилам».

**P2-8. Disable invalid choices up front (S).**
- Widget chips «Ползунок»/«Крутилка» are disabled (alpha 0.4) for RELAY/COIL12/SSR, with a caption under the row:
  «Плавная регулировка только с MOSFET-модулем или светодиодом на пинах D3, D5, D6, D9».
- Pin chips «D4 · занят: Шифтлайт» are disabled, and tapping one shows that text as a toast.

**P2-9. Draw the power side of the relay (M–L).**
Add a second canvas under the control picture, titled «Силовая часть: что включает реле». It shows the module's COM/NO,
then the car relay 86/85 and 30/87, then the fan or pump, plus a fuse on +12 V. Use the same colours (purple +12 V,
black mass) and the tip texts as numbered steps. Use the same approach for COIL12 (key, diode across 85/86).

**P2-10. Adapt the layout to 1024x600 (S).**
In `SensorBoxSettings`, use `BoxWithConstraints`: left column 430 dp when `maxWidth ≥ 1100.dp`, else 340 dp.
`drawBoard` `widthShare` goes from 0.52 to 0.6 when the canvas is narrower than 480 dp. Also add a «Во весь экран»
chip on the picture that opens it in a `Dialog` at full size (also P3-2).

**P2-11. Plain words for the jargon (S).**
- «Модуль включается низким уровнем» → «Реле работает наоборот» with the hint «Включите, если реле щёлкает, когда
  должно быть выключено. У большинства синих модулей реле так и есть.»
- `LoadKind` subs: «Твердотельное реле DC: без щелчков, для насосов и вентиляторов до 10 А».

### P3: polish

**P3-1. Tap a pin for details (M).** In `WiringPicture` and `BoardOverview`, add `pointerInput(detectTapGestures)` and
find the nearest `Pin` within 24 dp. A popup shows «A6 · аналоговый вход 0–5 В · занят: Давление масла VDO». For free
pins it shows «D5 · свободен · может управлять выходом, умеет плавно (ШИМ)». This is the pinout.xyz pattern on touch.

**P3-2. Zoom (M).** `Modifier.transformable` for pinch zoom on the wiring canvases, or the full-screen dialog from
P2-10.

**P3-3. Label both ends (M).** For module images (MAX31855, relay, SSR, MOSFET), draw a small tag at the module
terminal («SO», «IN», «3+») in the wire colour, so the user does not have to read the module's silkscreen. On the
board end the pin label exists already.

**P3-4. Show the +12 V feed in the overview (S).** In `drawOverview`, draw a short purple stub from the right edge to
the sensor's tag labelled «+12 В от машины, через предохранитель», matching the `PinTable` row.

**P3-5. Turn the pin table into lists (S).** In `PinTable`, show the 5V and GND rows as a heading plus one indented line
per consumer («→ Давление масла: питание», «→ D2: резистор 3,3 кОм»).

**P3-6. Simpler button look (S–M).** In `ButtonLookDialog`, use one colour row with a toggle «Цвет для: [включено]
[выключено]» to halve the height. Move the presets «Зелёный/серый», «Синий/серый», «Красный/серый» to the top.

**P3-7. A friendlier knob (S).** In `Knob`, add «−10 %» and «+10 %» 48 dp buttons under the arc, and a hint below the
widget on first use: «Тяните по кругу, нажмите в центр, чтобы включить или выключить».

**P3-8. Persist the wiring checklist and show «Собрано ✓» on `InputRow` (S).**

**P3-9. «Сохранить схему картинкой» (M).** Render `BoardOverview` plus `PinTable` to a PNG for sharing with a friend
who solders, or for printing.

**P3-10. A «Сохранено» indicator (S).** A short `C.Green` «Сохранено» next to the section title after a field changes.

### Summary table

| ID | Change | Composable | Effort |
|----|--------|------------|--------|
| P1-1 | Pinned status header | `SensorBoxSettings`, `BoxLeft` | S |
| P1-2 | Collapse board choice | `BoxLeft` | S |
| P1-3 | Confirm/undo destructive actions | `BoxLeft`, `InputDetails`, `OutputDetails`, `RuleCard` | S–M |
| P1-4 | Scroll to top after choosing | `SensorBoxSettings`, `InputDetails` | S |
| P1-5 | Faults on row and diagram | `InputRow`, `WiringPicture`/`drawWiring` | M |
| P1-6 | Intro order, tidy the left column | `BoxIntro`, `BoxLeft` | S |
| P1-7 | Text ≥ 13 sp, targets ≥ 48 dp | everywhere, `Chip` | S |
| P1-8 | Colour semantics | `SENSOR_COLORS`, `TAKEN`, `WIRE_BLACK` drawing | S |
| P1-9 | Flash: one primary button, pre-check, next step | `FlashHere`, `FlashGuide` | S |
| P2-1 | Stepper in the left column | `BoxLeft` | L |
| P2-2 | Numbered, tappable, checkable wires | `drawWiring`, `WiringSteps` | M |
| P2-3 | «Проверка» step | new `BoxCheck` | M–L |
| P2-4 | Gallery jump/sort/"don't know" | `PresetGallery` | M |
| P2-5 | Output page sections | `OutputDetails` | M |
| P2-6 | Rule wording | `RuleCard`, `ThresholdRow` | S |
| P2-7 | Manual-mode reminder | `OutputDetails`, `OutputsSection`, `OutputWidget` | S |
| P2-8 | Disable invalid widget/pin choices | `OutputDetails` | S |
| P2-9 | Relay power side drawing | `OutputDetails`, new canvas | M–L |
| P2-10 | 1024x600 layout | `SensorBoxSettings`, `drawBoard` | S |
| P2-11 | Plain words | `OutputDetails`, `LoadKind` | S |
| P3-1…10 | Pin popups, zoom, end labels, 12 V stub, lists, look dialog, knob, checklist persistence, export, «Сохранено» | various | S–M |

---

## 3. Proposed overall flow

### 3.1 Steps or tabs?

**Both: a non-linear stepper.** The first setup really is a sequence with dependencies: the board decides the pins,
the firmware must be on before anything reads, the sensors come before the rules that use them, and the check comes
last. That is staged disclosure, where a wizard helps. After setup, people come back to change one sensor or one rule,
in any order. That is random access, where tabs help. NN/g notes that staged disclosure works for steps with little
interdependence and fails when users must go back and forth. Wiring and choosing a sensor are back-and-forth, so every
step must stay clickable, and the stepper must never block.

So the left column shows five step rows. Each has a number, a title and a live status line. Completed steps get a ✓
and stay clickable, and the active step expands its list (inputs or outputs) under it. On the first visit the first
unfinished step is open. Later the last used step is open.

### 3.2 The left column (about 400–480 dp tall at 1280x720, no scrolling for the steps themselves)

```
┌ Доп датчики ─────────────────────────────── (i) ┐
│ ● Плата на связи · Nano · прошивка 3            │  ← pinned header (P1-1)
├─────────────────────────────────────────────────┤
│ ✓ 1  Плата            Arduino Nano          ›   │
│ ✓ 2  Прошивка         версия 3, свежая      ›   │
│ ▾ 3  Датчики          4 из 10 · ⚠ 1 обрыв       │
│      ┌ A0  Температура масла       90 °C ┐      │
│      ├ A1  Давление масла         1,4 бар┤      │
│      ├ A6  ⚠ Давление VDO          Обрыв?┤      │
│      ├ D10 Выхлоп (D10 D12 D13)   548 °C┤      │
│      └ + Добавить датчик (6 входов свободно)    │  ← free inputs collapsed into one row
│   4  Выходы           1 · вентилятор ВКЛ    ›   │
│   5  Проверка         2 вопроса             ›   │
└─────────────────────────────────────────────────┘
```

Changes from today:
- Free inputs are no longer 6 grey rows. They become one row «+ Добавить датчик (6 входов свободно)». It opens the
  gallery with an input picker at the top: «Куда: [A2] [A3] [A4] [A5] [A7]», preselecting the first free pin that suits
  the sensor (for example, a thermocouple always goes to TC0, flex fuel to F0). If the user wants a specific pin, they
  tap it in the picker. This removes about 300 dp.
- Multi-pin sensors show all their pins: «D10 Выхлоп (D10 D12 D13)».
- «Всё выключить» moves to the header of step 4 as a red icon button «Всё выкл» (48 dp), with a confirmation:
  «Выключить все выходы? Правила не будут работать, пока не вернёте.»
- When all steps are ✓ and nothing is open, the right panel shows «Ваша плата целиком».

Status lines (exact wording):

| Step | States |
|------|--------|
| 1 Плата | «не выбрана» · «Arduino Nano» · «⚠ подключена Pro Micro, выбрана Nano» |
| 2 Прошивка | «плата не на связи» · «версия 3, свежая» · «⚠ версия 2, обновите» · «прошиваю… 45 %» |
| 3 Датчики | «пока нет» · «4 из 10» · «4 из 10 · ⚠ 1 обрыв» |
| 4 Выходы | «пока нет» · «1 · вентилятор ВКЛ» · «2 · ⚠ 1 вручную» · «⛔ всё выключено» |
| 5 Проверка | «ещё не проверяли» · «всё в порядке» · «2 вопроса» |

### 3.3 The right panel per step

| Step | Right panel |
|------|-------------|
| 1 Плата | The 4 boards as large cards (title, 3D model 120 dp, one line «Самый удобный: маленький, 8 аналоговых входов» / «Лучше для реле: не перезагружается»), with the detected board marked «подключена сейчас». Under them, «Что ещё понадобится»: USB cable, terminal blocks (WAGO), wires, resistors. |
| 2 Прошивка | `FlashHere` with the pre-check and big button (P1-9). The PC route is collapsed. On success: «Дальше: датчики». |
| 3 Датчики | No input selected: `BoardOverview` + `PinTable` («Вся плата»). Input selected: `InputDetails`, with section order «Сейчас» → «Как подключить» (picture and checklist) → «Настройка» (collapsed) → «Что купить» (collapsed). Novice option: the first time, «Что купить» opens first and the others are collapsed. |
| 4 Выходы | No output: `OutputNew` templates. Output selected: `OutputDetails` in sections (P2-5). |
| 5 Проверка | `BoxCheck` (P2-3). |

### 3.4 Novice and expert

- **Novice.** It is the first visit when nothing is set up and demo is off. The right panel starts with «С чего
  начать» and a big «Посмотреть на примере» (demo plus example). All details are collapsed except «Как подключить».
  The checklists are on.
- **Expert.** Once `slots.size ≥ 3` or all steps are ✓, the intro collapses to one row, the panels open on «Вся
  плата», and «Настройка» opens by default. There is no explicit "expert mode" switch. Defaults follow what the user
  has done (progressive disclosure, not a mode).

---

## 4. Best practices from other tools

### 4.1 What each tool does that fits a touch head unit

| Tool | What it does | Take for SensorBox |
|------|--------------|--------------------|
| **Wokwi** (simulator) | New wires get their colour from the starting pin: «ground pins are black, 5 V pins are red, and other wires are green». Wires snap to a 2.54 mm grid, and the user clicks waypoints to route them. `diagram.json` stores each connection with a colour and route. | Colour by function, chosen by the app and not by the user. This is what `BoxWire.color` already does. Keep red and black reserved (P1-8). |
| **Fritzing** (breadboard view) | Connectors show red when unconnected and green when connected. The **ratsnest** is a suggestion line for a connection not yet wired, each net in its own colour, which disappears once the real wire exists. Bendpoints and curves keep wires readable. | Use the ratsnest idea for the checklist: unchecked wires drawn as thin dashed suggestions, checked ones solid (P2-2). Pin state colours: wired/OK green ring, fault orange (P1-5). |
| **Tinkercad Circuits** | Run the simulation and watch it: a broken part shows an explosion or a red star, and **hovering over it explains** what went wrong (for example too much current through an LED). Wire colours are set with number keys, red/black are recommended for +/−, and terminals show info on hover. | Faults shown **on the part in the drawing**, with a tap for the explanation (P1-5, P3-1). Live values are the simulator: «Сейчас» next to the wire. |
| **Cirkit Designer** | Wires route themselves around parts and re-route when a part moves. The first-circuit tutorial gives an explicit **wire list** («D13 → resistor pin 1 …»). **Upload stays disabled** (grey) until a port is chosen, then turns blue. A green or red console is the result. | Auto-routing is already done (nested lanes). Copy the gating: the flash button is disabled with a reason until the board is on USB, and the result is a clear green or red card (P1-9). |
| **Arduino Cloud** device setup | Install the agent → the page detects the board on USB («We could not find any Arduino device» if not) → name it → it configures the board itself, which «might take a while» (up to 5 min). | Detect first, act second. Say what was found («Плата на USB: Arduino Nano»). Set the expectation for time and progress. |
| **ESPHome Device Builder** | A wizard «New Device Setup» (platform → board → Wi-Fi). Then: «The very first install has to happen over a USB cable; every update after that can happen wirelessly.» Afterwards each device card shows an **Online/Offline badge** and a **Logs** button. Home Assistant then auto-discovers the device and offers to set it up. | The «Прошивка» step status and the pinned header are the Online badge. A «Журнал платы» (raw lines from the box) under «Проверка» is the Logs button, useful for remote support on drive2. |
| **Home Assistant** config flows | Config flows should be easy to understand, use the right selectors, validate input, and give each field a `data_description` helper text. Connection data is kept apart from options. | Every field in `SlotSettings` gets a one-line helper under it. Separate «Подключение» (pin, sensor type) from «Настройка» (name, thresholds), as HA separates `data` and `options`. |
| **pinout.xyz** | One board picture, pins in categories (GPIO, SPI, I2C, UART, Ground, 5 V, 3.3 V) with a legend. Each pin and each function has its own page with wiring notes (pull-ups, «not suitable for … where a pull-up might interfere»). Shows physical and BCM numbering, plus an orientation note («GPIO on the right and HDMI on the left»). | A pin popup on tap (P3-1). Keep the orientation note «USB слева» as today. Per-function notes such as «A6, A7 только аналоговые» or «D3, D5, D6, D9 умеют плавно (ШИМ)». |
| **Adafruit / SparkFun guides** | Each breakout has a **connection table** (breakout pin → Arduino pin) beside a Fritzing image, then a first-run test (open the serial monitor, see the temperature). Adafruit's thermocouple guide warns that the K-type **red lead is Alumel (−) and yellow is Chromel (+)**, and that if readings are wrong you should «Try swapping the two thermocouple leads». SparkFun uses D13/D12/D10 for the MAX31855, the same as this app. | The `PinTable` plus picture is right. Add the per-sensor first-run test (P2-3) and the thermocouple colour warning (P1-8). |
| **Shelly / Sonoff** relay apps | Relay behaviour is set by named options, not logic: Shelly has input mode «Detached», **«Power on default»** (on / off / restore last) and **«When on, turn off after N seconds»**. Sonoff/eWeLink has **inching** (on for a set time, then off) and power-on state. | The output's «Защита» section (P2-5) maps onto these: «Если связи с магнитолой нет» ≈ power-on default, «Не держать включённым дольше» ≈ auto off / inching. Present them in that plain-option style, each with a one-line consequence. |

### 4.2 Wiring diagram rules

1. **Colour by function, and keep it consistent.** Red for +5 V and black for GND are near-universal. University
   breadboard guides recommend red and black for the supplies and **reserving those colours for those roles only**.
   Hobby forums agree that only black-for-ground is close to a standard, and that consistency matters more than any
   particular scheme. For this app: red +5 V, black GND (drawn with a light outline on dark), purple +12 V of the car,
   and signal colours from a palette with no red, black or purple. Watch out for thermocouple wire, where red is the
   minus.
2. **Label both ends.** Harness documentation tools (WireViz) describe every wire as connector-pin → wire → connector-pin
   with standard colour codes (IEC 60757: BK, RD, …). In the picture, the board end is labelled with the pin name and
   the sensor or module end with its terminal name (P3-3). In the list, each step reads «what on the sensor → what on
   the board».
3. **Avoid crossings, keep lanes parallel.** `drawWiring` already nests lanes and turn points so wires do not cross.
   Keep that rule for the overview feeds, and check it on the relay module case (11), where the wires overlap at the
   terminals.
4. **Show power separately.** Shared +5 V and GND go through **terminal blocks** drawn once, with one feed each from the
   board (`drawOverview`). This is right, and better than most hobby diagrams, which daisy-chain power on a breadboard
   rail. In the per-sensor view, draw a short stub labelled «к клеммнику +5V» when others share it, instead of a wire
   straight to the pin (P3).
5. **Number the wires and link them to the steps.** The same number on the picture and in the list (P2-2). On touch
   there is no hover, so **tap** does what hover does in Wokwi, Tinkercad and pinout.xyz: highlight this wire and dim
   the rest.
6. **Step by step with a checklist.** One wire per step, with a checkbox. Unchecked wires are drawn as a dashed
   "suggestion" (Fritzing ratsnest) and checked wires as solid.
7. **Test every connection with a live value.** After each sensor: a value, a raw voltage or resistance, and an action
   that should move it («зажмите в руке», «заведите мотор»). After each output: a 1 s pulse and the question
   «Щёлкнуло?».
8. **Show errors on the diagram, in plain language.** Open or short circuits are marked on the wire they concern, with
   the tag «Обрыв? Проверьте этот провод» (Tinkercad's red star with an explanation). NN/g heuristic 9: «Error messages
   should be expressed in plain language (no error codes)». Heuristic 5: prevent first, so invalid choices are
   disabled, not explained after the fact (P2-8).
9. **Keep system status visible.** NN/g heuristic 1: «keep users informed about what is going on». This is the pinned
   status (P1-1) and the status line per step (3.2).
10. **Recognition rather than recall.** Show the pin names and what is already taken in the picture, so the user does
    not have to remember which pins are free (the gallery input picker in 3.2, P3-1).
11. **Progressive disclosure for novice vs expert.** NN/g: «Progressive disclosure defers advanced or rarely used
    features to a secondary screen», and «You have to disclose everything that users frequently need up front». Here
    that means the live value and «Как подключить» are up front, while calibration (0 V/5 V points), the role and the
    timers are in collapsed sections. A wizard is staged disclosure, which works only when steps have little
    interdependence, hence the non-linear stepper.
12. **Size for the car.** Android for Cars: «minimum size of 76 x 76dp», primary text 32 dp, secondary 24 dp, contrast
    ≥ 4.5:1, and night content in «negative polarity» (light on dark, as here). Those are for use while driving. For
    this parked-use settings tab, aim for ≥ 48 dp targets (≥ 56 dp for primary actions), ≥ 13 sp text and ≥ 11 sp on
    the canvas. The output widgets on screens are used while driving, so they should meet the car numbers (76 dp
    buttons, 24 sp text).

---

## 5. Naming: one word per thing

| Thing | Today (mixed) | Use |
|-------|---------------|-----|
| The device | «Доп датчики», «Доп датчики на Arduino», «плата», «SensorBox» (code) | «плата» in text; the tab stays «Доп датчики» |
| Supply | «+5V», «5V», «5 В», «клеммник +5V» | «+5 В» in sentences; «5V» only as a pin label as printed on the board |
| Ground | «GND», «масса», «минус» | «масса (GND)» the first time on each page, then «масса» |
| Car supply | «+12 В машины», «12 В», «+12 В через предохранитель» | «+12 В от машины (через предохранитель)» |
| Remove | «Убрать» (input, output), trash «Удалить правило», X «Убрать условие» | «Убрать» for all three, plus «Вернуть» in the undo snackbar |
| Rule mode | «По правилам» (output page), «авто» (widget) | «по правилам» everywhere |
| Manual | «вручную», «Включить/Выключить» | «вручную: вкл», «вручную: выкл» |
| Screens | «Мой экран», «главный экран», «главная», «плитки «Датчик 1–6»» | «Главный экран» and «Мой экран», as named in the navigation rail; nothing else |
| Knob | «Крутилка 0–100 %» (widget), «Потенциометр (крутилка)» (sensor) | widget «Крутилка на экране», sensor «Потенциометр (ручка на панели)» |
| Pin taken | «фиолетовые пины уже заняты», «(занят)» | «занят: <кто>» |
| The setup steps | «1. Какая у вас плата … 4. Выходы», «Три шага» | the stepper titles «Плата, Прошивка, Датчики, Выходы, Проверка»; the intro becomes «С чего начать» |

---

## 6. Sources

Tools:
- Wokwi diagram editor (wire colour by pin function, routing, grid): https://docs.wokwi.com/guides/diagram-editor
- Wokwi diagram format: https://mintlify.wiki/wokwi/wokwi-docs/parts/diagram-format
- Fritzing ratsnests (suggested connections, colour per net, views share connections): https://blog.fritzing.org/2010/09/27/ratsnests-less-mess
- Fritzing bendpoints and curves: https://blog.fritzing.org/2011/08/18/fritzing-gets-the-bends
- Fritzing connector colours red/green (forum): https://forum.fritzing.org/t/changing-colors-of-part-leads-in-schematic-view/7766
- Tinkercad wiring and hover info (Instructables): https://www.instructables.com/Wiring-Components/
- Tinkercad explosion with hover explanation: https://blogs.baruch.cuny.edu/makerhub/?p=5892 and https://dev.to/heroku/hardware-hacking-101-making-your-first-virtual-circuit-2df5
- Tinkercad red star on faulty components: https://sahl.io/index.php/u-questions/lesson/7393
- Cirkit Designer first circuit (wire list, gated upload, green/red console): https://www.cirkitdesigner.com/docs/tutorials/creating-your-first-circuit
- Cirkit Designer tutorial (auto routing): https://instructables.com/Cirkit-Designer-a-Tutorial
- Arduino Cloud getting started: https://docs.arduino.cc/arduino-cloud/guides/overview
- Arduino Cloud setup for the Nano ESP32: https://docs.arduino.cc/tutorials/nano-esp32/cloud-setup
- ESPHome getting started (wizard, first install over USB, Online/Offline, Logs, discovery): https://esphome.io/install/getting-started/
- Home Assistant config flow quality rule (data_description, selectors, validation): https://developers.home-assistant.io/docs/core/integration-quality-scale/rules/config-flow
- Home Assistant config flow handler: https://developers.home-assistant.io/docs/config_entries_config_flow_handler
- pinout.xyz (categories, legend, numbering, orientation): https://pinout.xyz/
- pinout.xyz I2C page (per-function notes): https://pinout.xyz/pinout/i2c
- Adafruit MAX31855 thermocouple guide (wiring, first test, red = Alumel, swap leads): https://learn.adafruit.com/thermocouple.md?view=all and https://learn.adafruit.com/thermocouple/using-a-thermocouple
- SparkFun MAX31855K hookup guide (D13/D12/D10): https://learn.sparkfun.com/tutorials/max31855k-thermocouple-breakout-hookup-guide/hardware-hookup
- Shelly Plus 1 web interface (detached, power on default): https://www.shelly.com/blogs/documentation/shelly-plus-1-web-interface-guide
- Shelly knowledge base (auto off «When on, turn off after N seconds»): https://shellyusa.atlassian.net/wiki/x/joA9Ig
- Sonoff/eWeLink inching and power-on state: https://community.home-assistant.io/t/sonoff-mini-d-inching-mode/903505 and https://help.ewelink.cc/hc/en-us/articles/360037725311-POWER-ON-STATE-does-not-work

Wiring conventions:
- University of Illinois breadboard how-to (red/black, reserve them): https://courses.grainger.illinois.edu/ECE110/content/labs/Experiments/BB_HowTo.pdf
- Illinois physics breadboard microtutorial (red 5 V, black ground): https://courses.physics.illinois.edu/phys371/fa2022/microtutorials/breadboard_microtutorial_.pdf
- Arduino forum, wire colour conventions (no official standard, consistency matters): https://forum.arduino.cc/t/wire-colour-conventions/910171
- WireViz / filare (wire per connector pin at both ends, IEC 60757 colour codes): https://pypi.org/project/filare/

UX and car guidelines:
- NN/g progressive disclosure and staged disclosure: https://www.nngroup.com/articles/progressive-disclosure/
- NN/g 10 usability heuristics: https://www.nngroup.com/articles/ten-usability-heuristics/
- Android for Cars visual principles (76 dp, 32/24 dp text, 4.5:1, negative polarity at night): https://developers.google.com/cars/design/design-foundations/visual-principles
- Android Automotive sizing (76 x 76 dp): https://developers.google.com/cars/design/automotive-os/design-system/sizing
- ISO 15008 (in-vehicle display legibility; scope only, numbers not checked): https://committee.iso.org/standard/62784.html

Not verified: whether Wokwi and Fritzing show a pin name tooltip on hover. The searches did not confirm it, so this
review does not rely on it. The tap-for-pin-details idea stands on pinout.xyz and Tinkercad.
