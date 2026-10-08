# ۰۲ · مرجع الگوریتم و تطابق با Pine

این سند «پُل» بین سند استراتژی و کد است: هر مرحله، نام تابع در کد Kotlin و معادل آن در Pine را می‌دهد.

## ۱) جریان پردازش هر کندل تریگر ۲ (کلوز)
```
Engine.processClosed(cd, i, prev)
 ├─ ۱) تجمیع تایم‌فریم‌ها:  aggS.step(...) , aggM.step(...) , agg1.step(...)   ← معادل f_step در Pine
 ├─ ۲) if (aggS.closedNow) structureEngine(aggS.cl)      ← ⑩ موتور ساختار
 ├─ ۳) if (aggM.closedNow) middleEngine(aggM.cl)         ← ⑪ موتور میانی
 ├─ ۴) if (agg1.closedNow) trigger1Engine(agg1.cl)       ← ⑫ ناحیهٔ ولوم کم
 ├─ ۵) trigger2Engine(cd)                                ← ⑬ ولوم زیاد/ورود/مدیریت
 ├─ ۶) microS (ریزکندل‌های داخل کندل ساختار) را به‌روز کن
 ├─ ۷) چرخهٔ عمر باکس‌های ناحیه (حذف با کلوز از سمت دور)
 └─ ۸) barCommitHook(cd)  → کارگزار کاغذی (پر شدن سفارش/مدیریت پوزیشن)
```
ترتیب دقیقاً مثل اسکریپت Pine است (ساختار → میانی → تریگر۱ → تریگر۲).

## ۲) جدول تطابق توابع
| مرحله | Kotlin (engine) | Pine |
|---|---|---|
| تجمیع تایم‌فریم | `Agg.step` | `f_step(agg, isNew)` |
| کندل مهم پایین/بالا | `pickLow` / `pickHigh` | `f_pickLow` / `f_pickHigh` |
| Ready + تناوب | `structureEngine` + `bestReady`/`hasBetterActive` | `f_bestReady` / `f_hasBetterActive` |
| روند | `structureEngine` (بخش trend) | همان بخش ⑩ |
| پروفایل حجم | `VolumeProfile.build` | `f_buildProfile` |
| هموارسازی | `VolumeProfile.smoothRows` | `f_smoothRows` |
| **ناحیه‌ها (v2)** | `VolumeProfile.zones(...)` | `f_zones(Profile p, rows, bull, cClose, minRows)` |
| تایید میانی | `middleEngine` | ⑪ `MIDDLE CLOSE ENGINE` |
| ناحیهٔ ولوم کم | `trigger1Engine` | ⑫ `TRIGGER-1 CLOSE ENGINE` |
| ولوم زیاد + ورود | `trigger2Engine` + `armSetup` | ⑬ `TRIGGER-2 (CHART) ENGINE` |
| حجم‌گذاری | `PaperBroker.qtyFor` | `f_qty` |
| مدیریت سفارش/خروج | `PaperBroker` (registerLimit/manageBar/closeAll) | ⑮ `STRATEGY ORDER MANAGEMENT` |
| ذخیرهٔ وضعیت | `Store.save/load` | (ندارد؛ افزودهٔ اپ) |

## ۳) شبه‌کد الگوریتم ناحیه (نسخهٔ ۲) — عیناً همان که در کد است
```
zones(profile, rows, bull, close, minRows):
    out = [], rej = []
    stepDir = bull ? +1 : -1
    i = bull ? 0 : rows-1
    while guard++ < 4*rows  and  out.size < 2:
        # ① اولین قلهٔ محلی در جهت حرکت
        pk = -1 ; j = i
        while j+stepDir در بازه  و  vol[j+stepDir] > vol[j]:  j += stepDir
        if j != i: pk = j
        if pk < 0: break
        # ② اولین درهٔ محلی بعد از قله
        tr = -1 ; k = pk
        while k+stepDir در بازه  و  vol[k+stepDir] < vol[k]:  k += stepDir
        if k != pk: tr = k
        if tr < 0: break
        # ③ باند = از مرز پایین قله تا مرز بالای دره
        loIdx = min(pk, tr) ; hiIdx = max(pk, tr)
        zBot  = lo + loIdx*step
        zTop  = lo + (hiIdx+1)*step
        closeOK = bull ? (zTop < close) : (zBot > close)
        if zTop > zBot and (hiIdx-loIdx+1) >= minRows:
            if closeOK: out.push(Zone(zTop, zBot))
            else:       rej.push(Zone(zTop, zBot))
        i = tr + stepDir
    return out, rej
```
**نکته‌ها**
* `step = (high − low) / rows` دامنهٔ ردیف‌ها؛ `lo` پایین‌ترین قیمت ریزکندل‌های داخل کندل ساختار است.
* شرط اعتبار روی **کلوز کندل ساختار** بررسی می‌شود، نه کلوز ریزکندل‌ها.
* `POC` و `Value Area` محاسبه می‌شوند اما **هیچ نقشی در ساخت ناحیه ندارند** (فقط نمایش/مقایسه).

## ۴) اجرای خط فرمان (برای صحت‌سنجی مستقل از گوشی)
```bash
gradle :cli:installDist
./cli/build/install/cli/bin/cli --csv data/xauusd-m1.csv --vol real --rows 24 --smooth 1 --mode 1
```
خروجی: تعداد سفارش/معامله، نرخ برد، ضریب سود، میانگین R، بیشترین افت، آمار موتور
(ستاپ/برخورد/میانی/ولوم‌کم/ولوم‌زیاد/ورود/پایان)، آمار باکس‌ها و ۸ معاملهٔ آخر.

## ۵) حجم‌گذاری (f_qty)
```
risk = |entry − sl|
q = (حالت ریسک ثابت)  equity × riskPct/100 / risk
    (حالت درصد سرمایه) equity × qtyPct/100 / entry
cap = equity × maxLeverage / entry
q = min(q, cap) ;  اگر roundQty → floor(q) ;  q = max(q, 0)
```
سه پلهٔ خروج از همین `q`: `q1 = 0.33q` ، `q2 = 0.33q` ، `q3 = 0.34q`.

## ۶) قاعدهٔ حذف ناحیه روی چارت
```
برای هر باکس فعال:
    حذف ⇐ (جهت صعودی: close < zBot)  یا  (جهت نزولی: close > zTop)
```
بررسی با کندل‌های **تریگر ۲** (چارت) انجام می‌شود؛ کندل تایم‌فریم میانی یا تریگر۱ ناحیه را حذف نمی‌کند.
تعداد باکس‌ها با `maxZoneBoxes` محدود است؛ در صورت سرریز، قدیمی‌ترین باکس وضعیت `CAP` می‌گیرد
(در فایل ذخیره‌سازی ثبت می‌شود تا در بازیابی همان‌طور برگردد).
