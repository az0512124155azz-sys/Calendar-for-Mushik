package com.magic3d.gcalsearchadd

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.EventDateTime
import java.text.SimpleDateFormat
import java.util.*

/**
 * עוטף את כל הקריאות ל-Google Calendar API.
 * ה-credential מחובר לחשבון שנבחר בהתחברות (GoogleSignIn).
 */
class CalendarHelper(context: android.content.Context) {

    // Scopes נדרשים לפי המסמך: קריאה וכתיבה מלאה
    val scopes = listOf(CalendarScopes.CALENDAR)

    private val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
        context, scopes
    )

    private val service: Calendar by lazy {
        Calendar.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("GCal Search & Add").build()
    }

    fun setAccount(accountName: String) {
        credential.selectedAccountName = accountName
    }

    private val dayFormat = SimpleDateFormat("dd/MM/yyyy", Locale("iw", "IL"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("iw", "IL"))

    /**
     * שליפת כל האירועים לתאריך נתון (dd/MM/yyyy) או לפי מילת מפתח.
     */
    fun searchEvents(query: String): List<EventItem> {
        val results = mutableListOf<EventItem>()

        // ננסה לפרש כתאריך; אם נכשל, נחפש כמילת מפתח בטווח רחב (שנה קדימה/אחורה)
        val parsedDate = try {
            dayFormat.parse(query)
        } catch (e: Exception) {
            null
        }

        val timeMin: com.google.api.client.util.DateTime
        val timeMax: com.google.api.client.util.DateTime
        val request = service.events().list("primary")

        if (parsedDate != null) {
            val startCal = java.util.Calendar.getInstance()
            startCal.time = parsedDate
            startCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            startCal.set(java.util.Calendar.MINUTE, 0)
            startCal.set(java.util.Calendar.SECOND, 0)

            val endCal = startCal.clone() as java.util.Calendar
            endCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            endCal.set(java.util.Calendar.MINUTE, 59)
            endCal.set(java.util.Calendar.SECOND, 59)

            timeMin = com.google.api.client.util.DateTime(startCal.time)
            timeMax = com.google.api.client.util.DateTime(endCal.time)

            request.timeMin = timeMin
            request.timeMax = timeMax
        } else {
            // חיפוש כמילת מפתח - Google Calendar API תומך בפרמטר q
            request.q = query
        }

        request.singleEvents = true
        request.orderBy = "startTime"

        val eventsResult = request.execute()
        val items = eventsResult.items ?: emptyList()

        for (event in items) {
            val start = event.start?.dateTime ?: event.start?.date
            val end = event.end?.dateTime ?: event.end?.date

            val startStr = start?.let { formatEventTime(it) } ?: ""
            val endStr = end?.let { formatEventTime(it) } ?: ""

            results.add(
                EventItem(
                    id = event.id ?: "",
                    title = event.summary ?: "(ללא כותרת)",
                    timeRange = if (startStr.isNotEmpty() && endStr.isNotEmpty())
                        "$startStr - $endStr" else "כל היום"
                )
            )
        }
        return results
    }

    private fun formatEventTime(dateTime: com.google.api.client.util.DateTime): String {
        return try {
            timeFormat.format(Date(dateTime.value))
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * הוספת אירוע חדש.
     * לוגיקת ברירת מחדל לפי המסמך: אם לא הוזנו שעות - 14:00 עד 23:59.
     */
    fun addEvent(title: String, dateStr: String, startTimeStr: String?, endTimeStr: String?) {
        val baseDate = dayFormat.parse(dateStr) ?: throw IllegalArgumentException("תאריך לא תקין")

        val startCal = java.util.Calendar.getInstance()
        startCal.time = baseDate
        val endCal = startCal.clone() as java.util.Calendar

        if (!startTimeStr.isNullOrBlank() && !endTimeStr.isNullOrBlank()) {
            val start = timeFormat.parse(startTimeStr)!!
            val end = timeFormat.parse(endTimeStr)!!

            val startTimeCal = java.util.Calendar.getInstance().apply { time = start }
            startCal.set(java.util.Calendar.HOUR_OF_DAY, startTimeCal.get(java.util.Calendar.HOUR_OF_DAY))
            startCal.set(java.util.Calendar.MINUTE, startTimeCal.get(java.util.Calendar.MINUTE))

            val endTimeCal = java.util.Calendar.getInstance().apply { time = end }
            endCal.set(java.util.Calendar.HOUR_OF_DAY, endTimeCal.get(java.util.Calendar.HOUR_OF_DAY))
            endCal.set(java.util.Calendar.MINUTE, endTimeCal.get(java.util.Calendar.MINUTE))
        } else {
            // ברירת המחדל מהמסמך: 14:00 - 23:59
            startCal.set(java.util.Calendar.HOUR_OF_DAY, 14)
            startCal.set(java.util.Calendar.MINUTE, 0)
            endCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            endCal.set(java.util.Calendar.MINUTE, 59)
        }
        startCal.set(java.util.Calendar.SECOND, 0)
        endCal.set(java.util.Calendar.SECOND, 59)

        val event = com.google.api.services.calendar.model.Event().apply {
            summary = title
            start = EventDateTime().setDateTime(com.google.api.client.util.DateTime(startCal.time))
            end = EventDateTime().setDateTime(com.google.api.client.util.DateTime(endCal.time))
        }

        service.events().insert("primary", event).execute()
    }
}
