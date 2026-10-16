"""
The SensorBox OLED font: 5x7 glyphs drawn here as text, written into hardware/sensorbox/sensorbox.ino between the
FONT BEGIN / FONT END markers, and drawn to a PNG to look at.

    python3 -I tools/sensorbox/gen_font.py [preview.png]

Codes on the wire: ASCII for digits, Latin and signs (lowercase is shown as uppercase), '~' for the degree sign,
and 0xC0..0xDF for Russian А..Я (the cp1251 capitals). Russian letters that look like Latin ones share their glyph.
"""
import os
import re
import sys

G = {
    ' ': ["     "] * 7,
    '0': [" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "],
    '1': ["  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "],
    '2': [" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"],
    '3': ["#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "],
    '4': ["   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "],
    '5': ["#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "],
    '6': ["  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "],
    '7': ["#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "],
    '8': [" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "],
    '9': [" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "],
    'A': [" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"],
    'B': ["#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### "],
    'C': [" ### ", "#   #", "#    ", "#    ", "#    ", "#   #", " ### "],
    'D': ["#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### "],
    'E': ["#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"],
    'F': ["#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    "],
    'G': [" ### ", "#   #", "#    ", "# ###", "#   #", "#   #", " ####"],
    'H': ["#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"],
    'I': [" ### ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "],
    'J': ["  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  "],
    'K': ["#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"],
    'L': ["#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"],
    'M': ["#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #"],
    'N': ["#   #", "#   #", "##  #", "# # #", "#  ##", "#   #", "#   #"],
    'O': [" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    'P': ["#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "],
    'Q': [" ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #"],
    'R': ["#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #"],
    'S': [" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "],
    'T': ["#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "],
    'U': ["#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    'V': ["#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  "],
    'W': ["#   #", "#   #", "#   #", "# # #", "# # #", "# # #", " # # "],
    'X': ["#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #"],
    'Y': ["#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  "],
    'Z': ["#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####"],
    '.': ["     ", "     ", "     ", "     ", "     ", " ##  ", " ##  "],
    ',': ["     ", "     ", "     ", "     ", " ##  ", "  #  ", " #   "],
    ':': ["     ", " ##  ", " ##  ", "     ", " ##  ", " ##  ", "     "],
    '-': ["     ", "     ", "     ", "#####", "     ", "     ", "     "],
    '+': ["     ", "  #  ", "  #  ", "#####", "  #  ", "  #  ", "     "],
    '%': ["##   ", "##  #", "   # ", "  #  ", " #   ", "#  ##", "   ##"],
    '/': ["     ", "    #", "   # ", "  #  ", " #   ", "#    ", "     "],
    '~': [" ##  ", "#  # ", "#  # ", " ##  ", "     ", "     ", "     "],  # degree
    '(': ["   # ", "  #  ", " #   ", " #   ", " #   ", "  #  ", "   # "],
    ')': [" #   ", "  #  ", "   # ", "   # ", "   # ", "  #  ", " #   "],
    '<': ["   # ", "  #  ", " #   ", "#    ", " #   ", "  #  ", "   # "],
    '>': [" #   ", "  #  ", "   # ", "    #", "   # ", "  #  ", " #   "],
    '=': ["     ", "     ", "#####", "     ", "#####", "     ", "     "],
    '!': ["  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "     ", "  #  "],
    '?': [" ### ", "#   #", "    #", "   # ", "  #  ", "     ", "  #  "],
    '_': ["     ", "     ", "     ", "     ", "     ", "     ", "#####"],
    '*': ["     ", "  #  ", "# # #", " ### ", "# # #", "  #  ", "     "],
    '#': [" # # ", " # # ", "#####", " # # ", "#####", " # # ", " # # "],
    # Russian letters that differ from Latin ones
    'Б': ["#####", "#    ", "#    ", "#### ", "#   #", "#   #", "#### "],
    'Г': ["#####", "#    ", "#    ", "#    ", "#    ", "#    ", "#    "],
    'Д': ["  ## ", " # # ", " # # ", " # # ", " # # ", "#####", "#   #"],
    'Ж': ["# # #", "# # #", " ### ", "  #  ", " ### ", "# # #", "# # #"],
    'З': [" ### ", "#   #", "    #", "  ## ", "    #", "#   #", " ### "],
    'И': ["#   #", "#   #", "#  ##", "# # #", "##  #", "#   #", "#   #"],
    'Й': [" # # ", "  #  ", "#   #", "#  ##", "# # #", "##  #", "#   #"],
    'Л': ["  ###", " #  #", " #  #", " #  #", " #  #", " #  #", "#   #"],
    'П': ["#####", "#   #", "#   #", "#   #", "#   #", "#   #", "#   #"],
    'У': ["#   #", "#   #", "#   #", " ####", "    #", "#   #", " ### "],
    'Ф': ["  #  ", " ### ", "# # #", "# # #", "# # #", " ### ", "  #  "],
    'Ц': ["#  # ", "#  # ", "#  # ", "#  # ", "#  # ", "#####", "    #"],
    'Ч': ["#   #", "#   #", "#   #", " ####", "    #", "    #", "    #"],
    'Ш': ["#   #", "#   #", "# # #", "# # #", "# # #", "# # #", "#####"],
    'Щ': ["# #  ", "# #  ", "# #  ", "# #  ", "# #  ", "#####", "    #"],
    'Ъ': ["##   ", " #   ", " #   ", " ### ", " #  #", " #  #", " ### "],
    'Ы': ["#   #", "#   #", "#   #", "##  #", "# # #", "# # #", "##  #"],
    'Ь': ["#    ", "#    ", "#    ", "#### ", "#   #", "#   #", "#### "],
    'Э': [" ### ", "#   #", "    #", "  ###", "    #", "#   #", " ### "],
    'Ю': ["#  # ", "# # #", "# # #", "### #", "# # #", "# # #", "#  # "],
    'Я': [" ####", "#   #", "#   #", " ####", "  # #", " #  #", "#   #"],
}
# Russian capitals drawn like Latin ones
SAME = {'А': 'A', 'В': 'B', 'Е': 'E', 'К': 'K', 'М': 'M', 'Н': 'H', 'О': 'O', 'Р': 'P', 'С': 'C', 'Т': 'T', 'Х': 'X'}
RU = 'АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ'


def columns(rows):
    """5 bytes, bit 0 at the top row, as the SSD1306 takes a page."""
    assert len(rows) == 7 and all(len(r) == 5 for r in rows), rows
    return [sum(1 << y for y in range(7) if rows[y][x] == '#') for x in range(5)]


def main():
    order = [c for c in G if c not in RU]
    codes = [ord(c) for c in order]
    data = [columns(G[c]) for c in order]
    # each Russian capital: its own glyph or a Latin twin's
    for i, ch in enumerate(RU):
        codes.append(0xC0 + i)
        data.append(columns(G[ch]) if ch in G else columns(G[SAME[ch]]))
    body = ["// FONT BEGIN (tools/sensorbox/gen_font.py)",
            "const uint8_t FONT_N = %d;" % len(codes),
            "const uint8_t FONT_CODE[] PROGMEM = {%s};" % ",".join("0x%02X" % c for c in codes),
            "const uint8_t FONT[] PROGMEM = {"]
    body += ["  " + ",".join("0x%02X" % b for b in d) + ",  // %s" % (chr(c) if c < 0xC0 else RU[c - 0xC0]) for c, d in zip(codes, data)]
    body += ["};", "// FONT END"]
    ino = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../hardware/sensorbox/sensorbox.ino")
    s = open(ino, encoding="utf-8").read()
    s = re.sub(r"// FONT BEGIN.*?// FONT END", "\n".join(body), s, flags=re.S)
    open(ino, "w", encoding="utf-8").write(s)
    # the same font in the app, for the preview of what the display will show
    kt = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/java/kg/forester/sg/data/BoxFont.kt")
    open(kt, "w", encoding="utf-8").write(
        "package kg.forester.sg.data\n\n"
        "/** The SensorBox OLED font (generated by tools/sensorbox/gen_font.py, the same as in the firmware): code to 5 columns, bit 0 on top. */\n"
        "object BoxFont {\n"
        "    val glyphs: Map<Int, IntArray> = mapOf(\n"
        + "".join("        0x%02X to intArrayOf(%s),\n" % (c, ", ".join("0x%02X" % b for b in d)) for c, d in zip(codes, data))
        + "    )\n\n"
        "    /** [c] as the firmware draws it: lowercase as capitals, an unknown code as '?'. */\n"
        "    fun of(c: Int): IntArray {\n"
        "        var k = c\n"
        "        if (k in 'a'.code..'z'.code) k -= 32\n"
        "        if (k >= 0xE0) k -= 0x20\n"
        "        return glyphs[k] ?: glyphs['?'.code]!!\n"
        "    }\n"
        "}\n")
    print(len(codes), "glyphs,", len(codes) * 5, "bytes")
    if len(sys.argv) > 1:
        from PIL import Image
        text = "0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ .,:-+%/~()<>=!?_*# " + RU
        im = Image.new("L", (len(text) * 6 * 4 + 8, 9 * 4 + 8), 0)
        px = im.load()
        lookup = {}
        for c, d in zip(codes, data):
            lookup[RU[c - 0xC0] if c >= 0xC0 else chr(c)] = d
        for i, ch in enumerate(text):
            for x, col in enumerate(lookup[ch]):
                for y in range(7):
                    if col >> y & 1:
                        for dx in range(4):
                            for dy in range(4):
                                px[4 + (i * 6 + x) * 4 + dx, 4 + y * 4 + dy] = 255
        w = im.width // 2
        a, b = im.crop((0, 0, w, im.height)), im.crop((w, 0, im.width, im.height))
        out = Image.new("L", (w, im.height * 2), 0)
        out.paste(a, (0, 0))
        out.paste(b, (0, im.height))
        out.save(sys.argv[1])


if __name__ == "__main__":
    main()
