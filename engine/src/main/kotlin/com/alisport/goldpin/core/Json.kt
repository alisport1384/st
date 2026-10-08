package com.alisport.goldpin.core

// ═══════════════════════════════════════════════════════════════════════════════
//  JSON کوچک و خودکفا (بدون وابستگی) — برای ذخیره/بازیابی دقیق وضعیت
//  مقادیر پشتیبانی‌شده: null, Boolean, Double, Long/Int, String, List, Map
// ═══════════════════════════════════════════════════════════════════════════════
object Json {

    fun write(v: Any?): String {
        val sb = StringBuilder(1 shl 16)
        writeTo(sb, v)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(fmt(v))
            is Float -> writeTo(sb, v.toDouble())
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is String -> str(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString()); sb.append(':'); writeTo(sb, vv)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, e)
                }
                sb.append(']')
            }
            is DoubleArray -> {
                sb.append('[')
                for ((i, e) in v.withIndex()) { if (i > 0) sb.append(','); writeTo(sb, e) }
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun fmt(d: Double): String {
        if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value()
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                ws()
                val k = str()
                ws()
                if (i < s.length && s[i] == ':') i++
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return m
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (i < s.length) {
                l.add(value())
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                break
            }
            return l
        }
        fun str(): String {
            val sb = StringBuilder()
            if (i < s.length && s[i] == '"') i++
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            // رشتهٔ بریده (فایل ذخیرهٔ ناتمام) نباید استثنا بدهد
                            if (i + 5 <= s.length) {
                                val hex = s.substring(i + 1, i + 5)
                                val cp = hex.toIntOrNull(16)
                                if (cp != null) { sb.append(cp.toChar()); i += 4 } else sb.append('u')
                            } else sb.append('u')
                        }
                        else -> sb.append(e)
                    }
                    i++
                } else if (c == '"') { i++; break } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) { i++; return 0.0 }
            return if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else t.toLong()
        }
    }
}

// ── کمکی‌های دسترسی ایمن ───────────────────────────────────────────────────────
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()

fun Any?.asD(): Double = when (this) {
    null -> Double.NaN
    is Double -> this
    is Long -> this.toDouble()
    is Int -> this.toDouble()
    is String -> this.toDoubleOrNull() ?: Double.NaN
    else -> Double.NaN
}

fun Any?.asL(): Long = when (this) {
    null -> 0L
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is String -> this.toLongOrNull() ?: 0L
    else -> 0L
}

fun Any?.asI(): Int = asL().toInt()
fun Any?.asB(): Boolean = when (this) {
    null -> false
    is Boolean -> this
    is Long -> this != 0L
    is Double -> this != 0.0
    else -> false
}

fun Any?.asS(): String = this?.toString() ?: ""
