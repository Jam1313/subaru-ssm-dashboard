# Flashing the SensorBox Arduino from the app (no computer)

Scope: a "Прошить плату" button in the app that writes the SensorBox firmware (`hardware/sensorbox/sensorbox.ino`, compiled to
Intel HEX and shipped in the APK) into an Arduino plugged into the head unit's USB, using the board's own bootloader and
`com.github.mik3y:usb-serial-for-android:3.8.1`, which the app already uses.

Boards: Nano / Nano V3 (ATmega328P, CH340 or FTDI, Optiboot 115200 or old ATmegaBOOT 57600), Uno R3 (16U2 or CH340),
Pro Micro 5 V / Leonardo / Micro (ATmega32U4, Caterina), Mega 2560 (STK500v2).

Research date: 2026-10. Everything below was checked against the bootloader and avrdude sources linked in [Sources](#13-sources)
unless it is marked **(unverified)**. Nothing here was tested on hardware yet.

Short answer: **feasible, and all protocols are small.** Three protocol implementations (STK500v1, AVR109, STK500v2), a DTR
reset, a 1200-baud touch and an Intel HEX parser come to roughly 800 to 1200 lines of Kotlin. The bootloader is never
written, so a failed upload can be retried. The hard part is not the protocols but Android: the ATmega32U4 re-enumerates as a
new USB device during the upload, which needs a separate USB permission inside an 8-second window.

---

## Contents

1. [Board table](#1-board-table)
2. [Reset into the bootloader](#2-reset-into-the-bootloader)
3. [STK500v1: Optiboot and ATmegaBOOT (Nano, Uno)](#3-stk500v1-optiboot-and-atmegaboot-nano-uno)
4. [AVR109 / Caterina (Pro Micro, Leonardo, Micro)](#4-avr109--caterina-pro-micro-leonardo-micro)
5. [STK500v2 (Mega 2560)](#5-stk500v2-mega-2560)
6. [Intel HEX and the .hex files](#6-intel-hex-and-the-hex-files)
7. [Recommended Kotlin design](#7-recommended-kotlin-design)
8. [Android pitfalls](#8-android-pitfalls)
9. [Safety: what can brick a board](#9-safety-what-can-brick-a-board)
10. [Existing implementations and licences](#10-existing-implementations-and-licences)
11. [Test plan](#11-test-plan)
12. [Open questions / unverified](#12-open-questions--unverified)
13. [Sources](#13-sources)

---

## 1. Board table

| | Nano, new bootloader | Nano, old bootloader | Uno R3 | Pro Micro 5 V / Leonardo / Micro | Mega 2560 |
|---|---|---|---|---|---|
| MCU | ATmega328P | ATmega328P | ATmega328P | ATmega32U4 | ATmega2560 |
| Signature | `1E 95 0F` | `1E 95 0F` | `1E 95 0F` | `1E 95 87` | `1E 98 01` |
| Flash | 32 768 B | 32 768 B | 32 768 B | 32 768 B | 262 144 B |
| Flash page | 128 B (64 words) | 128 B | 128 B | 128 B | 256 B (128 words) |
| EEPROM | 1 KB | 1 KB | 1 KB | 1 KB | 4 KB |
| Bootloader | Optiboot (Arduino core ships v4.4), 512 B at `0x7E00`; fuses reserve 2 KB (BOOTRST `0x7800`) | ATmegaBOOT_168_atmega328, 2 KB at `0x7800` | Optiboot v4.4, 512 B at `0x7E00` | Caterina (LUFA CDC), 4 KB at `0x7000` | stk500boot_v2_mega2560, 8 KB at `0x3E000` |
| Max sketch (boards.txt `upload.maximum_size`) | 30 720 | 30 720 | 32 256 | 28 672 | 253 952 |
| Protocol | STK500v1 ("arduino") | STK500v1 ("arduino") | STK500v1 ("arduino") | AVR109 ("avr109"/butterfly) | STK500v2 ("wiring") |
| Bootloader baud | 115 200 | 57 600 | 115 200 | none (native USB CDC; avrdude passes 57 600, ignored) | 115 200 |
| USB VID:PID, sketch running | bridge: CH340 `1A86:7523`, FTDI `0403:6001` | same | 16U2: `2341:0043` (also `2341:0001`, `2A03:0043`, `2341:0243`); CH340 clones `1A86:7523` | Leonardo `2341:8036`, Micro `2341:8037`, arduino.org `2A03:8036/8037`, SparkFun Pro Micro 5 V `1B4F:9206` (3.3 V `9204`) | 16U2: `2341:0042` (R3), `2341:0010` (original), `2A03:0042/0010`, `2341:0210/0242`; CH340 clones `1A86:7523` |
| USB VID:PID, bootloader | same as sketch (the bridge does not change) | same | same | Leonardo `2341:0036`, Micro `2341:0037`, `2A03:0036/0037`, SparkFun 5 V `1B4F:9205` (3.3 V `9203`) | same as sketch |
| Enter bootloader | DTR pulse | DTR pulse | DTR pulse (16U2 relays DTR to RESET) | 1200-baud touch, then a new USB device | DTR pulse |
| Bootloader window | LED flashes (~0.4 s), then a 1 s watchdog restarted by every byte | about 0.3 to 1 s per byte **(unverified exact)** | as Nano new | 8 s (restarted by every `B`/`g` command); forever if flash word 0 is `0xFFFF` | about 1 s for the first byte, then per-byte timeout |
| Arduino IDE FQBN | `arduino:avr:nano:cpu=atmega328` | `arduino:avr:nano:cpu=atmega328old` | `arduino:avr:uno` | `arduino:avr:leonardo` (or `arduino:avr:micro`) | `arduino:avr:mega:cpu=atmega2560` |
| Fuses in boards.txt (reference only) | L `FF` H `DA` E `FD`, lock `0F` | L `FF` H `DA` E `FD`, lock `0F` | L `FF` H `DE` E `FD`, lock `0F` | L `FF` H `D8` E `CB`, lock `2F` | L `FF` H `D8` E `FD`, lock `0F` |

Notes:
- **We never read-modify-write fuses or lock bits.** None of the four bootloaders can write fuses anyway (Optiboot ignores
  `STK_UNIVERSAL`; ATmegaBOOT has it disabled; Caterina has no fuse-write command; the Mega bootloader only implements
  `CMD_PROGRAM_LOCK_ISP`, which we never send).
- On genuine boards the lock bits (`0x0F`, `0x2F`) forbid SPM writes into the boot section, so even a wrong address cannot
  overwrite the bootloader. Clones may ship with unlocked boot sections **(unverified)**; the app must range-check anyway.
- Optiboot and the Mega bootloader report the signature **compiled into the bootloader**, not read from the chip. ATmegaBOOT
  also returns compiled constants. Caterina returns `AVR_SIGNATURE_3, _2, _1` (reversed order) from its build.
- The Nano with the "new bootloader" is Optiboot at `0x7E00`, but its high fuse `0xDA` sets a 2 KB boot section, so the IDE
  limit stays 30 720 B. Execution from `0x7800` runs through erased flash (`0xFFFF`) into Optiboot.
- Clone variants seen in the wild **(unverified, not supported by the first version)**: ATmega328PB Nano (`1E 95 16`),
  ATmega168 Nano (`1E 94 06`, 16 KB, old bootloader at 19 200), LGT8F328P "Nano" (different chip, different core), Nano Every
  (ATmega4809, UPDI: a completely different flow). The app must refuse any signature not in its table.
- CH340 PIDs: `1A86:7523` (CH340G/C, the usual one); `1A86:7522` and `1A86:5523` also exist **(unverified on Nano clones)**.
- **SensorBox sketch size: not measured** (no AVR toolchain on this machine, and installing one was out of scope).
  Estimate **(unverified)**: about 7 to 9 KB on the 328P (Serial, float printing, EEPROM, SPI, interrupts), about 10 to 12 KB on
  the 32U4 (adds the USB CDC stack), about 8 to 10 KB on the 2560. That is 55 to 90 pages; well under 64 KB, so extended
  addressing is never exercised by our own firmware (it is still described below).

---

## 2. Reset into the bootloader

### 2.1 Boards with a USB-serial bridge (Nano, Uno, Mega): DTR pulse

Hardware: the bridge's DTR# output (CH340/FTDI pin, or the 16U2's PD7 driven by the host's DTR state) goes through a 100 nF
capacitor to RESET, which has a 10 kΩ pull-up. **Asserting** DTR drives DTR# low; the falling edge pulls RESET low for about
RC = 1 ms and resets the AVR. The level after that does not matter; only the edge does. Some boards and adapters use RTS
instead, so toggle both.

What avrdude does (`arduino.c`, `butterfly.c`, `wiring.c`):

| Step | `-c arduino` (STK500v1) | `-c wiring` (Mega) |
|---|---|---|
| 1 | open port at the baud, deassert DTR+RTS ("high", discharges the cap) | same |
| 2 | wait 250 ms (comment: Optiboot fails on back-to-back runs otherwise) | wait 50 ms |
| 3 | assert DTR+RTS (reset edge) | same |
| 4 | wait 100 µs ("charging a cap longer creates a reset spike above Vcc") | same |
| 5 | deassert DTR+RTS | same |
| 6 | wait 100 ms, drain input | wait 100 ms (+ `-x delay`), drain |
| 7 | sync; on each failed sync attempt after the first, pulse again (assert, 100 µs, deassert, 20 ms, drain) | `CMD_SIGN_ON` |

usb-serial-for-android mapping (3.8.1): `port.dtr = x` (`setDTR`) and `port.rts = x` (`setRTS`). Each call is one USB control
transfer: CH34x sends vendor request `0xA4` with the inverted line bits (`SCL_DTR = 0x20`, `SCL_RTS = 0x40`); CDC-ACM
(16U2) sends `SET_CONTROL_LINE_STATE (0x22)` with `wValue = (RTS?2:0)|(DTR?1:0)`; FTDI uses `SIO_SET_MODEM_CTRL`. Android cannot
do 100 µs; two consecutive control transfers are about 1 ms apart, which matches the RC time and is fine.
`port.open()` does not touch DTR on CDC-ACM; the CH34x driver initialises with DTR and RTS deasserted.

```kotlin
/** Reset an AVR behind a USB-serial bridge (Nano, Uno, Mega). Port must be open. */
suspend fun dtrReset(port: UsbSerialPort, settleMs: Long = 250, afterMs: Long = 100) {
    runCatching { port.dtr = false; port.rts = false }   // lines "high": the coupling cap discharges
    delay(settleMs)
    runCatching { port.dtr = true; port.rts = true }     // DTR# falls: RESET pulled low through 100 nF
    runCatching { port.dtr = false; port.rts = false }   // ~1 ms later: back high, like avrdude after 100 µs
    delay(afterMs)                                       // bootloader starts; Optiboot flashes the LED
}
```

The existing `SensorBoxLink.session()` sets `port.dtr = true` after open and waits 2.2 s; that already resets a Nano/Uno/Mega.

### 2.2 ATmega32U4 (Pro Micro, Leonardo, Micro): 1200-baud touch

The sketch's USB stack (ArduinoCore-avr `cores/arduino/CDC.cpp`) watches CDC class requests. When it receives
`SET_CONTROL_LINE_STATE` with DTR = 0 while the last `SET_LINE_CODING` rate was 1200, it writes the boot key `0x7777` to RAM
(`0x0800`, or `RAMEND-1` with the newer LUFA bootloader) and starts the watchdog with 120 ms. If DTR goes high again before
that, the reset is cancelled. After the watchdog reset Caterina sees `WDRF` + the boot key and stays in the bootloader.

Sequence (arduino `go-serial-utils/reset.go` and avrgirl-arduino `avr109.js` do the same):
1. Snapshot the current USB device list.
2. Open the sketch's CDC port, `setParameters(1200, 8, STOPBITS_1, PARITY_NONE)` (sends `SET_LINE_CODING`).
3. `port.dtr = false` (sends `SET_CONTROL_LINE_STATE` 0). Do not raise DTR afterwards: that cancels the reset.
4. `port.close()` (usb-serial-for-android does not send any line-state request on close for CDC-ACM).
5. The sketch device detaches about 120 ms later. Arduino's tool then waits 500 ms before scanning, because scanning can
   open the port and cancel the reset.
6. Poll for a **new** device for up to 10 s (Arduino polls every 250 ms; when a new port appears it waits 1 s and checks again
   that it is still there, because "some boards have a glitch: the port appears and disappears rapidly before settling").
7. The new device is Caterina: `2341:0036` (Leonardo), `2341:0037` (Micro), `2A03:0036/0037`, `1B4F:9205` (SparkFun 5 V),
   `1B4F:9203` (3.3 V). Pro Micro clones usually carry the Leonardo bootloader, so `2341:0036`.
8. From the bootloader start there are **8 s** (`TIMEOUT_PERIOD 8000`, 1 ms timer ticks) until it jumps back to the sketch.
   Only `B` (write block) and `g` (read block) restart the countdown; `E` shortens it to 500 ms. If flash word 0 is `0xFFFF`
   (no sketch) the countdown never runs.

SparkFun's Caterina (Pro Micro from SparkFun) also uses 8 s after a watchdog reset with the key, but after an **external**
reset (RST pin) it runs only 750 ms unless reset twice quickly ("double tap", then 8 s). Its key check is one byte `0x77` at
`0x0800`, which the Arduino core's `0x7777` satisfies. Generic clones with the stock Leonardo Caterina run 8 s on every external
reset.

usb-serial-for-android 3.8.1 probes any device that has a CDC-ACM interface (`CdcAcmSerialDriver.probe()` counts ports), so
the bootloader device is found by `UsbSerialProber.getDefaultProber()` without adding its PID to a probe table.

---

## 3. STK500v1: Optiboot and ATmegaBOOT (Nano, Uno)

Reference: Atmel AVR061 (STK500 communication protocol) plus the actual bootloader sources. Both bootloaders implement a
subset. Every command ends with `Sync_CRC_EOP = 0x20`. Every reply is `Resp_STK_INSYNC 0x14`, payload, `Resp_STK_OK 0x10`.
`0x15` (`NOSYNC`) means the bootloader did not see `0x20` where it expected it.

### 3.1 Constants

| Name | Value | Name | Value |
|---|---|---|---|
| `STK_OK` | `0x10` | `STK_GET_SYNC` | `0x30` `'0'` |
| `STK_FAILED` | `0x11` | `STK_GET_PARAMETER` | `0x41` `'A'` |
| `STK_NODEVICE` | `0x13` | `STK_SET_DEVICE` | `0x42` `'B'` |
| `STK_INSYNC` | `0x14` | `STK_SET_DEVICE_EXT` | `0x45` `'E'` |
| `STK_NOSYNC` | `0x15` | `STK_ENTER_PROGMODE` | `0x50` `'P'` |
| `CRC_EOP` | `0x20` `' '` | `STK_LEAVE_PROGMODE` | `0x51` `'Q'` |
| `Parm_STK_HW_VER` | `0x80` | `STK_LOAD_ADDRESS` | `0x55` `'U'` |
| `Parm_STK_SW_MAJOR` | `0x81` | `STK_UNIVERSAL` | `0x56` `'V'` |
| `Parm_STK_SW_MINOR` | `0x82` | `STK_PROG_PAGE` | `0x64` `'d'` |
| | | `STK_READ_PAGE` | `0x74` `'t'` |
| | | `STK_READ_SIGN` | `0x75` `'u'` |

### 3.2 Byte-level session (what to send, what comes back)

`→` host to board, `←` board to host. Page size 128 B. Addresses are **word** addresses (byte address / 2), **little endian**
(low byte first). Lengths are **big endian**, in bytes.

| # | Step | → | ← | Notes |
|---|---|---|---|---|
| 0 | Reset | DTR pulse (2.1) at the right baud, wait 100 ms, drain | | |
| 1 | Sync | `30 20` | `14 10` | Send twice and drain first (line noise, leftover sketch output), then retry up to 10 times, each with a 200 to 300 ms read timeout; avrdude re-pulses reset before each retry |
| 2 | Software version major | `41 81 20` | `14 vv 10` | Optiboot from ArduinoCore-avr: `04`; upstream Optiboot 8.x: `08`; ATmegaBOOT: `01` |
| 3 | Software version minor | `41 82 20` | `14 vv 10` | Optiboot: `04` (Arduino) / `03` (8.3); ATmegaBOOT: `10` |
| 4 | Set device | `42` + 20 parameter bytes + `20` | `14 10` | Both bootloaders read and ignore the 20 bytes. A block in the shape avrdude builds for m328p (content ignored, approximate): `42 86 00 00 01 01 01 01 03 FF FF FF FF 00 80 04 00 00 00 80 00 20` |
| 5 | (optional) Set device ext | `45 05 04 D7 C2 00 20` | `14 10` | Ignored by both; may be skipped |
| 6 | Enter programming mode | `50 20` | `14 10` | No-op in both |
| 7 | Read signature | `75 20` | `14 1E 95 0F 10` | Abort if not in the table (section 1) |
| 8 | For each page `p` (byte address `a = p*128`): load address | `55 lo hi 20` with `w = a/2`, `lo = w & 0xFF`, `hi = w >> 8` | `14 10` | Always reload before every page; ATmegaBOOT shifts its stored address in place during `t` |
| 9 | Program page | `64 00 80 46` + 128 data bytes + `20` | `14 10` | `46` = `'F'` flash (`'E'` would be EEPROM: never). Optiboot sends `14` after the data, then erases + writes the page (about 8 to 9 ms, **unverified exact**), then `10`. Pad the last page with `FF` |
| 10 | Verify: load address | `55 lo hi 20` | `14 10` | |
| 11 | Read page | `74 00 80 46 20` | `14` + 128 bytes + `10` | Compare with the image |
| 12 | Leave programming mode | `51 20` | `14 10` | Optiboot: watchdog 16 ms, then the sketch starts. ATmegaBOOT (no `WATCHDOG_MODS` in the atmega328 build): stays until its read timeout, then starts the sketch |

Write order: pages from 0 up to the last page that holds data. No chip erase (`52`): Optiboot and ATmegaBOOT erase each page
before writing it. avrdude via the Arduino IDE also runs with `-D` (no chip erase).

### 3.3 Behaviour that matters for the implementation

- **Optiboot (v4.4 in ArduinoCore-avr, built with `LED_START_FLASHES=3`, 115200, U2X).**
  - Runs only after an external reset (`EXTRF`); after power-on or a watchdog reset it starts the sketch.
  - Flashes the LED 3 times first (6 × about 62.5 ms ≈ 375 ms **(computed, unverified on hardware)**). During that time it does
    not read the UART; the AVR's 2-byte RX FIFO keeps the first sync bytes, later ones overrun. That is why avrdude
    sends sync twice, drains and retries.
  - 1 s watchdog, reset by every received byte (`getch` calls `wdr`). Silence for 1 s → sketch starts.
  - Any command whose terminator is not `0x20` → watchdog 16 ms → sketch starts (no `NOSYNC` reply). A framing error (wrong
    baud) also leads to the sketch starting.
  - `GET_PARAMETER` answers `0x03` for every parameter other than SW major/minor.
  - Page buffer is exactly `SPM_PAGESIZE`; never send more than 128 bytes per `PROG_PAGE`.
- **ATmegaBOOT_168 (old Nano, 57600).**
  - `getch()` gives up after `MAX_TIME_COUNT = F_CPU >> 4` busy-loop iterations and jumps to the sketch (well under a second;
    exact time **unverified**). So the first sync must arrive quickly after the reset, and gaps between bytes must be short.
  - After `MAX_ERROR_COUNT = 5` malformed commands it jumps to the sketch. Garbage from a 115200 probe can trigger that, so
    always pulse reset again before trying 57600.
  - Replies `14 10` to `30 20`; `41 80/81/82` → `02`/`01`/`10`; `41 98` → `03`; any other parameter → `00`.
  - `Q` (`51`) replies and stays in the bootloader (no watchdog trick in this build).
- **Timing.** Use 500 ms per reply for normal commands, 1000 ms for `PROG_PAGE`. On a timeout or `NOSYNC`: resync (`30 20`) and
  repeat the same page (load address + program page), at most 3 times per page; then one full restart (reset + sync) from
  page 0; then fail.
- **Baud error.** 16 MHz AVR at "115200" with U2X runs at 117 647 baud (+2.1 %); at "57600" at 58 824 (+2.1 %). It works with
  CH340, FTDI and 16U2 in practice (the IDE relies on it). CH340 itself hits 115200 and 57600 exactly with the library's
  formula (`1532620800 / 13304 = 115200`, `/ 26608 = 57600`).
- **Throughput estimate (unverified).** Per page about 133 bytes out + 4 in at 115200 ≈ 12 ms on the wire, 8 to 9 ms page write,
  plus USB round trips. CH340/16U2: about 25 ms per page; 70 pages write + verify ≈ 3 s. FTDI with its default 16 ms latency
  timer adds 16 ms per reply (2 replies per page per pass); call `FtdiSerialPort.setLatencyTimer(1)` to avoid that.

---

## 4. AVR109 / Caterina (Pro Micro, Leonardo, Micro)

Reference: Atmel AVR109 (Microchip AN1644), `bootloaders/caterina/Caterina.c`, avrdude `butterfly.c`. Commands are single
ASCII bytes, no terminator. Most write-type commands answer `\r` (`0x0D`); unknown commands answer `?`. All of this goes over the
bootloader's own USB CDC interface; the baud rate is irrelevant (set 57600 like avrdude for neatness).

### 4.1 Commands Caterina implements

| Cmd | → | ← | Meaning |
|---|---|---|---|
| `S` | `53` | 7 bytes `CATERIN` | Software identifier (avrgirl uses it as the board "signature") |
| `V` | `56` | 2 ASCII digits, e.g. `31 30` | Bootloader version |
| `v` | `76` | `?` | Hardware version: not supported |
| `p` | `70` | `S` | Programmer type: serial |
| `a` | `61` | `Y` | Auto-increment supported |
| `b` | `62` | `Y` `00` `80` | Block mode supported, block size big endian = `SPM_PAGESIZE` = 128 |
| `t` | `74` | `44 00` | Supported device codes, list ends with `00` |
| `T` | `54 44` | `0D` | Select device (ignored) |
| `P` | `50` | `0D` | Enter programming mode (no-op) |
| `L` | `4C` | `0D` | Leave programming mode (no-op) |
| `s` | `73` | `87 95 1E` | Signature, **reversed** (SIG3, SIG2, SIG1) |
| `A` | `41 hi lo` | `0D` | Set address: **word** address, **big endian** (`CurrAddress = hi<<9 | lo<<1`) |
| `B` | `42 sizeHi sizeLo 46` + data | `0D` | Write block to flash (`46` = `'F'`; `'E'` = EEPROM: never). Erases the page at the current address, fills, writes, waits; address auto-increments. Restarts the 8 s countdown |
| `g` | `67 sizeHi sizeLo 46` | `size` raw bytes (no `0D`) | Read block from flash; address auto-increments. Restarts the countdown |
| `e` | `65` | `0D` | Chip erase (application section only). Not needed |
| `E` | `45` | `0D` | Exit: countdown set to 500 ms, then the sketch starts (if word 0 ≠ `0xFFFF`) |
| `r`, `F`, `N`, `Q` | | 1 byte | Read lock / low / high / extended fuse. Read only; never used |

Memory type other than `E`/`F` in `B`/`g` answers `?`.

### 4.2 Byte-level session

| # | → | ← | Notes |
|---|---|---|---|
| 0 | 1200-baud touch on the sketch device, wait for the bootloader device, get USB permission, open it | | Section 2.2 and 8.1 |
| 1 | `1B` (ESC), drain, then `53` | `CATERIN` | avrdude loops ESC + `S` until the answer is not `?` |
| 2 | `56` | 2 bytes | log only |
| 3 | `70` | `53` | |
| 4 | `61` | `59` | |
| 5 | `62` | `59 00 80` | Use the reported size as the block size; refuse if not 128 |
| 6 | `74` | `44 00` | Read until `00` |
| 7 | `54 44` | `0D` | First device code from step 6 |
| 8 | `50` | `0D` | |
| 9 | `73` | `87 95 1E` | Reverse, compare with `1E 95 87` |
| 10 | **Unbrick guard**: `41 00 00` then `42 00 80 46` + 128 × `FF` | `0D`, `0D` | Page 0 becomes erased, so "no sketch" from now on (see 9.2) |
| 11 | `41 00 40` (word address of byte 0x0080) | `0D` | Start at page 1 |
| 12 | for pages 1..N: `42 00 80 46` + 128 bytes | `0D` each | Auto-increment, no new `A` needed; pad last page with `FF` |
| 13 | Verify pages 1..N: `41 00 40`, then `67 00 80 46` per page | 128 bytes each | |
| 14 | Page 0: `41 00 00`, `42 00 80 46` + real page 0 | `0D`, `0D` | Written last |
| 15 | Verify page 0: `41 00 00`, `67 00 80 46` | 128 bytes | |
| 16 | `4C` | `0D` | |
| 17 | `45` | `0D` | 500 ms later the bootloader detaches; the sketch enumerates as `2341:8036` etc. |

Timeouts: 1000 ms per reply (USB, flash write about 4 to 5 ms per page **(unverified exact)**). Throughput is dominated by USB
round trips: about 112 pages at most, a few seconds in total.

Do not use `e` (chip erase): with page 0 erased the bootloader already treats the chip as empty, and `B` erases each page.

---

## 5. STK500v2 (Mega 2560)

Reference: Atmel AVR068 (STK500v2 protocol, Microchip AN2591), `bootloaders/stk500v2/stk500boot.c` and `command.h` in
ArduinoCore-avr, avrdude `stk500v2.c` / `wiring.c`.

### 5.1 Framing

```
MESSAGE_START 0x1B | SEQUENCE_NUMBER | SIZE_HI | SIZE_LO | TOKEN 0x0E | BODY (SIZE bytes) | CHECKSUM
CHECKSUM = XOR of every preceding byte of the frame, MESSAGE_START included
```

- The reply uses the same framing and echoes the sequence number. Body byte 0 echoes the command id, body byte 1 is the status:
  `STATUS_CMD_OK 0x00`, `STATUS_CMD_FAILED 0xC0`, `STATUS_CKSUM_ERROR 0xC1`, `STATUS_CMD_UNKNOWN 0xC9`.
- The Arduino bootloader (with `_FIX_ISSUE_505_`) accepts any sequence number. avrdude starts at 1 and increments per command.
- A frame with a bad checksum is silently dropped by the bootloader (parser goes back to `ST_START`): treat no reply as "resend".
- The bootloader's message buffer is 285 bytes; the largest message we send is 10 + 256 = 266.

### 5.2 Commands used

| Cmd | Body sent | Body received |
|---|---|---|
| `CMD_SIGN_ON 0x01` | `01` | `01 00 08 'A' 'V' 'R' 'I' 'S' 'P' '_' '2'` |
| `CMD_GET_PARAMETER 0x03` | `03 pp` | `03 00 vv` (`0x90` HW ver, `0x91` SW major, `0x92` SW minor; others → 0) |
| `CMD_ENTER_PROGMODE_ISP 0x10` | `10` + 11 parameter bytes, e.g. `10 C8 64 19 20 00 53 03 AC 53 00 00` | `10 00` (parameters ignored) |
| `CMD_READ_SIGNATURE_ISP 0x1B` | `1B 04 30 00 ii 00` (ii = 0, 1, 2) | `1B 00 ss 00` |
| `CMD_LOAD_ADDRESS 0x06` | `06 a3 a2 a1 a0`: **word** address, **big endian**; avrdude sets bit 31 (`a3 |= 0x80`) for parts over 64 KB | `06 00` |
| `CMD_PROGRAM_FLASH_ISP 0x13` | `13 sizeHi sizeLo mode delay cmd1 cmd2 cmd3 poll1 poll2` + data; for the bootloader only size and data matter. Use `13 01 00 C1 0A 40 4C 20 00 00` + 256 bytes | `13 00` |
| `CMD_READ_FLASH_ISP 0x14` | `14 sizeHi sizeLo 20` | `14 00` + size bytes + `00` |
| `CMD_LEAVE_PROGMODE_ISP 0x11` | `11 01 01` | `11 00`, then the bootloader jumps to the sketch |
| `CMD_CHIP_ERASE_ISP 0x12` | do **not** send | `12 C0` (deliberately "failed", issue 543) |

The address auto-increments after `PROGRAM_FLASH` and `READ_FLASH`; avrdude sends `LOAD_ADDRESS` only when the next block is not
contiguous.

### 5.3 Byte-level session

1. Open at 115200; DTR pulse (2.1, `wiring` timing: 50 ms released, assert, release, 100 ms); drain.
2. `CMD_SIGN_ON` until the reply arrives, up to about 10 tries with 200 ms timeouts. Frame for the first try:
   `1B 01 00 01 0E 01 14` (checksum `1B^01^00^01^0E^01 = 0x14`). Reply: `1B 01 00 0B 0E 01 00 08 41 56 52 49 53 50 5F 32 cc`.
3. `CMD_ENTER_PROGMODE_ISP`.
4. `CMD_READ_SIGNATURE_ISP` × 3 → `1E 98 01`.
5. `CMD_LOAD_ADDRESS 80 00 00 00`.
6. For **every** page from 0 to the last, **in order, no gaps**: `CMD_PROGRAM_FLASH_ISP` with 256 bytes (pad with `FF`).
7. `CMD_LOAD_ADDRESS 80 00 00 00`, then `CMD_READ_FLASH_ISP` 256 bytes per page; compare.
8. `CMD_LEAVE_PROGMODE_ISP`.

### 5.4 Mega bootloader quirks (important)

- **Erase is sequential and independent of the address.** `CMD_PROGRAM_FLASH_ISP` erases the page at an internal counter
  `eraseAddress`, which starts at 0 and advances by one page per program command, then writes the data at the loaded address.
  Writing out of order, skipping a page, or writing a page twice leaves pages unerased and corrupts flash. Always write all
  pages 0..N in order in one session (avrdude also writes every page, including all-`FF` ones, when talking to a bootloader).
- **Bootloader window:** the source says "should be about 1 second" (`boot_timeout = 20000` with the LED blinking); after a
  watchdog reset it jumps straight to the sketch. Any byte received in the window enters the bootloader; inside a session
  `recchar_timeout` gives up after `F_CPU >> 1` loop iterations and jumps to the sketch if word 0 is not `0xFFFF`.
- **"!!!" monitor:** three `!` within the first 10 bytes received starts a debug monitor. Our first bytes are a `SIGN_ON` frame,
  so this cannot happen. Some old/clone Mega bootloaders reportedly hang on `!!!` anywhere in the upload **(unverified)**; the
  CI script should warn if the Mega .hex contains `21 21 21`.
- Lock bits / fuses: `CMD_PROGRAM_LOCK_ISP` exists and must never be sent.

---

## 6. Intel HEX and the .hex files

### 6.1 Format

Each line: `:LLAAAATT<data>CC` in ASCII hex.
- `LL` data length, `AAAA` 16-bit offset (big endian), `TT` record type, `CC` checksum = two's complement of the sum of all bytes
  from `LL` through the data (so the sum of all bytes including `CC` is `0x00` mod 256).
- Types: `00` data; `01` end of file; `02` extended segment address (base = value × 16); `03` start segment address (ignore);
  `04` extended linear address (base = value << 16); `05` start linear address (ignore).
- avr-gcc output for sketches under 64 KB has only `00` and `01`. Support `02` and `04` anyway (Mega).

```kotlin
class HexImage(val bytes: ByteArray, val end: Int)   // bytes: 0xFF-filled, size = board's max sketch size

object IntelHex {
    fun parse(text: String, maxSize: Int): HexImage {
        val img = ByteArray(maxSize) { 0xFF.toByte() }
        var base = 0; var end = 0; var eof = false
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val n = i + 1
            require(!eof) { "line $n: data after EOF" }
            require(line[0] == ':' && line.length >= 11 && line.length % 2 == 1) { "line $n: not a HEX record" }
            val b = IntArray((line.length - 1) / 2) { line.substring(1 + 2 * it, 3 + 2 * it).toInt(16) }
            val len = b[0]
            require(b.size == len + 5) { "line $n: length" }
            require(b.sum() and 0xFF == 0) { "line $n: checksum" }
            val off = (b[1] shl 8) or b[2]
            when (b[3]) {
                0x00 -> {
                    val a = base + off
                    require(a >= 0 && a + len <= maxSize) { "line $n: 0x%X beyond the sketch area".format(a + len) }
                    for (k in 0 until len) img[a + k] = b[4 + k].toByte()
                    end = maxOf(end, a + len)
                }
                0x01 -> eof = true
                0x02 -> base = ((b[4] shl 8) or b[5]) shl 4
                0x04 -> base = ((b[4] shl 8) or b[5]) shl 16
                0x03, 0x05 -> Unit
                else -> error("line $n: record type ${b[3]}")
            }
        }
        require(eof) { "no EOF record" }
        require(end > 0) { "empty image" }
        return HexImage(img, end)
    }
}
```

`maxSize` is the board's `upload.maximum_size` (section 1), which makes "the file reaches into the bootloader" a parse error.
Number of pages to write = `ceil(end / pageSize)`.

### 6.2 Producing the files

Builds needed (Nano old and new bootloader produce the **same** binary; only the upload speed differs. Uno and Nano differ:
the sketch picks the analog list by `ARDUINO_AVR_UNO`):

| Asset | FQBN | Board(s) |
|---|---|---|
| `sensorbox-nano.hex` | `arduino:avr:nano:cpu=atmega328` | Nano / Nano V3, both bootloaders |
| `sensorbox-uno.hex` | `arduino:avr:uno` | Uno R3 (16U2 and CH340) |
| `sensorbox-32u4.hex` | `arduino:avr:leonardo` | Pro Micro 5 V, Leonardo, Micro |
| `sensorbox-mega.hex` | `arduino:avr:mega:cpu=atmega2560` | Mega 2560 |

```sh
arduino-cli core update-index
arduino-cli core install arduino:avr@1.8.6          # pin the core version for reproducible output
cd hardware/sensorbox
for b in "nano arduino:avr:nano:cpu=atmega328" "uno arduino:avr:uno" \
         "32u4 arduino:avr:leonardo" "mega arduino:avr:mega:cpu=atmega2560"; do
  set -- $b
  arduino-cli compile --fqbn "$2" --output-dir "build/$1" .
  cp "build/$1/sensorbox.ino.hex" "hex/sensorbox-$1.hex"   # NOT *.with_bootloader.hex
done
```

- `--output-dir` gets `sensorbox.ino.hex`, `sensorbox.ino.with_bootloader.hex`, `.elf` and others. Ship only
  `sensorbox.ino.hex`. The `with_bootloader` file contains the bootloader at its high address; the parser would reject it
  (beyond `maxSize`), which is the intended guard.
- Note on the 32U4 build: a Leonardo build enumerates as `2341:8036` "Arduino Leonardo" after flashing, also on a SparkFun Pro
  Micro (which had `1B4F:9206`). `SensorBoxLink.isArduino()` accepts both vendors, so nothing breaks. Building with the
  SparkFun core (`SparkFun:avr:promicro:cpu=16MHzatmega32U4`) would keep SparkFun's IDs but adds a third-party core; not
  worth it.
- `arduino-cli compile` prints "Sketch uses N bytes"; record it next to the files.

**Commit the .hex files** (recommended) rather than building in CI: the project has no CI, releases are built locally, and each
file is only about 20 to 35 KB of text (Intel HEX is about 2.8× the binary). Put them in `hardware/sensorbox/hex/` and they ship
automatically, because `app/build.gradle.kts` already uses `../hardware` as an assets source directory (asset path
`sensorbox/hex/sensorbox-nano.hex`). To prevent shipping a stale build:
- a script `hardware/sensorbox/build-hex.sh` with the commands above, which also writes `hex/manifest.json`:
  `{ "ino_sha256": "...", "core": "arduino:avr@1.8.6", "files": { "nano": { "sha256": "...", "bytes": 7612 }, ... } }`;
- a JVM unit test that hashes `sensorbox.ino` and fails if it differs from `manifest.json` ("rebuild the .hex files"), and that
  parses every .hex with `IntelHex.parse` against its board's `maxSize`.

Recommended (sketch change, not done here): add `fw=<n>` to the `I SENSORBOX` line so the app can tell an outdated box and offer
the update instead of reflashing blindly.

---

## 7. Recommended Kotlin design

### 7.1 Package and classes

```
kg.forester.sg.flash/
  IntelHex.kt          HexImage + parser (6.1)
  BoardSpec.kt         table of section 1 as data
  SerialLink.kt        byte transport over UsbSerialPort with a receive queue, timeouts, drain
  Resets.kt            dtrReset(), touch1200(), awaitBootloaderDevice()
  Stk500v1.kt          Optiboot / ATmegaBOOT client
  Avr109.kt            Caterina client
  Stk500v2.kt          Mega client (framing + commands)
  Programmer.kt        common interface + page loop with progress and verify
  FirmwareFlasher.kt   orchestration: pause SensorBoxLink, pick device, detect bootloader, flash, resume
  FlashState.kt        StateFlow model for the UI
```

```kotlin
enum class Proto { STK500V1, AVR109, STK500V2 }

data class BoardSpec(
    val id: String,                 // matches BoxBoard.id: nano, uno, promicro, mega
    val asset: String,              // "sensorbox/hex/sensorbox-nano.hex"
    val signatures: Set<String>,    // "1E950F"
    val pageSize: Int,
    val maxSketch: Int,
    val proto: Proto,
    val bauds: List<Int>,           // nano: [115200, 57600]; uno: [115200]; mega: [115200]; 32u4: [57600] (ignored)
)

interface Programmer {
    suspend fun connect(): String               // returns a description, e.g. "Optiboot 4.4"
    suspend fun signature(): ByteArray
    suspend fun writePage(byteAddr: Int, data: ByteArray)
    suspend fun readPage(byteAddr: Int, len: Int): ByteArray
    suspend fun finish()
}
```

`SerialLink`: start a `SerialInputOutputManager(port, listener)` (3.8.1 API: constructor, `start()`, `stop()`) feeding a
`LinkedBlockingQueue<Byte>` (or a small ring buffer), and read from the queue with deadlines. Reasons: usb-serial-for-android
warns that `bulkTransfer` with short timeouts can lose data, and `read()` throws "Read buffer too small" below the endpoint
packet size. API:

```kotlin
class SerialLink(val port: UsbSerialPort) : Closeable {
    private val q = LinkedBlockingQueue<Byte>()
    @Volatile private var failure: Exception? = null
    private val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) { data.forEach(q::put) }
        override fun onRunError(e: Exception) { failure = e }         // device unplugged, etc.
    })
    fun start() = io.start()
    fun write(b: ByteArray) { failure?.let { throw it }; port.write(b, 1000) }
    fun byte(timeoutMs: Long): Int {
        failure?.let { throw it }
        return (q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw FlashTimeout()).toInt() and 0xFF
    }
    fun bytes(n: Int, timeoutMs: Long): ByteArray {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        return ByteArray(n) { byte((end - SystemClock.elapsedRealtime()).coerceAtLeast(1)).toByte() }
    }
    fun drain(quietMs: Long = 30) { while (q.poll(quietMs, TimeUnit.MILLISECONDS) != null) Unit }
    override fun close() { io.stop(); runCatching { port.close() } }
}
```

STK500v1 core (the rest of the class is one line per command of table 3.2):

```kotlin
class Stk500v1(private val l: SerialLink, private val pulse: suspend () -> Unit) : Programmer {
    private fun cmd(body: ByteArray, replyLen: Int, timeoutMs: Long = 500): ByteArray {
        l.write(body + 0x20)
        when (val b = l.byte(timeoutMs)) { 0x14 -> Unit; 0x15 -> throw NoSync(); else -> throw Proto("0x14 expected, got %02X".format(b)) }
        val data = l.bytes(replyLen, timeoutMs)
        l.byte(timeoutMs).let { if (it != 0x10) throw Proto("0x10 expected, got %02X".format(it)) }
        return data
    }
    suspend fun sync(tries: Int = 8): Boolean {
        val s = byteArrayOf(0x30, 0x20)
        l.write(s); l.drain(); l.write(s); l.drain()
        repeat(tries) { t ->
            if (t > 0) { pulse(); l.drain(20) }
            l.write(s)
            if (runCatching { l.byte(250) }.getOrNull() == 0x14 && runCatching { l.byte(250) }.getOrNull() == 0x10) {
                l.drain(); return runCatching { cmd(byteArrayOf(0x30), 0) }.isSuccess   // confirm alignment
            }
        }
        return false
    }
    override suspend fun connect(): String {
        val maj = cmd(byteArrayOf(0x41, 0x81.toByte()), 1)[0].toInt()
        val min = cmd(byteArrayOf(0x41, 0x82.toByte()), 1)[0].toInt()
        cmd(SET_DEVICE_M328P, 0); cmd(byteArrayOf(0x50), 0)
        return if (maj == 1) "ATmegaBOOT $maj.$min" else "Optiboot $maj.$min"
    }
    override suspend fun signature() = cmd(byteArrayOf(0x75), 3)
    private fun load(a: Int) { val w = a ushr 1; cmd(byteArrayOf(0x55, w.toByte(), (w ushr 8).toByte()), 0) }
    override suspend fun writePage(byteAddr: Int, data: ByteArray) {
        load(byteAddr)
        cmd(byteArrayOf(0x64, (data.size ushr 8).toByte(), data.size.toByte(), 0x46) + data, 0, 1000)
    }
    override suspend fun readPage(byteAddr: Int, len: Int): ByteArray {
        load(byteAddr); return cmd(byteArrayOf(0x74, (len ushr 8).toByte(), len.toByte(), 0x46), len)
    }
    override suspend fun finish() { runCatching { cmd(byteArrayOf(0x51), 0) } }
}
```

STK500v2 frame helper:

```kotlin
private var seq = 1
fun command(body: ByteArray, timeoutMs: Long = 1000): ByteArray {
    val f = ByteArray(body.size + 6)
    f[0] = 0x1B; f[1] = seq.toByte(); f[2] = (body.size ushr 8).toByte(); f[3] = body.size.toByte(); f[4] = 0x0E
    body.copyInto(f, 5); f[f.size - 1] = f.fold(0) { x, b -> x xor b.toInt() }.toByte()  // last byte still 0 here
    l.write(f)
    while (l.byte(timeoutMs) != 0x1B) Unit                                   // skip noise
    val s = l.byte(timeoutMs); val len = (l.byte(timeoutMs) shl 8) or l.byte(timeoutMs)
    if (l.byte(timeoutMs) != 0x0E) throw Proto("token")
    val r = l.bytes(len, timeoutMs); val ck = l.byte(timeoutMs)
    val calc = (0x1B xor s xor (len ushr 8) xor (len and 0xFF) xor 0x0E) xor r.fold(0) { x, b -> x xor (b.toInt() and 0xFF) }
    if (ck != calc || s != (seq and 0xFF)) throw Proto("checksum/sequence")
    if (r[0] != body[0] || r[1].toInt() != 0) throw Proto("status %02X".format(r[1]))
    seq = (seq + 1) and 0xFF
    return r
}
```

AVR109 is the simplest: `send(byteArrayOf(...))`, then `expectCR()` (one byte `0x0D`) or `bytes(n)`.

### 7.2 Flow

```
user taps «Прошить плату»
  1. FlashState.Preparing
     - load + parse the asset for the selected BoxBoard (SensorBox.board) → HexImage, or fail early
     - pick the device: the one SensorBox is connected through (SensorBox.info.via = "USB VID:PID"), else let the user choose
       among USB serial devices that are NOT SensorBoxLink.ecuDevice and are not a known KKL/OpenPort adapter
     - SensorBoxLink.pause(deviceName): its loop skips everything while paused and closes its open session;
       EcuService.forEcu() also skips the paused device (add it to SensorBoxLink.busy)
     - acquire a partial WakeLock + FLAG_KEEP_SCREEN_ON; run inside EcuService's scope (foreground service), not in a ViewModel
  2. Route by device:
     - Caterina PID already present (0036/0037/9205/9203)       → AVR109 directly (the user pressed reset)
     - native 32U4 sketch (2341:8036/8037, 2A03:8036/8037, 1B4F:9206/9204, or BoxBoard.PROMICRO)
                                                               → 1200-baud touch → await bootloader → permission → AVR109
     - bridge (CH340/FTDI/CP210x/PL2303, or 16U2 2341:0043/0042/...) → probe (below)
  3. Probe a bridge (each attempt = open at baud, DTR reset, sync):
       hint MEGA (2341:0042/0010/2A03:0042/0010, BoxBoard.MEGA, or "board=mega" in the last I SENSORBOX line):
            STK500v2@115200 → STK500v1@115200 → STK500v1@57600
       otherwise:
            STK500v1@115200 → STK500v1@57600 → STK500v2@115200
       Identify: STK500v1 SW major 1 = ATmegaBOOT, otherwise Optiboot; STK500v2 "AVRISP_2"; AVR109 "CATERIN".
       Remember the winning (proto, baud) per device key (VID:PID + serial if any) and try it first next time.
  4. FlashState.Connecting(desc). Read signature. Must be in BoardSpec.signatures for the selected board,
     else FlashState.Failed(WrongBoard(found = <name from signature>)).
     Also cross-check: a 328P signature with BoxBoard.MEGA selected, etc. → offer to switch the board in settings.
  5. FlashState.Writing(page, pages): pages 0..N-1 (AVR109: guard page 0 first, page 0 last; STK500v2: strictly in order).
  6. FlashState.Verifying(page, pages): read back and compare; mismatch → one rewrite of that page (STK500v1/AVR109),
     or a full restart (STK500v2, because of the sequential erase).
  7. finish(): leave programming mode / exit bootloader. Close the port.
  8. FlashState.Done(bytes, ms). SensorBoxLink.resume(): clear `asked` for the device so it is probed with "?" again;
     expect an "I SENSORBOX" line within ~5 s (32U4: after re-enumeration as the sketch PID, permission again for that PID
     only if it changed, e.g. SparkFun 9206 → Leonardo 8036).
```

Progress: one number 0..1 for the bar: connect 0 to 0.05, write 0.05 to 0.70, verify 0.70 to 0.98, finish 1.0, plus a text line
("Записываю 37 из 84"). Emit at most every page.

### 7.3 Errors and what the user sees

```kotlin
sealed class FlashError {
    object NoBootloader : FlashError()          // no sync on any protocol/baud
    data class WrongBoard(val found: String) : FlashError()
    object TooBig : FlashError()                 // hex beyond maxSketch (build error, should never ship)
    object PermissionDenied : FlashError()
    object BootloaderTimeout : FlashError()      // 32U4: no bootloader device within 10 s, or the 8 s window lapsed
    object Disconnected : FlashError()           // onRunError / device detached mid-flash
    data class VerifyFailed(val addr: Int) : FlashError()
    data class Protocol(val detail: String) : FlashError()
}
```

| Error | Message (RU, suggestion) |
|---|---|
| NoBootloader | «Плата не отвечает загрузчику. Выберите правильную плату; для Nano попробуйте ещё раз, приложение само переберёт старый и новый загрузчик. Если не помогает, нажмите RESET на плате в момент прошивки.» |
| WrongBoard | «Это не Nano, а Mega 2560 (подпись 1E 98 01). Сменить плату в настройках?» |
| PermissionDenied / BootloaderTimeout (32U4) | «Плата перезагрузилась в режим прошивки, но Android не дал доступ вовремя. Нажмите «Прошить» ещё раз и в окне Android поставьте галочку «Использовать по умолчанию».» |
| Disconnected | «Плату отключили во время прошивки. Это не страшно: загрузчик цел. Подключите и нажмите «Прошить» ещё раз.» |
| VerifyFailed | «Проверка не сошлась на адресе 0x1A80. Повторите; если повторяется, проблема в питании USB: подключите плату напрямую, без хаба.» |

### 7.4 Integration points in the current code (read, not changed)

- `SensorBoxLink.loop()` opens every non-ECU serial device and sends `?`; it would grab the Caterina bootloader device too.
  It needs a pause flag (and to close a running `session()`), otherwise the flasher gets "device busy".
- `SensorBoxLink.forEcu()` hides Arduino VIDs from the ECU loop only while SensorBox is enabled, and CH340 Nanos are not hidden
  by VID at all. During flashing the device must be in `busy` so `EcuService` never probes it as a KKL cable.
- Never flash `SensorBoxLink.ecuDevice`: a DTR pulse and STK500 bytes into a KKL cable are pointless at best.
- `EcuService.requestUsbPermission()` already builds the permission `PendingIntent`; reuse it with a suspendable wait on the
  `ACTION_USB_PERMISSION` broadcast.
- `MainActivity` is `singleTask` with a `USB_DEVICE_ATTACHED` filter, so a bootloader attach with a stored default arrives as
  `onNewIntent`; it must not restart anything while a flash is running.
- `res/xml/device_filter.xml` already matches vendor ids 9025 (`0x2341`), 10755 (`0x2A03`), 6991 (`0x1B4F`) without
  product ids, so it covers the Caterina bootloader PIDs as well.

---

## 8. Android pitfalls

### 8.1 USB permission when the 32U4 re-enumerates

- The bootloader is a **different USB device** (different PID, new device node). Permission granted with `requestPermission`
  is tied to the device instance and is gone after detach, so `hasPermission()` is false for the bootloader device and a new
  dialog is needed. The Android docs: permission obtained via the attach intent lasts "until the device is disconnected".
- The 8 s Caterina window keeps running while the dialog is shown. The user must tap within roughly 6 to 7 s (enumeration
  takes part of the window; exact enumeration time on head units **unverified**).
- **Avoiding the dialog on later flashes.** AOSP stores "use by default" as a `DeviceFilter` built from the whole device
  (VID, PID, class, subclass, protocol, manufacturer, product, serial). When a device that has a stored default attaches,
  `UsbProfileGroupSettingsManager` grants permission to that app automatically and starts its activity. So:
  - the app needs a manifest `USB_DEVICE_ATTACHED` filter that matches the bootloader (it has one: vendor-only entries);
  - the user must tick "use by default" **once for the bootloader device itself** (the default for `2341:8036` does not
    cover `2341:0036`). The checkbox is shown in the attach chooser, and also in the `requestPermission` dialog when the app
    "can be default" for the device (SystemUI `UsbPermissionActivity` shows it when `canBeDefault()`; the exact source of
    that flag was not read, **partially verified**).
  - after that, every later 1200-baud touch gets permission silently (plus an `onNewIntent` to `MainActivity`).
  - There is no way to avoid the first dialog from a normal app. A system/privileged app or a ROM with permission dialogs
    disabled (`mDisablePermissionDialogs`) would skip it; some head-unit ROMs might grant USB permission silently
    **(unverified, do not rely on it)**.
- UX: before the first 32U4 flash, tell the user: «Сейчас плата перезагрузится и Android спросит доступ. Поставьте галочку
  «Использовать по умолчанию» и нажмите ОК в течение 5 секунд.» If the window is missed: retry is harmless; the second attempt
  is fast because the user is ready.
- Fallback when the touch does not work (sketch without USB CDC, crashed sketch, the page-0 guard state): "manual mode": the app
  waits up to 60 s for any Caterina PID while the user presses RESET (Leonardo, generic Pro Micro: once; SparkFun: twice
  quickly; boards without a button: touch RST to GND), then flashes immediately.

### 8.2 Weak USB power on head units

- The ATmega's own consumption while flashing is small; the risk is the USB port browning out or resetting when the SensorBox
  powers sensors (pressure senders, MAX31855) and shares a passive hub with the KKL cable. The README already notes boxes and
  cables "getting lost" on passive hubs.
- A port reset during flashing = `Disconnected`, recoverable (section 9). Advise flashing with the board on a port by itself,
  sensors optional.
- AVR side: Nano/Uno extended fuse `FD` = BOD 2.7 V; a sag below that resets the chip. A brown-out in the middle of a page
  write corrupts that page only; verify catches it.

### 8.3 CH340 baud rates

- usb-serial-for-android 3.8.1 `Ch34xSerialDriver.setBaudRate`: `factor = 1532620800 / baud`, divisor 3, shifted down by 3 bits
  until it fits `0xFFF0`; special cases 921600 and 307200. 1200, 57600 and 115200 are all exact (no rounding error on the CH340
  side). The app already runs CH340 KKL cables, so the driver works on these head units.
- The remaining error is on the AVR side (+2.1 % at both 57600 and 115200, section 3.3), which the Arduino ecosystem tolerates.
- Some head-unit kernels have the `ch341` serial driver built in; usb-serial-for-android claims the interface with `force`, so
  this has not been a problem for the KKL path **(no specific evidence for flashing)**.

### 8.4 Android killing the app during flashing

- A flash takes about 2 to 10 s. Run it in `EcuService` (already a foreground service with type `connectedDevice`, which
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` covers), on `Dispatchers.IO`, holding a partial wake lock, and keep the screen on.
- Do not run it from a composable's `LaunchedEffect` / ViewModel scope: rotation is handled by `configChanges`, but leaving the
  screen would cancel it.
- If the process dies anyway, the board keeps its bootloader (section 9) and the next attempt starts over; no state needs to
  survive.
- Handle `ACTION_USB_DEVICE_DETACHED` / `onRunError` as `Disconnected`, not as a crash.

### 8.5 Other Android notes

- `targetSdk 34`: the permission `PendingIntent` must be mutable and explicit (the existing code already does this).
- Opening the bootloader device immediately after it appears can fail; Arduino waits 1 s and re-checks presence. On Android
  wait about 300 ms, then retry `openDevice()` a few times within the window **(timing unverified)**.
- FTDI: `setLatencyTimer(1)` after open to avoid 16 ms per reply.
- Read through `SerialInputOutputManager` (7.1), never `read()` with a tiny buffer.

---

## 9. Safety: what can brick a board

### 9.1 What we never do

- Never write fuses or lock bits, never send `STK_UNIVERSAL` writes, `CMD_PROGRAM_LOCK_ISP`, or anything to EEPROM. A wrong
  fuse (clock source, `SPIEN`, `RSTDISBL`, `BOOTRST`) is the only way to brick a board beyond a bootloader re-upload; none of it is
  reachable through our code path.
- Never write at or above `upload.maximum_size` (parser rejects it; programmer asserts it again before each page). This protects
  clones whose boot section is not locked.
- Never flash an image whose board does not match the signature.
- Never chip-erase (`52`, `e`, `CMD_CHIP_ERASE_ISP`).
- EEPROM is not touched, so the box keeps its random `id` and `rate` (`EEPROM` bytes 0 to 3), and the app's sensor profiles for
  that box id survive the update.

### 9.2 Can a failed upload always be retried?

Yes in every case we found, because the bootloader section is never written and the bootloader is entered by hardware means:

| Board | After an interrupted upload | How to retry |
|---|---|---|
| Nano / Uno (Optiboot, ATmegaBOOT) | Partial sketch; may crash at start | DTR reset always enters the bootloader on external reset; retry from the app |
| Mega 2560 | Partial sketch | DTR reset enters the bootloader (except after a watchdog reset; a power cycle or the next DTR pulse is an external reset) |
| 32U4 (Caterina) | **Risky without a guard**: a partial sketch with a valid word 0 starts, has no USB, so no 1200-baud touch and no serial port | **Guard used in 4.2:** page 0 is erased first and written last. Until the very last page, word 0 = `0xFFFF`, and Caterina then stays in the bootloader after any reset and after power-on, with no timeout. Interruption → unplug/replug → the bootloader is there forever → the app sees the Caterina PID and flashes again |
| 32U4, new sketch itself broken | Sketch runs but USB does not | Manual mode (8.1): press RESET (SparkFun: double tap), 8 s window |

Remaining real risks:
- The old ATmegaBOOT has no watchdog handling in the atmega328 build: a sketch that enables the watchdog can reset-loop
  through the bootloader. SensorBox does not use the watchdog.
- A brown-out during a page write: only that page; verify catches it; retry.
- Unknown clone bootloaders (LGT8F328P, 328PB, "!!!"-sensitive Mega bootloaders) **(unverified)**: rejected by signature or
  documented as unsupported.

---

## 10. Existing implementations and licences

The app itself is **GPL-3.0** (`LICENSE`). That makes GPL-2.0-or-later, GPL-3.0, MIT and Apache-2.0 code legally reusable (with
notices), but none of these is a drop-in library for usb-serial-for-android on Android, and the protocols are small, so the
recommendation is **write our own Kotlin** and use the projects below as references.

| Project | Language | Licence | Protocols / boards | Reuse? |
|---|---|---|---|---|
| avrdude (`stk500.c`, `arduino.c`, `butterfly.c`, `stk500v2.c`, `wiring.c`) | C | GPL-2 (`COPYING`; the source files read here carry no per-file header, so "or later" was not confirmed) | Everything, the reference behaviour | Reference only. Copying code into a GPL-3 app needs "or later"; **unverified** for current files |
| Optiboot | C (AVR) | GPL-2.0-or-later (file header) | Bootloader side of STK500v1 | Reference |
| ATmegaBOOT_168, stk500boot (Mega) | C (AVR) | GPL (v2 or later per headers) | Bootloader side | Reference |
| Caterina (ArduinoCore-avr, LUFA) | C (AVR) | Permissive LUFA licence (MIT-like) | Bootloader side of AVR109 | Reference |
| arduino/go-serial-utils (`reset.go`) | Go | GPL-3.0 | 1200-baud touch and port wait timing | Reference for timings |
| avrgirl-arduino | JavaScript (Node) | MIT | STK500v1 (via `stk500`), STK500v2 (via `stk500-v2`), AVR109 (via `chip.avr.avr109`); Uno, Nano (old and new), Mega, Leonardo, Micro, SparkFun Pro Micro and more; board table with VID/PIDs | Good reference, especially `boards.js` and `lib/avr109.js`. Not usable on Android directly |
| js-stk500v1 (jacobrosenthal) | JavaScript | MIT | STK500v1 | Reference |
| js-stk500 / stk500-v2 (Pinoccio) | JavaScript | MIT | STK500v2 | Reference |
| ArduinoSketchUploader / ArduinoUploader (twinearthsoftware) | C# | MIT | STK500v1 (Uno R3, Nano R2/R3, Mega 1284), STK500v2 (Mega 2560), AVR109 (Leonardo, Micro) | Closest in shape to what we need (one library, all three protocols). Port ideas, not code (Windows serial API) |
| Physicaloid Library (ksksue) | Java (Android) | Apache-2.0 | Upload via STK500 (Uno, Nano 57600, Pro Mini, Duemilanove) and STK500v2 (Mega 2560); **Leonardo/Micro/AVR109 commented out**; own drivers for CDC-ACM, FTDI, CP210x, **no CH340** | Old and unmaintained (last activity date not confirmed). Using it means a second USB stack next to usb-serial-for-android and no 32U4. Not recommended |
| ArduinoDroid (Anton Smirnov) | Android app | No public source found (proprietary) | Compiles and uploads from Android; early versions Uno R3 and FTDI only | Not reusable |

---

## 11. Test plan

### 11.1 Unit tests (JVM, no hardware)

- `IntelHex`: valid file; bad checksum; odd length; type `02` and `04`; data after EOF; missing EOF; data beyond `maxSize`;
  `with_bootloader.hex` rejected; gaps filled with `FF`; `end` and page count.
- Every shipped asset parses against its board's `maxSize`; `manifest.json` hash equals `sensorbox.ino` (stale build guard).
- Mega asset does not contain `21 21 21` (warning test).
- Bootloader **simulators** behind a fake `SerialLink`, written from the sources in section 13:
  - Optiboot: 1 s silence → "starts sketch"; bad terminator → silent exit; drops bytes during the first 375 ms (LED);
  - ATmegaBOOT: 57600 only, 5 errors → exit, `Q` keeps it alive;
  - Caterina: 8 s timer reset only by `B`/`g`, page erase per `B`, word-0 rule, reversed signature;
  - Mega: `eraseAddress` sequential erase (a test that writes out of order must fail verify), checksum drop, `!!!` monitor.
  Run the full flow against each; inject timeouts, `NOSYNC`, corrupted reply bytes, a mid-flash disconnect; assert retries,
  the final state, and that no write ever targets an address ≥ `maxSketch`.
- Probe order: a 57600-only simulator must be found after the 115200 attempt; a Mega simulator must be found first with a Mega
  hint.

### 11.2 Hardware matrix (head unit and an Android phone with OTG)

| Board | USB | Bootloader | Expected |
|---|---|---|---|
| Nano clone | CH340G `1A86:7523` | old ATmegaBOOT 57600 | 115200 fails, 57600 succeeds; remembered next time |
| Nano clone | CH340 | Optiboot 115200 | first attempt |
| Nano | FTDI `0403:6001` | either | latency timer set; time comparable to CH340 |
| Uno R3 | 16U2 `2341:0043` | Optiboot | |
| Uno clone | CH340 | Optiboot | |
| Pro Micro clone | `2341:8036` → `2341:0036` | Caterina (Leonardo) | first time: permission dialog with checkbox; second time: silent |
| SparkFun Pro Micro 5 V | `1B4F:9206` → `9205` | SparkFun Caterina | after flash enumerates as `2341:8036`; new permission for the new sketch PID |
| Leonardo / Micro | `2341:8036/8037` | Caterina | |
| Mega 2560 R3 | 16U2 `2341:0042` | stk500v2 | STK500v2 first by VID/PID hint |
| Mega clone | CH340 | stk500v2 | found via hint from the selected board, else third in probe order |

For each: flash, then confirm the `I SENSORBOX` line and live frames; flash 20 times in a row (flakiness); flash with the KKL
cable connected and the ECU session running (ECU must keep working, the flasher must never touch the KKL device).

### 11.3 Fault injection

- Unplug the board at 10 %, 50 %, 95 % of write and during verify → `Disconnected`; replug → retry succeeds. For the 32U4,
  confirm that after an interruption the board comes up as the Caterina PID by itself (page-0 guard).
- Deny the permission dialog; wait > 8 s before tapping (32U4) → `BootloaderTimeout`, retry works.
- Wrong board selected (Nano hex on a Mega and vice versa) → `WrongBoard`, nothing written.
- Board without a sketch (erased 32U4, factory-fresh clone).
- App swiped away / screen off / head unit sleep (ACC off) during flashing → board recoverable, next attempt clean.
- Passive hub with KKL cable + box + sensors powered; weakest port of the head unit.
- Two Arduinos plugged in at once: the app flashes only the chosen one.

---

## 12. Open questions / unverified

- Real sketch sizes per board (not compiled here); upload times are estimates.
- Exact ATmegaBOOT first-byte timeout and Mega bootloader window on hardware (sources give loop counts, not measured times).
- Optiboot LED-flash duration (375 ms is computed from the timer settings).
- How long head units take to enumerate the Caterina device; whether any head-unit ROM auto-grants USB permission.
- Whether the "use by default" checkbox appears in the `requestPermission` dialog on the target head units (AOSP shows it only
  when the app can be default for the device).
- Clone bootloaders: Mega clones that hang on `!!!`, CH340 variants without DTR, Nano boards shipped with Optiboot 8.
- avrdude per-file licence wording (GPL-2.0-only vs or-later) if code were ever copied.
- Physicaloid's last maintenance date.

---

## 13. Sources

Protocol and bootloader sources:
- avrdude STK500v1: https://github.com/avrdudes/avrdude/blob/main/src/stk500.c and constants
  https://github.com/avrdudes/avrdude/blob/main/src/stk500_private.h
- avrdude Arduino reset and signature read: https://github.com/avrdudes/avrdude/blob/main/src/arduino.c
- avrdude AVR109 / butterfly: https://github.com/avrdudes/avrdude/blob/main/src/butterfly.c
- avrdude STK500v2: https://github.com/avrdudes/avrdude/blob/main/src/stk500v2.c,
  https://github.com/avrdudes/avrdude/blob/main/src/stk500v2_private.h
- avrdude "wiring" (Mega) reset: https://github.com/avrdudes/avrdude/blob/main/src/wiring.c
- avrdude part database (signatures, page sizes): https://github.com/avrdudes/avrdude/blob/main/src/avrdude.conf.in
- avrdude licence: https://github.com/avrdudes/avrdude/blob/main/COPYING
- Optiboot (upstream): https://github.com/Optiboot/optiboot/blob/master/optiboot/bootloaders/optiboot/optiboot.c
- Optiboot as shipped in the Arduino core (v4.4) and its Makefile:
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/optiboot/optiboot.c,
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/optiboot/Makefile
- ATmegaBOOT (old Nano): https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/atmega/ATmegaBOOT_168.c and
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/atmega/Makefile
- Caterina: https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/caterina/Caterina.c,
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/caterina/Descriptors.c,
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/caterina/Makefile
- Mega STK500v2 bootloader: https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/stk500v2/stk500boot.c,
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/stk500v2/command.h,
  https://github.com/arduino/ArduinoCore-avr/blob/master/bootloaders/stk500v2/Makefile
- 1200-baud touch in the sketch: https://github.com/arduino/ArduinoCore-avr/blob/master/cores/arduino/CDC.cpp
- Board definitions (VID/PID, speeds, sizes, fuses): https://github.com/arduino/ArduinoCore-avr/blob/master/boards.txt
- Arduino's touch and port wait: https://github.com/arduino/go-serial-utils (reset.go), https://pkg.go.dev/github.com/arduino/go-serial-utils
- SparkFun Caterina and boards: https://github.com/sparkfun/Arduino_Boards (sparkfun/avr/bootloaders/caterina/Caterina.c,
  sparkfun/avr/boards.txt); Pro Micro hookup guide:
  https://learn.sparkfun.com/tutorials/pro-micro--fio-v3-hookup-guide/troubleshooting-and-faq
- Microchip application notes: AVR061 STK500 protocol https://www.microchip.com/en-us/application-notes/an2525 ; AVR068
  STK500v2 https://www.microchip.com/en-us/application-notes/an2591 ; AVR109 self programming
  https://www.microchip.com/en-us/application-notes/an1644
- Intel HEX format: https://en.wikipedia.org/wiki/Intel_HEX
- arduino-cli compile: https://arduino.github.io/arduino-cli/latest/commands/arduino-cli_compile/

Android and USB library:
- usb-serial-for-android 3.8.1: CH34x driver
  https://github.com/mik3y/usb-serial-for-android/blob/v3.8.1/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/Ch34xSerialDriver.java ;
  CDC-ACM https://github.com/mik3y/usb-serial-for-android/blob/v3.8.1/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/CdcAcmSerialDriver.java ;
  read/write semantics https://github.com/mik3y/usb-serial-for-android/blob/v3.8.1/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/CommonUsbSerialPort.java ;
  prober https://github.com/mik3y/usb-serial-for-android/blob/v3.8.1/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/UsbSerialProber.java ;
  I/O manager https://github.com/mik3y/usb-serial-for-android/blob/v3.8.1/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/util/SerialInputOutputManager.java ;
  licence (MIT) https://github.com/mik3y/usb-serial-for-android/blob/master/LICENSE.txt
- Android USB host guide (permissions, device filter): https://developer.android.com/develop/connectivity/usb/host
- AOSP default-device handling: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java
- AOSP `DeviceFilter`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/usb/DeviceFilter.java
- AOSP permission dialog: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/usb/UsbPermissionActivity.java

Existing uploaders:
- avrgirl-arduino (MIT): https://github.com/noopkat/avrgirl-arduino (boards.js, lib/avr109.js, lib/stk500v1.js, LICENSE.md)
- js-stk500v1 (MIT): https://github.com/jacobrosenthal/js-stk500v1
- js-stk500 / STK500v2 (MIT): https://github.com/Pinoccio/js-stk500
- ArduinoSketchUploader / ArduinoUploader (MIT): https://github.com/twinearthsoftware/ArduinoSketchUploader
- Physicaloid Library (Apache-2.0): https://github.com/ksksue/PhysicaloidLibrary (board list in
  PhysicaloidLibrary/src/com/physicaloid/lib/Boards.java)
- ArduinoDroid background: https://www.i-programmer.info/news/91-hardware/5788-arduinodroid-an-arduino-ide-for-android.html ,
  https://learn.adafruit.com/programming-arduino-with-android-and-windows-tablets/android-tablets
