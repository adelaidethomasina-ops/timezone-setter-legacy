package com.ycr.tzsetter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 邮编-经纬度缓存。使用 SharedPreferences 存储，key 是 "country:zip"。
 * 查过一次的邮编永久缓存，不需要重新请求。
 */
object LocationCache {

    private const val PREF = "zip_geo_cache"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(context: Context, country: String, zip: String): NetworkDataSource.PlaceInfo? {
        val key = "${country.lowercase()}:$zip"
        val raw = prefs(context).getString(key, null) ?: return null
        return try {
            val j = JSONObject(raw)
            NetworkDataSource.PlaceInfo(
                zip = j.getString("zip"),
                country = j.getString("country"),
                placeName = j.getString("placeName"),
                state = j.getString("state"),
                stateAbbrev = j.getString("stateAbbrev"),
                latitude = j.getDouble("latitude"),
                longitude = j.getDouble("longitude"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun put(context: Context, country: String, zip: String, info: NetworkDataSource.PlaceInfo) {
        val key = "${country.lowercase()}:$zip"
        val j = JSONObject().apply {
            put("zip", info.zip)
            put("country", info.country)
            put("placeName", info.placeName)
            put("state", info.state)
            put("stateAbbrev", info.stateAbbrev)
            put("latitude", info.latitude)
            put("longitude", info.longitude)
        }
        prefs(context).edit().putString(key, j.toString()).apply()
    }

    /** 清除所有缓存（设置里可能用到） */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun size(context: Context): Int = prefs(context).all.size
}
