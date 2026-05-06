package com.ycr.tzsetter

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通过 Zippopotam.us 免费 API 查询邮编对应的经纬度和城市名。
 *
 * US:  https://api.zippopotam.us/us/97058
 * CA:  https://api.zippopotam.us/ca/M5V      (只接受 FSA 前3位)
 *
 * 返回样例:
 * {
 *   "post code": "97058",
 *   "country": "United States",
 *   "country abbreviation": "US",
 *   "places": [{
 *     "place name": "The Dalles",
 *     "longitude": "-121.1709",
 *     "state": "Oregon",
 *     "state abbreviation": "OR",
 *     "latitude": "45.5936"
 *   }]
 * }
 */
object NetworkDataSource {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class PlaceInfo(
        val zip: String,
        val country: String,
        val placeName: String,
        val state: String,
        val stateAbbrev: String,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * 阻塞调用（在 IO 线程执行）。
     * @param country "us" or "ca"
     * @param zip US 用 5 位数字；CA 用 FSA 前3位（例 M5V）
     */
    @Throws(Exception::class)
    fun fetch(country: String, zip: String): PlaceInfo? {
        val url = "https://api.zippopotam.us/${country.lowercase()}/$zip"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 404 = 没这个邮编
                return null
            }
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val places = json.optJSONArray("places") ?: return null
            if (places.length() == 0) return null
            val p = places.getJSONObject(0)
            return PlaceInfo(
                zip = json.optString("post code", zip),
                country = json.optString("country", country),
                placeName = p.optString("place name", ""),
                state = p.optString("state", ""),
                stateAbbrev = p.optString("state abbreviation", ""),
                latitude = p.getString("latitude").toDouble(),
                longitude = p.getString("longitude").toDouble(),
            )
        }
    }
}
