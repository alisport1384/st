# ۰۲ · مرجع الگوریتم — بازتولید دقیق از کد

**نسخه:** ۱٫۴٫۰ · **بازنویسی:** ۱۴۰۵/۰۷/۱۸ (2026-10-09)
**منبع:** `Engine.kt`، `VolumeProfile.kt`، `PaperBroker.kt` — نه سند استراتژی.

> این سند **چگونه** را می‌گوید. **چرا** و قاعدهٔ قانونی در `docs/01_STRATEGY_FA.md` است.
> اگر این دو اختلاف دارند، `docs/01` مرجع است.

---

## ۱) انتخاب کندل مهم

```
pickLow(a, b):  a.l > b.l  یا  (a.l == b.l و a.h < b.h)   →  a   وگرنه b
pickHigh(a, b): a.h > b.h  یا  (a.h == b.h و a.l > b.l)   →  a   وگرنه b
```

خروجی `(hi, lo, bi)` **خودِ کندل مهم** است.

**`ref`** همیشه از کندل مهم:

| | فرمول | اگر کندل مهم صعودی | اگر نزولی |
|---|---|---|---|
| BU | `min(imp.c, imp.o)` | `imp.o` | `imp.c` |
| BE | `max(imp.c, imp.o)` | `imp.c` | `imp.o` |

---

## ۲) چرخهٔ عمر کاندید

```
اگر status == 0 و sb.bi > startBi:
    مرگ   = (BU: sb.c <= ref)  یا  (BE: sb.c >= ref)      ← فقط کلوز
    اگر مرگ:  status = -1
    وگرنه:
        Ready = (BU: sb.c > imp.hi)  یا  (BE: sb.c < imp.lo)
        اگر Ready: status = 1
```

`if/else` است، پس **مرگ بر Ready اولویت دارد**.

---

## ۳) ابطال پین بعد از Ready

```
BU:  (sb.o < ref یا sb.c < ref)  و  sb.c >= lastBUpin    →  buActive = false
BE:  (sb.o > ref یا sb.c > ref)  و  sb.c <= lastBEpin    →  beActive = false
```

`lastBUpin`/`lastBEpin` **پاک نمی‌شوند** — فقط پرچم «فعال» خاموش می‌شود.

---

## ۴) روند

`trend ∈ {0, 1, −1}` · `pinSeq` با هر پین تاییدشده یکی زیاد می‌شود؛ `buSeq`/`beSeq`
شمارهٔ توالی آخرین پین از هر نوع.

```
trend == 0:
    اگر lastBUpin و lastBEpin هر دو موجود:
        sb.c > lastBEpin  →  trend = 1 ; HH = lastBEpin ; HL = lastBUpin
        sb.c < lastBUpin  →  trend = -1; LL = lastBUpin ; LH = lastBEpin

trend == 1:
    sb.c > HH  →  HH = sb.c
    sb.c < lastBUpin:
        beSeq > buSeq  →  trend = -1 ; LL = sb.c ; LH = HH
        وگرنه اگر buActive  →  buActive = false      ← روند دست نمی‌خورد

trend == -1:   قرینه با buSeq > beSeq
```

---

## ۵) پروفایل حجم فیکس‌رنج

**ورودی:** `microS` = کندل‌های چارت داخل کندل ساختار جاری (با هر کندل ساختار جدید `clear`).

```
lo = min(همهٔ کف‌ها)        hi = max(همهٔ سقف‌ها)
step = (hi − lo) / rows     (rows پیش‌فرض ۲۴)

برای هر کندل m:
    anchor = m.c                     (یا (h+l+c)/3 در حالت Typical)
    span   = max(m.h − m.l, mintick) × 1.2
    برای هر ردیف r:
        ov = max(0, min(m.h, rTop) − max(m.l, rBot))
        w  = max(0.05, 1 − |مرکز ردیف − anchor| / span)
        wSum += ov × w
    سپس:  rv[r] += m.v × ov × w / wSum

هموارسازی: vpSmooth بار با کرنل (۱،۲،۱)/۴
```

**POC و Value Area حساب می‌شوند ولی در ساخت ناحیه نقشی ندارند.**

---

## ۶) الگوریتم ناحیه

```
stepDir = +1 (صعودی) یا −1 (نزولی)
i = 0 (صعودی) یا rows−1 (نزولی)
firstPass = true

حداکثر ۲ ناحیه:
  ① قله: تا وقتی rv[j+stepDir] > rv[j]  →  j += stepDir
        اگر j != i  →  pk = j
        وگرنه اگر firstPass و rv[i+stepDir] < rv[i]  →  pk = i      ← قاعدهٔ ردیف نخست
        اگر pk < 0  →  پایان
  ② دره: تا وقتی rv[k+stepDir] < rv[k]  →  k += stepDir
        اگر k != pk  →  tr = k
        اگر tr < 0  →  پایان
  ③ loIdx = min(pk,tr)   hiIdx = max(pk,tr)
     zBot = lo + loIdx × step
     zTop = lo + (hiIdx+1) × step
  ④ اعتبار: صعودی zTop < close · نزولی zBot > close
     و hiIdx − loIdx + 1 >= minZoneRows
  ⑤ i = tr + stepDir ;  firstPass = false
```

چون `hiIdx = rows−1` ⇒ `zTop = hi`، قاعدهٔ ردیف نخست خودش **لبهٔ کندل** را می‌دهد.

---

## ۷) تایید میانی

```
touchMidStart = aggM.cur.t   (در لحظهٔ برخورد)

تایید:  midRef.isNaN() و mc.t == touchMidStart
        midRef = (dir == 1) ? mc.l : mc.h
        stage = 2 ; midConfirmBi = curBi

مارک:   کندل میانی بعدی فقط markH/markL/markBi  →  stage = 3

ابطال:  midInvalidClose == false  ⇒  عبور شمع کافی است
        (dir == 1) ? mc.l < midRef : mc.h > midRef
        → midRef = NaN ; lvBi/lvConfBi/hvBi/hvScanBi = −1
          lvTouched = lvUsed = false ; stage = 2
          touchMidStart = aggM.cur.t
```

---

## ۸) کندل ولوم کم (تریگر ۱)

شرط ورود: `2 ≤ stage ≤ 6` و `q1.bi > midConfirmBi`.

```
مارک:   prevT1Vol.isNaN() یا q1.v < prevT1Vol
        → lvBi = q1.bi ; lvH = q1.h ; lvL = q1.l

ابطال:  (BU: q1.l <= lvL) یا (BE: q1.h >= lvH)      →  lvBi = −1
تایید:  (BU: q1.c > lvH) یا (BE: q1.c < lvL)        →  stage = 4
```

**باکس = `lvL` تا `lvH` — سقف و کف خودِ کندل ولوم کم.**

```
یک‌بارمصرف:  inside = q1.l <= lvH && q1.h >= lvL
             inside و !lvTouched           →  lvTouched = true
             lvTouched و !inside و
             (BU: q1.c > lvH / BE: q1.c < lvL)  →  lvUsed = true
             اگر stage ∈ {4,5}  →  stage = 8
```

---

## ۹) کندل ولوم زیاد (تریگر ۲) و مسلح شدن

```
شروع اسکن:  stage == 4 و هم‌پوشانی با باکس ولوم کم  →  lvReEntered = true

مارک:   hvMarkFirstIncrease ⇒ volume > prevChartVol
        → hvH = high ; hvL = low ; stage = 5
ابطال:  (BU: low <= hvL) یا (BE: high >= hvH)       →  hvBi = −1 ; stage = 4
تایید:  (BU: close > hvH) یا (BE: close < hvL)      →  armSetup

stage == 6:
    (BU: close < hvL) یا (BE: close > hvH)  →  hvBi = −1 ; stage = 4
    وگرنه اگر قیمت به hvH/hvL برسد          →  entry ; stage = 7
```

### `armSetup`

```
eRef = (bull) ? hvH : hvL                      ← ورود
sl   = (bull) ? hvL − slBufTicks×mintick
              : hvH + slBufTicks×mintick
a0   = (bull) ? highestBE : lowestBUpin
a1   = midRef
haveFib = a0 و a1 معتبر و (bull: a0 > a1 / bear: a0 < a1)

اگر haveFib:
    R   = (bull) ? a0 − a1 : a1 − a0
    tp1 = a1 ± 0.382 R      tp2 = a1 ± 0.5 R      tpx = a1 ± 1.272 R
وگرنه:
    tpx = eRef ± minTPunits ;  tp1 = tp2 = NaN

سپس اصلاح کران‌ها:
    اگر فاصلهٔ tpx تا eRef < minTPunits  →  tpx = eRef ± minTPunits
    اگر tp1 نامعتبر یا آن‌طرف eRef       →  tp1 = eRef ± (tpx−eRef) × 0.382
    اگر tp2 نامعتبر یا آن‌طرف eRef       →  tp2 = eRef ± (tpx−eRef) × 0.5

stage = 6 ; hvConfBi = curBi ; broker.registerLimit(s, cd)
```

---

## ۱۰) کارگزار

```
registerLimit:  اگر pending != null یا open != null  →  ثبت نمی‌کند   ⚠ ۷-۱
qty = سرمایه × riskPct/100 / |ورود − sl|
      سپس min(qty, سرمایه × maxLeverage / قیمت)
      اگر roundQty  →  floor

onBar:
  ① پر شدن:  (BUY: cd.l <= price) یا (SELL: cd.h >= price)
  ② انقضا:   cd.bi − placedBi > maxBarsToFill (۱۵۰)  →  CANCELLED_EXPIRED
  ③ manageBar
  ④ refreshEquity

manageBar — ترتیب ثابت:
  slLvl = be ? entry : sl0
  ۱. حد ضرر      → closeAll ، پایان
  ۲. tpX         → closeAll ، پایان
  ۳. tp1 (اگر نزده) → addExit(33٪) ; be = true
  ۴. tp2 (اگر نزده و حجمی مانده) → addExit(33٪)

addExit:  pnl = (خروج − ورود) × حجم × contractSize     ⚠ بدون کارمزد (۷-۴)
```

---

## ۱۱) تجمیع تایم‌فریم‌ها

```
Agg.step(isNew, ...):
    اگر isNew:
        اگر periods >= 1 و cur != null:   cl = cur ; closedNow = true
        cur = کندل جدید ; periods++
    وگرنه:  cur.h = max(cur.h, h) ; cur.l = min(cur.l, l) ; cur.c = c ; cur.v += v
```

> ⚠ در نسخه‌های ۱٫۳.x شرط `periods >= 2` بود که اولین کندل کامل هر تایم‌فریم را دور
> می‌ریخت (اختلاف ۷-۳). از کامیت `e137a05` به `periods >= 1` تغییر کرد و اولین
> کندل هر تایم‌فریم پردازش می‌شود.

**ترتیب پردازش هر کندل بستهٔ چارت:**
`aggS.step → aggM.step → agg1.step → structureEngine → middleEngine → trigger1Engine → trigger2Engine`

---

## ۱۲) مرحله‌های ستاپ

| stage | معنی |
|---|---|
| ۰ | منتظر برخورد |
| ۱ | برخورد شد · منتظر کندل میانی |
| ۲ | تایید میانی ✔ |
| ۳ | اسکن ولوم کم |
| ۴ | ولوم کم ✔ · اسکن ولوم زیاد |
| ۵ | کندل ولوم زیاد مارک شد |
| ۶ | مسلح · منتظر برگشت |
| ۷ | داخل معامله |
| ۸ | تمام |
