package com.magic3d.gcalsearchadd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * חיפוש מיקומים חינמי לגמרי דרך OpenStreetMap Nominatim - בלי מפתח API ובלי חיוב (billing).
 * שפת התוצאות נקבעת אוטומטית לפי שפת המכשיר (עברית/אנגלית/כל שפה אחרת).
 *
 * חשוב: מדיניות השימוש של Nominatim מגבילה לכ-בקשה אחת בשנייה - ה-debounce
 * ב-AddEventActivity (600ms) שומר על קצב תקין.
 */
object NominatimClient {

    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val lang = Locale.getDefault().language
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://nominatim.openstreetmap.org/search" +
                "?q=$encodedQuery&format=json&addressdetails=0&limit=6&accept-language=$lang"

            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            // Nominatim מחייב User-Agent מזוהה לפי מדיניות השימוש שלהם
            connection.setRequestProperty("User-Agent", "EventSpotAndroidApp/1.0 (info@zoom-out.co.il)")
            connection.connectTimeout = 6000
            connection.readTimeout = 6000

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val jsonArray = JSONArray(responseText)
            val results = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(obj.getString("display_name"))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
