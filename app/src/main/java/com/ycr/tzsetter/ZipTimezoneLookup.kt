package com.ycr.tzsetter

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.TimeZone

/**
 * 邮编 → IANA 时区 ID 查询器。
 * 首次使用时从 assets 加载三张表：
 *   - us_zip3.csv        : 944 条，ZIP3 → 时区
 *   - us_overrides.csv   : 3000+ 条，ZIP5 精确覆盖跨时区州
 *   - ca_fsa.csv         : 3600 条，加拿大 FSA 前缀 → 时区
 *
 * 加载后全部放在 HashMap，查询 O(1)。
 */
object ZipTimezoneLookup {

    @Volatile private var usZip3: Map<String, String>? = null
    @Volatile private var usOverrides: Map<String, String>? = null
    @Volatile private var caFsa: Map<String, String>? = null

    /** 确保数据已加载 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (usZip3 != null) return
        usZip3 = loadCsv(context, "us_zip3.csv")
        usOverrides = loadCsv(context, "us_overrides.csv")
        caFsa = loadCsv(context, "ca_fsa.csv")
    }

    private fun loadCsv(context: Context, name: String): Map<String, String> {
        val map = HashMap<String, String>(4096)
        context.assets.open(name).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    val comma = line.indexOf(',')
                    if (comma > 0) {
                        map[line.substring(0, comma)] = line.substring(comma + 1).trim()
                    }
                }
            }
        }
        return map
    }

    data class LookupResult(
        val zipInput: String,
        val normalized: String,
        val country: Country,
        val timezoneId: String,
        /** 该时区当前的 GMT 偏移描述，如 "GMT-08:00 PST" */
        val offsetDisplay: String,
        /** 是否通过精确 ZIP5 覆盖匹配（仅 US） */
        val matchedPrecise: Boolean,
    )

    enum class Country { US, CA, UNKNOWN }

    /**
     * 查询邮编对应时区。支持：
     *   - 美国 5 位数字邮编（如 "97058" 或 "97058-1234"）
     *   - 加拿大 6 位 FSA+LDU（如 "M5V 3L9" 或 "M5V3L9"），只看前 3 位
     *
     * @return null 表示无法识别
     */
    fun lookup(context: Context, raw: String): LookupResult? {
        ensureLoaded(context)
        val cleaned = raw.trim().uppercase().replace("\\s|-".toRegex(), "")
        if (cleaned.isEmpty()) return null

        // 判断国家：全数字 → US；首位字母 → CA
        return when {
            cleaned.all { it.isDigit() } -> lookupUs(cleaned)
            cleaned[0].isLetter() -> lookupCa(cleaned)
            else -> null
        }
    }

    private fun lookupUs(digits: String): LookupResult? {
        if (digits.length < 3) return null
        val zip5 = digits.take(5).padEnd(5, '0')
        val zip3 = zip5.take(3)

        // 先查 ZIP5 精确覆盖
        val precise = usOverrides?.get(zip5)
        if (precise != null) {
            return buildResult(digits, zip5, Country.US, precise, matchedPrecise = true)
        }

        // 再查 ZIP3
        val tz = usZip3?.get(zip3) ?: return null
        return buildResult(digits, zip5, Country.US, tz, matchedPrecise = false)
    }

    private fun lookupCa(input: String): LookupResult? {
        if (input.length < 3) return null
        val fsa = input.take(3)
        val tz = caFsa?.get(fsa) ?: return null
        return buildResult(input, fsa, Country.CA, tz, matchedPrecise = true)
    }

    private fun buildResult(
        raw: String, normalized: String, country: Country,
        tzId: String, matchedPrecise: Boolean
    ): LookupResult {
        val tz = TimeZone.getTimeZone(tzId)
        val offsetMs = tz.getOffset(System.currentTimeMillis())
        val hours = offsetMs / 3_600_000
        val minutes = kotlin.math.abs((offsetMs / 60_000) % 60)
        val sign = if (offsetMs >= 0) "+" else "-"
        val absHours = kotlin.math.abs(hours)
        val shortName = tz.getDisplayName(tz.inDaylightTime(java.util.Date()), TimeZone.SHORT)
        val offsetDisplay = "GMT%s%02d:%02d  %s".format(sign, absHours, minutes, shortName)
        return LookupResult(raw, normalized, country, tzId, offsetDisplay, matchedPrecise)
    }
}
