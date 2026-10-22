# 3D-модели машин и моторов для всех марок: источники и находки

Пайплайн как у текущих Subaru: Sketchfab-модель → `render/render_turntable.py`
(или `tools/engine3d/render_engine.py` для моторов) → `render/pack_frames.py` → кадры
в `app/src/main/assets/`. Любой `.glb` из источников ниже встаёт в этот пайплайн.

## Правила лицензий

- **Можно:** CC0, CC Attribution (CC BY) — автор + ссылка + список изменений в README
  (как в разделе «3D-модели»), формат уже отработан.
- **Осторожно:** CC BY-NC-SA (2 текущие модели WRX VA и 22B) — NC несовместим с
  коммерческим распространением; для новых моделей брать только CC0/CC BY.
- **Нельзя без покупки:** TurboSquid/CGTrader «royalty-free», Sketchfab-модели без
  Downloadable, «editorial use only».
- На странице каждой модели проверять галочку Downloadable и тип лицензии —
  автор может менять условия.

## Источники

| Источник | Что даёт | Лицензии |
|---|---|---|
| [Sketchfab](https://sketchfab.com/3d-models?features=downloadable&sort_by=-likeCount) (фильтр Downloadable + CC0/CC BY) | реальные модели конкретных машин и моторов | CC BY / CC0 / CC BY-NC |
| [poly.pizza — Quaternius Cars Bundle](https://poly.pizza/bundle/Cars-Bundle-FE5IWe6OMk) | набор low-poly машин (fbx/obj/gltf), без логотипов | CC0 |
| [Kenney Car Kit](https://kenney.nl/assets/car-kit) | 45+ low-poly авто (седан, фургон, кар и т.д.) | CC0 |
| [OpenGameArt Car Kit](https://opengameart.org/content/car-kit) | 45+ моделей + колёса, FBX/OBJ/glTF | CC0 |
| [Eclair Assets Car Kit GLB](https://eclair-assets.itch.io/car-kit-glb-pack-50-free-cc0-3d-models) | 50 GLB-конверсий Kenney-кита | CC0 |

**Стратегия «под все марки»:** конкретная модель — где есть (Sketchfab CC BY);
нет — generic-кузов из CC0-китов (седан/хэтч/SUV/купе/пикап/фургон/грузовик/EV)
с цветом марки. Так закрывается любая из 150+ марок без поиска точной модели.

## Найденные модели машин (Sketchfab, CC BY, downloadable)

| Марка/модель | Модель | Автор | UID Sketchfab |
|---|---|---|---|
| Lada Granta | Lada Granta Comfort | deepa.gurnani.nt | `b7c13bc7e93a458392a6f9df8448e7d9` |
| Lada Vesta | Lada Vesta | tiunov.se | `3c333139feb744ae9d36767c0e9cabfd` |
| UAZ Patriot | UAZ Patriot | tiunov.se | `35ba1f77bf7748b4ad2aa89f5dd75e9f` |
| Toyota Camry | Toyota Camry 2020 | ItsDiyor | `236a5a6e2fa6420fbdf641f4800cd544` |
| Toyota Land Cruiser | Land Cruiser J200 2017 | David_Holiday | `374e85a6e18743429f6d9c0e7bbffb3c` |
| Hyundai (Solaris/Accent) | hyundai accent 2013 | I95XP | `cec6b04b06724f1088129c1054ccb1ec` |
| Kia Rio | KIA RIO | zairiqzairiq | `b5119dce852f461d9eccd59841101a12` |
| Renault Duster | Renault Duster | ANDREO12 | `a253d913f99d4611860e8dd955c476d7` |
| Nissan Qashqai | Nissan Qashqai 2010 | niev | `d26880a3c5464b87b7a5a67323775947` |
| Mazda | 2015 Mazda 6 | Tyler_Dave | `24fb449e5f4243f49666ccf22d10c834` |
| Honda | Honda E 2021 | Xlay3D | `814cf3f8cfd64594b1c4f96e873c3cef` |
| Audi A4 | Audi A4 B5.5 Avant | Tomasoo | `2f6aea1435bd4ad093146c019c8e0fb6` |
| Skoda Octavia | 2000 Skoda Octavia | tonielpro520 | `892e152237fa4e8bbeb6afb20169f0c2` |
| Opel Astra | OPEL Astra GTC 2012 | comrade1280 | `d76ad5a0495443eda24a9052565ef9a8` |
| Peugeot 308 | 2022 Peugeot 308 | sohyalebret | `0d9c9c265bec42678bfc269c1a8cebc3` |
| VW Tiguan | VW Tiguan 2021 | source5525 | `0a11d748fd8f4b3f9bc879aed8d6d208` |
| Volvo XC60 | Volvo XC60 | niev | `eb8ce77c64c04339983cdf30593abca3` |
| Land Rover | Discovery Sport 2018 | cgassetlibrary | `5c788943379e4197a27fabd536903809` |
| Chevrolet Cruze | 2017 Chevrolet Cruze LTZ | ddiaz-design | `235872def34f43198c9c2e98ca439109` |
| Ford Focus | Ford Focus Sedan | BHP3D | `9bc5e9e5edd5487f97d8501a22c3c8b5` |
| Ford Mustang | Ford Mustang 1965 | Pooya_dh | `5f4e3965f79540a9888b5d05acea5943` |
| BMW M3 | [FREE] BMW M3 E30 | TinoD2 | `ac3c7013434e403e8faff87948caf422` |
| Mercedes E-Class | Mercedes E Class W212 | better_peter | `119c5e10733142b197aa53b86f6aeb04` |
| Porsche 911 | FREE 1975 Porsche 911 (930) Turbo | lionsharp | `8568d9d14a994b9cae59499f0dbed21e` |
| Tesla Model 3 | Tesla 2018 Model 3 | uchiha.321abc | `5ef9b845aaf44203b6d04e2c677e444f` |
| Polestar 2 | 2020 Polestar 2 | ddiaz-design | `24f78b3dc6e84fe0b0b2afdb43d547ea` |
| Mitsubishi Outlander | Mitsubishi Outlander 2022 | niev | `bc2dabd5a1454c9f967bdf680102352c` |
| Suzuki Jimny | 2023 Suzuki Jimny Sierra | tonielpro520 | `8b7c4d136f6742e180faf654d874f14a` |
| Haval F7 | Haval F7 | MattDoesBlender | `76f4d871898d4355b2b9d35d0ad430b9` |
| Chery Tiggo | Chery Tiggo | neominigan | `646178bea58d4cc5ba258f1c4ae540b4` |
| Xpeng P7 | 2022 XPeng P7 | supercarmodels | `e787dac0c6f84be7b39362fca9dc93da` |
| Jeep Wrangler | Jeep Wrangler | Suyogmodak | `531f120594d946be8cd9d87b5095f856` |
| RAM 1500 | Dodge Ram 1500 | David_Holiday | `5b3898052ca64641ab1857a638551039` |
| GMC Sierra | GMC SIERRA HD2500 | brayaj691 | `c31554882b074e168ddc65bd6d1e5464` |

Ссылка: `https://sketchfab.com/3d-models/<uid>`. Geely, BYD, NIO — точных CC-моделей
в топе нет; брать generic-SUV/EV из CC0-китов или искать по запросу марки с фильтром.

Поисковые запросы Sketchfab (фильтры Downloadable + CC BY/CC0): `"<марка> <модель>"` —
для Toyota/Nissan/VW/BMW/Mercedes/Ford/Porsche/Lada есть десятки моделей; у каждой
проверить лицензию на странице и занести в README по формату текущего раздела «3D-модели».

## Найденные модели моторов (Sketchfab, CC BY, downloadable)

| Архитектура | Модель | Автор | UID |
|---|---|---|---|
| Оппозитный 6 (Porsche) | F6 Boxer Engine | scudmorello | `5700259eeb494b8f8a0b8f63486c59cc` |
| V8 | Disassembled V8 Engine Block | Tomasoo | `3026bd87ca3945d6829d24c86ce695f0` |
| V8 | V8 Engine | Tahminayeasminmony | `7a957b5f9f954fe5b24e685f5e22046f` |
| V8 | Animated Engine V8 | meeww | `b0dbf778b81e4afba4edf11336e2a099` |
| V6 | V6 car engine with engine | Blendoriano | `7309cf5e6b07435c962f954513ca170e` |
| Рядная 4 | Inline-4 Engine | rafaelaraujo55 | `64c16a336bd74b2db337ca03bc156f7a` |
| Рядная 4 (Toyota 1ZZ-FE) | Car Engine 4 Cylinder - Toyota 1ZZ-FE | zarrarabbas73 | `3442d69b82b6416389eff8e961dfe333` |
| Рядная 4 | Inline 4 Engine Block, Crank, And Piston | ao3743 | `e9f882032e6b40609c5a2d3326104216` |
| Роторный (Mazda RX-7) | 13B-REW / MAZDA RX7 CAR ENGINE | EZ-GAZI | `b4feef96666a4bc494caae6c98fb9b83` |
| Роторный | Rotary Engine | kyou0086 | `fae8a079080c49669502fda3621e893e` |
| Дизель | Cat 3116 Engine | neominigan | `3de49c24fa004e8998d90f2a9c76d9f3` |
| Электромотор | Electric Motor | Scarecrow_original | `66b2ea9eb6e5468d91d814395036a537` |
| Электромотор (hi-poly) | Electric Motor (high poly) | Scarecrow_original | `048b9dc3095e44c0883b6b7944ad4916` |
| ТВД/турбо | Basic Turbo Charger | AngusWhitburn | `414dd8615bb24b798807fd187cedfe73` |
| V12 (Lamborghini) | Engine Lamborghini V12 6.5L | Lambo_SC04 | `16d4a2d9ee954bdd8ccf71fcf647b52b` |
| Детали (набор) | Detailed Engine Components Pack | mujtaba.abbasi.pk | `957cd4e295614071bfe3d861f2fde8aa` |

Текущие моторы (EJ турбо/атмо, FA) уже покрывают Subaru. Каталог архитектур для
остальных марок: оппозит 4/6 (Subaru/Porsche — готово/есть F6), рядная 4 (большинство
бюджетных), V6/V8 (США, Toyota, Nissan), роторный (Mazda RX), дизель, электромотор
(все EV), V12/турбо — витринные. Датчики на кадры — тем же способом, что
`tools/engine3d/*_anchors.json`.

## Как подключать новую модель (чек-лист)

1. Найти модель на Sketchfab: Downloadable, лицензия CC0/CC BY (не NC/ND).
2. Скачать GLB, прогнать `render_turntable.py --list` (материалы для покраски/номеров).
3. Нужные `--fix`-правки (тюнинг, салон, номера, масштаб в метры) — как в README.
4. Отрендерить и запаковать `pack_frames.py` в `assets/car_<id>/`.
5. Записать в README: название, автор, ссылка, лицензия, изменения (текущий формат).
