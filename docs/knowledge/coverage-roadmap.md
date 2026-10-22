# Roadmap: универсальное покрытие марок (не только Subaru)

Цель: приложение работало «на все машины», как Launch X-431. Ориентир Launch:
**94+ марки, 1000+ моделей** (X431 V+), IMMO — 126+ марок. Официальная база покрытия
Launch: https://qcar.x431.com/qcar/ (проверка по марке/модели/функции).
Официального open-source у Launch нет; аналоги баз — ddt4all, opendbc, FreeSSM/RomRaider.

## Три уровня покрытия

| Уровень | Что даёт | Охват | Статус |
|---|---|---|---|
| 1. Универсальный OBD-II (`Obd2.kt`, ELM327, автоопределение протокола) | обороты, скорость, температуры, MAF, нагрузка, ошибки, сброс | **все марки**: бензин 2001+ (США 1996+), дизель 2004+ | готово |
| 2. Слушок CAN по DBC (`CanMonitor` + opendbc) | сотни сигналов без запросов: колёса, руль, свет, двери, батарея EV | 26 брендов, 300+ моделей 2010+ | есть монитор, нужны DBC |
| 3. Брендовый пакет (как `SsmClient` для Subaru) | все датчики ЭБУ/АКПП, адаптации, обучение, спецфункции | по одному на марку | только Subaru |

Архитектура «по аналогии с Launch»: универсальный стек протоколов (ISO 9141, KWP2000,
CAN 11/29-bit, UDS, DoIP, J1939) + модуль на марку за общим интерфейсом
(`ObdLink`/`SsmTransport` уже являются такими шинами).

## Источники определений для брендовых пакетов

- **RomRaider / FreeSSM / SubaruDefs** — Subaru (уже интегрированы, GPL)
- **ddt4all** (`github.com/cedricp/ddt4all`, GPL-3.0) — XML-базы ЭБУ (`ecu.zip`):
  Renault/Dacia/Samsung, Nissan, Kia/Hyundai, часть Fiat/Peugeot; CAN, KWP2000, DoIP
- **opendbc** (`github.com/commaai/opendbc`, MIT) — 58 DBC: toyota, ford, gm, vw, bmw,
  mercedes, hyundai, nissan, mazda, psa, volvo, tesla, byd, gwm, hongqi, mg, rivian, opel…
- **Mode 22 (manufacturer PIDs)** — «дешёвый» глубокий уровень для Toyota/Nissan/Ford/GM
  поверх ELM327 без полного пакета
- **SAE J2012** — стандартные DTC; брендовые коды — из баз ddt4all/FreeSSM-типа
- **SAE J1939** — грузовые (MAN, Scania, Volvo Trucks, Isuzu, КамАЗ…)

## Волны внедрения

### Волна 0 — готово
Subaru (SSM2 K-Line/CAN) + универсальный OBD-II на все марки + CAN-монитор + Link G4.

### Волна 1 — топ российского рынка (максимум машин на строку кода)
| Марка | Протокол глубокого уровня | Источник определений | Приоритет |
|---|---|---|---|
| Toyota / Lexus | ISO 9141/KWP → UDS (CAN), Mode 22 PIDs | opendbc (toyota), Mode 22 | 1 |
| Hyundai / Kia | KWP2000 → UDS | ddt4all (kia/hyundai XML), opendbc | 2 |
| VW / Audi / Skoda / SEAT | KWP2000 → UDS, DoIP | opendbc (vw), vag-комьюнити | 3 |
| Renault / Dacia | CAN/KWP2000 | **ddt4all — база готова** | 4 |
| Nissan / Infiniti | Consult/KWP → UDS | ddt4all (nissan), opendbc | 5 |
| BMW / MINI | KWP → UDS (F/G) | opendbc (bmw) | 6 |
| Mazda | KWP → UDS | opendbc (mazda), Mode 22 | 7 |
| Ford / Lincoln | HS-CAN + MS-CAN (переключение) | opendbc (ford) | 8 |
| Chevrolet / Cadillac / Buick / Opel | GMLAN → UDS | opendbc (gm, opel) | 9 |
| Lada / VAZ, UAZ, GAZ | OBD-II (Euro 3/4), KWP | стандартный OBD + полевые логи | 10 |

### Волна 2 — расширение
Honda / Acura (HDS), Mitsubishi, Suzuki, Peugeot / Citroën (opendbc psa),
Mercedes-Benz / Smart (UDS), Volvo (opendbc volvo), Land Rover / Jaguar,
Porsche, Chrysler / Dodge / Jeep / RAM (FCA), Fiat / Alfa Romeo / Lancia,
SsangYong, Proton, Tata, Mahindra.

### Волна 3 — китайские EV и новые бренды
BYD, Geely / Zeekr / Lynk & Co / Polestar, Chery / Jetour / EXEED,
Great Wall / Haval, Changan, GAC, Xpeng, NIO, Li Auto, Voyah, Tesla, Rivian.
Протоколы: UDS/DoIP по CAN-Ethernet; в opendbc уже есть byd, gwm, hongqi, rivian, tesla.

### Волна 4 — грузовые
MAN, Scania, Volvo Trucks, Isuzu, Mercedes-Benz Trucks, Renault Trucks,
FAW, Dongfeng, Sinotruk, Shaanxi, JAC Trucks, КамАЗ: J1939 + J1708.

## Полный список марок (по аналогии со списком Launch PAD V)

- **Европа:** BMW, MINI, Rolls-Royce, Mercedes-Benz, Maybach, Smart, Volkswagen,
  Audi, Skoda, SEAT, Bentley, Bugatti, Lamborghini, Porsche, Opel, Fiat, Abarth,
  Lancia, Alfa Romeo, Maserati, Ferrari, Aston Martin, Renault, Dacia, Peugeot,
  Citroën, Volvo, Land Rover, Jaguar
- **Азия:** Toyota, Lexus, Honda, Acura, Nissan, Infiniti, Mazda, Mitsubishi,
  Subaru (готово), Suzuki, Hyundai, Genesis, Kia, SsangYong, Proton, Tata, Mahindra
- **Америка:** Ford, Lincoln, GM, Chevrolet, Buick, Cadillac, Chrysler, Dodge,
  Jeep, RAM, Tesla, Rivian
- **Китай:** BYD, Geely, Zeekr, Lynk & Co, Polestar, Chery, Jetour, EXEED,
  Great Wall, Haval, Changan, GAC Trumpchi, Roewe, MG, Maxus, Xpeng, NIO,
  Li Auto, Voyah, BAIC, FAW, Dongfeng, JAC, Haima, Zotye, Luxgen
- **РФ:** Lada/VAZ, UAZ, GAZ
- **Грузовые:** MAN, Scania, Volvo Trucks, Isuzu, Mercedes-Benz Trucks,
  Renault Trucks, FAW, Dongfeng, Sinotruk, Shaanxi, JAC Trucks, КамАЗ

Итого потолок: **150+ марок** (легковые 94+ по уровню Launch, китайские + грузовые).

## Проверка совместимости конкретной модели

1. OBD-II уровень — любая машина из таблицы выше (2001+).
2. CAN-сигналы — искать DBC в `commaai/opendbc/opendbc/dbc`.
3. Глубокий уровень — искать XML в `cedricp/ddt4all` (`ecu.zip`) или писать пакет по
   образцу `SsmClient` + логам с реальной машины (`SsmTrace`, «Отправить журнал»).
4. Свериться с Launch: https://qcar.x431.com/qcar/ — какие функции вообще реализуемы
   на данной марке/модели.
