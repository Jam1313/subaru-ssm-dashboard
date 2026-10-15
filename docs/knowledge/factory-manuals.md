# Subaru factory service manuals (FSM): sources, SSM reference values, specs, TSBs

Scope: Forester SF/SG/SH, Impreza GC/GD/GG, Legacy BE/BH/BL/BP, 1999-2010, EJ20/EJ25 NA and turbo, 4EAT/5EAT.
Research date: 2026-10. Only short facts and figures are quoted. Nothing was redistributed; links point to where copies live.

Abbreviations: NA = non-turbo, T = turbo, H4SO = flat-4 SOHC NA (EJ251/EJ253/EJ201/EJ202), H4DOTC = flat-4 DOHC turbo (EJ205/EJ255/EJ257), H6DO = EZ30.
"FSM-EU" = the general/export FSM edition (covers 2.0 L engines and Europe/Australia notes), "FSM-US" = USDM edition.

---

## 1. Where the manuals and TSBs are

| Model / years | Language | Official or copy | Where | Access / cost / notes |
|---|---|---|---|---|
| All US-market Subaru 1990+ (FSM, TSBs, TechTIPS, wiring) | EN | Official | https://techinfo.subaru.com (STIS) | Pay per period. Last prices seen on forums: 72 h $34.95, 30 days $299.95, 365 days $2,499.95 (older posts quote $19.95-35 for 3 days). Title search free; download limited per hour. Sources: [tapatalk STIS](https://www.tapatalk.com/groups/outdoor_garage/subaru-technical-information-system-stis-t355.html), [aa1car OEM sites](https://www.aa1car.com/library/oemwebsites.htm), [subaruforester.org how-to](https://www.subaruforester.org/threads/how-to-service-manuals-from-stis-3-day-subscription-merged-thread.271290/). Live price could not be read (site reset connection). |
| EU-market Subaru, Euro 5 onwards only | EN/DE etc. | Official | https://www.subaru-repairinfo.com (Subaru Europe N.V./S.A.) | Tiered access for independent operators. Terms say it supports Euro5-onward models imported by Subaru Europe; so SG/early SH and older EU cars are mostly NOT covered. [terms](https://www.subaru-repairinfo.com/scr/ini/en/term_and_conditons.html) |
| JDM Forester SG5/SG9 (2005.12~), SH5/SH9 (2011.08~ listed), Legacy BE/BH (1998.6~, PDF), Legacy BL/BP (2004.5~) | JA | Official (JASPA registry) | FAINES, run by JASPA (日整連): https://faines.jaspa.or.jp/enduser/registration_manual_list/103?page=2 | For registered workshops; manuals for most Japanese cars sold since ~2000. Paper 整備解説書 trade used on Yahoo Auctions. Impreza not seen on page 3 of the list. [seibii note on FAINES](https://seibii.co.jp/blog/contents/fainess-problem/) |
| Owner's manuals JDM | JA | Official | https://sp.subaru.jp/afterservice/tnst/forester/ | Owner manuals only. |
| Forester 2001 (SF), 2003 (SG), 2004 (SG) | EN PDF, RU index page | Copy of FHI FSM (FSM-EU, pub. G8080GE1..GE8 for 2004) | https://subaruport.ru/manuals/forester/ (files at subaruport.ru/for4/*.pdf etc.) | Free, split by section (EN H4SO, EN H4DOTC 2.0, EN H4DOTC 2.5, 4AT diag per engine). Looks like genuine FHI PDFs. |
| Legacy 2001 (BE/BH), 2003, 2004 (BL/BP, incl. 5AT) | EN PDF | Copy of FHI FSM-EU | https://subaruport.ru/manuals/legacy/ (leg4/*.pdf) | 2004 set has H4SO 2.0, H4SO 2.5, H4DOTC 2.0, H6DO, 4AT, 5AT diag. Best free source of 5AT and BL/BP data. |
| Impreza 2001 (GC/GD), 2004 (GD/GG) | EN PDF | Copy of FHI FSM-EU (G1870GE*) | https://subaruport.ru/manuals/impreza/ ; mirror https://ken-gilbert.com/wrx/04impreza/imp04_sec2_11-1.pdf | Includes w/o-OBD variants. |
| Forester 2003 (SG) | EN PDF, RU index | Copy | http://www.a2motors.ru/sm-for03.shtml (downs/sm-for03/*.pdf) | Same FHI PDFs as subaruport; SOHC and TURBO diag, 4AT diag. |
| Impreza USDM 1996-2006 (MY96-01 GC, MY02 GD, 2005, 2006, STi 2004) | EN PDF | Copy of USDM FSM CD | https://www.ludicrous-speed.com/automotive/impreza/fsm/ | Full CD dumps, e.g. Impreza_2006/H4SO Engine/EN(H4SO)(diag) Engine (Diagnostics).pdf. Site says "backup of software you own". |
| Forester 2007, Impreza 2007-2008 (incl. STI), many others | EN PDF | Copy (JDM/US CD dumps) | https://jdmfsm.info/Auto/Japan/Subaru/ | Directory listing visible in search results; direct access returned HTTP 403 (Cloudflare) during research. |
| WRX STI 2008+ (EJ257), engine diag excerpt | EN PDF | Copy (10-page excerpt) | http://www.bescaredracing.com/sti/misc/Subaru%20Select%20Monitor.pdf | Pages EN(H4DOTC)(diag)-35..39 with "Note (at idling)" values. |
| Legacy/Outback 2008 USDM BP/BL (G2440BE) | EN | Copy | https://dokumen.pub/subaru-legacy-outback-2008-factory-service-manual-usdm-bp-bl-g2440be-v32nbsped.html | Full FSM incl. EN(H4SO)(diag); returned 403 to automated fetch. |
| Forester SF/SG/SH/SJ/SK all years | RU and EN | Copies (Yandex.Disk, Google Drive) | DRIVE2 "Euro Forester" community index: https://www.drive2.ru/c/2130901/ | SG: service EN 2003/2004/2007, service RU 2004 (maintenance) and 2008; SH: service RU 2009 and 2012, owner RU/EN; wiring SG 2002-05 and 2006-08 (RU). Links may rot. |
| Forester SH 2009/2012 service (RU), SH 2010 online | RU | Copies | Almaty Subaru Club thread https://asc.kz/forum/topic/2612-manualy-obschaya-tema/?page=7 ; exist.ru thread https://www.exist.ru/Forum/107995/599232 | forester-manual.ru (SH 2010 online) and myfc.zatulinka.ru are dead or parked now. |
| Forester 2001/2003/2004, SG 2004 | EN | Copy (viewer) | https://www.manualslib.com/manual/830105/Subaru-2004-Forester.html , /805513/ (2003), /918386/ (2001), /1556059/ (SG 2004) | Page-by-page viewer. |
| Impreza 2004 | EN | Copy (viewer) | https://www.manualslib.com/manual/739113/Subaru-Impreza-2004.html | |
| 2007 Forester, others | EN | Paid VIN-based reprints | https://www.factory-manuals.com/expand-2007-subaru-forester-repair-manual-1359.html | Commercial; not verified. |
| Subaru TSBs, campaigns, TechTIPS (US) | EN | Official, free | NHTSA manufacturer communications: https://www.nhtsa.gov/vehicle/2004/SUBARU/FORESTER (API: https://api.nhtsa.gov/vehicles/byYmmt?modelYear=2004&make=SUBARU&model=FORESTER&issueType=t) | Pre-2008 items often have only a one-line summary, no PDF. |
| TechTIPS article index (1990-2021) | EN | Official | https://static.nhtsa.gov/odi/tsbs/2021/MC-10191562-0001.pdf | 55-page index of Subaru TechTIPS newsletter topics with dates. |
| EndWrench (Subaru technician magazine) archive | EN | Official magazine, rehosted | https://automotivetechinfo.com/wp-content/uploads/2004/02/Subaru-EndWrench-Winter-2004.pdf , STIS guide https://automotivetechinfo.com/wp-content/uploads/2005/08/Subaru-Technical-Information-Website.pdf | 4EAT article there is Phase 1 (pre-1999), not our scope. |
| SSM operation guide | EN | Copy | https://www.scribd.com/document/62693223/Subaru-Select-Monitor | Menus only, no reference values. |

Official SH part numbers exist (example quoted on subaruforester.org: MSA5T0921A = 2009 Forester Engine 1 H4SO), but the thread now redirects behind a paywall proxy.

---

## 2. Subaru Select Monitor reference values (engine)

Important finding: the 2003-2004 FSM-EU editions for Forester SG and Impreza GD list SSM items with units only (no values).
"Note (at idling)" value columns appear in the Legacy BL/BP 2004 FSM-EU and in USDM FSMs from MY2006 on (Impreza 2006, STI 2008+).
Conditions in all tables: engine warmed up, idling, N/P, A/C off, accessories off.

### 2.1 Engine load spec ("Engine Condition Data", EN section, page "Engine Condition Data")

| Model / MY / engine | Idle | 2,500 rpm no load | Source |
|---|---|---|---|
| Forester 2003 SG, EJ25 SOHC NA | 1.6-2.9 % | 6.4-12.8 % | a2motors for03_sec01_11-1.pdf, EN(SOHC) |
| Forester 2004 SG, H4SO (2.0/2.5 NA) | 1.6-2.9 % | 6.4-12.8 % | subaruport for4_sec1_11.pdf, EN(H4SO)-24 |
| Forester 2004 SG, H4DOTC 2.0 T (EJ205) | 1.3-2.6 % | 4.4-9.6 % | for4_sec2_11-1.pdf, EN(H4DOTC)-26 |
| Forester 2004 SG XT, H4DOTC 2.5 T (EJ255) | 1.9-3.9 % | 6.7-14.6 % | for4_sec3_11.pdf, EN(H4DOTC 2.5) |
| Legacy 2004 BL/BP, H4SO 2.0 and 2.5 | 1.6-2.9 % | 6.4-12.8 % | leg4_sec1_11.pdf, leg4_sec1_22-1.pdf |
| Legacy 2004 BL/BP, H4DOTC 2.0 T (EJ20X/Y) | 1.2-2.9 % | 4.7-12.8 % | leg4_sec2_11.pdf |
| Impreza 2004 GD, H4SO and H4DOTC 2.0 T | 1.6-2.9 % | 6.4-12.8 % | imp04_sec1_11-1.pdf, imp04_sec2_11-1.pdf |
| Impreza 2005 US, H4SO 2.5 | 2.2-4.4 % (actual 2.7) | 6.4-12.8 % (actual 7.8) | ludicrous-speed Impreza_2005 EN(H4SO)(diag) |
| Impreza 2005 US WRX, H4DOTC (EJ205) | 0.9-1.8 % (actual 1.1) | 4.0-8.1 % (actual 5.0) | Impreza_2005 EN(H4DOTC)(diag) |
| Impreza 2006 US, H4SO 2.5 (EJ253) | 17.6-40.5 % | 14.72-29.8 % | Impreza_2006 EN(H4SO)(diag) "Engine Condition Data" |
| Impreza 2006 US WRX, H4DOTC 2.5 (EJ255) | 17.6-35.64 % | 13.2-26.73 % | Impreza_2006 EN(H4DOTC)(diag) |

Note: from MY2006 the "Engine load" item changed scale (OBD calculated load), so 2006+ values (~21 % at idle) are not comparable to the 2004 values (~2 %). Our app must know which ECU generation it talks to before judging load.

### 2.2 SSM "Note (at idling)" tables

Column legend: L-20 = Legacy BL/BP 2004 EJ20 NA SOHC (leg4_sec1_11, EN(H4SO 2.0)(diag)-25/26); L-25 = Legacy BL/BP 2004 EJ25 NA SOHC (leg4_sec1_22-1); L-20T = Legacy BL/BP 2004 EJ20 DOHC turbo (leg4_sec2_11, EN(H4DOTC)(diag)-21/22); I-25 = Impreza 2006 US EJ253 (EN(H4SO)(diag)-28/29); I-25T = Impreza WRX 2006 US EJ255 (EN(H4DOTC)(diag)-31/32); STI = WRX STI 2008+ EJ257 (EN(H4DOTC)(diag)-35/36, bescaredracing excerpt).

| SSM item | L-20 | L-25 | L-20T | I-25 | I-25T | STI |
|---|---|---|---|---|---|---|
| Engine load | 1-3 % | 3.5 % | - | 21.0 % | 21.0 % | - |
| Coolant temp | >= 75 C | >= 75 C | 80-100 C | 92 C (example) | 80-100 C | 80-100 C |
| A/F correction #1 | -10..+10 % | -10..+10 % | -10..+10 % | -0.8 % (example) | -10..+10 % | -10..+10 % |
| A/F learning #1 | -15..+15 % | -15..+15 % | -15..+15 % | 0.0 % (example) | -15..+15 % | -15..+15 % |
| Manifold abs. pressure | 200-300 (unit printed "kpa", likely mmHg) | 200-300 mmHg | 220-275 mmHg | 200-300 mmHg = 26.7-40 kPa | 220-275 mmHg = 29.5-37 kPa | 220-275 mmHg |
| Engine speed | 600-800 rpm | 600-800 rpm | 630-770 rpm | 600-800 rpm | 700 rpm | 630-770 rpm |
| Ignition timing | 10-15 deg | 12.5-13.5 deg | 10-15 deg | 14-16 deg | +17.0 deg | 10-15 deg |
| Intake air temp | ambient | ambient | 20-50 C | ambient | 20-50 C | 20-50 C |
| Mass air flow | - (MAP engine) | 2.8-3.2 g/s | 2.1-3.1 g/s | 2.8-3.2 g/s | 3.6 g/s | 2.1-3.1 g/s |
| MAF sensor voltage | - | 1.1-1.2 V | 1.0-1.7 V | 1.26 V | 1.0-1.7 V | 1.0-1.7 V |
| Throttle opening | 1-2 % | 1.2-1.6 % | 2.0-2.4 % | 2.0 % | 3.0-3.2 % | 2.0-2.4 % |
| Rear O2 | 0.01-0.85 V | 0.6-0.85 V | 0-1.0 V | 0.1-0.7 V | 0-1.0 V | 0-1.0 V |
| Battery | 12-14 V | 12-14 V | 12-15 V | 12-14 V | 12-15 V | 12-15 V |
| Injector #1 pulse | 2-4 ms | 2.56-3.3 ms | 1.2-2.2 ms | 2.82 ms | 1.2-2.2 ms | 1.2-2.2 ms |
| Knock correction | 0.0 deg | 0.0 deg | 0.0 deg | 0.0 deg | 0.0 deg | 0.0 deg |
| Learned ignition timing | -2..+2 deg | - | - | - | - | - |
| Accel opening | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 0.0 % | 0.0 % |
| CPC (purge) duty | 0-3 % | 0-3 % | 0-25 % | 0-3 % | 0-25 % | 0-25 % |
| ALT duty | 0-100 % | 0 % | 0-100 % | - | - | 0-100 % |
| Fuel pump duty | - | - | 30-40 % | - | 33 % | 30-40 % |
| AVCS advance R/L | - | - | +-5 deg | - | 0 deg | 5 deg |
| OCV (AVCS) duty R/L | - | - | 0-20 % | - | 9.4 % | 0-20 % |
| OCV current R/L | - | - | 40-100 mA | - | 40-100 mA | 40-100 mA |
| A/F sensor #1 current | - | -0.2..+0.2 mA | -20..+20 mA | -0.2..+0.2 mA | - | -20..+20 mA |
| A/F sensor #1 resistance | 25-27 (unit misprint "mA", ohm intended) | 28-31 | 27-35 | 32 ohm | - | 27-35 |
| A/F sensor #1 lambda | 0.85-1.15 | 0.85-1.05 | 1.0 | 1.0 | - | 1.0 |
| A/F correction #3 (rear O2 trim) | 3.5-6.5 % | 5.08 % | 0.00 % | 0.3 % | - | 0.00 % |
| A/F heater current | 5-10 A (as printed) | - | - | - | - | - |
| Rear O2 heater current | 0.9-1.1 A | - | - | - | - | - |
| Throttle motor duty | 5-10 % | -12..-20 % | -5 % | -15 % | - | -5 % |
| Main throttle sensor | 0.62 V (closed 0.3-0.7 V) | 0.62 V | 0.6 V | 0.66 V | - | 0.6 V |
| Sub throttle sensor | 1.48-1.50 V | 1.48-1.50 V | 1.5 V | 1.52 V | - | 1.5 V |
| Main / sub accel sensor | 0.5-1.5 V | 0.98-1.0 / 1.12 V | 1.0 / 1.1 V | - | - | 1.0 / 1.1 V |
| TGV position R/L | - | - | - | - | 0.44 / 0.48 V | - |
| Fuel tank pressure | - | - | - | +1.1 kPa | +1.2 kPa | +1.2 kPa |
| Fuel temp | - | - | - | +20 C | +21 C | +28 C |
| Exhaust gas temp | - | - | - | - | - | 385 C |
| Roughness monitor #1-4 | - | - | - | - | - | 0 |
| EGR steps | - | 0 | - | 0 | - | - |
| Fuel level resistance | 0-100 ohm | - | - | - | - | - |

STI OBD-mode example at idle (same excerpt, EN(H4DOTC)(diag)-39): calc load 1.6 %, ECT +91 C, STFT +0.0 %, LTFT +1.6 %, MAP 256 mmHg, 693 rpm, timing adv #1 +16.0 deg, IAT 54 C, MAF 3.1 g/s, rear O2 0.800 V, A/F lambda 0.984-0.992.

### 2.3 ECM I/O signal voltages (Forester 2004 FSM-EU, EN(H4SO)-20..23 and EN(H4DOTC 2.5))

| Signal | Key ON engine off | Idling | Note |
|---|---|---|---|
| ECT sensor | 1.0-1.4 V | 1.0-1.4 V | after warm-up |
| TPS (cable throttle H4SO) | closed 0.2-1.0 V, open 4.2-4.7 V | | sensor supply 5 V |
| MAP sensor (H4SO 2004) | 4.0-4.8 V | 1.1-1.9 V | |
| IAT sensor (H4SO) | 3.15-3.33 V | 3.15-3.33 V | at 25 C |
| Front A/F sensor signal 1 / 2 (H4SO) | | 2.05-2.25 / 1.75-1.95 V | |
| Rear O2 (H4SO) | 0 | 0-0.9 V | |
| Knock sensor | 2.8 V | 2.8 V | |
| Battery / ignition supply | 10-13 V | 12-14 V (EJ255: 13-14 V) | |
| Fuel level sensor | 0.12-4.75 V | | |
| MAF signal (EJ255) | | 0.3-4.5 V | |
| IAT (EJ255) | 0.3-4.6 V | | |
| A/F sensor + / - (EJ255) | 2.8-3.2 / 2.4-2.7 V | | |
| Starter switch | 0 | | cranking 8-14 V |

### 2.4 DTC flowchart / detection numbers

| DTC | Numbers | Source |
|---|---|---|
| P0171/P0172 (Forester 2004 H4SO; same in H4DOTC 2.5) | Fuel pressure with regulator vacuum hose disconnected 284-314 kPa (41-46 psi); connected, idle 206-235 kPa (30-34 psi); ECT should read 70-100 C; MAP at idle 24.0-41.3 kPa (180-310 mmHg), key on 73.3-106.6 kPa (550-800 mmHg) | for4_sec1_11.pdf EN(H4SO)-139/140 "DTC P0172 SYSTEM TOO RICH" flow; for4_sec3_11.pdf |
| P0171/P0172 detection (Impreza 2006 US EJ253) | Enable: ECT >= 75 C, A/F learning active. NG if fsobd = (meas lambda - target) + faf + flaf >= +40 % (low air flow) falling to +26.5 % (>= 9.6 g/s) for >50 s; OK if < 19 %. Rich side <= -40..-26.5 %, OK >= -20 %. MIL after 2 trips. | Impreza_2006 GD(H4SO)-63/64 |
| P0128 thermostat (Impreza 2006 US EJ253) | NG for >30 s when: start ECT < 55 C, ECT < 75 C, estimated minus measured > 30 C, speed >= 30 km/h, estimated ECT >= 75 C, est. ambient >= -7 C. MIL after 2 trips. OK when ECT reaches 75 C with difference <= 30 C. | Impreza_2006 GD(H4SO)-44/45 |
| P0301-P0304 misfire (Impreza 2006 US EJ253) | FTP 1.5x level: > 1.0 % misfire in 1,000 revs; catalyst damage level: map, e.g. 14.25 % to 5 % of 400 ignitions depending on rpm/load; detection 460-6,200 rpm | Impreza_2006 GD(H4SO)-78.. |

---

## 3. Automatic transmission (4EAT / 5EAT) reference data

Finding: in all FSM editions checked (Forester 2004, Legacy 2004, Impreza 2006), the AT "Read current data" SSM table lists items and units only, no normal values. Usable numbers are in the TCM I/O table and in the line-pressure / stall tests.

### 3.1 4EAT SSM items (Forester 2004, EN(H4SO) "Read current data for AT")
Battery V, rear/front wheel speed, engine rpm, ATF temp, throttle sensor V, gear position, line pressure duty %, L/U duty %, AWD (transfer) duty %, throttle sensor supply V, turbine speed rpm, 2-4 brake pressure duty %, manifold relative voltage, FWD switch, kickdown, stop light, ABS, cruise, N/P, R, D, 3, 2, 1 range signals, shift solenoids 1/2, torque control signals, 2-4B timing solenoid, L/C timing solenoid, diagnosis lamp.

### 3.2 4EAT TCM I/O (Forester 2004, 4AT(H4SO)-12/13)

| Item | Value |
|---|---|
| ATF temp sensor | 20 C: 1.6-2.0 V, 2.1-2.9 kOhm; 80 C: 0.4-0.9 V, 275-375 Ohm |
| Throttle position sensor | closed 0.2-1.0 V, open 4.2-4.7 V; supply 4.8-5.3 V |
| Front/rear speed sensor, turbine sensor | 450-650 Ohm; > 1 V AC when turning |
| Shift solenoids 1/2 | 10-16 Ohm |
| Line pressure duty solenoid | 2.0-4.5 Ohm |
| Lock-up duty solenoid | 10-17 Ohm |
| Transfer (AWD) duty solenoid | 10-17 Ohm |
| 2-4 brake duty solenoid | 2.0-4.5 Ohm |
| 2-4 brake timing / low clutch timing solenoids | 10-16 Ohm |
| Engine speed input | 8-11 V with engine running |

### 3.3 4EAT tests (Forester 2004 FSM-EU, 4AT section, pages 4AT-2, 4AT-35..38)

| Test | NA | Turbo |
|---|---|---|
| Stall speed (sea level) | 2.0 NA 2,000-2,500 rpm; 2.5 NA 2,100-2,600 rpm | 2.0 T 2,600-3,300 rpm; 2.5 T 2,700-3,200 rpm |
| Stall torque ratio | 2.0 NA 2.0-2.2; 2.5 NA 1.9-2.1 | 2.0 T 1.85-2.15; 2.5 T 2.05-2.35 |
| Time lag N->D / N->R | < 1.2 s / < 1.5 s | same |
| Line pressure, 2 range HOLD, WOT | duty 5 %: 1,130-1,275 kPa | printed (2.5-3.5): 1,400-1,700 kPa |
| Line pressure, R, WOT | duty 5 %: 1,520-1,716 kPa | printed (1.5-2.5): 1,600-1,900 kPa |
| Line pressure, D, closed throttle | duty 95 %: 300-410 kPa | printed (3.5-4.5): 500-800 kPa |
| Transfer clutch pressure AWD mode | 910-1,070 kPa / 410-490 kPa (two load points) | 930-1,100 / 420-560 kPa |
| Test ATF temperature | 70-80 C | |

Turbo duty column is printed in brackets without a unit; check page 4AT-36 before using.
Gear ratios 4EAT: 2.785 / 1.545 / 1.000 / 0.694, R 2.272; torque converter 246 mm.

### 3.4 5EAT (Legacy BL/BP 2004, 5AT(diag)-12..17)
SSM items: ATF temp 1 and 2, turbine speed 1 and 2, solenoid currents (H&LR/C, D/C, F/B, I/C, P/L, L/U, AWD) in A, solenoid target pressures in kPa, lateral G sensor V, inhibitor switches 1-4, oil pressure switches.

| Item | Value |
|---|---|
| ATF temp sensor 1 | 80 C: 0.8-1.0 V, 0.7-0.9 kOhm |
| ATF temp sensor 2 | 80 C: 0.6-0.8 V, 0.4-0.6 kOhm |
| Linear solenoids (P/L, L/U, I/C, D/C, F/B, H&LR/C, AWD) | 3-9 Ohm at 20 C, drive 750-850 Hz |
| Turbine speed sensor 1 / 2 at 2,000 rpm turbine, 4th manual | approx 1,900-2,100 Hz / approx 1,300-1,500 Hz (other gear) |

5EAT fluid: SUBARU ATF (K0410Y0700); alternatives IDEMITSU ATF HP, Castrol Transmax J. Dexron III not allowed for 5AT (allowed as last resort for 4AT).

---

## 4. Specifications

### 4.1 Forester SG 2004 (FSM-EU, SPC-2..4, CO, FU, ME, IG sections)

| Item | 2.0 NA (EJ201/202) | 2.0 T (EJ205) | 2.5 NA (EJ25 SOHC) | 2.5 T (EJ255) |
|---|---|---|---|---|
| Compression ratio | 10.0 | 8.0 | 10.0 | 8.2 |
| Idle, P/N | 650 +-100 | 700 +-100 | 650 +-100 | 700 +-100 |
| Idle A/C on | 850 +-100 (NA) | MT 725-800 / AT 750-825 +-100 (by refrigerant pressure) | 850 +-100 | not extracted |
| Ignition timing at idle (BTDC) | 10 +-10 deg / 650 | 12 +-10 deg / 700 | MT 10, AT 15 +-10 deg / 650 | 17 +-10 deg / 700 |
| Spark plug | Champion RC10YC4, alt NGK BKR5E-11 | NGK PFR6G | Champion RC10YC4, alt NGK BKR5E-11 | NGK ILFR6B |
| Plug gap | 1.0-1.1 mm | 0.7-0.8 mm | 1.0-1.1 mm | 0.7-0.8 mm |
| Compression (350 rpm WOT) | std 1,275 kPa, limit 1,020 kPa | std 981-1,177, limit 882 kPa | std 1,275, limit 1,020 | (turbo section) |
| Max cylinder difference | 49 kPa | < 49 kPa | 49 kPa | |
| Thermostat starts / full open | 80-84 C / 95 C | 76-80 C / 91 C | 80-84 C / 95 C | 76-80 C / 91 C |
| Thermostat lift | >= 9.0 mm | | | |
| Coolant total | MT 6.6 L, AT 6.5 L (6.9 with ATF warmer) | AT 7.3 L, MT 7.3-7.4 L | MT 6.9 L, AT 6.8 L | MT 7.4 L, AT 7.3 L |
| Engine oil, change | approx 4.0 L | 4.0 L | 4.0 L | 4.0 L |
| Oil spec | API SL/SJ "Energy conserving", ILSAC, ACEA A1/A2/A3 (alt SH/SG); viscosity chart in RM-2 (0W-20 and 5W-30 shown) | | | |
| ATF (4AT) | 8.4 L | 9.3 L | 9.3 L | 9.3 L |
| ATF spec | Dexron III | | | |
| Front diff (AT) | 1.2 L | | | |
| Rear diff | 0.8 L GL-5 | | | |
| MT gear oil | 3.5-4.0 L GL-5 | | | |
| Fuel pump shutoff pressure | 370-677 kPa (H4SO); 450-677 kPa (EJ255) | | | |
| Regulated fuel pressure | 206-235 kPa idle with vacuum, 284-314 kPa hose off (from DTC flow) | | | |
| Valve clearance (cold) | intake 0.20 +-0.04, exhaust 0.25 +-0.04 mm | | | |
| Timing belt | Europe schedule (PM-3) prints "R" every 24 months / 30,000 km for "Camshaft drive belt" (looks unusual, verify); non-Europe schedule: replace at 100,000 km / 48 months block (Legacy 2004 non-EU table) | | | |
| Spark plug interval | Europe: NA every 30,000 km; turbo at 105,000 km | | | |

### 4.2 Legacy BL/BP 2004 (FSM-EU SPC)

| Item | 2.0 SOHC | 2.0 DOHC turbo | 2.5 SOHC | 3.0 DOHC (H6) |
|---|---|---|---|---|
| Compression ratio | 10.0 | 9.5 | 10.0 | 10.7 |
| Idle | 650 | 650 | 650 | 650 |
| Ignition timing idle | 13 deg | 14 deg | 13 deg | 15 deg |
| Plug | Champion RC10YC4 | NGK ILFR6B | Champion RC10YC4 / NGK PFR5B-11 | NGK ILFR6B |
| Oil at change / overhaul | 4.0 / 4.5 L | 4.0 / 5.0 L | 4.0 / 4.5 L | approx 5.5 / 7.2 L |
| ATF | 4AT 8.4 L | 5AT 9.8 L | 4AT 9.3 L | 5AT 9.8 L |
| Front diff (AT) | 4AT 1.2 L, 5AT 1.4 L | | | |
| Coolant | AT 6.7-6.8 L (6.3-6.4 noted variants) | 7.3 L | 6.7-6.8 L | 7.7-7.8 L |
| Timing belt | Europe: replace at 105,000 km / 84 months; non-EU: 100,000 km / 48-month block | | | H6: chain, drive belt every 160,000 km |

### 4.3 Impreza GD/GG 2004 (FSM-EU SPC)

| Item | 2.0 NA | 2.0 T (WRX) | 2.5 NA | STi |
|---|---|---|---|---|
| Compression ratio | 10.0 | 8.0 | 10.0 | 8.0 |
| Idle | 650 +-100 (with OBD), 700 +-100 (w/o OBD) | 700 +-100 (Europe 750) | 650 +-100 | 700 +-100 |
| Timing at idle | 10 +-10 deg | 12 +-10 deg | MT 10, AT 15 +-10 deg | 12 +-10 deg |
| Plug | Champion RC10YC4 / NGK BKR5E-11 | NGK PFR6G | Champion RC10YC4 / NGK BKR5E-11 | NGK PFR6G |
| Oil total | 4.0 L | 4.5 L | 4.0 L | 4.5 L |
| ATF | 8.4 L | 9.3 L | 9.3 L | - |
| Coolant | MT 7.0 / AT 6.9 L | MT 7.7 / AT 7.6 L | MT 7.0 / AT 6.9 L | 7.7 L |

### 4.4 Impreza 2006 US EJ253 (CO(H4SO))
Thermostat starts 80-84 C, full 95 C, lift >= 9.0 mm. Coolant AT approx 6.9 L, MT approx 7.0 L. (Confirms the NA thermostat numbers above for USDM too.)

### 4.5 ATF type changes (TechTIPS index)
"Automatic Transmission Fluid for All 5EAT and 4EAT Transmissions" (TechTIPS 12/06) and "More ATF HP Fluid Information" (09/07): Subaru moved 4EAT/5EAT to Subaru ATF / ATF-HP; 2004 FSM-EU still lists Dexron III for 4EAT. Forum consensus: Dexron III up to ~2005 MY, ATF-HP 2006-2009 4EAT (unverified against FSM).

---

## 5. TSBs, campaigns, TechTIPS (official, via NHTSA manufacturer communications)

Number format on NHTSA for old items: 166806 = Subaru bulletin 16-68-06. NHTSA ID in brackets.

| Bulletin | Models / MY | One-line summary |
|---|---|---|
| WWP-99 campaign "Engine cooling system conditioner" (Feb 2004) [10006149] | Forester, Impreza, Legacy, Outback 1999-2002 (2.5 SOHC) | Add Subaru Cooling System Conditioner (SOA635071) to stop small external head gasket coolant seeps. |
| TechTIPS "Head Gasket Repairs" (11/03), "Revised Cylinder Head Gasket Diagnostic Procedure" (05/11) | EJ25 | Head gasket repair and diagnosis guidance (index: MC-10191562). Later MLS gasket 11044AA642 replaced 11044AA633 (parts listings, not a TSB). |
| 16-65-99 / 16-65-99R [610541, 613408] | Forester, Impreza, Legacy 1999-2000 | AT temp light flashing, TCM code 38 (torque control signal). |
| 2000 TechTIPS [610111] | 1999+ second-gen 4EAT | Duty solenoid DTC: check AT oil pan for dents. |
| 16-66-02 [10001352] | Forester, Impreza, Legacy 1999-2002 | TCU code 45 and harsh AWD engagement. |
| 16-67-04 [10010527] | Legacy/Outback 2004 | Transfer clutch binding (torque bind). |
| 16-68-06 [10020531] | Forester, Impreza, Legacy, Outback 2005-2006 4EAT | Diagnostic procedure for P0851/P0852 (neutral switch / inhibitor). |
| TechTIPS (12/04) [10015746] | Forester, Impreza 2005 4EAT | Inspect/correct connector T4/B11. |
| 2003-12 bulletin "94" [10000639] | 2001-2003 4EAT | Improperly made 4EAT parking pawl rod. |
| 16-91-14 [10067835] https://static.nhtsa.gov/odi/tsbs/2014/SB-10067835-0699.pdf | Legacy/Outback 2005-2009 5AT | Two corrections to FSM diagnostic procedures for 5AT DTCs. |
| 16-92-14 [10092742] https://static.nhtsa.gov/odi/tsbs/2014/SB-10092742-2280.pdf | Forester 2009+, Impreza 2008+ AT | Revised (coated) AT fluid cooler piping, corrosion. |
| 16-63-99R reman program [10234261] https://static.nhtsa.gov/odi/tsbs/2012/MC-10234261-9999.pdf | 1999-2010 | Remanufactured automatic transaxle program. |
| 03-70-12R [10079980] https://static.nhtsa.gov/odi/tsbs/2013/SB-10079980-0699.pdf | 1999-2010 | High-pitched noise from transmission extension housing in cold, revised parts. |
| WXW-80 [618424] | Forester, Impreza, Legacy, Outback 2000-2001 | Front A/F sensor element cracking. |
| WWG-90 [633865] | 1999 Forester, Impreza, Legacy | Air flow meter (MAF) sensor campaign. |
| WWF-89 [631499] | 2000-2002 all | ECM reprogramming. |
| TechTIPS (09/03) [10016482] | Legacy 2001-2004 | Rough idle / CEL after battery disconnect (idle relearn). |
| 02-93-04 [10011775] | Forester, Impreza, Legacy 2004-2005 | Timing belt tensioner bracket modified to stop knocking noise. |
| 02-97-05 [10017968] | Forester, Impreza 2004 turbo | AVCS union screw (banjo) filter damage causing rough idle / slow rev. |
| TechTIPS "Don't forget to check those banjo bolt filters" (07/08); forum-cited TSB 02-106-08 (turbo oil feed banjo filter removal, TD04) | turbo EJ | Remove turbo oil feed filter screen. 02-106-08 seen only as a forum link, not verified. |
| TechTIPS (02/06) [10019343] | 1999-2006 | Rubbing mark on timing belt (normal vs replace). |
| TechTIPS P0171 (04/05, 09/05, 11/05 "2006MY H4 Legacy/Outback", 12/07 "P0171 and hard start", 04/09 "P0171 rough running after cold start", 07/10 "Tips on P0171/P0172") [10018399, 10017947] | Legacy GT 2004-2005: loose small blue vacuum T fitting under intercooler | Lean code causes and checks. |
| 11-75-05 / 11-76-05 [10016433, 10016432] | Legacy/Outback 2005, Forester/Impreza 2005 | Fuel tank pressure sensor modification (EVAP codes). |
| WVM-23, WVN-24, WVU-31 (+R) [10032512, 10032511, 10038970] https://static.nhtsa.gov/odi/tsbs/2009/CSC-10032512-1285.pdf | Forester, Legacy, Outback, Impreza 2006-2010 2.5 NA | ECM reprogramming to protect catalytic converters; WVN-24 also removes fuel tank one-way valve. TechTIPS 06/11 "Rough idle after WVU-31 reprogramming". |
| WVL-22 [10032510] | Forester, Impreza 2005-2008 | Remove fuel tank one-way fuel valve. |
| WQD-40 [10080261] https://static.nhtsa.gov/odi/tsbs/2013/SB-10080261-0699.pdf | Forester, Legacy, Outback 2008-2009 | Replace front catalytic converter and reprogram ECM (P0420). |
| WVH-18 / WVI-19 [10030865, 10030868] | Legacy/Outback 2005 (PZEV) | ECM reprogramming for catalytic converter protection. |
| 08 ECM reprogramming [10020126] | Legacy/Outback 2004 | ECM reprogramming service program. |
| 02-155-14R [10093466] https://static.nhtsa.gov/odi/tsbs/2014/SB-10093466-2280.pdf | Forester, Legacy, Outback turbo 2005-2012 | Revised intercooler/intake duct; old duct insulation separates and leaks boost. |
| 07-75-13R [10173117] https://static.nhtsa.gov/odi/tsbs/2020/MC-10173117-0001.pdf | Forester turbo, WRX | DTC P0606 determination. |
| WTM-73R recall [10192930] | Turbo Legacy/Outback 2007-2009, Impreza 2008+, Forester 2009+ | Replace secondary air pump relay (turbo models). |
| TechTIPS "Leaking engine oil seals? Check the PCV!" (02/11) | all EJ | Blocked PCV pushes oil out of seals. No dedicated PCV TSB found for this era. |
| 02-115-11R [10067846] | Forester 2010 | White smoke / oil use from badly machined intake valve guide holes #1/#4. |
| 04-13-09R | 2001-2010 | Power steering pump groan at full lock (relief valve). |

---

## 6. What I could NOT find

- SSM "Note (at idling)" value tables for Forester SG 2003-2008 and Forester SH 2009-2010 specifically. Closest proxies: Legacy BL/BP 2004 (same EJ20/EJ25 gen) and Impreza 2006 US (EJ253/EJ255, same ECUs as SG 2006+). The SG 2003/2004 FSM-EU tables have units only.
- Any SSM values at 2,500 rpm other than "engine load". FSMs give only idle values for all other items.
- SSM normal values for AT items (line pressure duty, L/U duty, transfer duty, ATF temp, turbine speed) at idle: FSMs list items without values. Only TCM I/O voltages/resistances and pressure tests are given.
- Official TSB numbers for 4EAT torque converter shudder / lock-up judder on 2003-2009 cars: none found in NHTSA data or searches (only forum mentions). Torque-converter relearn TechTIPS are 2018/2020 (CVT era).
- A head gasket TSB with a number for EJ25 SOHC Phase 2 (only campaign WWP-99 conditioner and TechTIPS articles).
- Knock sensor TSB for this era (only the 2014-2017 FA/FB item 07-133-18, out of scope).
- Current techinfo.subaru.com pricing (site did not respond); prices above are from forum posts.
- Forester SH (2009-2010) spec tables (thermostat, capacities) from an FSM; retail sites conflict, not recorded.
- 4EAT turbo line-pressure duty values: printed without unit in the 2004 FSM-EU, needs a visual check.
- Japanese 整備解説書 content online: only FAINES registry (members) and used paper copies; no public scans found.
