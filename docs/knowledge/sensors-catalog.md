# SensorBox: каталог датчиков

Researched catalogue of extra sensors for the SensorBox (Arduino Nano/Uno/Pro Micro/Mega, 5 V, 10-bit ADC) on Subaru
EJ20/EJ25 (turbo and NA), with notes for FA/FB. The machine-readable presets are in
[`hardware/sensorbox/sensors-catalog.json`](../../hardware/sensorbox/sensors-catalog.json) (39 presets, shipped in the app as an asset; ratiometric MAP and ACS758 presets use kind `RATIO`). Technical notes are in English. Text for users is in Russian,
marked **Для пользователя**.

Researched 2026-10-16. Every transfer function and table comes from a data sheet, manufacturer manual or firmware source
listed under each section. Where only forum or retailer data exists, it is marked so.

**Confidence**
- **high:** a manufacturer document or factory manual.
- **medium:** consistent secondary sources such as tuning firmware, vendor pages or several forums.
- **low:** one weak source, or a generic part whose curve may differ.

**Prices:** ruble prices marked "оценка" are estimates and were not checked against a live Russian listing. Web search ran
out during research, and exist.ru, emex and Avito blocked fetching. Prices with a named shop were seen on that page or in
its search snippet.

---

## 0. Что нужно поменять в приложении (summary for developers)

### 0.1 New kinds

| Kind | Why | Formula | Presets |
|---|---|---|---|
| `RES` | Resistive senders whose resistance **rises** with the value (VDO 10–184 Ω pressure). `ntc()` assumes falling resistance and clamps wrongly. | `ohm = pull·x/(1−x)`, then linear interpolation in `res` = [[value, ohm]…], ascending in both. Fault: x < 0.01 short (below about 2 Ω with 220 Ω); x > 0.6 open (about 330 Ω, well above 184 Ω). The NTC open threshold of 0.985 is too high here. | `oil_p_vdo5`, `oil_p_vdo10` |
| `RATIO` (suggested) | Ratiometric sensors (GM/Bosch MAP, ACS758) are read as `VOLT`, i.e. `U = x·vcc`. Their output scales with the supply, so a 4.8 V USB rail gives about a 4 % error. For ACS758 the zero moves by about 5 A on the 100 A part. | `value = a + b·(x·5.0)`. Keep `a`/`b` as now; only use 5.0 in place of the measured `vcc`. | `map_gm3`, `map_gm2`, `map_bosch_pst4`, `map_bosch_3bar`, `batt_i_acs758_100`, `batt_i_acs758_200` |
| `PW` (optional) | Flex-fuel fuel temperature is encoded as pulse width. | °C = 41.5·ms − 81.25 (1 ms = −40 °C, 5 ms = 125 °C). This needs firmware to report the low or high time on F0 as well as the frequency. | (no preset yet) |

`RES` presets also carry `pull` and `pullUp`, like NTC.

### 0.2 Suggested roles (field `suggestedRole`)

| Role | Meaning | Presets |
|---|---|---|
| `fuelP` | Fuel pressure: compare with the factory 284–314 kPa (vacuum hose off). Later this could check fuel minus manifold pressure, using MAP from the ECU. | `fuel_p_aem100` |
| `boost` | Absolute manifold pressure from an external MAP: overboost warning, and a second opinion beside the ECU's MAP. | `map_*` |
| `iatPre`, `iatPost` | Intercooler inlet and outlet: intercooler efficiency, and top-mount heat soak in traffic. | `iat_pre_bosch`, `iat_post_aem`, `iat_gm`, `tmap_t_bosch` |
| `atf` | Aftermarket ATF temperature. 4EAT/5EAT already have a TCU sensor readable over SSM (see §6), so this role should only win when no TCU value is available. | `atf_t_bosch` |
| `ethanol` | Ethanol %. A value above 100 % (frequency above 150 Hz, contamination at 180–190 Hz) should show as "вода или грязь в топливе", not be clamped. | `flex_e` |

### 0.3 Corrections to the built-in presets in `SensorBox.kt`

1. **`BOSCH_NTC` table.** The table is the Bosch NTC M12-H (0 281 002 170) curve.
   - 0 280 130 026 (the part named in the preset) is rated only to 130 °C.
   - Its sheet gives **187 Ω at 100 °C**, not 186.
   - The 140 and 150 °C rows exist only on the M12-H sheet.
   - Either rename the preset to 0 281 002 170 or cut the table at 130 °C.
2. **Pull-up.** Bosch states "typically 1 or 3 kOhm" for the M12 sensors.
   - With 2.2 kΩ the step at 120–130 °C is about 1 °C per ADC count.
   - With 1 kΩ it is about 0.5 °C per count.
   - Self-heating at 1 kΩ stays below 2 mW at 130 °C.
   - The catalogue presets use 1 kΩ. 2.2 kΩ still works.
3. **`oil_t_10k`** uses a pure B3950 formula. The catalogue uses the MF52A103F3950 data-sheet table instead. The difference is small, but this one is sourced.
4. **Thread tip "1/8 NPT"**: the EJ oil pressure switch port is **1/8 BSPT (PT)**, and Perrin and Blitz plates are 1/8 PT (§2). Mention the adapter.
5. **`fuel_p` tip "2,5–3 бар на холостых"**: the factory figures are 230–260 kPa (2.0T) or 206–235 kPa (2.5i) at idle with the hose on, and 284–314 kPa with it off (§3).
6. **`afr_aem` text** is right for the X-Series (30-0300/0310). The old **30-4110/30-4100 are 10 + 2·U**, not 2.375·U + 7.3125. A separate preset has been added.
7. **`flex` preset.**
   - The firmware's `INPUT_PULLUP` is 20–50 kΩ (ATmega328P data sheet, R_PU), but the sensor wants 2.2–10 kΩ (Racetronix) or about 3 kΩ (rusEFI).
   - Make the external 3.3 kΩ resistor mandatory, not optional.
   - The pinout in one vendor source is A = 12 V, B = GND, C = signal (§10).
8. **`egt_k` tip.** Add: the thermocouple must be **ungrounded**, because MAX31855 T− must not touch GND (§9). Most bare MAX31855 chips are 3.3 V only, so a 5 V board needs a breakout with a regulator and level shifting.
9. **`PRESSURE` fault thresholds** (x < 0.05, x > 0.97) are fine. The AEM X-Series wideband reports "not ready" below 0.5 V and "error" above 4.5 V. A `VOLT` preset could show that if the app gets per-preset `validMin`/`validMax` volts.

---

## 1. Subaru: резьбы и места установки (mounting facts)

| What | Answer | Confidence | Source |
|---|---|---|---|
| Oil filter boss, EJ/FA/FB | **M20×1.5** | high | [K&N][kn_m20], [motofaction][motof] |
| Oil pressure switch, EJ | On the front of the block, under the alternator (the FSM removes the alternator first). Sealant ThreeBond 1324, **25 N·m** | high | [FSM 2.0T removal][fsm_ops_rem], [install][fsm_ops] |
| Oil pressure switch thread, EJ | **1/8 BSPT (PT1/8, 28 TPI)**, not NPT and not M10 | medium | [GRM forum][bspt_grm], [autodoc SH listing][autodoc_sh] |
| Oil pressure, FA20DIT | 1/8 PT port on the front case. A Y adapter exists (Torque Solution TS-SU-730) | medium | [Subimods][ts730] |
| Oil pressure, FA20 NA / FB20 / FB25 | not found, **unverified** | n/a | |
| Other oil taps, EJ | M18×1.5 galley plugs on top of the block (under the alternator and the throttle body). Adapters M18×1.5 → 1/8 NPT: Killer B, Torque Solution TS-SU-609 | medium | [Killer B][kb_m18], [TS-SU-609][m18] |
| Remote sender kit | ProSport: galley plug, braided hose, bracket on the strut tower | medium | [SubiSpeed][prosport_remote] |
| Sandwich plates | Perrin ASM-GAU-100/102 (M20×1.5, 2× 1/8 PT); ProSport M20×1.5; Mishimoto MMOP-SPTR (thermostatic, 1/8 NPT); Blitz Type H 65 mm (1/8 PT, FA20/FB20/SJ). FA20 needs a spacer for the recessed seat | medium | [Perrin][perrin_plate], [Mishimoto][mishimoto_plate], [Blitz][blitz_plate], [Improved Racing][ir_spacer] |
| Coolant temp sensor, EJ OEM | M12×1.5, 2-pin NTC, 19 mm hex; 18 N·m. Location: the FSM removes the alternator first. Another guide says rear of the intake manifold (conflict) | medium | [Meyle listing][meyle_ect], [FSM ECT][fsm_ect], [go-parts][goparts_ect] |
| Upper radiator hose, Forester SG EJ255 | **38 mm ID** (not 32–35) | medium | [Gates][gates_sg] |
| Coolant hose adapter | Perrin ASM-GAU-106 (1/8 PT), GReddy 16401690 (BRZ, 1/8 PT) | medium | [Perrin][perrin_hose], [GReddy][greddy_hose] |
| EGT, OEM | 2002–05 US WRX (EJ205): EGT sensor on the up-pipe ("joint pipe"), 21 N·m. STI and Forester XT up-pipes have no bung. Thread M12×1.25 is from one forum post only | high / low (thread) | [FSM EGT][fsm_egt], [SIDC][sidc], [anandtech][anand_fxt] |
| Aftermarket up-pipes | GrimmSpeed: no bung. Tomioka Racing: capped EGT bung | medium | [Kami][grimm], [SubiSpeed][tomioka] |
| Fuel system | EJ205 (2005) is return-style with a vacuum-referenced regulator. 2017+ Forester and the DIT engines (2019+ Ascent, 2020+ Legacy/Outback XT, 2022+ WRX) are returnless with an in-tank regulator. Late NA EJ25/FB: **unverified** | high / medium | [FSM intake][fsm_intake], [COBB][cobb_fpr], [Radium][radium] |
| Coolant level sensor | none found on EJ/FB | low | [9-2X FSM instrumentation][fsm_instr] |

Thread note: 1/8 NPT (27 TPI, 60°) and 1/8 BSPT (28 TPI, 55°) are near enough to start by hand, but mixing them is a
common leak source. The GRM thread recommends an adapter over re-tapping. Perrin's coolant adapter is advertised as
working with both.

---

## 2. Температура масла (oil temperature)

**Why it matters:** the app currently estimates oil temperature from coolant (engine-ej.md). A real sensor replaces that
estimate in EngineGuard, the turbo timer, the 3D engine view and the oil card (role `oil`). Normal hot oil on a stock STI
was about 106 °C on track ([Mishimoto STI test][mishimoto_sti]).

**Mounting:** use a sandwich plate under the filter (M20×1.5). On turbo EJ, crossmember and header clearance can limit
port orientation; a WRX owner reported the OEM headers block most positions ([desertcart review][desert], low).

| Preset | Part | Thread | Curve | Pull-up | Conf. |
|---|---|---|---|---|---|
| `oil_t_bosch_m12h` | Bosch 0 281 002 170 (NTC M12-H) | M12×1.5 | [Bosch sheet][bosch170], −40…150 °C, ±0.8 °C at 100 °C | 1 kΩ | high |
| `oil_t_aem` | AEM 30-2012 | 1/8-27 PTF | [AEM sheet][aem2012], −40…150 °C. High-resistance curve (37.5 kΩ at 0 °C), **not GM** | 2.2 kΩ ([AEM install sheet][aem2012i]) | high |
| `oil_t_10k` | generic "10K 3950" 1/8 NPT | 1/8 NPT? | [Semiware MF52A103F3950 sheet][mf52], −20…150 °C. The actual probe may differ | 2.2 kΩ | low |
| `oil_t_vdo150` | VDO 323-801-…, 150 °C, single wire | many | VDO curve table CT 2, −40…180 °C | 220 Ω, 0.5 W | high |
| `oil_p_pstf_t` | Bosch PST-F 1 temperature channel | M10×1 cone | Bosch PST-F sheet, −40…150 °C (Bosch suggests 4.6 kΩ) | 1 kΩ | high |

**Also checked:** AEM 30-2011 (3/8 NPT coolant, GM-like curve to 150 °C, [AEM][aem2011]) has a sourced table but no preset, since its thread suits few Subaru adapters.

**Not used:** Defi, Greddy, Apexi, Autometer, ProSport and GlowShift senders.
- No published R–T tables were found.
- Defi PDF00703S works only with Defi-Link.
- ProSport is quoted only as "32–925 Ω over 20–150 °C" ([IAG][iag_prosport], low).
- Do not invent a table: use `oil_t_10k`, then check it in boiling water, or use a custom calibration.

### Reading a VDO-style single-wire sender

A VDO temperature sender is an NTC between its terminal and its case. The case is grounded through the thread, so no
PTFE tape ([VDO TU00-0770][vdo_t]).

**Wiring:** connect the terminal to an analog input with a pull-up to 5 V, and run a dedicated wire from the box GND to
the engine block near the sender. The app's NTC kind then works unchanged, because any falling resistance curve is fine.

**Pull-up choice:**
- The geometric-mean rule ([Hamilton College][geomean]) gives about 55–64 Ω for 60–150 °C.
- That would push 50–60 mA from the USB 5 V and heat the sender.
- 220 Ω is the compromise: about 0.4 °C per count at 110–120 °C (CT 1) and about 0.6 °C per count at 140–150 °C (CT 2), with under 10 mW in the sender.
- Use a 0.5 W resistor, since a shorted sender puts 114 mW into it.

**VDO curves** (from the VDO characteristic curve tables):
- CT 1 (120 °C senders): 40 °C 291.5 Ω … 120 °C 22.4 Ω.
- CT 2 (150 °C senders): 50 °C 322.2 Ω … 150 °C 18.6 Ω. This matches the separate [Merlin VDO sheet][vdo_t150].

The full tables are in the JSON. **A sender already wired to a VDO gauge cannot be shared.** The gauge and the pull-up
load each other, so fit a second sender.

**Для пользователя:**
- Датчик температуры масла ставится в проставку под масляный фильтр. Резьба там M20×1,5 на всех EJ, FA и FB.
- Проставки бывают с отверстиями 1/8 PT (японская резьба) и 1/8 NPT (американская). Датчик берите под ту же резьбу или купите переходник.
- Резистор из настроек (1 кОм, 2,2 кОм или 220 Ом) ставится между сигнальным входом платы и 5V.
- Однопроводной датчик держит массу через корпус: ленту ФУМ на резьбу не мотайте и проведите провод от GND платы к мотору.

---

## 3. Давление масла (oil pressure)

**What exists:** EJ has only a switch, which lights the lamp at 14.7 kPa. The factory minimum at 80 °C oil is **98 kPa at
800 rpm and 294 kPa at 5000 rpm** ([FSM 2.0T][fsm_oilp]; also in engine-ej.md). Role `oilP` uses these figures.

**3-wire 0.5–4.5 V sensors** (kind `PRESSURE`, ratiometric, `P = (x − 0.1)/0.8 · pMax`):

| Preset | Part | Formula from source | Conf. |
|---|---|---|---|
| `oil_p_aem150` | AEM 30-2130-150 (316L, ±1 % FS, 5 V ±0.5 V, <6 mA, burst 3× FS) | psi = 37.5·V − 18.75 | high ([AEM][aem2130]) |
| `oil_p_pstf` | Bosch PST-F 1 (0 261 544 01F), 0–10 bar gauge, M10×1 sealed cone, 24 N·m, trapezoid connector: 2 = p, 3 = +5 V, 4 = GND, 5 = NTC | p = (V − 0.5)/0.4 bar | high ([Bosch][pstf]) |
| `oil_p_10` | generic 0–1.0 MPa, 0.5–4.5 V | assumed linear 0.5–4.5 V | medium |
| `oil_p_150` | generic 0–150 psi; Honeywell MIPAN2XX150PSAAX is a sourced alternative (1/8 NPT) | assumed linear | medium ([DigiKey][honeywell_mipan]) |

**Cheap Chinese senders.**
- Specs claim ±0.5–2 % FS. DATAQ quotes ±2 % FS for generic ceramic units and ±0.25 % for oil-filled stainless ([DATAQ][dataq]).
- Field reports:
  - A unit that never responded ([Link 11781][link_cheap2]).
  - "Most cheap sensors don't last" when hard-mounted, plus one burst unit that caused a fire ([Link 10823][link_cheap1]).
  - Bosch combined sensors failing under motorsport vibration ([HP Academy][hpa_bosch]).
- Wire colours on generic units are sometimes swapped.
- **Recommendations:**
  - Remote-mount on a braided hose.
  - Check 0.5 V with the engine off.
  - Compare against a mechanical gauge once.

**Resistive VDO pressure senders (kind `RES`, new).**
- VDO European senders run 10 Ω at 0 to 184 Ω at full scale, and resistance **rises** with pressure.
- Points from a VDO reseller ([druckgeber-oeldruckgeber.de][vdo_p], medium):
  - 5 bar: 0→10, 1→48, 2→82, 3→116, 5→184 Ω.
  - 10 bar: 0→10, 2→52, 6→124, 10→184 Ω.
- The curve is not linear (2 bar is 52 Ω, while linear would give 44.8 Ω), so interpolate the table.
- **Pull-up 220 Ω:** x runs from 0.044 to 0.455. The resolution is about 0.04 bar per count on the 10-bar sender.
- US 240–33 Ω senders (e.g. AutoMeter 2242: 240 Ω at 0 psi, 33 Ω at 100 psi, [AutoMeter][am2242]) fall with pressure, so the NTC kind would work mathematically. No intermediate table was found, so there is no preset. ProSport dual-post (3–160 Ω over 0–10 bar) has no table either.
- Many US retailers write "10–180 Ω". That is the same family; use the table.

**Mounting on EJ.**
1. Easiest: a Y/T adapter in place of the lamp switch (1/8 PT). Killer B Dual Sender Adapter and Torque Solution TS-SU-730 are made for FA20DIT; Killer B says the dual sender also fits the EJ switch port.
2. M18×1.5 galley plug adapter → 1/8 NPT. Killer B says these ports are "best used for oil pressure".
3. Sandwich plate under the filter. This reads pressure before the filter element.
- Heavy senders on long rigid fittings crack from vibration, so use a remote mount ([iboats][iboats], [ProSport kit][prosport_remote]).

**Для пользователя:**
- Штатная лампа давления загорается почти на нуле (0,15 бар). Датчик давления нужен, чтобы заранее увидеть, что давление ниже нормы.
- Норма на горячем моторе: не меньше 1 бар на холостых и не меньше 2,9 бар на 5000 об/мин.
- Проще всего поставить тройник вместо датчика лампы, он спереди мотора под генератором. Резьба там 1/8 PT, не NPT.
- Трёхпроводный датчик 0,5–4,5 В: красный на 5V, чёрный на GND, сигнальный на вход. Без давления он даёт 0,5 В.
- Однопроводной датчик VDO (10–184 Ом) работает по-другому: нужен резистор 220 Ом к 5V и масса через корпус.

---

## 4. Давление топлива (fuel pressure)

**Factory figures** ([Saab 9-2X FSM][fsm_fuel_20t] (a rebadged 2005 WRX); [2.5i][fsm_fuel_25i]):

| Engine | Regulator vacuum hose off | Hose on (idle) |
|---|---|---|
| 2.0T EJ205 | 284–314 kPa | 230–260 kPa |
| 2.5i EJ253 | 284–314 kPa | 206–235 kPa |

- At high altitude the gauge can read 10–20 kPa higher.
- The FSM test point is the delivery hose at the fuel filter (2.0T) or at the fuel damper (2.5i).

**Return vs returnless:** EJ cars of this era are return-style with a vacuum-referenced regulator. With a 1:1 regulator,
pressure rises with boost, so fuel minus manifold pressure stays about constant. That difference is the useful number
(future role `fuelP` together with MAP from the ECU). Newer DIT cars are returnless (§1).

**Sensor:** use a gauge-referenced 0–100 psi sensor. **AEM 30-2130-100** follows psi = 25·V − 12.5 ([AEM][aem2130]).
30-2131 is brass with ±3 % accuracy and is not rated for methanol ([AEM 30-2131][aem2131]). Bosch PST-F 1 is rated for fuel too.

**Tee options** (vendor pages, medium):
- GlowShift WRX brass tee: 8 mm barbs, 1/8 NPT female ([GlowShift][glowshift_tee]).
- COBB 1/8 NPT tap for 5/16" quick-connect (2008–14 WRX, 2008–21 STI) ([COBB][cobb_tap]).
- GFB 5752 rail adapter ([GFB][gfb5752]).
- Tomei adapter (2002–07 WRX, 2004–07 STI).

**Для пользователя:**
- Датчик ставится в тройник на шланг подачи к рампе. Работайте на холодном моторе, сначала сбросьте давление в системе.
- Норма по заводскому мануалу: 2,8–3,1 бар со снятой вакуумной трубкой регулятора.
- Со шлангом на холостых: 2,3–2,6 бар на турбо 2.0 и 2,1–2,35 бар на атмо 2.5.
- На турбо давление топлива под наддувом растёт, так и должно быть.

---

## 5. Наддув / MAP (boost, absolute)

**All absolute.** About 100 kPa is atmosphere, so boost = value − 100 kPa.

**Kind `VOLT` for now.** These sensors are ratiometric, so the app should get `RATIO` (§0.1). Plumb them with a vacuum
hose to the intake manifold after the throttle.

| Preset | Part | kPa = a + b·V | Range | Source / conf. |
|---|---|---|---|---|
| `map_gm3` | GM 12223861 (= 16040749, MSD 2313). Pins A = GND, B = signal, C = +5 V | 1.1 + 62.88·V | 0.1–4.9 V | [DIYAutoTune][diy_gm3], [Speeduino ini][speeduino], [robietherobot][robie]. high |
| `map_gm2` | GM 16040609 (MSD 2312) | 9 + 39.8·V | 9–208 kPa | Speeduino preset. robietherobot's table gives about 2.7 + 40.3·V. medium |
| `map_bosch_pst4` | Bosch PST 4, 0 261 230 423. Pins 1 GND, 2 NTC, 3 +5 V, 4 p | 6.25 + 87.49·V (from 1.143 V/bar, −71.43 mV) | 40–400 kPa | [Bosch][pst4]. high |
| `map_bosch_3bar` | Bosch PST 3 0 261 230 280, Bosch 0 281 002 845 | −6.35 + 65.88·V (0.40 V = 20 kPa, 4.65 V = 300 kPa) | 20–300 kPa | [MoTeC][motec_tmap]. Speeduino uses −6/323. medium |
| `map_aem50` | AEM 30-2130-50 (psia) | `PRESSURE`, pMax 50 psia = 3.447 bar abs | 0–50 psia | [AEM][aem2130]. high |

**Caveats:**
- GM 16017460 and 16212460 end in "460", which robietherobot lists as a 1-bar code. 12592525 is an LS9 sensor with a different curve.
- **Bosch 0 261 230 042** (2.5 bar TMAP, cross-referenced to 283/302) has unverified endpoints, so it has no preset. Users can enter two calibration points.
- 0 261 230 030, 119, 120 and 146 had no data found.
- **1-bar GM** (10–105 kPa) is skipped, because NA cars already have a MAF and turbo cars need more than 1 bar.

**Для пользователя:**
- Датчик показывает абсолютное давление: на стоянке около 100 кПа. Наддув 1 бар это 200 кПа.
- Подключите его шлангом к впускному коллектору за дроссельной заслонкой.
- Для стокового турбо EJ берите датчик на 3 бара.

---

## 6. Доп. температура ОЖ, ATF, редуктор

**Extra coolant** (`coolant_t_bosch`, `coolant_t_vdo120`).
- Bosch 0 280 130 026: M12×1.5, 19 mm hex, 25 N·m, −40…130 °C, ±1.4 °C at 25 °C, ±3.4 °C at 100 °C ([Bosch][bosch026]).
- 0 280 130 023, 032 and 039 have the same curve ([msefi][bosch_same], medium).
- Subaru OEM ECT 22630AA161 (supersedes AA100/AA11A/AA160) is set up as "Standard Bosch NTC" on Link ECUs, with Bosch 026 named as a substitute ([Link forum][link_ect], medium).
- Mount it in an upper-hose adapter (38 mm on SG).
- `warnAbove` 105 matches the app's existing coolant "danger" level (engine-ej.md, rule thresholds 102/105/110).
- Never tee into the ECU's own ECT signal.

**ATF** (`atf_t_bosch`, low value).
- The 4EAT ATF temperature sensor sits on the lower valve body and feeds the TCU. The TCU uses it to block 4th when cold and to shift in POWER mode when hot ([automotivetechinfo][atf_4eat]).
- RomRaider lists ATF temperature at SSM address 0x49 for the 4AT ([RomRaider][atf_ssm], medium).
- **So read the TCU first.** An inline sensor (Bosch 026 in a hose-through adapter) only adds "temperature into the cooler".
- Line diameter and drain plug thread: not found.

**Rear differential** (`diff_t_aem`, low).
- 2008–14 STI has a rear-diff thermo switch (27043AA020). 2015+ STI has a rear-diff temperature sensor (22630AA260) ([TSB][sti_diff_tsb], [Subimods][sti_thermo]).
- The Mooresport R180 cover has a 1/8 NPT port ([SubiSpeed][r180_cover]).
- R160 plug thread: not found. Low value on a street car (our judgement).

**Для пользователя:**
- Доп. датчик ОЖ ставится в проставку в верхний патрубок радиатора. Штатный датчик ЭБУ не трогайте.
- Для АКПП сначала посмотрите температуру ATF от блока АКПП, она уже есть. Отдельный датчик нужен редко.

---

## 7. Воздух до и после интеркулера (IAT pre/post IC)

**Background:**
- Process West says the stock Forester XT top-mount intercooler peaked at **72 °C** in mountain driving ([Process West via revzone][pw_ic], vendor claim, low-medium).
- Mishimoto drilled the inlet and outlet tanks for its tests ([Mishimoto][mishimoto_ic]).

**Presets:**

| Preset | Part | Thread | Table | Pull-up |
|---|---|---|---|---|
| `iat_pre_bosch` | Bosch 0 281 002 170 (to 150 °C) | M12×1.5 | [Bosch sheet][bosch170] | 1 kΩ |
| `iat_post_aem` | AEM 30-2010 | 3/8-18 NPT | [AEM sheet][aem2010], rated to 135 °C | 2.2 kΩ |
| `iat_gm` | GM 25036751 open element, 12146312 and others | not verified | Forum-copied GM table that matches the MegaSquirt defaults (−40 = 100.7 kΩ, 30 = 2238 Ω, 99 = 177 Ω), up to 100 °C only ([truckmodcentral][gm_tab], [msefi][gm_ms]) | 2.2 kΩ |
| `tmap_t_bosch` | Bosch PST 1/PST 4, pin 2 | n/a | [Bosch PST 1][pst1] / [PST 4][pst4] sheets, −40…130 °C | 1 kΩ |

Mount the pre-IC sensor in the turbo-to-intercooler pipe and the post-IC sensor near the throttle body. No `warnAbove`:
no sourced threshold was found.

**Для пользователя:**
- Один датчик ставится до интеркулера, второй после. По разнице видно, как интеркулер справляется в пробке и на трассе.
- Нужны бобышки в патрубки: их вваривают или вклеивают. Обычно это делают в мастерской.

---

## 8. Широкополосная лямбда (wideband AFR analog outputs)

All are kind `VOLT`, AFR = a + b·U, gasoline, 14.7 stoich (AEM uses 14.65).

| Preset | Controller | a | b | 0 V → 5 V | Wire | Source | Conf. |
|---|---|---|---|---|---|---|---|
| `afr_lc2` | Innovate LC-2 out 1; LC-1 brown wire; MTX-L; LM-2 out 2 | 7.35 | 3.008 | 7.35 → 22.39 | LC-2 yellow | [LC-2 manual p.4][lc2], [ecmtuning LC-1][lc1], [rusEFI][rusefi_ego] | high (LM-2 medium) |
| `afr_aem_x` | AEM X-Series 30-0300/0310 | 7.3125 | 2.375 | valid 0.5–4.5 V = 8.5–18.0 | white +, brown − | [AEM 30-0310 manual][aemx] | high |
| `afr_aem_uego` | AEM 30-4110, 30-4100 (switch at P0) | 10 | 2 | 10 → 20 | white | [AEM 30-4100][aem4100]; 30-4110 from listings | 30-4100 high, 30-4110 medium |
| `afr_spartan` | 14Point7 Spartan 2, 3, 3 Lite v2, SLC Free | 10 | 2 | 10 → 20 (λ 0.68–1.36) | green | [Spartan 3 Lite v2 manual][spartan], [drive2][spartan_d2] | high |
| `afr_plx` | PLX SM-AFR / DM-6 | 10 | 2 | 10 → 20 | | [PLX guide][plx] (search excerpt) | medium |
| `afr_techedge` | Tech Edge 2J, 3A/3B/3H WBlin | 9 | 2 | 9 → 19 | WB LIN+/− | [wbo2.com][techedge] | high |
| `afr_zeitronix` | Zeitronix Zt-2 (2010+), ZT-3 | 9.6 | 2 | 9.6 → 19.6 | white | [Zeitronix][zeitronix] | high |
| `afr_ecotrons` | Ecotrons ALM | 7.35 | 4.41 | λ 0.5 → 2.0 | blue ANOUT, black GND-R | [ALM-LED manual §5.5][ecotrons] | high |
| `afr_china` | generic LSU 4.9 | 10 | 2 | most often 10 → 20 | | [drive2 SLC][spartan_d2] | low |

**Caveats:**
- **LC-2 out 2 and LC-1 yellow** are narrowband simulation and unusable here.
- **AEM X:** below 0.5 V means "not ready" and above 4.5 V means "error" (free air reads 4.5 V). The output is differential, so brown goes to the box GND.
- **AEM 30-4100** with no sensor outputs 2.35 V.
- **Tech Edge 3-series** can be set to 0–8.192 V, which would damage the Arduino.
- **Zt-2** units from 2003–09 have a nonlinear main output.
- **Ecotrons ALM-LD** is 0–10 V.
- **Haltech WB1/WB2 and Link CAN-Lambda** are CAN only, with no analog output ([Haltech][haltech_wb]).
- **RUSC-3.oem** (5000 ₽) is sold as an LC-2-compatible clone; its output scaling is unverified ([lampakb][rusc3]).
- **Resolution:** 10-bit at 5 V is about 0.01 AFR per count, which is fine. Ground offset matters more: on LC-2, 1 mV of offset is about 0.003 AFR. Heater current (3–4 A) shifts ground with load ([HP Academy][hpa_innovate]). Run a separate signal-ground wire from the controller to the box.

**Prices:**
- Bosch LSU 4.9 0 258 017 025 is about 11 790 ₽ ([forwardauto][lsu_price]). Cheap Yandex Market listings around 800 ₽ are likely copies.
- The MCE controller (Russia) is 15 900 ₽ ([mcedevices][mce]).
- LC-2, AEM X and Spartan in rubles are estimates from US/EU prices: Spartan 3 Lite v2 is $135 with sensor, LC-2 $157–211, AEM X about $200–231.

**Для пользователя:**
- Берите линейный выход 0–5 В, не «узкополосный» 0–1 В.
- Массу сигнала ведите отдельным проводом прямо от контроллера к GND платы.
- Если контроллера нет в списке, найдите в инструкции, сколько AFR он показывает при 0 В и при 5 В, и впишите.

---

## 9. Температура выхлопа (EGT)

**Amplifier choice:**

| Chip | Supply | Range, resolution | Accuracy (K) | Fault detect | Verdict |
|---|---|---|---|---|---|
| **MAX31855K** | 3.0–3.6 V, abs max 4.0 V | −200…1350 °C, 14 bit, 0.25 °C, ~10 Hz | ±2 °C (−200…700), ±4 °C (700…1350) | open, short to GND, short to VCC | **use**; the current firmware reads it |
| MAX6675 | 3.0–5.5 V | 0…1023.75 °C, 12 bit, 0.25 °C, ~4–5 Hz | ±2.25 °C (0–700), ±4.75 °C (700–1000) | open only; T− must be grounded | works, but clips at 1024 °C and is legacy/NRND. The firmware would need a 16-bit read (`>>3`, ×0.25) |
| MAX31856 | ~3.3 V (unverified) | any type, 19 bit, LUT linearization | ±0.15 % | ±45 V input protection | best, but the firmware would need a register-level driver |

Sources: [MAX31855 data sheet][max31855], [MAX6675 data sheet][max6675], [Adafruit FAQ][ada_faq], [ADI EZ][ez6675].

**MAX31855 notes:**
- It uses a straight line (41.276 µV/°C). Against the NIST K table our own calculation gives about 806 °C at a true 800 and 904 at 900, so under 6 °C error in the EGT band. Correction matters below 400 °C and above 1100 °C.
- The breakout must be 5 V tolerant. Adafruit 269 has a 3.3 V regulator and level shifting ([Adafruit][ada269]).
- Marketplace listings often sell MAX6675 boards under a "MAX31855" title, so check the chip marking.

**Probe:**
- Use an **ungrounded** K junction. The MAX31855 data sheet says T− "Do not connect to GND". A grounded tip in the manifold gives a short-to-GND fault and ties engine ground to USB ground.
- Exposed tips are fastest (Sensor Connection EGT-EP: 3/16" Inconel, 250 ms, 1/8-27 NPT compression fitting, ±0.4 %, [data sheet][egt_probe]). Closed tips last longer and are slower.
- A US probe cost 7310 ₽ at 1evel.ru per a drive2 log ([drive2][egt_d2]).

**Mounting on EJ:** pre-turbo, in the up-pipe.
- 2002–05 US WRX already has a factory EGT sensor there. Turbo removal is required to take the joint pipe off ([FSM][fsm_egt]).
- Drilling cast manifolds risks cracks. Welding a 1/8 NPT bung into a steel up-pipe is the usual route.

**Limits:**
- Garrett G-series turbine housings are rated to 1050 °C ([Garrett][garrett]).
- The often quoted 950 °C limit for older turbos, and typical EJ257 WOT values of 850–950 °C, are **unverified**.
- `warnAbove` 900 °C is kept from the existing preset as a judgement value.

**Для пользователя:**
- Нужен модуль MAX31855 с питанием 5 В (с регулятором на плате) и щуп K-типа с изолированным спаем.
- Щуп ставится до турбины, в приёмную трубу. Бобышку 1/8 NPT вваривают в мастерской.
- Удлинять провода щупа можно только термопарным проводом типа K, обычный медный даст ошибку.

---

## 10. Flex fuel (этанол)

**GM/Continental 13577429** (short body, 3/8" quick-connect) ([Racetronix][flex_rt]):
- **Supply:** 12 V battery feed. Output is a frequency signal that needs a pull-up to 5 V: 2.2–10 kΩ (Racetronix), about 3 kΩ (rusEFI, [wiki][flex_rusefi]).
- **Ethanol:** % = f − 50 over 50–150 Hz, ±5 % accuracy. 180–190 Hz means contaminated fuel or water ([MoTeC forum][flex_motec]).
- **Fuel temperature:** °C = 41.5·ms − 81.25 from the pulse width.
- **Pinout:** A = 12 V, B = GND, C = signal (Racetronix). This differs from other summaries, so check the body markings.
- **Behaviour:** one user reports it does not read without fuel flow.
- **Part numbers:** 13577379 is the short-tube version and 13577394 the long-tube version (rusEFI). The Racetronix listing marks it obsolete ($72; Exoracing £124).
- **Ruble price:** unverified.

**Power:** use a Littelfuse-style MINI add-a-circuit ([Littelfuse][littel]) on an ignition-switched fuse, with a 5 A fuse
in the tap. Ozon prices are 109–390 ₽ ([Ozon][fuse_ozon]).

The SG/SH fuse type was not found in a source. It is probably MINI (ATM), so pull one fuse and check before buying. The SH
cabin box is behind the cover left of the steering column ([carwiki][carwiki]).

**Для пользователя:**
- Датчику нужно 12 В. Возьмите их через разветвитель предохранителя (fuse tap) с предохранителем 5 А, в гнезде, где плюс есть только при включённом зажигании.
- Сначала выньте один предохранитель и посмотрите, какой он формы (обычно «мини»), и разветвитель берите такой же.
- Сигнал подключите к цифровому входу F0 платы и поставьте резистор 3,3 кОм от этого входа к 5V.

---

## 11. Ток АКБ / генератора (battery current)

**ACS758** ([Allegro data sheet Rev 20][acs758]):
- Supply 3–5.5 V. Output is ratiometric for 4.5–5.5 V.
- Zero is VCC/2 for B (bidirectional) parts and 0.6 V for U (unidirectional) parts.

| Part | Sensitivity | Noise |
|---|---|---|
| 050B | 40 mV/A | 10 mV |
| 100B | 20 mV/A | 6 mV |
| 150B | 13.3 mV/A | 4 mV |
| 200B | 10 mV/A | 3 mV |

- U parts are not exactly double: 050U is 60 mV/A.
- It survives 1200 A for 1 s at 1 % duty.
- Allegro marks it "not for new design" and points to ACS772/773.
- Current must flow through the leadframe: cut the cable and bolt it on. **It is not a clamp.**

**Presets:**
- `batt_i_acs758_100`: alternator output, I = 50·U − 125.
- `batt_i_acs758_200`: battery cable, I = 100·U − 250. Cranking current clips.

**Error and alternatives:**
- With the `VOLT` kind the zero error is about (vcc − 5)/2 V, which is about 5 A on 100B at 4.8 V. This preset needs `RATIO` or a user zero trim.
- Subaru alternator rating: unverified.
- ACS712 (5/20/30 A: 185/100/66 mV/A, [data sheet][acs712]) is too small for a car's main current.
- I²C shunt modules (INA219/INA226) and split-core Hall sensors (HSTS016L, DHAB) were not researched. They would need firmware support.
- Module price is about 370–400 ₽ ([Yandex Market][acs758_ym]).

**Для пользователя:**
- Датчик тока ставится в разрыв провода: провод режут и прикручивают к датчику. Работайте только при снятой клемме АКБ.
- Для генератора хватит датчика на 100 А, на провод аккумулятора нужен 200 А.

---

## 12. Тормоза (brake pressure)

- **Sensor:** AEM 30-2130-2000, psi = 500·V − 250 ([AEM][aem2130]).
- **Pressures:** hard braking is about 1200 psi / 83 bar or more ([Mark Williams][brake_mw]). Race logs show about 1200 psi normal, up to 1900 psi / 131 bar ([Rennlist][brake_rl]).
- **Fitting:** needs an M10×1 brake-line tee with a 1/8 NPT port (no sourced part number) and brake-fluid compatible wetted parts.
- **Safety:** this is a safety-critical joint, so the tip says "делать в сервисе". Confidence medium.

**Skipped: clutch pressure.** A pedal switch gives the same information on a road car (our judgement, no source).

---

## 13. Пропущено (skipped, with reasons)

| Sensor | Why skipped |
|---|---|
| **Fuel level sender** | Sources disagree. The Forester has two senders in a saddle tank, about 1–4 Ω each when full and 2–8 Ω combined ([Subaru Techline via UHS][uhs_fuel]). A retailer guide gives 6.5–8.5 Ω full and about 185–226 Ω empty for 2012–16 Impreza (low). The gauge is driven by the body integrated unit, which averages the two. Tapping the sender with a pull-up would disturb the factory gauge. Read fuel level over SSM/BIU instead. |
| **Coolant level switch** | None found on EJ/FB. A float switch could go on a free digital pin later, but there is no Subaru-specific part. |
| **Clutch pressure** | No value on a street car (judgement). |
| **GM 1-bar MAP** | No use on a turbo car (above 1 bar absolute), and NA cars already have a MAF. |
| **Bosch TMAP 0 261 230 042 / 030 / 119 / 146** | Voltage endpoints not verified. A user can enter two calibration points with `volt_any`. |
| **Defi, Greddy, Apexi, Autometer, ProSport senders** | No published tables. Defi needs Defi-Link. |
| **MAX6675 / MAX31856** | Need firmware changes (§9). |

---

## 14. Электрика (electrical guidance)

### 14.1 Input protection (each analog input)
- **Absolute max** on any ATmega328P pin is −0.5 V to VCC + 0.5 V. Injection current is limited to ±1 mA in the automotive data sheet ([ATmega328P automotive][m328]).
- **Series resistor 10 kΩ** keeps injection under 1 mA up to about 15 V of overvoltage.
- **100 nF to GND at the pin:**
  - It gives τ = 1 ms, fc ≈ 159 Hz.
  - It also feeds the sample-and-hold capacitor, which relaxes the "source impedance ≤ 10 kΩ" ADC guideline for slow signals.
  - Use it for temperature and pressure inputs.
- **Clamp:** BAT54S Schottky pair to 5 V and GND, for lower forward voltage and leakage than a TVS.
  - TI's guidance is to limit current with the series resistor, then clamp with Schottkys.
  - Put a TVS or Zener on the 5 V rail, because the USB rail may not be able to sink the clamp current ([TI SBAA506][ti_sbaa506]).
- **F0 frequency input:** 1 kΩ series, 3.3 kΩ pull-up to 5 V, 10 nF to GND (τ = 10 µs at 1 kΩ, harmless at 150 Hz).
  - The internal pull-up (20–50 kΩ) is too weak for flex sensors (§10).
- **Car transients** need not reach the box directly: everything is powered from USB.
  - The only 12 V wire is the flex sensor feed, which never touches the board.
  - For reference: load dump is 65–87 V under ISO 7637-2 pulse 5 and 79–101 V under ISO 16750-2 test A, and about 35 V when suppressed ([Diotec][diotec], [ADI][adi_ld]).

### 14.2 Pull-ups for NTC (60–130 °C)
- The geometric mean of the end resistances maximises swing ([Hamilton][geomean]). For the Bosch curve: 230 Ω for 60–130 °C, 323 Ω for 40–130 °C.
- Self-heating sets a lower limit. AEM gives 12 mW/°C dissipation, so 20 mW means 1–2 °C of error.
- **Choice:**

| Curve | Pull-up | Notes |
|---|---|---|
| Bosch/Subaru (2.5 kΩ at 20 °C) | **1 kΩ** | Bosch "typically 1 or 3 kΩ"; ~0.5 °C/count at 120–130 °C, ~1.9 mW at 130 °C |
| AEM 30-2012 and 10 kΩ B3950 | 2.2 kΩ | |
| GM/AEM 30-2010 (3.5 kΩ at 20 °C) | 2.2 kΩ | |
| VDO single-wire | 220 Ω / 0.5 W | |

- Use 1 % metal-film resistors.
- Because the pull-up and the ADC reference are the same 5 V, readings are ratiometric and USB sag cancels.

### 14.3 Grounding
- **Ground offset:** USB ground comes from the head unit, which is grounded somewhere in the dash. Sensor grounds are on the engine. Under alternator and starter load these points differ.
  - Haltech's guidance: one star ground (they suggest the cylinder head), sensors returned only to the controller's signal ground, and no parallel ground paths ([Haltech][haltech_gnd]).
  - Offsets of hundreds of mV during cranking are commonly reported, but no measured figure was sourced.
- **Practice for the box:**
  1. One dedicated GND wire from the box to the engine block near the sensors. Case-grounded sensors (VDO, 1-wire NTC, thermocouple bung) need this.
  2. 3-wire sensors return their ground to the box GND, not to the body.
  3. Wideband controllers: heater/power ground to chassis as their manual says; signal ground (LC-2 shares one, AEM brown, Tech Edge WB LIN−, Ecotrons GND-R) as a separate wire to the box GND.
  4. If readings jump with the fan or headlights, use a USB isolator, ADuM3160 class (full-speed, 2500 Vrms, [ADI][adum3160]). The box then lives entirely on the engine side and needs its own 5 V from a 12 V→5 V converter.
- **Shielding:** shielded cable for the thermocouple, F0 and wideband signal runs, with the shield grounded **at the box end only**. This is common practice; no source was found for it.

### 14.4 5 V vs 3.3 V boards
- 0.5–4.5 V ratiometric sensors and the app's ratiometric math assume a 5 V supply equal to the ADC reference.
- A 3.3 V board (Pro Micro 3.3 V/8 MHz, ESP32) needs dividers and loses that cancellation ([SparkFun][sf_promicro]).
- The ESP32 ADC is accurate only within 150–2450 mV at 11 dB, and ADC2 is blocked while Wi-Fi is on ([Espressif][esp_adc]).
- **Use 5 V boards only.**

### 14.5 12 V sensors
- Only the flex sensor needs 12 V. Feed it through a fuse tap with its own 5 A fuse on an ignition-switched circuit.
- Ground it at the same engine point as the box. Its signal goes to F0 with the pull-up to the box's 5 V.

**Для пользователя (электрика):**
- Каждый аналоговый вход защитите: резистор 10 кОм последовательно, конденсатор 100 нФ на GND и сдвоенный диод BAT54S к 5V и GND.
- От GND платы проведите один толстый провод к массе двигателя рядом с датчиками. Все массы датчиков сводите в эту точку.
- Массу сигнала ШДК ведите к плате отдельным проводом. Массу питания ШДК подключайте к кузову, как в его инструкции.
- Провода термопары, датчика этанола и ШДК лучше вести экранированным кабелем. Экран подключайте только со стороны платы.
- Плата нужна на 5 В (Nano, Uno, Pro Micro 5 В, Mega). Платы на 3,3 В и ESP32 не подойдут.
- 12 В нужно только датчику этанола. Берите их через разветвитель предохранителя, с отдельным предохранителем 5 А.

---

## 15. Источники (sources)

### Subaru

[kn_m20]: https://www.knfilters.com/oil-filters/subaru/impreza/2.0l-h4-gas/2024
[motof]: https://motofaction.org/cars/subaru-liberty-legacy-be-bh-bt-3rd-gen/which-oil-filter-aftermarket-options-alternatives-list-can-use
[fsm_ops_rem]: https://saabwisonline.com/9-2x-9346/2005/2-engine/lubrication-2-0t/oil-pressure-switch/adjustment-replacement/removal
[fsm_ops]: https://saabwisonline.com/9-2x-9346/2005/2-engine/lubrication-2-0t/oil-pressure-switch/adjustment-replacement/installation
[bspt_grm]: https://grassrootsmotorsports.com/forum/grm/parts-hunt-18-bsp-threaded-oil-pressure-sender/240234/page1
[autodoc_sh]: https://www.autodoc.co.uk/car-parts/oil-pressure-switch-sender-unit-valve-10578/subaru/forester/forester-sh
[ts730]: https://subimods.com/products/torque-solution-oil-sender-y-adapter-2015-2021-wrx-2014-2017-forester-xt
[kb_m18]: https://subiesupplyco.ca/products/killer-b-oil-temperature-sensor-adapter-m18-npt
[m18]: https://www.rallysportdirect.com/products/ts-su-609-torque-solution-oil-galley-plug-m18x1-5-to-1-8npt-adapter
[prosport_remote]: https://www.subispeed.com/products/prs-psropkit-prosport-remote-oil-pressure-temperature-sender-install-kit
[iboats]: https://forums.iboats.com/threads/lost-all-my-oil-again.739564/post-5613980
[perrin_plate]: https://www.kamispeed.com/products/perrin-oil-temp-and-pressure-adapter
[mishimoto_plate]: https://jbspowercentre.com/products/flds-mmop-sptr
[blitz_plate]: https://justjap.com/collections/brz/products/blitz-oil-filter-sensor-attachment-sandwich-plate-type-h-65mm-fits-subaru-toyota-honda-fa20-fa24-fb16-fb20-k20c-l15-leb-s07a-s07b
[ir_spacer]: https://www.improvedracing.com/oil-adapters/oil-filter-spacer-recessed-filters-for-fa20-brz-86.html
[desert]: https://www.desertcart.vn/products/6633084
[meyle_ect]: https://www.autopartspro.co.uk/carparts-online/temperature-sensor/subaru-legacy/mf-meyle
[fsm_ect]: https://saabwisonline.com/9-2x-9346/2005/2-engine/fuel-injection-fuel-systems-2-0t/engine-coolant-temperature-sensor/adjustment-replacement/removal
[goparts_ect]: https://www.go-parts.com/garage/ps-1995-2004-subaru-legacy-engine-coolant-temperature-sensor
[gates_sg]: https://www.superspares.com.au/gates-upper-radiator-curved-hose-for-subaru-forest~11140061
[perrin_hose]: https://www.kamispeed.com/products/perrin-coolant-hose-adapter
[greddy_hose]: https://greddy.com/products/16401690
[fsm_egt]: https://saabwisonline.com/9-2x-9346/2005/2-engine/fuel-injection-fuel-systems-2-0t/exhaust-temperature-sensor/adjustment-replacement/installation
[sidc]: https://sidc.co.uk/forums/topic/54210-up-pipe-question
[anand_fxt]: https://forums.anandtech.com/threads/vipergtss-2004-subaru-forester-xt-build-thread.2237467/page-3
[grimm]: https://www.kamispeed.com/products/grimmspeed-uppipe-subaru-2002-2014-wrx-sti
[tomioka]: https://www.subispeed.com/products/tr-subaru-2-bolt-up-pipe
[fsm_intake]: https://saabwisonline.com/9-2x-9346/2005/2-engine/fuel-injection-fuel-systems-2-0t/intake-manifold/adjustment-replacement/removal
[cobb_fpr]: https://www.cobbtuning.com/products/fuel-system/subaru-toyota-in-tank-4-5-bar-fuel-pressure-regulator-kit-wrx-2022-2026-outback-xt-2020-2026-outback-wilderness-2022-2026-ascent-2019-2026-legacy-2020-2025-brz-2022-2025-gr86-2022-2025
[radium]: https://subiesupplyco.ca/products/radium-engineering-fuel-level-sender-adapter-fa20dit
[fsm_instr]: https://saabwisonline.com/9-2x-9346/2005/3-electrical-system/instrumentation-driver-info
[fsm_oilp]: https://saabwisonline.com/9-2x-9346/2005/2-engine/mechanical-2-0t/engine-oil-pressure/adjustment-replacement/inspection
[fsm_fuel_20t]: https://saabwisonline.com/9-2x-9346/2005/2-engine/mechanical-2-0t/fuel-pressure/adjustment-replacement/inspection
[fsm_fuel_25i]: https://saabwisonline.com/9-2x-9346/2005/2-engine/mechanical-2-5i/fuel-pressure/adjustment-replacement/inspection
[glowshift_tee]: https://www.glowshiftdirect.com/subaru-impreza-wrx-fuel-line-fuel-pressure-t-fitting-adapter/
[cobb_tap]: https://wrxdaily.com/products/cobb-tuning-billet-efi-quick-disconnect-1-8-npt-tap-5-16-2008-2014-wrx-2008-2021-sti
[gfb5752]: https://gfb.com.au/products/fuel-management-products/fuel-pressure-regulator-accessories-and-spares/5752-subaru-nissan-fuel-rail-adaptor-to-1-8-npt/
[atf_4eat]: https://automotivetechinfo.com/2004/02/subaru-4eat-phase-1-transmission-diagnosis-and-service
[atf_ssm]: https://www.romraider.com/forum/viewtopic.php?p=176380
[sti_thermo]: https://subimods.com/products/subaru-oem-thermo-switch-rear-differential-08-14-sti-27043aa020
[sti_diff_tsb]: https://static.nhtsa.gov/odi/tsbs/2014/SB-10068343-0699.pdf
[r180_cover]: https://www.subispeed.com/products/msi-msisub03-00-010-mooresport-inc-r180-rear-differential-cover
[mishimoto_ic]: https://www.mishimoto.com/engineering/2014/07/2008-2014-subaru-sti-performance-top-mount-intercooler-part-4-testing-data-compilation-and-product-completion
[pw_ic]: https://shop.revzone.com.au/products/process-west-top-mount-intercooler-kit-black-for-s-669943
[mishimoto_sti]: https://www.mishimoto.com/mwdownloads/download/link/id/1094
[uhs_fuel]: https://www.underhoodservice.com/?p=79382
[carwiki]: https://carwiki.de/subaru-forester-sicherungskasten

### Temperature sensors

[bosch026]: https://www.bosch-motorsport.com/content/downloads/Raceparts/Resources/pdf/Data%20Sheet_70101387_Temperature_Sensor_NTC_M12.pdf
[bosch170]: https://www.bosch-motorsport.de/content/downloads/Raceparts/Resources/pdf/Data%20Sheet_70115595_Temperature_Sensor_NTC_M12-H.pdf
[bosch_same]: https://msefi.com/viewtopic.php?p=178067
[link_ect]: https://forums.linkecu.com/topic/600-quick-question-about-subaru-coolant-sensor/
[aem2012]: https://documents.holley.com/30-2012_sensor_data.pdf
[aem2012i]: https://documents.holley.com/30-2012.pdf
[aem2011]: https://documents.aemelectronics.com/techlibrary_30-2011_sensor_data.pdf
[aem2010]: https://documents.holley.com/30-2010_sensor_data.pdf
[gm_tab]: https://www.truckmodcentral.com/threads/how-to-testing-coolant-temperature-sensors.4567/
[gm_ms]: https://msefi.com/viewtopic.php?p=211611
[mf52]: https://en.semiware.com/uploads/datasheet/MF52A103F3950.pdf
[vdo_t]: https://web.archive.org/web/2016/http://www.vdo-instruments.com/media/instructions/TU00-0770-5104620%20Temperature%20Sensors_short_version.pdf
[vdo_t150]: https://www.merlinmotorsport.co.uk/files/transfer/technical/doc/vdotempsensspec.pdf
[iag_prosport]: https://www.iagperformance.com/products/prosport-oil-or-water-temperature-sensor-for-stepper-motor-gauges
[geomean]: https://physerver.hamilton.edu/courses/Spring18/Phy245/GeometricMean.xhtml

### Pressure sensors

[aem2130]: https://documents.aemelectronics.com/techlibrary_30-2130-xx-pressure-sensors-instructions.pdf
[aem2131]: https://cdn.vividracing.com/file/manual30-2131-XXXPressureSensors.pdf
[pst1]: https://www.bosch-motorsport.com/media/catalog_content/downloads_catalog/pdf_catalog/data_sheet_70513419_pressure_sensor_combined_pst_1.pdf
[pst4]: https://www.bosch-motorsport.com/content/downloads/raceparts/Resources/pdf/Data%20Sheet_70524939_Pressure_Sensor_Combined_PST_4.pdf
[pstf]: https://bosch-motorsport.com/content/downloads/Raceparts/Resources/pdf/Data%20Sheet_70496907_Pressure_Sensor_Combined_PST-F_1.pdf
[motec_tmap]: https://www.motec.com.au/products/TMAP%20Sensor
[speeduino]: https://raw.githubusercontent.com/noisymime/speeduino/master/reference/speeduino.ini
[diy_gm3]: https://diyautotune.com/products/gm-3-bar-map-sensor
[robie]: http://robietherobot.com/storm/mapsensor.htm
[vdo_p]: http://www.druckgeber-oeldruckgeber.de/vdo-druckgeber-ohne-wk-daten.html
[am2242]: https://autometer.com/100-psi-electric-oil-pressure-sensor.html
[honeywell_mipan]: https://www.digikey.co.uk/en/products/detail/honeywell-sensing-and-productivity-solutions/MIPAN2XX150PSAAX/21714067
[dataq]: https://www.dataq.com/products/accessories/pressure-sensor/5vindex.html
[link_cheap1]: https://forums.linkecu.com/topic/10823-oil-and-fuel-pressure-sensor/
[link_cheap2]: https://forums.linkecu.com/topic/11781-oil-pressure-sensor-issue/
[hpa_bosch]: https://www.hpacademy.com/es/foro/discusion-general-sobre-afinacion/show/combination-fluid-temperature-and-pressure-sensor/
[brake_mw]: https://markwilliams.com/brake-system-pressure.html
[brake_rl]: https://rennlist.com/forums/993-forum/603721-brake-bias-issues-2.html

### Wideband

[lc2]: https://www.manualsdir.com/manuals/402580/innovate-motorsports-lc-2.html?page=4
[lc1]: https://ecmtuning.com/wiki/lc1install
[rusefi_ego]: https://raw.githubusercontent.com/rusefi/rusefi/master/firmware/controllers/sensors/impl/ego.cpp
[aemx]: https://www.carmodsaustralia.com.au/assets/brochures/30-0310.pdf
[aem4100]: https://documents.aemelectronics.com/techlibrary_30-4100_digital_wideband_uego_gauge.pdf
[spartan]: https://diyautotune.com/wp-content/uploads/Spartan_3_Lite_v2_User_Manual_Dec_19_2023.pdf
[spartan_d2]: https://www.drive2.ru/l/668991528802191034/
[plx]: https://www.plxdevices.com/v/vspfiles/support/user_guides/SM-AFRGen2_user_guide.pdf
[techedge]: https://www.wbo2.com/3b1/default.htm
[zeitronix]: https://www.zeitronix.com/Products/zt2/widebandoutput.shtml
[ecotrons]: https://ecotrons.com/files/ALM-LED%20Manual.pdf
[haltech_wb]: https://support.haltech.com/portal/en/kb/articles/can-wideband-controller-wb1-and-wb2-bosch-lsu-4-9-lambda-sensors
[hpa_innovate]: https://www.hpacademy.com/forum/link-g4-plus-software-tutorial/show/innovate-analog-input-not-reading-correctly
[lsu_price]: https://forwardauto.ru/catalog/product_bosch-0258017025-datchik-kislorodnyy-lsu-4-9-bosch-lyambda-zond-shirokopolosnyy-95591/
[mce]: https://mcedevices.ru/catalog/MotorSport/602.product
[rusc3]: https://lampakb.ru/index.php?product_id=74&route=product%2Fproduct

### EGT, flex, current, electrical

[max31855]: https://cdn-shop.adafruit.com/datasheets/MAX31855.pdf
[max6675]: https://cdn-shop.adafruit.com/datasheets/MAX6675.pdf
[ada_faq]: https://learn.adafruit.com/thermocouple/f-a-q
[ez6675]: https://ez.analog.com/data_converters/w/documents/28925/what-is-maxim-s-best-alternative-to-obsolete-part-max6675
[ada269]: https://www.adafruit.com/product/269
[egt_probe]: https://alliancesensors.com/sites/default/files/2021-09/EGT-EP%20Exhaust%20Probe_90%20Degree_Compression%20Fitting_Type%20K_Data%20Sheet_Rev5.pdf
[egt_d2]: https://www.drive2.ru/l/634312932062138725/
[garrett]: https://www.garrettmotion.com/news/newsroom/article/harut-stepanyan-discusses-the-features-of-the-new-garrett-g-series-g35-turbochargers/
[flex_rt]: https://www.racetronix.biz/p/sensor-e85-qd-3-8-i-o-short-small/13577429
[flex_rusefi]: https://wiki.rusefi.com/Flex-Fuel
[flex_motec]: https://forum.motec.com.au/viewtopic.php?p=20549
[littel]: https://littelfuse.com/products/fuse-blocks-fuseholders-and-fuse-accessories/specialty-items/add-a-circuit-mini-carded.aspx
[fuse_ozon]: https://www.ozon.ru/category/razvetvitel-predohranitelya-mini-s-gilzoy/
[acs758]: https://www.allegromicro.com/-/media/files/datasheets/acs758-datasheet.pdf
[acs712]: https://www.allegromicro.com/-/media/files/datasheets/acs712-datasheet.pdf
[acs758_ym]: https://market.yandex.ru/card/lineynyy-datchik-toka-acs758lcb-050b-pff-t-acs758lcb-200b-pfff-t-acs758lcb-100b/103721509489
[m328]: https://ww1.microchip.com/downloads/en/DeviceDoc/Atmel-7810-Automotive-Microcontrollers-ATmega328P_Datasheet.pdf
[ti_sbaa506]: https://ti.com/lit/SBAA506
[diotec]: https://p7f.vogel.de/companies/6a/4e/6a4e52d6c1b9a/app-note-load-dump-protection-v10.pdf
[adi_ld]: https://www-cce.cldnet.analog.com/media/en/analog-dialogue/volume-54/number-1/protecting-and-powering-automotive-electronics-systems-with-no-switching-noise.pdf
[haltech_gnd]: https://support.haltech.com/portal/en/kb/articles/ecu-grounding-the-dos-and-donts
[adum3160]: https://www.analog.com/en/products/adum3160.html
[sf_promicro]: https://www.sparkfun.com/products/12587
[esp_adc]: https://docs.espressif.com/projects/esp-idf/en/v4.4/esp32/api-reference/peripherals/adc.html

### Key documents (plain links)

- Bosch NTC M12 (0 280 130 026): https://www.bosch-motorsport.com/content/downloads/Raceparts/Resources/pdf/Data%20Sheet_70101387_Temperature_Sensor_NTC_M12.pdf
- Bosch NTC M12-H (0 281 002 170): https://www.bosch-motorsport.de/content/downloads/Raceparts/Resources/pdf/Data%20Sheet_70115595_Temperature_Sensor_NTC_M12-H.pdf
- Bosch PST 1 / PST 4 / PST-F 1: see the URLs above
- AEM 30-2010 / 30-2011 / 30-2012 sensor data: https://documents.holley.com/30-2010_sensor_data.pdf, https://documents.aemelectronics.com/techlibrary_30-2011_sensor_data.pdf, https://documents.holley.com/30-2012_sensor_data.pdf
- AEM 30-2130 instructions: https://documents.aemelectronics.com/techlibrary_30-2130-xx-pressure-sensors-instructions.pdf
- VDO temperature senders: https://web.archive.org/web/2016/http://www.vdo-instruments.com/media/instructions/TU00-0770-5104620%20Temperature%20Sensors_short_version.pdf
- Saab 9-2X (2005 WRX) service manual: https://saabwisonline.com/9-2x-9346/2005/
- MAX31855: https://cdn-shop.adafruit.com/datasheets/MAX31855.pdf
- ACS758: https://www.allegromicro.com/-/media/files/datasheets/acs758-datasheet.pdf
- rusEFI wideband defaults: https://raw.githubusercontent.com/rusefi/rusefi/master/firmware/controllers/sensors/impl/ego.cpp
