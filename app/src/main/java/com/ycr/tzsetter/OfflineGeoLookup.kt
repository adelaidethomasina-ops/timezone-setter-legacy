package com.ycr.tzsetter

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 离线邮编 → 经纬度查询器。
 *
 * 数据源：GeoNames 官方（US 41488 条 + CA 1653 条）。
 * 存储：assets/us_geo.csv.gz, assets/ca_geo.csv.gz，gzip 压缩约 550KB。
 * 首次访问时解压到内存 HashMap，查询 O(1)。
 *
 * CSV 行格式：zip|lat|lng|city|state_abbrev
 */
object OfflineGeoLookup {

    data class GeoEntry(
        val zip: String,
        val latitude: Double,
        val longitude: Double,
        val city: String,
        val stateAbbrev: String,
    )

    @Volatile private var usMap: Map<String, GeoEntry>? = null
    @Volatile private var caMap: Map<String, GeoEntry>? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (usMap != null) return
        usMap = loadCompressed(context, "us_geo.csv")
        caMap = loadCompressed(context, "ca_geo.csv")
    }

    private fun loadCompressed(context: Context, assetName: String): Map<String, GeoEntry> {
        val map = HashMap<String, GeoEntry>(50_000)
        context.assets.open(assetName).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                        val parts = line.split("|")
                        if (parts.size >= 5) {
                            val zip = parts[0]
                            val lat = parts[1].toDoubleOrNull() ?: return@forEachLine
                            val lng = parts[2].toDoubleOrNull() ?: return@forEachLine
                            val city = parts[3]
                            val state = parts[4]
                            map[zip] = GeoEntry(zip, lat, lng, city, state)
                        }
                    }
                }
            }
        return map
    }

    /**
     * 查询。
     * @param country "us" or "ca"
     * @param zip US 用 5 位数字（会自动取前 5 位），CA 用 FSA（前 3 位，大写）
     */
    fun lookup(context: Context, country: String, zip: String): GeoEntry? {
        ensureLoaded(context)
        val normalized = zip.uppercase().replace("\\s|-".toRegex(), "")
        return when (country.lowercase()) {
            "us" -> usMap?.get(normalized.take(5))
            "ca" -> caMap?.get(normalized.take(3))
            else -> null
        }
    }

    fun totalEntries(context: Context): Int {
        ensureLoaded(context)
        return (usMap?.size ?: 0) + (caMap?.size ?: 0)
    }
}
