/*
 * The colour screens the app can drive, one block per board: its panel, the bus and the pins, what powers it.
 * The pins come from Arduino_GFX's own board list (examples/PDQgraphicstest/Arduino_GFX_dev_device.h) and the makers'
 * wikis; build-bin.sh builds one firmware per block (screen_board.h names it).
 *
 * SET_BRIGHT(b) takes 0..255: an AMOLED panel by its own command, an LCD by the PWM of its backlight.
 */
#pragma once
#include <Arduino_GFX_Library.h>

#if defined(SCREEN_LILYGO_AMOLED_191)
  // LilyGO T-Display-S3 AMOLED 1.91": RM67162 240x536 on QSPI
  #define SCREEN_ID "lilygo_amoled_191"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(6 /* CS */, 47 /* SCK */, 18 /* D0 */, 7 /* D1 */, 48 /* D2 */, 5 /* D3 */);
  static Arduino_RM67162 *gfx = new Arduino_RM67162(bus, 17 /* RST */, 0);
  #define BOARD_INIT()
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_LILYGO_AMOLED_164)
  // LilyGO T-Display-S3 AMOLED 1.64": CO5300 280x456, the panel switched on by LCD_EN (GPIO16)
  #define SCREEN_ID "lilygo_amoled_164"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(10 /* CS */, 12 /* SCK */, 11 /* D0 */, 13 /* D1 */, 14 /* D2 */, 15 /* D3 */);
  static Arduino_CO5300 *gfx = new Arduino_CO5300(bus, 17 /* RST */, 0, 280, 456, 20, 0, 180, 24);
  #define BOARD_INIT() { pinMode(16, OUTPUT); digitalWrite(16, HIGH); }
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_LILYGO_TDISPLAY_S3)
  // LilyGO T-Display-S3 1.9": ST7789 170x320 IPS LCD on an 8-bit bus, backlight on GPIO38, power on GPIO15
  #define SCREEN_ID "lilygo_tdisplay_s3"
  #define SCREEN_ROUND 0
  #define SCREEN_BL 38
  static Arduino_DataBus *bus = new Arduino_ESP32LCD8(7 /* DC */, 6 /* CS */, 8 /* WR */, 9 /* RD */,
                                                      39, 40, 41, 42, 45, 46, 47, 48 /* D0..D7 */);
  static Arduino_ST7789 *gfx = new Arduino_ST7789(bus, 5 /* RST */, 0, true /* IPS */, 170, 320, 35, 0, 35, 0);
  #define BOARD_INIT() { pinMode(15, OUTPUT); digitalWrite(15, HIGH); }
  #define SET_BRIGHT(b) analogWrite(SCREEN_BL, b)

#elif defined(SCREEN_LILYGO_T4_S3)
  // LilyGO T4-S3 2.41": RM690B0 450x600, power on GPIO9
  #define SCREEN_ID "lilygo_t4_s3"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(11 /* CS */, 15 /* SCK */, 14 /* D0 */, 10 /* D1 */, 16 /* D2 */, 12 /* D3 */);
  static Arduino_RM690B0 *gfx = new Arduino_RM690B0(bus, 13 /* RST */, 0, 450, 600, 16, 0, 16, 0);
  #define BOARD_INIT() { pinMode(9, OUTPUT); digitalWrite(9, HIGH); }
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_AMOLED_143)
  // Waveshare ESP32-S3-Touch-AMOLED-1.43: round 466x466 (CO5300; early boards SH8601), AMOLED_EN on GPIO42
  #define SCREEN_ID "ws_amoled_143"
  #define SCREEN_ROUND 1
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(9 /* CS */, 10 /* SCK */, 11 /* D0 */, 12 /* D1 */, 13 /* D2 */, 14 /* D3 */);
  static Arduino_CO5300 *gfx = new Arduino_CO5300(bus, 21 /* RST */, 0, 466, 466, 6, 0, 0, 0);
  #define BOARD_INIT() { pinMode(42, OUTPUT); digitalWrite(42, HIGH); }
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_AMOLED_175)
  // Waveshare ESP32-S3-Touch-AMOLED-1.75: round 466x466 CO5300
  #define SCREEN_ID "ws_amoled_175"
  #define SCREEN_ROUND 1
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(12 /* CS */, 38 /* SCK */, 4 /* D0 */, 5 /* D1 */, 6 /* D2 */, 7 /* D3 */);
  static Arduino_CO5300 *gfx = new Arduino_CO5300(bus, 39 /* RST */, 0, 466, 466, 6, 0, 0, 0);
  #define BOARD_INIT()
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_AMOLED_164)
  // Waveshare ESP32-S3-Touch-AMOLED-1.64: CO5300 280x456
  #define SCREEN_ID "ws_amoled_164"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(9 /* CS */, 10 /* SCK */, 11 /* D0 */, 12 /* D1 */, 13 /* D2 */, 14 /* D3 */);
  static Arduino_CO5300 *gfx = new Arduino_CO5300(bus, 21 /* RST */, 0, 280, 456, 20, 0, 180, 24);
  #define BOARD_INIT()
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_AMOLED_18)
  // Waveshare ESP32-S3-Touch-AMOLED-1.8: SH8601 368x448; its reset sits on a TCA9554 (I2C 0x20, SDA 15, SCL 14)
  #include <Wire.h>
  #define SCREEN_ID "ws_amoled_18"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(12 /* CS */, 11 /* SCK */, 4 /* D0 */, 5 /* D1 */, 6 /* D2 */, 7 /* D3 */);
  static Arduino_SH8601 *gfx = new Arduino_SH8601(bus, GFX_NOT_DEFINED, 0, 368, 448);
  // every expander pin an output and high: the panel and the touch out of reset
  #define BOARD_INIT() { Wire.begin(15, 14); \
    Wire.beginTransmission(0x20); Wire.write(0x01); Wire.write(0xFF); Wire.endTransmission(); \
    Wire.beginTransmission(0x20); Wire.write(0x03); Wire.write(0x00); Wire.endTransmission(); delay(50); }
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_AMOLED_241)
  // Waveshare ESP32-S3-Touch-AMOLED-2.41: RM690B0 450x600, the panel on BAT_PWR (GPIO16)
  #define SCREEN_ID "ws_amoled_241"
  #define SCREEN_ROUND 0
  static Arduino_DataBus *bus = new Arduino_ESP32QSPI(9 /* CS */, 10 /* SCK */, 11 /* D0 */, 12 /* D1 */, 13 /* D2 */, 14 /* D3 */);
  static Arduino_RM690B0 *gfx = new Arduino_RM690B0(bus, 21 /* RST */, 0, 450, 600, 16, 0, 16, 0);
  #define BOARD_INIT() { pinMode(16, OUTPUT); digitalWrite(16, HIGH); }
  #define SET_BRIGHT(b) gfx->setBrightness(b)

#elif defined(SCREEN_WS_LCD_128)
  // Waveshare ESP32-S3-LCD-1.28: round 240x240 GC9A01 IPS LCD on SPI, backlight on GPIO40
  #define SCREEN_ID "ws_lcd_128"
  #define SCREEN_ROUND 1
  #define SCREEN_BL 40
  static Arduino_DataBus *bus = new Arduino_ESP32SPI(8 /* DC */, 9 /* CS */, 10 /* SCK */, 11 /* MOSI */, GFX_NOT_DEFINED /* MISO */);
  static Arduino_GC9A01 *gfx = new Arduino_GC9A01(bus, 12 /* RST */, 0, true /* IPS */);
  #define BOARD_INIT()
  #define SET_BRIGHT(b) analogWrite(SCREEN_BL, b)

#else
  #error "screen_board.h names no board: see boards.h"
#endif
