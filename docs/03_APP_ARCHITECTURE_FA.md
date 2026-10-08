# ۰۳ · معماری اپ اندروید

## ۱) ماژول‌ها
| ماژول | نوع | وابستگی | نقش |
|---|---|---|---|
| `:engine` | کتابخانهٔ JVM خالص | هیچ | استراتژی، کارگزار کاغذی، گزارش، ذخیره‌سازی |
| `:cli` | اجرایی JVM | `:engine` | بک‌تست خط فرمان روی CSV (صحت‌سنجی مستقل از گوشی) |
| `:app` | اندروید (minSdk 24، target 34) | `:engine` | رابط، چارت، فید، سرویس لایو، لاگر، هشدار |

`:engine` هیچ `import android.*` ندارد. به همین دلیل تست‌های الگوریتمی سریع‌اند و همان کد روی
سرور/خط فرمان هم اجرا می‌شود.

## ۲) نمای کلاس‌های اپ
```
MainActivity  ── نمایش ۵ تب، تمام‌صفحه، دیالوگ‌ها، منوی زمینه، بنر هشدار
 ├─ AppState.instance           (Singleton، منبع یکتای حقیقت)
 │   ├─ candles: ArrayList<Candle>        کندل‌های تریگر۲ (چارت)
 │   ├─ engine: Engine                     موتور استراتژی
 │   ├─ broker: PaperBroker                کارگزار کاغذی
 │   ├─ cfg: EngineCfg                     پارامترها
 │   ├─ io: Executor                       کارهای پس‌زمینه
 │   └─ wireLogging()                      جفت‌سازی لاگر/هشدار با موتور
 ├─ ui/ChartView                 چارت کندل + نواحی + ژست‌ها + SetupOverlay
 ├─ ui/EquityChartView           منحنی سرمایه (تب گزارش)
 ├─ data/Feed                    Yahoo / gold-api / Swissquote / CoinGecko + CSV
 ├─ data/Storage                 فایل gz داخلی، SAF (انتخاب مسیر)، گزارش
 ├─ svc/EngineService            سرویس پیش‌زمینهٔ ارزیابی زنده (نوتیفیکیشن دائمی)
 ├─ util/Log                     لاگر (md + txt)
 ├─ util/Alerts                  هشدار + نوتیفیکیشن + تاریخچه
 ├─ util/Ui , Palette , Fa       ابزار UI، رنگ‌ها، اعداد فارسی
```

## ۳) چرخهٔ عمر
```
onCreate : UI → AppState.init(ctx) → بارگذاری خودکار وضعیت → راه‌اندازی نما
onResume : updateLiveBadge ، بازآوری PollFg (اگر سرویس روشن باشد)
onPause  : توقف PollFg ، ذخیرهٔ خودکار (اگر روشن باشد)
onDestroy: Log.i پایان نشست ، flush
BACK     : اگر تمام‌صفحه → خروج از تمام‌صفحه؛ وگرنه خروج از تب
```
`AppState.init(ctx)` کارهای زیر را انجام می‌دهد:
1. `Log.init(ctx, "1.3")` (خواندن تنظیمات لاگر از SharedPreferences با کلید `log_enabled`، پیش‌فرض **خاموش**)
2. `Alerts.init(ctx)` (کانال نوتیفیکیشن `goldpin_alerts_v1`)
3. خواندن `auto_fullscreen` (پیش‌فرض **خاموش**؛ مهاجرت نسخهٔ ۱٫۱ هم یک‌بار آن را خاموش می‌کند)
4. `wireLogging()` → وصل کردن `engine.logSink`، `broker.logSink`، `engine.alertSink`، `broker.alertSink`

## ۴) جریان داده (بک‌تست و لایو)
```
[دانلود تاریخچه]  Feed.downloadBase()  →  کندل‌های پایه M1
        │
        ▼
  AppState.setCandles()  →  Agg (تجمیع به تایم‌فریم چارت)  →  Engine.feed()
        │                                                      │
        │                                   barCommitHook ────► PaperBroker.onBar()
        ▼
   ChartView.refresh()  ◄── notifyUi()  ◄── listener ها

[لایو]  EngineService (هر livePollMs)  →  Feed.tailRecent() + Feed.spotPrice()
        →  AppState.updateLiveOnce()  →  mergeTail()  →  Engine.feed(دنباله)  →  سفارش‌های نو
        →  Alerts.fire (نوتیفیکیشن) + Log (ثبت) + notifyUi (تازه‌سازی نما)
```

## ۵) قلاب‌های موتور → اپ
| قلاب | نوع | چه‌وقت صدا زده می‌شود |
|---|---|---|
| `engine.barCommitHook` | `(Candle)->Unit` | پایان پردازش هر کندل بستهٔ تریگر۲ |
| `engine.logSink` | `(cat,msg,data)->Unit` | هر رخداد قابل‌ثبت موتور |
| `engine.alertSink` | `(kind,title,body)->Unit` | هر رخداد هشداردهنده |
| `broker.logSink` / `broker.alertSink` | همان | سفارش‌ها، پر شدن‌ها، پله‌های خروج |
`wireLogging()` اگر لاگر خاموش باشد قلاب‌های لاگ را `null` می‌کند ⇒ **هزینهٔ صفر** وقتی خاموش است.

## ۶) نخ‌بندی (Threading)
| کار | نخ |
|---|---|
| محاسبات موتور | نخ UI (سریع است: ۱۸۲٬۷۴۷ کندل در چند ثانیه) یا نخ IO در دانلود |
| دانلود فید | `AppState.io` (تک‌رشته‌ای) → نتیجه با `main.post` به UI |
| سرویس لایو | `Handler` روی نخ اصلی سرویس + `wakeLock` پارسیال + `state.io` برای ذخیره |
| نوشتن لاگ | همان نخ فراخوان (BufferedWriter با flush دوره‌ای؛ سبک) |
هیچ کار سنگینی روی نخ UI انجام نمی‌شود و هیچ عملیات شبکه‌ای روی نخ UI نیست.

## ۷) نقشهٔ فایل‌های سورس
| فایل | خطوط (تقریبی) | نقش |
|---|---|---|
| `engine/.../Engine.kt` | ~۱۵۰۰ | چهار موتور + چرخهٔ باکس + آمار |
| `engine/.../VolumeProfile.kt` | ~۲۵۰ | پروفایل حجم + الگوریتم v2 |
| `engine/.../PaperBroker.kt` | ~۵۰۰ | سفارش، پوزیشن، پله‌ها، سر‌به‌سر، لاگ |
| `engine/.../Store.kt` | ~۴۵۰ | سریال‌سازی/بازسازی کامل |
| `engine/.../Reports.kt` | ~۳۰۰ | گزارش دوره‌ای + منحنی سرمایه |
| `engine/.../Types.kt` | ~۳۵۰ | مدل‌ها + `AlertKind` |
| `app/.../MainActivity.kt` | ~۱۴۰۰ | UI کامل |
| `app/.../ui/ChartView.kt` | ~۱۱۰۰ | چارت + ژست‌ها |
| `app/.../App.kt` | ~۹۰۰ | AppState |
| `app/.../util/Log.kt` | ~۳۴۰ | لاگر |
| `app/.../util/Alerts.kt` | ~۳۲۰ | هشدار |

## ۸) قواعد معماری (نباید نقض شوند)
1. `:engine` نباید به اندروید وابسته شود.
2. هیچ کتابخانهٔ بیرونی اضافه نشود (نه AndroidX، نه Gson، نه MPAndroidChart).
3. هر رخداد مهم موتور باید هم لاگ و هم (در صورت هشدار بودن) alert بدهد.
4. هر تغییر رفتار = یک ورودی در `13_CHANGELOG_FA.md`.
5. مقدار پیش‌فرض هر قابلیت «محافظه‌کارانه» است (لاگر خاموش، هشدار خاموش، مخصوصاً در بک‌تست).
