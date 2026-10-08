# ۱۲ · آزمون‌ها

## ۱) اجرا
```bash
source tools/env.sh
gradle :engine:test                 # تست‌های موتور (JVM خالص)
gradle :app:testReleaseUnitTest     # تست‌های اپ (Robolectric)
gradle :engine:test :app:testReleaseUnitTest :app:assembleRelease   # همه + ساخت APK
```
گزارش‌ها: `engine/build/reports/tests/test/index.html` و `app/build/reports/tests/testReleaseUnitTest/index.html`

## ۲) فهرست کامل تست‌ها (۲۸ تست)
### موتور — `ZoneTest` (۵)
| تست | چه چیزی را تضمین می‌کند |
|---|---|
| قله/دره و باند ناحیهٔ ۱ | مرز پایین = بین ردیف ۱۲۵ و ۳۲۵ (مثال عددی کارفرما) |
| ناحیهٔ ۲ بعد از دره | مرز بالا = بین ۲۱۷ و ۳۹۰ |
| بی‌نقشی POC | POC (۶۷۹) بیرون ناحیه‌ها می‌افتد |
| قرینهٔ نزولی | برای کندل نزولی همان نواحی آینه‌ای به دست می‌آید |
| رد شدن ناحیه | اگر کلوز داخل/روی ناحیه باشد، در فهرست «ردشده» می‌آید نه «معتبر» |

### موتور — `StoreTest` (۲)
| تست | تضمین |
|---|---|
| رفت‌وبرگشت ذخیره/بازیابی | همهٔ فیلدها (کندل‌ها، باکس‌ها، سفارش‌ها، معاملات، پوزیشن) برمی‌گردند |
| ادامه پس از بازیابی | ذخیره ⇒ بازیابی ⇒ فید با کندل‌های بیشتر = نتیجهٔ یکسان با اجرای پیوسته |

### موتور — `LogPerfTest` (۱)
| تست | تضمین |
|---|---|
| هزینهٔ لاگر | روی ۱۸۲٬۷۴۷ کندل سرباره ≈ ۷ms و **نتیجهٔ بک‌تست با/بدون سینک یکسان** (۲۵۵ سفارش/۲۵۵ معامله) |

### اپ — `AppSmokeTest` (۵)
راه‌اندازی اکتیویتی، بارگذاری نمونه، اجرای موتور، وجود تب‌ها، پاسخ نداشتن به کرش.

### اپ — `LoggerAlertTest` (۸) ← **جدید در ۱٫۱**
| تست | تضمین |
|---|---|
| `logger is off by default` | در حالت پیش‌فرض **هیچ خطی** ثبت نمی‌شود |
| `logger writes both md and txt` | فایل `.md` با جدول Markdown و فایل `.txt` هر دو نوشته می‌شوند؛ فارسی سالم است |
| `level filter and mute work` | سطح `WARN` جلوی خطوط `INFO` را می‌گیرد؛ دستهٔ خاموش خط تولید نمی‌کند |
| `engine and broker feed the logger` | قلاب‌ها واقعاً وصل می‌شوند و اجرای موتور لاگ تولید می‌کند |
| `alerts fire, record history and call banner` | هشدار در تاریخچه ثبت و بنر صدا زده می‌شود؛ نوع خاموش = بدون بنر |
| `pinch gesture zooms both time and price` | پینچ دو انگشتی **هم زمان و هم قیمت** را زوم می‌کند، مقیاس خودکار خاموش می‌شود، جابه‌جایی و بازنشانی درست کار می‌کند |
| `price axis drag disables auto and double tap restores` | کشیدن روی محور قیمت = مقیاس دستی، `resetPrice()` = خودکار |
| `fullscreen mode hides header and tabs` | در تمام‌صفحهٔ `MainActivity` هدر و تب‌ها `GONE` می‌شوند و با BACK برمی‌گردند |

### اپ — `FreezeAndLayoutTest` (۷) ← **جدید در ۱٫۲**
| تست | تضمین |
|---|---|
| `flat data never freezes the chart draw` | دادهٔ تخت (دامنهٔ صفر) دیگر حلقهٔ رسم را بی‌پایان نمی‌کند |
| `extreme pinch then draw stays fast` | ۱۲۰ پینچ شدید پیاپی + رسم، همه زیر حد مجاز زمان |
| `degenerate view state is repaired before draw` | مقادیر NaN/صفر پیش از رسم ترمیم می‌شوند |
| `toolbar and timeframe row sit above the chart without overlap` | نوارها خواهرِ چارت‌اند (نه شناور روی آن) و چارت وزن ۱ دارد |
| `fullscreen is off by default` | تمام‌صفحه اختیاری است |
| `reports tab and log menu open quickly` | تب گزارش و منوی لاگ با ۱۵٬۰۰۰ خط لاگ سریع باز می‌شوند |
| `frequent ui notifications are coalesced` | ۲۰۰ اطلاع‌رسانی پیاپی ارزان است و شنونده‌ها انباشته نمی‌شوند |

## ۳) سیاست تست
* هر باگ که یک بار رخ داد، یک تست می‌گیرد (مثال: باگ «نشست لاگ پس از پاک شدن حافظه» ⇒ ترمیم در `Log.add`).
* هر تغییر استراتژی باید تست عددی داشته باشد (مثل `ZoneTest`).
* هیچ تستی نباید به شبکه نیاز داشته باشد؛ داده از دارایی داخلی یا فایل CSV می‌آید.
* تست کارایی (`LogPerfTest`) اگر CSV موجود نباشد خودش را skip می‌کند تا بیلد در هر محیطی سبز بماند.
