/*
 * Subaru SSM dashboard: a colour screen on the head unit's USB (an ESP32-S3 board with an AMOLED or IPS panel).
 *
 * The app draws the picture itself (its gauges and tiles, any value it has: the ECU, the SensorBox, the trip) and
 * sends only the parts that changed; this firmware puts them on the panel. So every design the app has works here,
 * and a new look needs no new firmware. The board is picked in screen_board.h (build-bin.sh writes it).
 *
 * USB: the S3's own USB serial (Arduino: USB Mode «Hardware CDC and JTAG», USB CDC On Boot «Enabled»), 303A:1001.
 *   host → screen   ?                          who are you → I USBSCREEN 1 board=<id> w=<W> h=<H> round=<0|1> fw=<N> id=<MAC>
 *                   P <x> <y> <w> <h> <n>\n    then n bytes: the rectangle's pixels row by row, RGB565 little-endian,
 *                                              packed: a byte h, then (h & 0x80) a run of (h & 0x7F) + 1 pixels of one
 *                                              colour (2 bytes), else h + 1 pixels as they are; answer K (or E)
 *                   B <0..255>                 brightness
 *                   Z                          black
 *                   H                          «still here» (any line is)
 *   screen → host   N / L                      the BOOT button pressed short / long: next page / the app decides
 *                   Y R                        no line from the app for 5 s («NO LINK» was shown): draw everything again
 * Rectangles stay under 16384 pixels; the app keeps x, y, w and h even (AMOLED panels write in pairs).
 */
#include "screen_board.h"
#include "boards.h"

#define FW_VERSION 1
#define STR_(x) #x
#define STR(x) STR_(x)
#define MAX_PX 16384

static uint16_t px[MAX_PX];
static uint8_t raw[MAX_PX * 2 + MAX_PX / 128 + 8];
static char line[96];
static uint8_t lineLen = 0;
static uint32_t lastLine = 0, pressedAt = 0;
static bool drew = false, stale = false, pressed = false;

static void centred(const char *t, int16_t y, uint8_t size, uint16_t colour) {
  gfx->setTextSize(size);
  gfx->setTextColor(colour);
  int16_t w = strlen(t) * 6 * size;
  gfx->setCursor((gfx->width() - w) / 2, y);
  gfx->print(t);
}

static void hello() {
  gfx->fillScreen(0);
  int16_t y = gfx->height() / 2 - 24;
  centred("SUBARU SSM", y, 3, 0x2E7F);
  centred("waiting for the app", y + 34, 1, 0x8410);
}

static void printInfo() {
  Serial.print(F("I USBSCREEN 1 board=" SCREEN_ID " w="));
  Serial.print(gfx->width());
  Serial.print(F(" h="));
  Serial.print(gfx->height());
  Serial.print(F(" round=" STR(SCREEN_ROUND) " fw=" STR(FW_VERSION) " id="));
  // the chip's MAC: two or three screens on one head unit each keep their own settings
  Serial.printf("%012llX", ESP.getEfuseMac() & 0xFFFFFFFFFFFFULL);
  Serial.println();
}

/** [n] packed bytes into exactly [count] pixels; false when they do not add up. */
static bool unpack(const uint8_t *s, uint32_t n, uint16_t *out, uint32_t count) {
  uint32_t i = 0, o = 0;
  while (i < n && o < count) {
    uint8_t h = s[i++];
    uint32_t k = (h & 0x7F) + 1;
    if (o + k > count) return false;
    if (h & 0x80) {
      if (i + 2 > n) return false;
      uint16_t c = s[i] | (s[i + 1] << 8);
      i += 2;
      while (k--) out[o++] = c;
    } else {
      if (i + 2 * k > n) return false;
      while (k--) { out[o++] = s[i] | (s[i + 1] << 8); i += 2; }
    }
  }
  return o == count && i == n;
}

static void blit(char *s) {
  char *p = s + 1;
  long x = strtol(p, &p, 10), y = strtol(p, &p, 10), w = strtol(p, &p, 10), h = strtol(p, &p, 10), n = strtol(p, &p, 10);
  if (w <= 0 || h <= 0 || w * h > MAX_PX || n <= 0 || n > (long)sizeof(raw)) { Serial.println('E'); return; }
  if ((long)Serial.readBytes(raw, n) != n) { Serial.println('E'); return; }
  if (!unpack(raw, n, px, w * h)) { Serial.println('E'); return; }
  gfx->draw16bitRGBBitmap(x, y, px, w, h);
  drew = true;
  Serial.println('K');
}

static void handle(char *s) {
  switch (s[0]) {
    case '?': printInfo(); break;
    case 'P': blit(s); break;
    case 'B': SET_BRIGHT(constrain(atoi(s + 1), 0, 255)); break;
    case 'Z': gfx->fillScreen(0); break;
    case 'H': break;
    default: Serial.println('E');
  }
}

void setup() {
  Serial.setRxBufferSize(MAX_PX * 2);
  Serial.setTimeout(500);
  Serial.begin(115200);
  BOARD_INIT();
  gfx->begin();
  SET_BRIGHT(200);
  hello();
  pinMode(0, INPUT_PULLUP);  // BOOT
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (!lineLen) continue;
      line[lineLen] = 0;
      lineLen = 0;
      lastLine = millis();
      if (stale) { stale = false; Serial.println(F("Y R")); }
      handle(line);
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = c;
    }
  }
  uint32_t now = millis();
  // old numbers must not look live
  if (drew && !stale && now - lastLine > 5000) {
    stale = true;
    gfx->fillScreen(0);
    centred("NO LINK", gfx->height() / 2 - 12, 3, 0xF800);
  }
  bool down = digitalRead(0) == LOW;
  if (down && !pressed) { pressed = true; pressedAt = now; }
  if (!down && pressed) {
    pressed = false;
    if (now - pressedAt > 30) Serial.println(now - pressedAt > 800 ? 'L' : 'N');
  }
}
