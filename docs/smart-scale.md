# Smart scale: protocol, weigh-in rules and formulas

Platebit's optional integration with the **Xiaomi Mi Body Composition Scale 2 (model XMTZC05HM)**.
It's turned on in *Settings → Smart scale* (off by default). This page documents how it works,
so the code can be understood and maintained without the original research notes. Every number
here is copied from the code; if they ever disagree, the code wins.

| Part | File |
|---|---|
| Bluetooth frame parser | `app/src/main/java/app/morsel/ble/MiScaleParser.kt` |
| Bluetooth scanner (listen only) | `ble/MiScaleScanner.kt` |
| Hands-free weigh-in rules | `ble/WeighInDetector.kt` |
| Full-screen dial and saving | `ui/scale/AutoWeighIn.kt` |
| Formulas, ranges, score | `domain/BodyComposition.kt` |
| Body screen | `ui/body/BodyScreen.kt`, `BodyViewModel.kt` |
| Tests | `test/.../ble/MiScaleParserTest.kt`, `WeighInDetectorTest.kt`, `domain/BodyCompositionTest.kt`, `BodyAnalysisTest`, `androidTest/.../MigrationTest.kt` |

## 1. Bluetooth protocol

- **Nothing is paired or connected.** The scale advertises each reading in its BLE advertisement.
  Platebit only listens, so Zepp Life keeps working, and no Xiaomi account is involved.
- **Permission:** `BLUETOOTH_SCAN` with `neverForLocation`. No location permission.
- **Filter:** service data under the Body Composition service UUID **`0x181B`**
  (`0000181B-0000-1000-8000-00805F9B34FB`). Scan mode `LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`.
- **Frame:** 13 bytes, not encrypted on this model.

| Byte(s) | Meaning |
|---|---|
| 0 | Unit flags: bit 0 = pounds |
| 1 | Flags: bit 1 = impedance present, bit 5 = **stable**, bit 6 = catty (jin), bit 7 = **stepped off** |
| 2–8 | Scale date and time (ignored; the phone's clock is used) |
| 9–10 | Impedance in ohms, little endian |
| 11–12 | Weight, little endian: **kg × 200**, or pounds × 100 / catty × 100 |

- **Weight:** kg = raw / 200; in pounds mode raw / 100 × 0.45359237; catty raw / 100 / 2.
- **Rejected frames:** the stepped-off flag set, a weight outside **2–250 kg**, or fewer than 13 bytes.
- **Impedance:** used only when the impedance flag is set and the value is **1–2999 Ω**.
  Otherwise the reading is weight only (shoes or socks on, dry feet, stepped off too soon).

## 2. Hands-free weigh-ins (`WeighInDetector`, `AutoWeighIn`)

- Listening only happens while Platebit is **on screen** (`repeatOnLifecycle(STARTED)`) **and** the
  scale is turned on in Settings. Never in the background. Bluetooth off or no permission just means nothing is heard; it retries every 5 s.
- **Sequence:** unstable frames update the live weight. A stable frame without impedance starts
  "measuring body composition". A stable frame **with** impedance finishes the weigh-in.
- **Weight only:** if no impedance arrives **12 s** after the weight settled, the weight is saved alone.
- **Same weigh-in:** two stable frames within **0.3 kg** are the same weigh-in.
- **Repeats:** the scale repeats its final frame after you step off. Within **90 s**, frames within
  **1 kg** of the last saved weigh-in are ignored, so it's saved once. A clearly different
  weight (someone else) still counts.
- **Is this you?** A finished weigh-in more than **5 kg** from your latest weigh-in (or the
  current trend if there's none) asks before saving.
- **Screen:** a full-screen analog dial (0–150 kg over a 240° arc, ticks every 2 kg, longer every
  10 kg), the weight gliding to each reading, and a progress bar: 12 % while weighing, filling
  to 92 % over 7 s while measuring, 100 % when done. Then it saves and opens the Body screen.
  X or Back hides it. If the scale goes quiet for 6 s mid-weighing, the screen closes.

## 3. Storage

Table `weights` (database version 2; migration `MIGRATION_1_2` adds the nullable columns to v1 rows):
`id, date, timestamp, kg, impedance, fatPct, waterPct, muscleKg, boneKg, leanKg`.
The stored composition is a snapshot. **The Body screen always recomputes from `kg` + `impedance`
and the current profile**, so formula improvements and birthdays apply to old weigh-ins too.

## 4. Body composition formulas (`BodyCompositionCalc.compute`)

Inputs: weight W (kg), impedance R (Ω), height H (cm), age A, sex (male = 1).
Height index **I = H² / R**. Returns nothing without impedance.

**Lean mass (fat-free mass)**, a blend that doesn't trust one noisy equation:
- Sun et al. 2003 (NHANES):
  - men: `−10.678 + 0.262·W + 0.652·I + 0.015·R`
  - women: `−9.529 + 0.168·W + 0.696·I + 0.016·R`
- Foot-to-foot, fitted against DXA (Nutr J 2015;14:65): `13.055 + 0.204·W + 0.394·I − 0.136·A + 8.125·male`
- Impedance estimate = mean of the two above.
- Build estimate = `W × (1 − CUN-BAE fat % / 100)`, CUN-BAE clamped to 3–60 %.
- **Lean = 0.75 × impedance estimate + 0.25 × build estimate**, clamped to 40–97 % of W.

**CUN-BAE** (Gómez-Ambrosi 2012), sex s = 0 male / 1 female, BMI B:
`−44.988 + 0.503·A + 10.689·s + 3.172·B − 0.026·B² + 0.181·B·s − 0.02·B·A − 0.005·B²·s + 0.00021·B²·A`

**Fat %** = `100 × (W − lean) / W`, clamped to 3–60 %.

**Water**, the mean of three, kept within 65–78 % of lean mass:
- Sun 2003: men `1.203 + 0.449·I + 0.176·W`, women `3.747 + 0.450·I + 0.113·W`
- Watson 1980: men `2.447 − 0.09156·A + 0.1074·H + 0.3362·W`, women `−2.097 + 0.1069·H + 0.2466·W`
- `0.732 × lean` (hydration of lean tissue)

**Bone mineral** = `lean × 0.052 × age factor`. The factor falls 0.3 %/year after 40 for men and
0.6 %/year after 45 for women, never below 0.75.

**Muscle mass** (bathroom-scale style) = `lean − bone`.

**Protein %** = `100 × max(0, lean − water − 1.2 × bone) / W` (bone plus ~20 % soft-tissue mineral).

**Skeletal muscle** (Janssen 2000) = `0.401·I + 3.825·male − 0.071·A + 5.102`, kept between 10 % of W
and 75 % of (lean − bone).

**BMR** = average of Cunningham 1980 `500 + 22·lean` and Mifflin-St Jeor for the profile.

## 5. Platebit's own indices (`BodyAnalysis`)

- **Visceral fat level (1–59):** the visceral share of fat is `0.08 + 0.0018 × (A − 20)` for men and
  `0.04 + 0.0014 × (A − 20)` for women, capped at 0.22. Level = `fat kg × share / 0.2`, rounded.
  Calibrated so a lean 30-year-old lands around 5 and middle-aged obesity passes 15.
- **Body age:** age + `0.5 × (fat % − middle of the healthy fat range)` − `0.4 × (skeletal % − typical)`.
  Kept within 15 years of your age, minimum 18.
- **Typical skeletal muscle %:** men `40 − 0.1 × (A − 25)`, women `31 − 0.08 × (A − 25)` (from 25).
- **Body type words:**
  - fat: low / healthy / high, by the fat range
  - muscle: less / typical / more, when skeletal % is more than 3 points below or above typical
- **Body score (30–100):** 100 minus penalties:
  - BMI: 4 per point outside 18.5–25, max 25
  - Fat: 2 per point below the healthy range, 3.5 per point above, max 40
  - Visceral: 2 per level above 9, max 20
  - Muscle: 1.5 per point of skeletal % below typical − 3, max 15
  - Water: 1 per point below 50 % (men) or 45 % (women), max 10

## 6. Healthy ranges (bars and pills)

- **BMI:** <18.5 underweight · <25 normal · <30 overweight · ≥30 obese. Healthy weight = 18.5–24.9 × m²; "BMI 22 at" = 22 × m².
- **Body fat** (Gallagher 2000), cut-offs low / healthy / high, then very high:

| | <40 | 40–59 | 60+ |
|---|---|---|---|
| Men | 8 / 21 / 26 | 11 / 23 / 29 | 13 / 25 / 31 |
| Women | 21 / 33 / 39 | 23 / 35 / 41 | 25 / 38 / 43 |

- **Water:** men healthy 50–65 %, women 45–60 %.
- **Skeletal muscle:** typical ± 3 points is normal. **Muscle mass** uses the same range scaled by
  your own muscle-to-skeletal ratio, so the two always agree.
- **Protein:** 13–20 % healthy.
- **Bone** (shown in kg), normal within ±10 % of the reference:
  - men: 2.5 kg under 60 kg, 2.9 kg under 75 kg, 3.2 kg otherwise
  - women: 1.8 kg under 45 kg, 2.2 kg under 60 kg, 2.5 kg otherwise
- **Visceral:** <10 normal · <15 high · above that very high.
- **BMR:** 90–130 % of the Mifflin-St Jeor estimate is typical.
- **Body age:** younger up to your age + 0.5, older above.

## 7. History

- **Scalebit:** built as a separate side app (package `app.scalebit`, branch `scale-support`).
- **Platebit 1.0.0:** its final state (B03) was merged in. The scale became opt-in in Settings.
- **1.0.1:** hands-free weigh-ins.
- **Later:** the full-screen dial, bone and muscle in kg, and swiping between measurements on the
  Body screen. Scalebit itself was then retired; this page and the code are the reference.
- **1.1.8:** the manual *Weigh in* button and its dialog were removed, since weigh-ins are picked
  up on their own. *Log* (typing a weight) stays.
