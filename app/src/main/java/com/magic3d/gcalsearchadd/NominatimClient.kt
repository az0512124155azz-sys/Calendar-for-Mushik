package com.magic3d.gcalsearchadd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 *
 * הערה לגבי איכות התוצאות: זהו מנוע חיפוש כתובות/מפות (כמו Google Maps), לא מנוע חיפוש
 * שמות עסקים חופשי כמו Google Places - לכן הוא הכי מדויק עם רחוב+עיר, ופחות עם שמות מקומות ספציפיים.
 */
object NominatimClient {

    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val lang = normalizeLanguageCode(Locale.getDefault().language)
            val acceptLanguage = if (lang == "en") "en" else "$lang,en"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://nominatim.openstreetmap.org/search" +
                "?q=$encodedQuery&format=json&addressdetails=1&limit=6&accept-language=$acceptLanguage"

            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "EventSpotAndroidApp/1.0 (info@zoom-out.co.il)")
            connection.connectTimeout = 4000
            connection.readTimeout = 4000

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val jsonArray = JSONArray(responseText)
            val results = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                results.add(buildConciseLabel(jsonArray.getJSONObject(i)))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * ל-Hebrew, Java/Android עדיין מחזירים את הקוד הישן "iw" במקום "he" הסטנדרטי -
     * ובלי התיקון הזה, Nominatim לא מזהה את השפה ומחזיר תוצאות מעורבות (כולל ערבית).
     */
    private fun normalizeLanguageCode(code: String): String {
        return if (code == "iw") "he" else code
    }

    /**
     * בונה תווית קצרה וקריאה ("רחוב ומספר, עיר") במקום הכתובת הארוכה והמלאה
     * שכוללת מחוז, מיקוד ומדינה שאף אחד לא צריך לראות ברשימת הצעות.
     */
    private fun buildConciseLabel(obj: JSONObject): String {
        val address = obj.optJSONObject("address")
        if (address != null) {
            val road = address.optString("road", "")
            val houseNumber = address.optString("house_number", "")
            val city = address.optString(
                "city",
                address.optString(
                    "town",
                    address.optString("village", address.optString("municipality", ""))
                )
            )

            val roadPart = when {
                road.isNotBlank() && houseNumber.isNotBlank() -> "$road $houseNumber"
                road.isNotBlank() -> road
                else -> ""
            }

            val parts = listOfNotNull(
                roadPart.takeIf { it.isNotBlank() },
                city.takeIf { it.isNotBlank() }
            )
            if (parts.isNotEmpty()) return parts.joinToString(", ")
        }

        // גיבוי - אם אין פירוט כתובת מובנה, מקצרים את display_name לשני החלקים הראשונים בלבד
        val displayName = obj.optString("display_name", "")
        val segments = displayName.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return if (segments.size > 2) segments.take(2).joinToString(", ") else displayName
    }
}
