# Knowledge base: automatic transmission, AWD, tyres, electrical, maintenance

Scope: Subaru Forester SG/SH, Impreza GC/GD/GG, Legacy BE/BH/BL/BP, 1999 to 2010, 4EAT (4AT) and 5EAT (5AT).
Engine rules live in a separate file. This file is for the smart analyzer: each rule has numbers, context and sources.
Driver texts are plain Russian, one or two sentences, no jargon, no long dashes.

Conventions used below:

* `steady` = speed change < 3 km/h and throttle change < 5 % over the window, gear unchanged, no shift in progress.
* `warm` = atfC between 50 and 100 °C (factory road test window, source S5).
* `straight` = GPS heading change < 2 ° per second over the window (or no GPS: skip rules that need it).
* `slipRpm` = rpm (ECU) minus turbineRpm (TCU), sampled in the same polling cycle. `slipPct` = slipRpm / rpm × 100.
* Gear ratios 4EAT: 1st 2.785, 2nd 1.545, 3rd 1.000, 4th 0.694, R 2.272 (S5).
* SSM sampling: ECU and TCU share one K-line. Real update rate per parameter is a few Hz. Any rule that needs sub-second timing (shift time, crank dip, shudder) is marked low or medium confidence for this reason.

---

## 0. Critical interpretation notes (read before coding)

1. **Lock-up duty polarity is consistent**: about 5 % = released, about 95 % = fully applied, on every Subaru document found (S4, S5, S7, S13). Slip (partial) lock-up at 35 to 40 km/h reads 25 to 45 % (S5).
2. **Transfer duty polarity is NOT consistent between TCU generations and markets.**
   * 2004 USDM Forester 4AT (H4SO) FSM, "ON duty": P/N 5 % gives 0 kPa clutch pressure, D at full throttle 95 % gives 910 to 1070 kPa (maximum AWD), FWD fuse gives 0 kPa (S5).
   * EDM Forester SG (H4SO) FSM, Select Monitor reading: D, full throttle, 0 km/h = 5 to 10 %, N closed throttle = 60 to 70 % (S7). Opposite direction.
   * Phase 1 4EAT Select Monitor: 4WD mode 25 %, FWD mode 95 % (S4). End Wrench: "as the duty ratio increases, the amount of AWD decreases" (S2).
   * 2004 Forester XT (H4DOTC) FSM: N closed throttle 5 %, D full throttle 90 to 95 % (S6).
   * **Therefore the app must self-calibrate** (rule `transfer_duty_polarity_learn`). Never hard-code "high = more AWD".
3. **Line pressure duty goes the opposite way to pressure on 1999+ TCUs**: USDM 2004 NA: D closed throttle 95 % = 300 to 410 kPa (low), R full throttle 5 % = 1520 to 1716 kPa (high) (S5). EDM SG: N closed 100 %, full throttle 25 % (S7). Phase 1 Select Monitor shows the opposite (full throttle 100 %, closed 10 %) (S4). Learn direction from throttle.
4. **Alternator "smart charging" makes 12.5 to 13.0 V while driving normal** on 2001 to 2008 Forester SG, Impreza GD/GG, Legacy BE/BH/BL/BP with the 3-pin C/S/L alternator (S20, S21). The existing alert "alternator < 13.0 V running" will false-alarm. See `charging_cutout_normal`.
5. **5EAT TCU (Legacy BL/BP 2004+, Tribeca) does not report duty ratios**: it reports solenoid currents (A) and target pressures (kPa), two ATF temperatures and two turbine speeds (S12). Rules that use lockUpPct / transferPct / linePressurePct apply to 4EAT only unless noted. Slip and ratio rules work on both.
6. **TCU DTC numbering depends on TCU**: 1999 to 2005 4AT TCUs on SG/GD/GG/BE/BH mostly return 2-digit Subaru codes (11, 27, 33, 36, 71 to 79, 93), 2004+ Legacy 4AT, Forester XT and all 5AT return OBD-II P-codes. See section 5.
7. **1999 to 2000 TCUs**: some early TCUs (and all MY97-era units) talk only SSM1 (1953 baud) or do not answer SSM2 TCU requests (S37, S38). If the TCU does not answer, show "блок АКПП не отвечает по этому протоколу", never zeros, and disable all TCU rules.

---

## 1. Normal ranges

### 1.1 ATF temperature (atfC)

| Situation | Typical / target | Notes | Source |
|---|---|---|---|
| Cold start, winter | equals ambient | Phase 2 4EAT will not shift to 4th below about 12 °C ATF; this is normal | S13 |
| Cold ATF, start-up | line pressure set to minimum at low ATF temp / cranking | normal soft engagement when very cold | S2 |
| Level check window | 70 to 80 °C | after 5 to 10 km of driving or idling; level read at idle in P after cycling all positions | S5, S12 |
| Learning procedure window | 60 to 90 °C | factory "facilitation of learning control" | S6 |
| Factory road test window | 50 to 100 °C | lock-up checks are valid only inside it | S5 |
| Highway cruise, mild weather | 70 to 90 °C | owners with logs: 74 to 90 °C, "manual says 80 to 85" | S30, S32 |
| Summer city jam, no extra cooler | 95 to 110 °C | 106 °C in Moscow jam; up to 110 °C in city | S29, S32 |
| Mountain climb or towing, no extra cooler | 110 to 125 °C | > 124 °C logged on SH climbing | S30 |
| Damage zone | sustained ≥ 120 °C | fluid life roughly halves per +11 °C above 80 °C (cooler-maker chart, commercial source, treat as order of magnitude) | S36 |
| Sensor sanity | 20 °C ≈ 3.9 to 4.7 kΩ, 80 °C ≈ 500 to 600 Ω (H4DOTC) / 275 to 375 Ω (some H4SO) | for P0712/P0713 / code 27 diagnosis | S5, S6 |

### 1.2 Torque converter lock-up (lockUpPct, slip)

| Item | Value | Source |
|---|---|---|
| Lock-up released | duty ≈ 5 % | S4, S7, S13 |
| Lock-up fully applied | duty ≈ 95 % (H4SO EDM check: D, 75 km/h, ATF > 80 °C) | S4, S7 |
| Slip (partial) lock-up, steady 35 to 40 km/h, ATF 50 to 100 °C | 25 to 45 % | S5 |
| Slip lock-up abnormal | duty < 5 %, or duty drops right after rising: suspect wrong or degraded ATF | S5 |
| Gears with lock-up | all forward gears except 1st | S2 |
| Hot ATF mode | TCU also locks in 2nd and 3rd, raises shift points to pump more ATF through cooler | S1, S2 |
| Factory slip fault (4EAT OBD-II, P0740/P0741 logic) | lock-up duty ≥ 90 % and abs(engine rpm minus input rpm) > 40 + speed/2 continuously for 10.2 s | S3 |
| Factory gross slip criterion | in 4th, duty ≥ 90 %: engine rpm ≥ output speed × 0.694 × 9/8 (12.5 % slip) | S3 |
| Turbine at standstill in D or R, brake held | 0 rpm (turbine stopped by engaged clutch); in N/P turbine spins | S6, S8 |
| Engagement time lag N→D | < 1.2 s at 70 to 80 °C ATF | S2, S5 |
| Engagement time lag N→R | < 1.5 s | S2, S5 |
| Stall speed (sea level) | 2.0 NA 2000 to 2500, 2.5 NA 2100 to 2600, 2.0 T 2600 to 3300, 2.5 T 2700 to 3200 rpm | S5 |

### 1.3 Line pressure duty (linePressurePct), 4EAT

| Condition | Duty | Line pressure | Source |
|---|---|---|---|
| USDM 2004 NA, D, throttle closed | 95 % | 300 to 410 kPa | S5 |
| USDM 2004 NA, "2" HOLD, full throttle stall | 5 % | 1130 to 1275 kPa | S5 |
| USDM 2004 NA, R, full throttle stall | 5 % | 1520 to 1716 kPa | S5 |
| USDM 2004 turbo, D closed / 2 WOT / R WOT | 3.5 to 4.5 / 2.5 to 3.5 / 1.5 to 2.5 % (as printed) | 500 to 800 / 1400 to 1700 / 1600 to 1900 kPa | S5 |
| EDM SG, key on engine off, N, ATF > 80 °C | closed 100 %, full throttle 25 % | not given | S7 |
| Failsafe (solenoid fault) | solenoid off | line pressure maximum, harsh shifts, only 1st and 3rd on Phase 2 | S13 |

`brakeClutchPct` (2-4 brake duty, H4SO): closed throttle 100 %, full throttle 25 %, same direction as line pressure (S7).

### 1.4 Transfer (AWD) duty (transferPct), 4EAT, by situation

| Situation | Expected TCU behaviour | Source |
|---|---|---|
| P or N, idle | clutch pressure 0 (released end of the scale) | S5 |
| FWD fuse installed | clutch pressure 0 at idle and at full throttle; FWD lamp on | S5, S14 |
| D or R, idle, closed throttle (older 4EAT) | 7 to 11 psi (50 to 75 kPa) | S14 |
| D, full throttle, standstill or 1st | maximum: 910 to 1070 kPa (NA), 930 to 1100 kPa (turbo, in 2) | S5 |
| Light cruise, straight | low engagement, TCU varies it with throttle, speed and front/rear speed difference | S1, S2 |
| Front/rear speed difference (slip) | TCU raises engagement | S1, S14 |
| ABS active (Legacy) | fixed mostly-FWD ratio | S1 |
| Manual 1st range (1990+ Legacy) | more aggressive AWD map | S1 |
| Duty C solenoid failure (Phase 2) | TCU switches solenoid off, clutch pressure maximum: permanent 50/50, tight-corner braking | S13 |

### 1.5 Battery and charging (batteryV, altDutyPct)

| State | Value | Source |
|---|---|---|
| Open-circuit voltage after ≥ 4 h rest (24 h ideal), 26 °C | 100 % 12.65 V, 75 % 12.45 V, 50 % 12.24 V, 25 % 12.06 V, 0 % 11.89 V | S17 |
| Surface charge | reads high right after driving or charging; wait or apply brief load | S17 |
| Cranking minimum (load test ½ CCA, 15 s) | 9.6 V at ≥ 21 °C; scales down to 8.5 V at −18 °C (BCI table: 10 °C 9.4, −1 °C 9.1, −7 °C 8.9, −12 °C 8.7) | S19 |
| Charging, alternator engaged | 13.8 to 14.8 V (owners quoting 2008 FSM: 14.1 to 14.8 V) | S20, S21 |
| Smart charging cut-out (2001 to 2008 C/S/L alternator) | 12.5 to 13.0 V after 20 s to 1 min of low electrical load; returns to about 14 V with headlights, blower, low battery | S20, S21, S22 |
| altDutyPct | 0 to 100 %; owners' logs: 0 = alternator engaged (≈13.8 V+), 100 = cut out | S22, S23 |
| Lead-acid absorption limit at 25 °C | 13.8 to 14.7 V; temperature coefficient −3 mV per cell per °C (≈ −18 mV/°C for 12 V) | S18 |
| Overcharge signs | > 14.7 V warm and sustained: water loss, grid corrosion | S18 |
| ECM data item | "ALT Duty %" (generator duty ratio) is a factory Select Monitor item on 2004 Forester ECMs; its polarity must be learned (see `charging_cutout_normal`) | S24 |
| Parasitic draw (Subaru TSB, later models) | ≤ 70 mA average, typical 30 to 35 mA; an older 2008 TSB allowed 150 mA | S25 |
| Android head unit | standby 5 to 50 mA; awake roughly 0.5 to 2 A; a real case: 0.9 A with ignition off | S26 |

### 1.6 Tyres and wheel speeds

| Item | Value | Source |
|---|---|---|
| Subaru AWD circumference rule | all four within 1/4 inch (6 mm) of rolling circumference, roughly 2/32 inch (1.6 mm) tread depth | S27 |
| Equivalent speed mismatch | 6 mm on ≈ 2000 mm circumference ≈ 0.3 % | derived |
| Pressure vs temperature | about 1 psi (7 kPa) per 10 °F (5.6 °C), i.e. ≈ 1.2 kPa per °C at 220 to 250 kPa; use ideal gas on absolute pressure | S28 |
| Natural loss | about 1 psi (7 kPa) per month | S28 |
| TPMS legal threshold (FMVSS 138) | warn at ≥ 25 % below placard cold pressure within 20 min | S29b |
| TCU wheel speed resolution | 1 km/h per bit (frontWheelKmh / rearWheelKmh) | S0 |

---

## 2. Rules

Format per rule: id, title (RU), params, condition, severity, driver text (RU), causes ranked, cheapest checks first, applicability, sources, confidence, disagreements.

### A. ATF temperature

#### `atf_temp_elevated`
* Title: Коробка греется сильнее обычного
* Params: atfC, speedKmh, gear, range, throttlePct
* Condition: atfC ≥ 100 °C for ≥ 5 min continuously, or ≥ 105 °C for ≥ 2 min. Suppress during the first 2 min after a stop longer than 10 min (heat soak).
* Severity: info
* Driver text: Масло в коробке нагрелось выше 100 градусов. В пробке на долгих стоянках лучше ставить N или P, а в горку и с прицепом ехать спокойнее.
* Causes: 1) summer jam or long creeping in D; 2) climbing or towing; 3) radiator or ATF cooler fins clogged with dirt or fluff; 4) old ATF, slipping lock-up.
* Checks: radiator front clean; ATF level hot; compare `lockup_slip_*`; consider an extra ATF cooler (very common on drive2).
* Applicability: 4EAT, 5EAT, all years.
* Sources: S29, S30, S32, S5.
* Confidence: medium (threshold is community/derived, the factory "normal" window ends at 100 °C).

#### `atf_temp_high`
* Title: Перегрев масла в коробке
* Params: atfC, atfLamp
* Condition: atfC ≥ 110 °C for ≥ 60 s (keeps the existing 110 warn, adds duration to avoid spikes).
* Severity: warn
* Driver text: Коробка перегрета, масло выше 110 градусов. Сбавьте нагрузку, дайте ей остыть на ходу без рывков и проверьте радиатор и уровень масла.
* Causes: 1) heavy load + heat; 2) low ATF level; 3) clogged cooler / radiator; 4) clutch or lock-up slip.
* Checks: ATF level hot; radiator cleanliness; lock-up slip rules; extra cooler.
* Applicability: all.
* Sources: S29, S30, S36, S1.
* Confidence: medium.

#### `atf_temp_critical`
* Title: Опасный перегрев коробки
* Params: atfC, atfLamp
* Condition: atfC ≥ 120 °C for ≥ 30 s, or any sample ≥ 130 °C (if not flagged implausible by `atf_sensor_implausible`), or atfLamp steady ON while driving.
* Severity: danger
* Driver text: Коробка опасно перегрета. Остановитесь в безопасном месте, поставьте P и дайте двигателю поработать на холостых несколько минут, чтобы масло остыло через радиатор.
* Causes: as above; steady AT OIL TEMP lamp = TCU itself detected overheat (S3).
* Checks: after cooling check level and smell/colour of ATF; plan ATF change (see `atf_overheat_counter`).
* Applicability: all. Lamp exists on USDM 4EAT; EDM cars use POWER lamp for codes.
* Sources: S3, S36, S30.
* Confidence: medium (Subaru lamp threshold is not published; aftermarket sites claim 127 to 135 °C, unverified).
* Disagreement: no factory number for the lamp. Keep 120 °C as app threshold.

#### `atf_overheat_counter`
* Title: Коробка часто работала горячей
* Params: atfC, odometer
* Condition: accumulate minutes with atfC ≥ 100 °C and ≥ 110 °C since the last ATF change. If ≥ 300 min above 100 °C or ≥ 60 min above 110 °C, flag.
* Severity: info
* Driver text: С прошлой замены масла коробка много раз работала горячей. Масло стареет быстрее, замену лучше сделать раньше срока.
* Causes: city traffic, towing, mountains.
* Checks: ATF colour and smell; pull the next ATF change forward by 30 to 50 %.
* Applicability: all.
* Sources: S36 (life halves per ~11 °C), S9 (FSM: towing = replace more often).
* Confidence: medium (counter limits are a design choice).

#### `atf_cold_no_4th`
* Title: Холодная коробка, четвёртая не включается
* Params: atfC, gear, range, speedKmh
* Condition: range D, atfC < 12 °C, speedKmh > 70 for ≥ 30 s, gear = 3.
* Severity: info
* Driver text: Пока масло в коробке холодное, она специально не включает четвёртую передачу. Это нормально, после прогрева всё станет как обычно.
* Causes: normal Phase 2 logic.
* Checks: none. Make sure `limp_mode_third` is not raised when atfC < 20 °C.
* Applicability: 4EAT Phase 2 (1999+).
* Sources: S13.
* Confidence: high.

#### `atf_warmup_slow`
* Title: Масло в коробке не прогревается
* Params: atfC, coolantC, trip time
* Condition: ≥ 25 min driving, coolantC ≥ 80 °C for ≥ 10 min, atfC < 35 °C and changing < 2 °C over 10 min.
* Severity: info
* Driver text: Масло в коробке почти не прогревается, хотя мотор тёплый. Возможно, неисправен датчик температуры коробки, стоит проверить ошибки блока АКПП.
* Causes: 1) ATF temp sensor or wiring (code 27 / 21, P0712/P0713); 2) aftermarket cooler without thermostat in deep frost; 3) TCU reading mapped wrong.
* Checks: read TCU DTCs; compare with ambient at cold start (should match).
* Applicability: 4EAT, 5EAT.
* Sources: S5, S6, S30.
* Confidence: medium.

#### `atf_sensor_implausible`
* Title: Странные показания датчика температуры коробки
* Params: atfC, coolantC, trip time
* Condition: atfC ≤ −35 °C or ≥ 150 °C for ≥ 5 s; or jump > 20 °C between two samples 1 s apart; or at a cold start after ≥ 8 h parked, abs(atfC − coolantC) > 15 °C.
* Severity: warn
* Driver text: Датчик температуры масла в коробке показывает неправдоподобные значения. Из-за этого коробка может переключаться жёстко, проверьте ошибки и разъём датчика.
* Causes: 1) sensor or harness open/short (code 27, P0712/P0713); 2) connector corrosion; 3) TCU fault.
* Checks: TCU DTCs; sensor resistance at 20 °C ≈ 3.9 to 4.7 kΩ (H4DOTC table); harness.
* Applicability: all.
* Sources: S5, S6, S7.
* Confidence: high for symptoms ("excessive shift shock" in FSM), medium for thresholds.

#### `atf_hotter_than_coolant`
* Title: Коробка греется сильнее мотора
* Params: atfC, coolantC, speedKmh
* Condition: steady cruise ≥ 60 km/h for ≥ 10 min, coolantC 80 to 100 °C, atfC − coolantC ≥ 20 °C.
* Severity: info (warn if ≥ 30 °C)
* Driver text: Масло в коробке на ровной трассе заметно горячее антифриза. Это бывает при пробуксовке внутри коробки или забитом охлаждении, стоит проверить уровень и состояние масла.
* Causes: 1) lock-up slip; 2) low ATF; 3) cooler line or radiator heat exchanger restriction; 4) aftermarket cooler bypassed wrong.
* Checks: ATF level; lock-up slip rules; cooler lines warm by hand.
* Applicability: 4EAT/5EAT with cooler in the radiator tank.
* Sources: S30 (cooler layout), S32.
* Confidence: low (heuristic, no factory number).

#### `atf_lamp_fault_stored`
* Title: Блок коробки сообщает о неисправности
* Params: atfLamp, diagLamp, TCU DTCs
* Condition: after engine start the lamp blinks 16 times (0.25 s on, 0.25 s off, 4 times, 2 s pause, repeated 4 times), or diagLamp = ON, or TCU returns any DTC.
* Severity: warn
* Driver text: Блок автоматической коробки записал неисправность. Коробка может работать в аварийном режиме, лучше прочитать коды ошибок и не нагружать машину.
* Causes: see section 5. Most frequent on drive2/forums: Duty C (transfer) solenoid, speed sensors, ATF temp sensor, wiring.
* Checks: read TCU DTCs in the app; note the fault appears only after the next ignition cycle (TCU waits) (S3).
* Applicability: 4EAT (USDM AT OIL TEMP lamp; EDM POWER lamp flashes codes), 5EAT (SPORT lamp).
* Sources: S3, S6, S7, S12.
* Confidence: high.
* Note: fast continuous blink at 4 Hz during code read = TCU power supply or ground circuit fault (S5 diag step 1).

### B. Torque converter lock-up

#### `lockup_slip_factory`
* Title: Проскальзывает блокировка гидротрансформатора
* Params: lockUpPct, rpm, turbineRpm, speedKmh, gear, atfC
* Condition: lockUpPct ≥ 90, gear 3 or 4, steady, warm, abs(rpm − turbineRpm) > 40 + speedKmh / 2 continuously for ≥ 10 s. Replaces the existing "duty > 60 %, slip > 10 % for 3 s" as the first-level warning.
* Severity: warn
* Driver text: Блокировка гидротрансформатора проскальзывает, обороты мотора выше, чем должны быть. Начните с проверки уровня и замены масла в коробке, старое масло часто даёт именно это.
* Causes: 1) worn or glazed lock-up friction / old ATF; 2) low ATF level; 3) lock-up duty solenoid or valve (P0741); 4) wrong ATF type.
* Checks: ATF level and condition; ATF change; then valve body / converter.
* Applicability: 4EAT 1999+. 5EAT: use same slip criterion with lock-up target pressure > 0 instead of lockUpPct.
* Sources: S3 (factory criterion), S5, S7.
* Confidence: high (factory criterion); medium for our sampling.
* Disagreement: existing app threshold (10 %) is far looser than the factory criterion (≈ 3 to 4 % at highway speed). Keep 10 % only as the danger level. The booklet does not state the speed unit in "40 + vehicle speed / 2"; Subaru TCU logic is metric, so km/h is assumed (with mph the threshold would be tighter). Validate on real logs before enabling as warn.

#### `lockup_slip_severe`
* Title: Сильное проскальзывание блокировки
* Params: lockUpPct, rpm, turbineRpm, gear
* Condition: lockUpPct ≥ 90, gear 4, slipPct ≥ 12.5 % (rpm ≥ turbineRpm × 9/8) for ≥ 5 s; or slipPct ≥ 10 % with duty ≥ 60 % for ≥ 3 s (existing rule kept).
* Severity: danger
* Driver text: Блокировка гидротрансформатора сильно буксует, коробка греется и изнашивается. Не нагружайте машину и как можно скорее проверьте коробку.
* Causes: as above, further progressed; possible P0741 with MIL after two trips.
* Checks: same; watch atfC.
* Applicability: 4EAT.
* Sources: S3.
* Confidence: high.

#### `lockup_never_engages`
* Title: Блокировка гидротрансформатора не включается
* Params: lockUpPct, gear, speedKmh, throttlePct, atfC
* Condition: warm, gear 4, 70 to 110 km/h, throttle < 30 %, ≥ 3 min cumulative in this state in one trip, lockUpPct never > 10 % and slipPct > 3 %.
* Severity: warn
* Driver text: На трассе блокировка гидротрансформатора так и не включилась. Расход и нагрев коробки будут выше, проверьте ошибки блока АКПП.
* Causes: 1) engine speed signal to TCU missing (code 11 / 23, P0725) (S6, S7); 2) lock-up solenoid circuit (code 77 / 12, P0743); 3) rear speed sensor (code 93, P1706); 4) brake switch stuck on; 5) ATF temperature sensor reading cold.
* Checks: TCU DTCs; brake lamp switch; ATF temp plausibility.
* Applicability: 4EAT. 5EAT: L/U target pressure stays 0.
* Sources: S6, S7, S13.
* Confidence: high.

#### `slip_lockup_control_abnormal`
* Title: Частичная блокировка работает неправильно
* Params: lockUpPct, speedKmh, atfC
* Condition: steady 35 to 40 km/h on flat road for ≥ 20 s, warm, lockUpPct < 5 % throughout, or lockUpPct rises above 20 % and falls back below 5 % within 2 s repeatedly (≥ 3 times). Normal 25 to 45 %.
* Severity: info
* Driver text: Плавная блокировка гидротрансформатора на малой скорости работает с перебоями. По заводской инструкции так бывает из-за старого или неподходящего масла, начните с его замены.
* Causes: 1) degraded or wrong ATF (factory statement); 2) low ATF.
* Checks: ATF level, ATF change with correct fluid, recheck.
* Applicability: 4EAT 2003+ with slip lock-up (USDM EC/EK etc.).
* Sources: S5.
* Confidence: high (factory text), medium (applicability per TCU).

#### `lockup_shudder_suspect`
* Title: Похоже на вибрацию при блокировке
* Params: rpm, lockUpPct, speedKmh
* Condition: lockUpPct rising through 30 to 90 %, speed 40 to 90 km/h, throttle 10 to 40 %, rpm oscillates with peak-to-peak ≥ 60 rpm at ≥ 3 reversals in 2 s, repeated in ≥ 3 lock-up events per trip.
* Severity: info
* Driver text: Похоже, что при включении блокировки появляется дрожь. Часто помогает свежее масло нужного типа, если не поможет, нужна проверка гидротрансформатора.
* Causes: 1) aged ATF / friction modifier loss; 2) lock-up clutch wear; 3) engine misfire (check engine file).
* Checks: ATF change; engine misfire counters.
* Applicability: 4EAT, 5EAT.
* Sources: S33, S35 (community), S5.
* Confidence: low (sample rate limits detection).

### C. Shifts, gears and failsafe

#### `gear_ratio_slip`
* Title: Пробуксовка на одной из передач
* Params: gear, turbineRpm, frontWheelKmh, throttlePct, atfC
* Condition: learn k = turbineRpm / frontWheelKmh in gear 3 (ratio 1.000) during steady driving (median of ≥ 200 samples). Expected turbine in gear n = k × ratio_n × frontWheelKmh. Flag if actual turbine exceeds expected by > 5 % (and > 150 rpm) for ≥ 2 s with throttle ≥ 20 %, not during a shift (gear unchanged ≥ 1.5 s).
* Severity: warn (danger if > 15 % or seen in two gears)
* Driver text: На {gear}-й передаче коробка проскальзывает, обороты растут быстрее скорости. Не разгоняйтесь резко и проверьте уровень масла и ошибки коробки.
* Causes: 1) low ATF; 2) worn clutch pack for that gear (Phase 2: low clutch for 1st, 2-4 brake for 2nd/4th, high clutch for 3rd/4th); 3) line pressure low; 4) speed or turbine sensor (then DTC 33/36, P0715/P0720).
* Checks: ATF level hot; TCU DTCs; then pressure test at a shop.
* Applicability: 4EAT (ratios above). 5EAT: learn ratios per gear from data, compare relative.
* Sources: S5 (ratios), S6 (P0731 to P0734 detect "incorrect ratio"), S13.
* Confidence: medium (uses learned constant; tyre slip on ice can fake it, require GPS speed agreement within 3 %).

#### `gear_ratio_slip_wot`
* Title: Пробуксовка сцепления или гидротрансформатора на полном газу
* Params: rpm, speedKmh, gear (TCU, the ECU's calculated gear E25/E59, or learned ratio clusters on a manual), throttlePct
* Condition: full throttle ≥ 1 s, speed ≥ 50 km/h, not within 1.5 s of a shift, rpm 3500 to 6500 (automatic) or 2500 to 6500 (manual). Ratio r = rpm / speed against the learned one for the gear: manual = the gear's cruise cluster (steady driving, clutch in, clusters within 5 %, ≥ 30 samples); automatic = the full-throttle ratio by TCU gear (its normal converter slip included; learned only within 3 % of itself after 20 samples). Mean excess over 2 s ≥ 4 % (manual) or ≥ 6 % (automatic), held 1.5 s.
* Severity: warn
* Driver text (AT): На полном газу на {gear}-й передаче обороты выше обычного на N %. Похоже на проскальзывание гидротрансформатора или фрикционов: проверьте уровень и состояние ATF и ошибки коробки.
* Driver text (MT): На полном газу обороты растут быстрее скорости. Так пробуксовывает сцепление: изношен диск или нет свободного хода педали.
* Causes: AT: tired ATF, worn clutch packs, converter; MT: worn clutch disc, no pedal free play, oil on the disc.
* Applicability: all; the ECU speed comes from the gearbox (VSS), so wheelspin does not fake it. Whole km/h steps are 2 % at 50 km/h, hence the 2 s mean.
* Sources: owners' Telegram chat (messages 289513 to 289598: 200 km/h in 4th at 6300 against 5800 in an older log, a jerk at 130 km/h), 258600, 258611 (gear from the rpm/speed ratio).
* Confidence: low to medium.

#### `knock_at_shift` (engine side, see engine-ej K16)
* Pulls of the knock correction within ±0.7 s of a gear change (TCU gear, the ECU's gear or a ≥ 12 % jump of rpm/speed between samples) are counted apart on the knock map and treated as a transient: the valve body's solenoids click at 2000 to 2400 rpm and the "AT retard" signal makes the ECU cut torque with timing during the shift (chat messages 265249, 272630).

#### `shift_flare_2_3`
* Title: Перегазовка при переключении со второй на третью
* Params: gear, turbineRpm, rpm, frontWheelKmh, throttlePct
* Condition: during 2→3 upshift with throttle 15 to 60 %, warm, turbine rpm rises > 150 rpm above the 2nd-gear line (k × 1.545 × speed) before dropping to the 3rd line, in ≥ 2 shifts per trip.
* Severity: warn
* Driver text: При переключении со второй на третью мотор на мгновение взлетает по оборотам. Это ранний признак износа или нехватки масла, проверьте уровень и состояние ATF.
* Causes: 1) high clutch slip or 2-4 brake releasing early; 2) low ATF / low line pressure; 3) valve body / accumulator; 4) learning values lost after battery disconnect (temporary, relearns after several shifts).
* Checks: ATF level; if after battery disconnect, wait 50 to 100 km; else shop.
* Applicability: 4EAT Phase 2 (band replaced by 2-4 clutch in 1999).
* Sources: S13, S39 (forums on 2-3 flare).
* Confidence: low to medium (sample rate).

#### `engagement_delay`
* Title: Долго включается D или R
* Params: range, turbineRpm, speedKmh, atfC
* Condition: speed 0, idle, range changes N→D: time until turbineRpm < 100 rpm > 1.5 s (factory limit 1.2 s); N→R > 2.0 s (factory 1.5 s). Require ≥ 2 events per trip with atfC 50 to 100 °C.
* Severity: warn
* Driver text: Передача включается с заметной задержкой. Обычно причина в низком уровне масла или износе фрикционов, начните с проверки уровня.
* Causes: N→D: low line pressure, worn low clutch, one-way clutch. N→R: low line pressure, worn reverse clutch, worn low and reverse brake.
* Checks: ATF level; TCU DTCs; shop time-lag and pressure test.
* Applicability: 4EAT, 5EAT.
* Sources: S2, S5.
* Confidence: medium (factory limits; sample rate adds ≈ 0.3 s uncertainty, hence relaxed thresholds).

#### `turbine_spins_in_gear_at_stop`
* Title: В D на месте коробка будто в нейтрали
* Params: range, turbineRpm, speedKmh, rpm
* Condition: range D or R, speed 0, idle, turbineRpm > 200 rpm for ≥ 3 s (factory: turbine 0 in D at idle after warm-up).
* Severity: warn
* Driver text: В положении D на месте коробка не держит передачу, как будто включена нейтраль. Проверьте уровень масла и не трогайтесь резко.
* Causes: 1) low ATF; 2) forward / low clutch slip; 3) range sensor mismatch (P0705); 4) turbine sensor fault.
* Checks: level; range reading vs lever; DTCs.
* Applicability: 4EAT, 5EAT.
* Sources: S6, S8.
* Confidence: medium.

#### `limp_mode_third`
* Title: Коробка в аварийном режиме
* Params: range, gear, speedKmh, atfC, diagLamp
* Condition: range D, atfC ≥ 20 °C, no ABS activity, gear = 3 continuously while speed passes through 10 km/h and 80 km/h, or gear = 3 for ≥ 2 min with speed varying 10 to 100 km/h.
* Severity: danger
* Driver text: Коробка ушла в аварийный режим и держит одну третью передачу. Ехать можно спокойно до сервиса, но без нагрузки, и нужно прочитать ошибки блока АКПП.
* Causes: shift solenoid A/B fault (both switched off, 3rd fixed), speed sensors both failed, other solenoid faults (only 1st and 3rd available, line pressure maximum).
* Checks: DTCs; connectors on transmission; battery voltage at TCU.
* Applicability: 4EAT Phase 2.
* Sources: S13 (failsafe table), S2.
* Confidence: high. Note: ABS activity also forces 3rd temporarily (S13), and cold ATF blocks 4th (`atf_cold_no_4th`).

#### `line_pressure_failsafe`
* Title: Давление в коробке на максимуме постоянно
* Params: linePressurePct, throttlePct, range
* Condition: after learning direction (duty vs throttle), linePressurePct sits at the high-pressure end (within 5 % of the value seen at full throttle) while idling in D with closed throttle for ≥ 30 s.
* Severity: warn
* Driver text: Блок коробки держит максимальное давление даже на холостых, поэтому переключения будут жёсткими. Это аварийный режим, нужно прочитать ошибки.
* Causes: line pressure solenoid circuit (code 75 / 11, P0748), dropping resistor (pre-Phase 2 and some H4SO), TCU.
* Checks: DTCs; solenoid resistance; dropping resistor connector on strut tower (older cars).
* Applicability: 4EAT.
* Sources: S2, S7, S13.
* Confidence: medium.

#### `range_signal_mismatch`
* Title: Коробка неправильно видит положение селектора
* Params: range, gear, speedKmh, turbineRpm
* Condition: range = N or P while speed > 10 km/h and gear reported > 0 for ≥ 3 s; or range flickers between two values > 5 times in 10 s; or range missing (no position bit set) for ≥ 2 s.
* Severity: warn
* Driver text: Блок коробки путается в положении рычага. Из-за этого переключения могут быть неправильными, проверьте датчик положения селектора и ошибки.
* Causes: inhibitor switch (code / P0705), its adjustment, cable, connector.
* Checks: DTCs; inhibitor switch adjustment.
* Applicability: 4EAT, 5EAT.
* Sources: S6 (P0705 detecting conditions).
* Confidence: medium.

#### `launch_rpm_vs_stall`
* Title: Обороты при резком старте не в норме
* Params: rpm, turbineRpm, speedKmh, throttlePct, atfC
* Condition: passive observation only. Throttle ≥ 90 % from standstill, turbine < 300 rpm, speed < 5 km/h; peak rpm in that window compared with stall spec for the engine (1.2). Flag if > spec max + 300 rpm in ≥ 2 launches; info if < spec min − 300 rpm.
* Severity: info
* Driver text (high): При резком старте мотор раскручивается выше нормы, а машина едет вяло. Это похоже на пробуксовку в коробке, проверьте масло.
* Driver text (low): При резком старте обороты ниже нормы. Причина может быть в моторе или в гидротрансформаторе, сначала проверьте работу двигателя.
* Causes: high: forward/low clutch slip, one-way clutch, low line pressure. Low: engine down on power, converter stator one-way clutch slipping.
* Checks: ATF level; engine rules. Never ask the driver to perform a stall test.
* Applicability: 4EAT with known engine (user setting).
* Sources: S5.
* Confidence: low (launch is not a stall test).

### D. AWD and driveline

#### `transfer_duty_polarity_learn` (system rule, no driver message)
* Params: transferPct, range, speedKmh, throttlePct, fwdFuse
* Logic: v_rel = median transferPct with range P or N, speed 0, throttle 0 (clutch pressure is 0 here on every TCU documented) and, if available, with fwdFuse = true. v_max = median transferPct when throttle ≥ 80 % and speed < 20 km/h in D/1st. Direction = sign(v_max − v_rel). Normalised engagement e = (transferPct − v_rel) / (v_max − v_rel), clamp 0 to 1. Require ≥ 3 samples of each before AWD rules activate.
* Sources: S4, S5, S6, S7.
* Confidence: high that polarity differs; medium that P/N always marks the released end on every TCU (true for S5 and S6, EDM S7 shows 60 to 70 % in N while D/WOT is 5 to 10 %, consistent with this logic).

#### `tyre_mismatch_axles`
* Title: Шины спереди и сзади разного размера
* Params: frontWheelKmh, rearWheelKmh, speedKmh, GPS heading, throttlePct
* Condition: straight and steady, 50 to 110 km/h, throttle 5 to 40 %, ABS inactive. Compute r = mean(front − rear) / mean(rear) over ≥ 5 min of qualifying data (integer km/h values average out over many samples). info if abs(r) ≥ 0.3 %, warn if ≥ 0.6 % on two separate trips.
* Severity: info / warn
* Driver text: Передние и задние колёса крутятся с разной скоростью, похоже на разный износ или давление в шинах. Для полного привода Subaru это вредно, муфта постоянно работает на проскальзывание.
* Causes: 1) different tread depth front vs rear (one new pair); 2) different pressure front vs rear; 3) different tyre model or size; 4) temporary spare.
* Checks: tyre pressures (TPMS if present); tread depth all four; tyre size labels. Subaru rule: within 1/4 inch circumference.
* Applicability: 4EAT (TCU gives both speeds). 5EAT also reports front and rear wheel speed (S12).
* Sources: S27, S5, S14.
* Confidence: medium (cannot see left vs right on the same axle).

#### `transfer_engaged_at_cruise`
* Title: Полный привод постоянно поджат на трассе
* Params: e (normalised engagement), speedKmh, throttlePct, straight
* Condition: straight steady 70 to 110 km/h, throttle < 30 %, e ≥ 0.5 median over ≥ 5 min, or e median exceeds this car's own 30-day baseline by ≥ 0.25.
* Severity: info
* Driver text: На ровной трассе блок коробки держит муфту полного привода сильно поджатой. Обычно так бывает из-за разницы шин или неисправности клапана муфты.
* Causes: 1) front/rear tyre mismatch (TCU reacts to speed difference); 2) Duty C solenoid or transfer valve; 3) speed sensor error.
* Checks: `tyre_mismatch_axles`; tyre pressures; DTC 79 / 24 / P1707.
* Applicability: 4EAT.
* Sources: S1, S2, S14.
* Confidence: low to medium (no factory cruise value published).

#### `torque_bind_tight_turn`
* Title: Подклинивание полного привода в крутых поворотах
* Params: frontWheelKmh, rearWheelKmh, GPS speed and heading, e, throttlePct, load
* Condition: speed 5 to 20 km/h, turning radius R = v / yawRate ≤ 10 m (from GPS heading rate), throttle ≤ 25 %. Expected front/rear ratio ≈ sqrt(R² + L²)/R − 1 (L = wheelbase, ≈ 2.5 to 2.67 m): about +3 % at 10 m, +12 % at 5 m. Flag if measured (front − rear)/rear < 1/3 of expected in ≥ 3 turns, together with e ≥ 0.6 or engine load rising > 20 % vs straight at same speed.
* Severity: warn
* Driver text: В крутых поворотах на малой скорости машину как будто тормозит, полный привод подклинивает. Проверьте одинаковость шин и давления, а если всё в порядке, клапан муфты полного привода (Duty C).
* Causes: 1) Duty C solenoid failure (Phase 2 failsafe = maximum clutch pressure) (S13); 2) mismatched tyres (clutch worn by constant slip); 3) transfer valve sticking, weak return spring (forum); 4) rear speed sensor fault (code 33/93, P1706 lists "tight corner braking").
* Checks: tyre sizes and pressures; DTCs; FWD fuse test: if binding disappears with the fuse, the clutch is being commanded or stuck (S35); duty C solenoid replacement is the most common fix (S34, S40).
* Applicability: 4EAT AWD.
* Sources: S5 (road test item 10), S6, S7, S13, S34, S35, S40.
* Confidence: medium (GPS radius estimate is noisy at low speed).

#### `fwd_fuse_installed`
* Title: Полный привод отключён предохранителем
* Params: fwdFuse
* Condition: fwdFuse = true at engine start, repeat once per day while true.
* Severity: info
* Driver text: В гнездо FWD вставлен предохранитель, полный привод отключён и машина едет только передними колёсами. Это нужно только для проверок, буксировки и стенда, не забудьте его вынуть.
* Causes: test, towing, chassis dyno, owner masking torque bind.
* Checks: underhood FWD connector.
* Applicability: 4EAT AWD.
* Sources: S1, S2.
* Confidence: high.

#### `front_wheelspin_frequent`
* Title: Передние колёса часто срываются в пробуксовку
* Params: frontWheelKmh, rearWheelKmh, throttlePct, e, atfC
* Condition: speed < 40 km/h, throttle ≥ 30 %, (front − rear)/rear ≥ 10 % for ≥ 1 s, ≥ 5 events in a trip with ambient (cold-start coolant) > 5 °C (dry season), and e stays < 0.3 during the events.
* Severity: info
* Driver text: При разгоне передние колёса проскальзывают, а задние почти не помогают. Возможно, муфта полного привода недожимается, стоит проверить коробку.
* Causes: 1) transfer clutch worn; 2) Duty C / transfer valve; 3) fwdFuse installed; 4) worn front tyres.
* Checks: fwdFuse; DTCs; transfer clutch pressure test at a shop.
* Applicability: 4EAT AWD.
* Sources: S1, S14.
* Confidence: low.

#### `awd_speed_sensor_fault`
* Title: Датчик скорости коробки не работает
* Params: frontWheelKmh, rearWheelKmh, speedKmh, GPS speed
* Condition: one of front/rear = 0 while the other > 20 km/h and GPS > 20 km/h for ≥ 5 s; or abs(front − rear) > 20 % for ≥ 30 s straight.
* Severity: warn
* Driver text: Один из датчиков скорости коробки не даёт сигнал. Из-за этого возможны подклинивание полного привода в поворотах и отказ блокировки, прочитайте ошибки.
* Causes: speed sensor, wiring, connector (codes 33 / 93 / 32, P0720, P1706).
* Checks: DTCs; sensor connectors on transmission.
* Applicability: 4EAT, 5EAT.
* Sources: S6, S7, S13.
* Confidence: high.

#### `speedo_vs_gps`
* Title: Спидометр заметно расходится с GPS
* Params: speedKmh, GPS speed
* Condition: straight steady 60 to 110 km/h, good GPS fix; median (speedKmh / GPS − 1) over ≥ 10 min. Learn the car baseline on the first 500 km. Flag if it moves by ≥ 3 % from the baseline (tyre size changed) or is < −1 % (speedo reads low).
* Severity: info
* Driver text: Спидометр стал показывать скорость иначе, чем раньше. Похоже, поставлены шины другого размера, проверьте, что все четыре одинаковые.
* Causes: different tyre size; worn vs new tyres.
* Checks: tyre labels on all four.
* Applicability: all.
* Sources: derived; S27.
* Confidence: medium.

### E. Tyres and TPMS (when sensors exist)

Pressure normalisation: P_cold(gauge) = (P_meas + P_atm) × (T_ref + 273.15) / (T_tyre + 273.15) − P_atm, with T_ref = 20 °C (or ambient at cold start), P_atm = 101.3 kPa. Placard pressure entered by user (door pillar).

#### `tpms_low_compensated`
* Title: Давление в шине ниже нормы
* Params: TPMS pressure and temperature per wheel
* Condition: P_cold ≤ placard × 0.85 for ≥ 2 min (warn); ≤ placard × 0.75 (danger, the FMVSS 138 level).
* Severity: warn / danger
* Driver text (warn): В колесе {wheel} давление ниже нормы с учётом температуры. Подкачайте до значения на табличке в проёме двери.
* Driver text (danger): В колесе {wheel} давление сильно ниже нормы. Снизьте скорость и как можно скорее подкачайте или поменяйте колесо.
* Causes: puncture, valve, rim seal, cold weather.
* Checks: pump, soapy water on valve and bead.
* Applicability: all with TPMS.
* Sources: S29b, S28.
* Confidence: high.

#### `tpms_slow_leak`
* Title: Медленно спускает колесо
* Params: P_cold per wheel, date
* Condition: per wheel linear trend of daily first-trip P_cold over ≥ 7 days; slope ≤ −10 kPa per week, and at least 7 kPa per week worse than the average of the other three (excludes weather).
* Severity: warn
* Driver text: Колесо {wheel} медленно теряет давление, примерно {X} кПа в неделю. Это похоже на прокол или травящий ниппель, загляните в шиномонтаж.
* Causes: nail, valve core, bead corrosion on alloy rims (common after winter salt).
* Checks: soapy water; valve.
* Applicability: TPMS.
* Sources: S28 (normal loss ≈ 7 kPa per month).
* Confidence: high.

#### `tpms_wheel_hot`
* Title: Одно колесо горячее остальных
* Params: TPMS temp per wheel, speedKmh
* Condition: speed ≥ 50 km/h for ≥ 10 min; one wheel ≥ 10 °C hotter than the other wheel on the same axle, persisting ≥ 5 min.
* Severity: warn
* Driver text: Колесо {wheel} заметно горячее соседнего. Так бывает при подклинивающем тормозе или низком давлении, проверьте на остановке, не перегрет ли диск.
* Causes: 1) caliper slider or piston sticking; 2) parking brake dragging (rear); 3) low pressure; 4) wheel bearing.
* Checks: touch test after a stop (careful), pressure, brake service.
* Applicability: TPMS with temperature.
* Sources: derived (physics), low.
* Confidence: low to medium.

#### `tpms_cold_weather_drop`
* Title: Похолодало, давление в шинах упало
* Params: P_meas, ambient (coolant at cold start)
* Condition: all four uncompensated pressures dropped ≥ 15 kPa compared with 14 days ago while ambient fell ≥ 10 °C, and all four P_meas are below placard.
* Severity: info
* Driver text: Из-за похолодания давление во всех шинах упало. Подкачайте их на холодную до нормы с таблички.
* Causes: temperature (≈ 1.2 kPa per °C).
* Checks: pump.
* Applicability: TPMS.
* Sources: S28.
* Confidence: high.

#### `tpms_axle_imbalance_awd`
* Title: Разное давление спереди и сзади влияет на полный привод
* Params: P_cold per wheel
* Condition: abs(mean front − mean rear) − abs(placard front − placard rear) ≥ 30 kPa.
* Severity: info
* Driver text: Давление спереди и сзади заметно отличается от нормы. Для полного привода лучше выровнять, иначе колёса осей крутятся с разной скоростью.
* Causes: uneven inflation.
* Checks: pump to placard.
* Applicability: AWD with TPMS. Combine with `tyre_mismatch_axles`.
* Sources: S27 (principle), derived.
* Confidence: low (effect size of pressure on rolling radius is small, about 0.1 to 0.3 %).

### F. Battery and charging

Battery temperature proxy: coolantC at key-on after ≥ 6 h parked ≈ ambient.

#### `battery_rest_soc`
* Title: Аккумулятор разряжен на стоянке
* Params: batteryV (first ECU sample after key-on, engine not cranked yet), time since last engine stop, coolantC
* Condition: parked ≥ 6 h (≥ 4 h minimum). Estimate SoC from 1.5 table, adding 0.10 V to the reading to offset key-on load (ECU, fuel pump prime) and ECU harness drop. info if est. ≤ 12.40 V (≤ 70 %), warn if ≤ 12.20 V (≤ 50 %), danger if ≤ 11.95 V (≈ 0 to 10 %). Below 0 °C, lower each threshold by 0.05 V.
* Severity: info / warn / danger
* Driver text (warn): Аккумулятор на стоянке разряжен примерно наполовину. Зимой это риск не завестись, проедьте подольше или зарядите его зарядным устройством.
* Driver text (danger): Аккумулятор почти полностью разряжен. Если машина заведётся, не глушите её минимум полчаса, и проверьте аккумулятор и утечку тока.
* Causes: 1) short trips in cold; 2) parasitic drain (head unit, alarm, interior light); 3) old battery; 4) charging cut-out + short trips (see below).
* Checks: charge with charger, then load test; check drain (`battery_drain_overnight`).
* Applicability: all.
* Sources: S17 (table, rest time).
* Confidence: medium (key-on offset 0.10 V is an estimate; ECU batteryV resolution ≈ 0.08 V).
* Disagreement: existing app rule "< 12.2 V parked" equals ≈ 50 % SoC; keep as warn, add the info and danger levels.

#### `battery_crank_dip`
* Title: Аккумулятор слабо держит пуск
* Params: batteryV min during crank (rpm 50 to 400), coolantC at key-on
* Condition: minimum below the temperature limit: ≥ 21 °C 9.6 V, 10 °C 9.4 V, −1 °C 9.1 V, −7 °C 8.9 V, −12 °C 8.7 V, −18 °C and below 8.5 V. Require 2 starts within 7 days, or 1 start with min < limit − 0.5 V.
* Severity: warn
* Driver text: При пуске напряжение аккумулятора проседает сильнее нормы для этой температуры. Аккумулятор слабеет, проверьте клеммы и сделайте нагрузочный тест до морозов.
* Causes: 1) aged or sulfated battery; 2) low state of charge; 3) loose or corroded terminals, bad engine-to-body ground strap; 4) starter drawing high current.
* Checks: clean and tighten terminals and ground straps (cheap, frequent on old Subarus); charge then load test; starter.
* Applicability: all.
* Sources: S19 (9.6 V at 21 °C, 8.5 V at −18 °C; intermediate values from the BCI load-test table).
* Confidence: medium. ECU sampling usually misses the very first inrush dip, so the measured minimum is higher than true. A reading below the limit is meaningful; a reading above it does not prove the battery is fine.

#### `charging_cutout_normal` (suppression + info)
* Title: Экономичный режим генератора
* Params: batteryV, altDutyPct, rpm, coolantC
* Condition: engine running ≥ 2 min, batteryV 12.5 to 13.1 V stable (± 0.15 V), and altDutyPct at the "disengaged" end (learned: the altDutyPct value that coincides with 12.5 to 13.1 V while 13.8+ V is seen at the other end in the same trip). Then suppress `alternator < 13.0 V` alerts. Show info at most once per 30 days.
* Severity: info
* Driver text: Напряжение около 12,8 вольт на ходу у этой Subaru нормально. Блок двигателя сам временно отключает зарядку при малой нагрузке, при включении фар зарядка вернётся.
* Causes: designed ECU charge cut-out on 2001 to 2008 cars with C/S/L alternator.
* Checks: switch on headlights or blower: voltage should return to ≈ 14 V within a few seconds.
* Applicability: Forester SG, Impreza GD/GG, Legacy BE/BH/BL/BP 2001 to 2008 (most); not WRX/STI where the wire is not connected (forum).
* Sources: S20, S21, S22, S23.
* Confidence: high for existence; medium for exact voltages.
* Note: the cut-out plus many short winter trips can leave the battery chronically undercharged (S21); combine with `short_trips_undercharge`.

#### `alternator_not_charging`
* Title: Генератор не заряжает
* Params: batteryV, altDutyPct, rpm, trip time
* Condition: engine running ≥ 2 min, rpm ≥ 1500, and either (altDutyPct at engaged end and batteryV < 13.2 V for ≥ 60 s) or (batteryV < 12.6 V for ≥ 60 s regardless of altDuty), or batteryV falling ≥ 0.2 V per 5 min to below 12.4 V.
* Severity: danger
* Driver text: Генератор не заряжает аккумулятор, машина едет на его запасе. Выключите лишние потребители и езжайте в сервис без остановок двигателя.
* Causes: 1) alternator regulator or brushes; 2) belt slipping or broken (check engine: power steering also shares belt on some); 3) C/S/L connector or blown fuse; 4) bad ground.
* Checks: belt; alternator connector; fuse; measure at battery with lights on.
* Applicability: all.
* Sources: S20, S21, S22.
* Confidence: high.
* Disagreement: existing "< 13.0 V running" rule false-alarms on the cut-out; use this rule plus `charging_cutout_normal`.

#### `charging_overvoltage`
* Title: Слишком высокое напряжение зарядки
* Params: batteryV, coolantC, time since start
* Condition: warn if batteryV > 15.0 V for ≥ 10 s once coolantC ≥ 60 °C or ≥ 10 min after start (a cold regulator may legally sit 14.9 to 15.0 V for a few minutes in frost); danger if > 15.5 V for ≥ 5 s at any time.
* Severity: warn / danger
* Driver text: Генератор выдаёт слишком высокое напряжение. Это портит аккумулятор и электронику, нужно проверить регулятор напряжения.
* Causes: 1) regulator failure (case of up to 19 V on a 2008 Forester); 2) poor sense or ground connection making regulator over-compensate.
* Checks: measure at battery; replace regulator or alternator.
* Applicability: all.
* Sources: S18 (14.7 V limit, −3 mV/cell/°C), S21.
* Confidence: high for > 15.5 V, medium for 15.0 V.

#### `charging_weak_at_idle`
* Title: На холостых генератор не тянет нагрузку
* Params: batteryV, rpm, altDutyPct
* Condition: altDuty engaged; idle (rpm < 900) batteryV < 13.2 V for ≥ 60 s, while at 1800 to 3000 rpm in the same trip ≥ 13.8 V.
* Severity: info
* Driver text: На холостых при включённых потребителях напряжение просаживается, а на оборотах в норме. Стоит проверить натяжение ремня генератора и сам генератор.
* Causes: 1) belt slip; 2) worn alternator; 3) many loads (seat heaters, rear demister, aftermarket amps).
* Checks: belt tension and glazing; load list.
* Applicability: all.
* Sources: S21 (ranges).
* Confidence: low to medium.

#### `voltage_unstable`
* Title: Напряжение в сети скачет
* Params: batteryV, rpm, throttlePct
* Condition: steady rpm (± 100) and no load switching visible, batteryV peak-to-peak ≥ 0.5 V with ≥ 4 reversals in 30 s, repeated in ≥ 2 trips; exclude cut-out transitions (single step).
* Severity: info
* Driver text: Напряжение бортовой сети заметно скачет без причины. Часто виноваты окисленные клеммы, массовые провода или щётки генератора.
* Causes: 1) loose battery terminal; 2) engine-to-body or body ground strap corroded; 3) alternator brushes / regulator.
* Checks: terminals; ground straps (engine to body, battery to body); alternator.
* Applicability: all.
* Sources: S21, S22 (forum diagnostics), low.
* Confidence: low.

#### `ecu_tcu_voltage_mismatch`
* Title: Разное напряжение у блоков двигателя и коробки
* Params: batteryV (ECU), TCU battery voltage (address 0x1044, value × 0.08 V) if polled
* Condition: abs(ECU − TCU) ≥ 0.5 V median over ≥ 2 min with engine running.
* Severity: info
* Driver text: Блоки двигателя и коробки видят разное напряжение питания. Это признак плохого контакта питания или массы, проверьте массовые провода и разъёмы.
* Causes: ground strap, fuse box contact, connector corrosion.
* Checks: grounds; fuse contacts.
* Applicability: 4EAT TCUs that expose battery voltage.
* Sources: S0, S2 (Select Monitor F01 battery voltage).
* Confidence: low (needs validation on cars; resolution 0.08 V).

#### `headunit_left_on`
* Title: Магнитола не выключилась после остановки
* Params: ECU link state, head unit awake time (app-level)
* Condition: ECU stops answering (ignition off) and the head unit stays awake ≥ 10 min (app still running). Estimate drain: assume 1 A if no measurement. warn at 10 min, repeat at 60 min.
* Severity: warn
* Driver text: Зажигание выключено, а магнитола продолжает работать и разряжает аккумулятор. Выключите её, иначе за ночь аккумулятор может сесть так, что машина не заведётся.
* Causes: head unit wired to constant 12 V on both feeds; ACC wire not connected; sleep mode disabled; app keeping wake lock.
* Checks: ACC (red) wire should be 0 V with key out; constant (yellow) draw in sleep should be < 10 mA ideally; measure total draw (≤ 70 mA target).
* Applicability: all (app-level rule).
* Sources: S26, S25.
* Confidence: high.
* Note: a typical 50 to 60 Ah battery loses about half its charge in 25 to 30 h at 1 A, enough to fail a winter start.

#### `battery_drain_overnight`
* Title: Аккумулятор теряет заряд на стоянке
* Params: rest voltage at consecutive key-ons, park duration, ambient
* Condition: reference = key-on voltage after a short park of 1 to 3 h (surface charge mostly gone). Test = key-on voltage after the next park of ≥ 24 h, same ambient band (± 5 °C). Drop per 24 h = (reference − test) / park days. Flag if ≥ 0.15 V per 24 h at ambient > 0 °C on ≥ 2 occasions. A healthy car with ≈ 30 mA draw loses well under 0.05 V per day.
* Severity: warn
* Driver text: На стоянке аккумулятор садится быстрее нормы. Похоже на утечку тока, частые причины на старых машинах: магнитола, сигнализация, подсветка багажника или бардачка.
* Causes: 1) aftermarket head unit or amplifier; 2) alarm/immobiliser; 3) glovebox/trunk/vanity light; 4) alternator diode leakage.
* Checks: dark current test after 30 min sleep: Subaru target ≤ 70 mA, typical 30 to 35 mA; pull fuses one by one.
* Applicability: all.
* Sources: S25, S26, S17.
* Confidence: low to medium (indirect).

#### `short_trips_undercharge`
* Title: Короткие поездки не успевают зарядить аккумулятор
* Params: trip durations, ambient, rest voltage trend
* Condition: ≥ 70 % of trips in last 14 days shorter than 15 min, ambient (cold-start coolant) < 5 °C, and rest SoC estimate fell ≥ 15 points over the period.
* Severity: info
* Driver text: Поездки короткие и холодные, аккумулятор не успевает заряжаться. Раз в неделю проедьте больше получаса или подзарядите его от сети.
* Causes: winter short trips; charge cut-out; heaters on.
* Checks: charger overnight.
* Applicability: all.
* Sources: S9 (severe: repeat short distance, extremely cold), S21.
* Confidence: medium.

#### `no_recovery_after_start` (refines existing)
* Title: После пуска зарядка не восстанавливается
* Params: batteryV, altDutyPct, time since start
* Condition: within 60 s after start batteryV should reach ≥ 13.5 V (existing 13.2 V kept as warn level). Skip if altDuty indicates cut-out. warn if < 13.2 V at 60 s; danger if < 12.6 V at 120 s.
* Severity: warn / danger
* Driver text: После пуска генератор так и не начал нормально заряжать. Проверьте ремень и генератор, а пока не выключайте мотор без необходимости.
* Causes: as `alternator_not_charging`; or a deeply discharged battery pulling voltage down (then voltage climbs slowly, not flat).
* Applicability: all.
* Sources: S18, S21.
* Confidence: medium.

### G. Maintenance-triggered rules

#### `maintenance_due`
* Title: Пора на обслуживание: {item}
* Params: odometer, last service date and km per item, severe flag
* Condition: per item in section 4: due at the earlier of km or months; "soon" at 90 %. Use CIS/severe column when the user marks severe use or when the app sees ≥ 50 % short trips in cold months or ≥ 20 % time with atfC ≥ 100.
* Severity: info (warn when overdue by ≥ 10 % or ≥ 2 months)
* Driver text: Подходит срок замены {item}: осталось около {km} км или {weeks} недель. Лучше сделать это заранее, особенно перед зимой.
* Sources: section 4.

#### `atf_change_after_events`
* Title: После перегрева масло в коробке лучше заменить
* Params: atfC history, odometer
* Condition: any `atf_temp_critical` event, or `atf_overheat_counter` flagged, and last ATF change ≥ 10 000 km ago.
* Severity: info
* Driver text: После сильного перегрева масло в коробке теряет свойства. Сделайте частичную замену, не дожидаясь планового срока.
* Sources: S36, S29, S33.
* Confidence: medium.

#### `timing_belt_age`
* Title: Ремень ГРМ по возрасту
* Params: user-entered belt date and km
* Condition: ≥ 105 000 km or ≥ 84 months since replacement (EU FSM), or unknown history on a car older than 7 years.
* Severity: warn
* Driver text: Ремень ГРМ отработал свой срок по пробегу или возрасту. На этих моторах обрыв ремня обычно ведёт к дорогому ремонту, меняйте его вместе с роликами и помпой.
* Sources: S9, S10, S11, S15.
* Confidence: high.

Rule count: 50 (A 9, B 5, C 8, D 8, E 5, F 12, G 3), plus 9 trend rules in section 3.

---

## 3. Trend rules (weeks to months)

All trends use per-trip medians in a fixed operating window so that weather and driving style cancel out. Minimum 6 data points over ≥ 3 weeks before any message.

| id | Title (RU) | Metric and window | Trigger | Severity | Driver text (RU) | Confidence |
|---|---|---|---|---|---|---|
| `trend_atf_hotter` | Коробка со временем греется сильнее | median atfC − coolantC during straight steady 80 to 100 km/h, ≥ 10 min per trip | rises ≥ 6 °C vs the first 4 weeks of data, same ambient band (± 10 °C) | info | Коробка на трассе стала греться сильнее, чем месяц назад при той же погоде. Проверьте радиатор и уровень масла, масло могло устареть. | medium |
| `trend_lockup_slip_growing` | Растёт проскальзывание блокировки | median slipRpm with lockUpPct ≥ 90, 4th, 70 to 110 km/h, warm | median grows from < 30 rpm to > 60 rpm, or slope > +10 rpm per 1000 km over ≥ 3000 km | warn | Блокировка гидротрансформатора от месяца к месяцу проскальзывает всё сильнее. Замена масла сейчас может продлить жизнь коробке. | medium |
| `trend_transfer_baseline_up` | Муфта полного привода поджимается всё сильнее | median normalised engagement e at straight cruise | +0.2 over ≥ 4 weeks | info | Блок коробки всё сильнее поджимает муфту полного привода на трассе. Проверьте износ и давление шин на всех колёсах. | low |
| `trend_axle_ratio_drift` | Расходятся передние и задние шины | weekly r from `tyre_mismatch_axles` | abs(r) grows ≥ 0.2 % since tyre change date | info | Шины спереди и сзади изнашиваются по-разному. Переставьте колёса по схеме или проверьте давление. | medium |
| `trend_shift_time_growing` | Переключения становятся медленнее | median 1→2 and 2→3 shift duration at throttle 15 to 40 %, warm | +40 % vs baseline | info | Переключения стали заметно медленнее, чем раньше. Это повод проверить уровень и состояние масла в коробке. | low |
| `trend_battery_rest_falling` | Аккумулятор стареет | rest voltage at key-on after ≥ 6 h, same ambient band | slope ≤ −0.05 V per week over ≥ 3 weeks, or 0.2 V lower than same month last year | info, warn if est. SoC < 60 % | Аккумулятор от недели к неделе держит заряд хуже. Перед зимой его стоит проверить нагрузочной вилкой. | medium |
| `trend_crank_dip_deeper` | Пуск даётся аккумулятору всё тяжелее | min crank voltage, grouped by cold-start coolant band (10 °C bins) | drops ≥ 0.4 V in the same band vs earliest data | warn | При одинаковой температуре напряжение при пуске проседает всё глубже. Аккумулятор или клеммы требуют внимания. | medium |
| `trend_charging_drift` | Меняется напряжение зарядки | median batteryV, altDuty engaged, 1800 to 3000 rpm, coolant ≥ 80 °C | drift ≥ 0.3 V down (< 13.7 V) or up (> 14.9 V) over ≥ 4 weeks | info | Напряжение зарядки постепенно уходит от нормы. Стоит проверить генератор и его регулятор, пока он не отказал. | medium |
| `trend_tyre_pressure` | Давление в колесе постепенно падает | daily P_cold per wheel | slope ≤ −10 kPa per week, or ≥ 7 kPa per week worse than other wheels | warn | Колесо {wheel} постоянно теряет давление. Проверьте его на прокол и ниппель. | high |

---

## 4. Maintenance schedule (for reminders)

Columns: official Europe schedule (EDM/RU cars, 2004 FSM, Forester/Impreza/Legacy), official USA schedule (2000 to 2009), and the recommended "severe / CIS" interval the app should use when severe mode is on. Severe per Subaru: repeated short trips, rough or muddy roads, extremely cold climate, salt or coastal, high humidity or mountains, towing (S9).

| Item | Europe / RU official | USA official | Severe / CIS (app default for severe) | Notes, sources |
|---|---|---|---|---|
| Engine oil + filter | 15 000 km / 12 mo | 7 500 mi (12 000 km) / 7.5 mo; turbo 3 750 mi or 4 mo | NA 7 500 km / 6 mo; turbo 5 000 km / 6 mo | FSM: replace more often for short trips, cold, towing. S9, S10, S11, S15 |
| ATF (4EAT/5EAT) | inspect 30 000 km, replace 60 000 km / 48 mo | inspect 30k and 90k mi; severe replace every 24 000 mi (38 400 km) | partial change every 30 000 to 40 000 km; after any overheat event | ATF filter is "maintenance free" per FSM (replace only if damaged/leaking); owners change it at the first service of an unknown car. Do not do a full flush on a high-mileage box with unknown history (drive2). Fluid: Dexron III (SG/GD early), Subaru ATF / ATF-HP later (check dipstick/manual). S9, S10, S15, S29, S31, S33 |
| Front diff (AT, separate oil) and rear diff | inspect 30 000 km, replace 60 000 km / 48 mo | inspect 30k mi; severe replace 30 000 mi | 40 000 to 60 000 km; after deep water | GL-5 75W-90, front diff on 4AT ≈ 1.1 to 1.3 L. Never ATF in diffs. S5, S9, S15 |
| Coolant (older green / Long Life) | 30 000 km / 24 mo | 30k mi intervals (2000 to 2008) | same; check strength (−40 °C) every autumn | Mixed with older coolant = old interval. S9, S15 |
| Coolant (Super Coolant, 2008 STI, 2009+) | 11 yr / 220 000 km first, then 6 yr / 120 000 km | same | same | Do not mix. S16 |
| Spark plugs NA | 30 000 km / 24 mo (Forester, Impreza); Legacy 2004 2.0/2.5 iridium 105 000 km | 30 000 mi (NA 2.2/2.5) | 30 000 km for copper/platinum; per plug type otherwise | User should record plug type. S9, S10, S11, S15 |
| Spark plugs turbo | 105 000 km / 84 mo (EU FSM) | 60 000 mi | 40 000 to 60 000 km | Turbo cars in CIS often get plugs earlier due to fuel quality. S9, S11, S15 |
| Timing belt (EJ20/EJ25) | 105 000 km / 84 mo (Impreza, Legacy EU FSM) | 105 000 mi (2.2/2.5, 2000 to 2009) | 90 000 to 105 000 km or 7 yr, with tensioner, idlers, water pump | 2004 Forester EU FSM table appears to show the belt in every 30 000 km column, almost certainly a layout error (Impreza and Legacy tables of the same year: 105 000 km). S9, S10, S11, S15, drive2 owners |
| Air filter | inspect 15 000 km, replace 30 000 km / 24 mo | 30 000 mi | 15 000 km; gravel and dust roads every service | FSM note: dusty use, replace more often. S9 |
| Fuel filter | replace 45 000 km and 90 000 km (Forester/Impreza 2004) | 2004 and earlier 30k mi; later in-tank | 45 000 km on external-filter cars | S9, S11, S15 |
| Brake fluid | 30 000 km / 24 mo | 30 000 mi | every 24 mo; 12 mo / 15 000 km in high humidity or mountains | FSM schedule 2. S9, S15 |
| Axle boots and joints (CV boots) | inspect every 15 000 km / 12 mo | inspect every 15 000 mi | inspect every service; winter salt | Torn outer boots are a frequent drive2 finding. S9, S15, S30 |
| Drive belts | inspect 15 000 km | inspect | inspect every service | S9 |
| Battery | not in schedule | not in schedule | test each autumn (load test), clean terminals | S17, S19 |
| Tyres | n/a | n/a | rotate every 10 000 to 12 000 km to keep all four within 1/4 inch circumference | S27 |

---

## 5. TCU DTCs (plain Russian meaning, typical causes)

### 5.1 Subaru 2-digit codes, 4AT (H4SO) SG/GD/GG/BE/BH around 2002 to 2005 (FSM)

Blink format: long flash 1.2 s = tens, short 0.2 s = ones, 1.5 s pause (S6, S7).

| Code | Meaning (RU) | Symptom per FSM | Typical causes |
|---|---|---|---|
| 11 | Нет сигнала оборотов двигателя для коробки | нет блокировки после прогрева, лампа POWER горит на месте | обрыв провода от ЭБУ, разъём |
| 23 | Нет сигнала расходомера (турбо) | жёсткие переключения | ДМРВ, проводка |
| 27 | Неисправен датчик температуры масла коробки | жёсткие переключения | датчик, проводка в коробке |
| 31 | Неисправен датчик положения дросселя для коробки | переключения не вовремя, подклинивание в поворотах | ДПДЗ, проводка |
| 33 | Не работает передний датчик скорости коробки | неустойчивый холостой, глохнет, плохо едет | датчик, разъём, щиток приборов |
| 36 | Не работает датчик оборотов турбины | жёсткие переключения | датчик, разъём |
| 38 | Нарушена связь коробки и двигателя по снижению момента | жёсткие переключения | проводка ЭБУ-АКПП |
| 44 | Ошибка сигнала ABS для коробки | | проводка ABS-АКПП |
| 45 | Нет сигнала давления во впуске (атмо) | жёсткие переключения | датчик, проводка |
| 71 | Неисправен соленоид переключения 1 | не переключается | соленоид, проводка |
| 72 | Неисправен соленоид переключения 2 | не переключается | соленоид, проводка |
| 73 | Неисправен соленоид муфты Low | жёсткие переключения | соленоид |
| 74 | Неисправен соленоид тормоза 2-4 (тайминг) | жёсткие переключения | соленоид |
| 75 | Неисправен соленоид давления в линии | жёсткие переключения | соленоид, резистор |
| 76 | Неисправен соленоид тормоза 2-4 (давление) | жёсткие переключения | соленоид |
| 77 | Неисправен соленоид блокировки гидротрансформатора | нет блокировки после прогрева | соленоид, проводка |
| 79 | Неисправен соленоид муфты полного привода (Duty C) | сильное подклинивание в крутых поворотах | соленоид Duty C (частая поломка), проводка |
| 93 | Не работает задний датчик скорости коробки | нет блокировки или подклинивание в поворотах | датчик на удлинителе коробки, разъём |

Sources: S5 (USDM 2004 Forester, codes 11 to 93 and 44), S7 (EDM SG list and symptoms).

### 5.2 Older Phase 2 blink codes (End Wrench Winter 2004, earlier 1999+ TCUs)

| Code | Meaning (RU) | Symptom |
|---|---|---|
| 11 | Соленоид давления в линии | жёсткие удары, неправильные переключения |
| 12 | Соленоид блокировки | нет блокировки после прогрева |
| 13 | Соленоид тайминга тормоза 2-4 | неправильные переключения |
| 14 | Соленоид переключения B | не переключается |
| 15 | Соленоид переключения A | не переключается |
| 16 | Соленоид давления тормоза 2-4 | неправильные переключения |
| 21 | Датчик температуры масла коробки | плохие переключения на холодную |
| 22 | Датчик давления (атмосферного) | жёсткие переключения |
| 23 | Нет сигнала оборотов двигателя выше 10 км/ч | нет блокировки |
| 24 | Соленоид муфты полного привода (Duty C) | подклинивание в крутых поворотах |
| 25 | Сигнал снижения момента двигателя | жёсткие переключения |
| 31 | Датчик дросселя | неправильные и жёсткие переключения |
| 32 | Датчик скорости 1 (нет сигнала выше 20 км/ч) | жёсткие переключения |
| 33 | Датчик скорости 2 | подклинивание в поворотах; оба датчика: не переключается |
| 34 | Датчик турбины | жёсткие переключения |
| 36 | Соленоид тайминга муфты Low | неправильные переключения |

Source: S13. Pre-1999 Gears article numbering (11 duty A, 12 duty B, 13 shift sol 3, 14 sol 2, 15 sol 1, 21 ATF temp, 22 baro, 23 engine rev, 24 duty C, 31 TPS, 32 VSS1, 33 VSS2) agrees for 11, 12, 21 to 24, 31 to 33 (S14).

Disagreement: the same number means different things on different TCUs (for example 11 = line pressure solenoid on Phase 2 End Wrench, but 11 = engine speed signal on the 2004 H4SO FSM). The app must decode by TCU ROM ID / model year, not by number alone.

### 5.3 OBD-II P-codes (2004+ Legacy 4AT, 2004+ Forester XT 4AT H4DOTC, 5AT)

| Code | Meaning (RU) | Typical causes / FSM symptom |
|---|---|---|
| P0705 | Ошибка датчика положения селектора | неправильные переключения; датчик, регулировка, троса |
| P0712 / P0713 | Датчик температуры масла коробки: замыкание / обрыв | жёсткие переключения |
| P0715 | Датчик оборотов турбины | жёсткие переключения |
| P0719 / P0724 | Сигнал выключателя стоп-сигнала | нет понижения передачи в горку; лягушка стоп-сигнала |
| P0720 | Датчик выходной скорости (передний) | плохо едет, глохнет; датчик, щиток |
| P0725 | Нет сигнала оборотов двигателя | нет блокировки после прогрева |
| P0731 to P0734 (P0735 5AT) | Неверное передаточное число на 1 / 2 / 3 / 4 (5) передаче | пробуксовка фрикционов, датчики скорости, гидроблок |
| P0736 | Неверное передаточное число задней передачи | жёсткие удары, подклинивание в поворотах |
| P0741 | Блокировка гидротрансформатора не работает или буксует | нет блокировки; старое масло, гидроблок, гидротрансформатор |
| P0743 | Электрическая цепь соленоида блокировки | нет блокировки |
| P0748 | Цепь соленоида давления в линии | жёсткие переключения |
| P0753 / P0758 / P0763 / P0768 | Цепи соленоидов переключения A / B / C / D | жёсткие переключения или нет переключений |
| P0751 / P0756 / P0761 / P0766 / P0771 / P0773 (5AT) | Механика / цепи соленоидов муфт 5AT | |
| P0801 | Блокировка включения задней на ходу | R включается на ходу выше 20 км/ч или не включается |
| P0817, P0882 (5AT) | Цепь запрета стартера, низкое питание блока коробки | проверить аккумулятор, реле, массу |
| P1706 | Задний датчик скорости коробки | нет блокировки, подклинивание в поворотах |
| P1707 | Цепь соленоида муфты полного привода | подклинивание в поворотах |
| P1708 / P1709 / P1714 | Сигнал или питание датчика дросселя для коробки | |
| P1710 (5AT) | Датчик турбины 2 | |
| P1716 / P1717 (5AT) | Датчики температуры масла 1 / 2 | |
| P1718 | Связь CAN между блоками | разъёмы, питание |
| P1760 to P1762 (5AT) | Датчик бокового ускорения | |
| P1798 / P1799 (5AT) | Давление муфт при торможении двигателем на 1-й / блокировка двух муфт | гидроблок |

Sources: S6 (H4DOTC list and symptoms), S12 (5AT list), S3 (P0740/P0741 logic). P0741 and several others light MIL only after two consecutive failed trips (S3).

---

## 6. What cannot be detected with these parameters (be honest with the driver)

* ATF level and condition (colour, smell, debris). Only indirect hints (slip, temperature). The dipstick check is still required.
* Clutch, band and converter wear before it causes measurable slip; shift quality "feel" (harshness) is only partly visible because shift events are shorter than the polling interval.
* Shudder at frequencies above about 2 to 3 Hz, and short flares under 0.3 s.
* Duty C solenoid mechanical sticking when the circuit is fine and the car drives straight: binding appears only in tight low-speed turns, which needs GPS turn detection.
* Left vs right wheel differences on the same axle, individual tyre circumference, tread depth (TCU sees only front and rear shaft speeds).
* Rear differential, viscous LSD and front diff oil condition, CV joint and boot state, wheel bearings (except possibly via TPMS temperature).
* Battery internal health without a real load: resting voltage and crank dip are proxies; no battery temperature sensor; ECU voltage is read through the ECU supply line (a few tenths lower than at terminals) with ≈ 0.08 V resolution.
* True parasitic drain in mA (no current sensor); only voltage-drop inference between key-ons and the head unit's own awake time.
* Alternator diode ripple and AC noise.
* Ground strap resistance directly (only hints via voltage instability or ECU/TCU voltage mismatch).
* Coolant mix ratio, brake fluid moisture, timing belt condition: these come only from the maintenance log.
* 5EAT: no duty ratios, so lock-up and transfer duty rules do not apply; slip and ratio rules still work.
* Some 1999 to 2000 TCUs do not answer SSM2: no transmission data at all.

---

## Sources

* S0. Alcyone, Subaru 4EAT TCU parameter addresses (ATF temp formula, duty A/B/C, battery 0x1044, speed sensors, switch bits): http://www.alcyone.org.uk/ssm/tcureverse.html
* S1. Subaru End Wrench, "Subaru 4EAT Phase 1 Transmission Diagnosis and Service": https://automotivetechinfo.com/2004/02/subaru-4eat-phase-1-transmission-diagnosis-and-service/
* S2. Subaru End Wrench, "Subaru 4EAT Transmissions" overview (failsafe, duty solenoids, time lag 1.2 / 1.5 s, FWD fuse): https://subarufanclub.cz/wiki/_media/navody/4eat/subaru_4eat.pdf
* S3. Subaru Technicians Reference Booklet, Automatic Transmissions (4EAT) Module 302, Feb 2001 (16-blink pattern, P0740/P0741 slip criteria): https://ludicrous-speed.com/automotive/impreza/fsm/Others/TECHNICAL%20REFERENCE%20BOOKLETS/Automatic%20Transmissions%20(4EAT).pdf
* S4. Subaru 4EAT Transmission Diagnosis, Video Reference Booklet (Select Monitor data list: lock-up 5/95 %, 4WD duty 25/95 %): https://www.ludicrous-speed.com/automotive/impreza/fsm/Others/VIDEO%20TRAINING/4EAT%20Transmission%20Diagnosis%20(Video%20Reference%20Booklet).pdf
* S5. 2004 Forester Service Manual, 4AT mechanical and 4AT(H4SO) diagnostics (ATF level, road test lock-up 25 to 45 %, stall, time lag, line and transfer clutch pressure tables, gear ratios, 2-digit DTC list): https://subaruport.ru/for4/for4_trans_2-1.pdf and https://subaruport.ru/for4/for4_trans_3.pdf
* S6. 2004 Forester Service Manual, 4AT(H4DOTC) diagnostics (P-codes, I/O signals, transfer duty 5 % / 90 to 95 %, learning control 60 to 90 °C): https://subaruport.ru/for4/for4_trans_4.pdf
* S7. Subaru Forester (EDM SG) Automatic Transmission (Diagnostics) FSM excerpt (2-digit DTCs with symptoms, line pressure 100/25 %, lock-up 95/5 %, transfer 5 to 10 % / 60 to 70 %): https://s3cf792cad773e861.jimcontent.com/download/version/1612602639/module/15687812022/name/Subaru%20Forester%20Automatic%20Transmission%20Fault%20Codes%20DTC.pdf
* S8. 2003 Forester TCM pinout / I/O signal table: https://www.busaru.com/attachments/article/143/2003%20Forester%20TCM%20Pinout.pdf
* S8b. Forester MY03-04 FSM, Subaru Select Monitor data list (TCU items incl. Brake Clutch Duty, FWD switch, lamps): https://www.ludicrous-speed.com/automotive/forester/fsm/MY03-04/3%20-%20Transmission/Automatic%20Transmission%20(Diagnostics)/Subaru%20Select%20Monitor.pdf
* S9. 2004 Forester Service Manual, General Information, Periodic Maintenance (Europe schedule, severe "schedule 2"): https://subaruport.ru/for4/for4_gen.pdf
* S10. 2004 Legacy Service Manual, General Information, Periodic Maintenance: https://subaruport.ru/leg4/leg4_gen.pdf
* S11. 2004 Impreza Service Manual, General Information, Periodic Maintenance: https://subaruport.ru/imp4/imp04_gen.pdf
* S12. 2004 Legacy Service Manual, 5AT diagnostics (data items, DTC list, learning at > 70 °C): https://subaruport.ru/leg4/leg4_trans_5.pdf
* S13. Subaru End Wrench Winter 2004, "4EAT Phase 2 Overview" (no 4th below ≈ 12 °C, ABS → 3rd, blink codes, failsafe table): https://automotivetechinfo.com/wp-content/uploads/2004/02/Subaru-4EAT-Phase-2-Overview.pdf
* S14. Gears magazine, April 1997, Subaru 4EAT (transfer clutch pressures, old code list): https://atracom.blob.core.windows.net/gears/1997/1997-04/gapr97p44.htm
* S15. cars101, Subaru maintenance schedules 2000 to 2009: https://www.cars101.com/subaru/subaru_maintenance.html
* S16. Subaru of America, maintenance recommendations by interval (Super Coolant 11 yr / 137 500 mi): https://contactus.subaru.com/knowledgebase/article/1001832/en-us
* S17. Battery University BU-903, How to Measure State-of-charge: https://batteryuniversity.com/article/bu-903-how-to-measure-state-of-charge
* S18. Battery University BU-403, Charging Lead Acid: https://batteryuniversity.com/article/bu-403-charging-lead-acid
* S19. ASE T6 study guide, battery capacity / load testing (9.6 V at 70 °F, 8.5 V at 0 °F per SAE J537): https://open-exam-prep.com/study-guides/ase-t6/batteries-battery-management/battery-testing-service
* S20. Strong for Subaru, Subaru alternator fault fix (ECU charge cut-out 2001 to 2008, 12.9 V): https://www.strongforsubaru.com/subaru-alternator-fault-fix/
* S21. Go-Parts, Subaru 2.5L alternator 2003 to 2006 diagnosis (13.8 to 14.5 V, cut-out 12.5 to 12.9 V): https://www.go-parts.com/garage/alternator-subaru-legacy-subaru-impreza-subaru-forester-2003-2006
* S22. RomRaider forum, "ECU controlling alternator output?" (alt duty 0 = engaged, 100 = disengaged): https://www.romraider.com/forum/viewtopic.php?f=40&t=10851
* S23. SubaruOutback.org, "H6 alternator voltage & hack" (alt_duty logs, return to ≈ 14 V with load): https://www.subaruoutback.org/threads/h6-alternator-voltage-hack.526143/
* S24. 2004 Forester Service Manual, engine section 3 (H4DOTC 2.5), ECM I/O and Select Monitor items incl. "Alternate duty ratio, ALT Duty %" and "Generator control" terminal: https://subaruport.ru/for4/for4_sec3_11.pdf
* S25. Subaru service bulletin on dark current / parasitic draw (≤ 70 mA, typical 30 to 35 mA): https://static.nhtsa.gov/odi/tsbs/2025/MC-11024869-0001.pdf
* S26. Android head units, power consumption management (standby 5 to 50 mA, ACC wiring): https://android-headunits.com/power-consumption-management/
* S27. SubaruOutback.org, "Tire circumference spec confirmed by Subaru (TechTips)" (1/4 inch, ≈ 2/32 inch): https://www.subaruoutback.org/threads/tire-circumference-spec-confirmed-by-subaru-techtips.30110/
* S28. Ford, How to care for your tires (≈ 1 psi per month, 1 psi per 10 °F): https://www.ford.com/support/how-tos/tires-and-wheels/tire-replacement-and-maintenance/how-to-care-for-your-tires/
* S29. drive2, "ATF для 4-х ступенчатой АКПП SUBARU" (fluid types by version, 106 °C in Moscow jam, level check 70 to 80 °C, partial change advice): https://www.drive2.ru/l/543510863793881982/
* S29b. 49 CFR 571.138 (FMVSS 138, TPMS 25 % threshold, 20 min): https://www.law.cornell.edu/cfr/text/49/571.138
* S30. drive2, "Охлаждение АКПП Subaru и прочее ТО" (SH, > 124 °C climbing, ≈ 74 °C with extra cooler, torn CV boot): https://www.drive2.ru/l/595867958241133029/
* S31. drive2, "немного о акпп 4EAT-SUBARU" (Forester SG 2.5, RomRaider shift times 0.3 to 0.4 s, ATF every 20 to 30 thousand km for modified boxes): https://www.drive2.ru/l/2227786/
* S32. drom forum, "Тюнинг АКПП 4ЕАТ. Отчет." (80 to 85 °C per manual, 110 °C city without cooler, ≤ 95 °C with cooler): https://forums.drom.ru/subaru/t1151851873.html
* S33. АКПП-центр, Subaru 4EAT weak points by mileage (valve body from 120k km, Duty C from 130k km, converter from 150k km): https://samara.akppcenter.com/subaru/4eat
* S34. Subaru Legacy International forum, "4eat mod explained Duty Solenoid C": https://sl-i.net/FORUM/archive/index.php/t-4859.html
* S35. SubaruOutback.org, "[SOLVED] 4eat has torque bind" (FWD fuse test): https://www.subaruoutback.org/threads/solved-4eat-has-torque-bind-duty-c-solenoid-and-clutch-packs-seem-fine.535541/
* S36. Niehoff ATF temperature vs life chart (commercial cooler maker, order-of-magnitude only): https://v8sho.com/SHO/ATFTempChart.htm
* S37. RomRaider forum, older ECUs and SSM1 (MY97 SSM1 1953 baud): https://www.romraider.com/forum/viewtopic.php?f=3&t=7389
* S38. RomRaider forum, TCU communication and protocol support differences: https://www.romraider.com/forum/viewtopic.php?f=5&t=9398
* S39. NASIOC, "98 Legacy 4EAT Slow 2-3 shift" (2-3 flare discussion): https://forums.nasioc.com/forums/showthread.php?t=2669722
* S40. VehicleServicePros, "Transfer Clutch Solenoid Failure" (Phase 2 v2 wheel hop in tight turns, solenoid only sold with valve body): https://www.vehicleservicepros.com/service-repair/underhood/article/21187051/transfer-clutch-solenoid-failure
