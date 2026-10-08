// ریشهٔ پروژهٔ GoldPin — استراتژی PinReady روی طلا (XAUUSD)
// ماژول‌ها:
//   :engine → موتور استراتژی، Kotlin خالص (بدون اندروید) → قابل تست روی JVM
//   :cli    → هارنس خط فرمان برای بک‌تست روی فایل CSV (اجرای موتور بدون گوشی)
//   :app    → اپلیکیشن اندروید (چارت، بک‌تست، لایو کاغذی، ذخیره/بازیابی)

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    kotlin("jvm") version "1.9.24" apply false
}
