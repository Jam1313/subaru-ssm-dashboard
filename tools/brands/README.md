# Car makes and logos

`build_brands.py` writes `app/src/main/assets/brands/`:

- `wmi.json`: WMI (VIN chars 1-3, or 1-3 + 12-14 for small makers with a 9 in the third place) to brand slug,
  2-char fallbacks for big makers, first-char regions, brand names and countries. Read by `data/Brands.kt`.
- `logos.json` and `logos/<slug>.webp`: logos of the makes common in Russia/CIS (128 px tall, transparent),
  and pinned download URLs for the rest of the dataset.

```
python3 -I tools/brands/build_brands.py --dl /some/cache/dir   # --offline: cache only, --refresh: download again
```

Downloads are cached in `--dl` and only read as data. Pillow with WebP is needed for the logos.

## Sources and licenses

- NHTSA vPIC API (https://vpic.nhtsa.dot.gov/api/), US government data, public domain.
- Wikipedia, "Vehicle identification number" (en) and "Идентификационный номер транспортного средства" (ru),
  CC BY-SA 4.0: used only to check the hand-made supplement in the script and to settle WMIs that vPIC lists
  for several makes; nothing from it is shipped.
- Logos: https://github.com/filippofilip95/car-logos-dataset (MIT), pinned to a commit in the script.
  The logos are trademarks of their respective owners and are shown only to name the car's make.

WMIs shared by several makes (VW Group Rus XW8, Hyundai Rus Z94, Avtotor, UzAuto XWB, ...) are left out on
purpose: such a VIN gives no make rather than a wrong one. The list is in the script.
