package com.magic3d.gcalsearchadd

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.magic3d.gcalsearchadd.model.EventItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * שכבת גישה ל-Google Calendar API.
 * כל קריאה רשתית מבוצעת ב-Dispatchers.IO כי ה-Google API Client הוא סינכרוני (חוסם).
 */
class CalendarRepository(context: Context, account: Account) {

    // קוד ה-scope כאן חייב להתאים בדיוק להרשאה שביקשנו ב-GoogleSignInOptions
    private val credential: GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(context, listOf(CalendarScopes.CALENDAR)).apply {
            selectedAccount = account
        }

    private val service: Calendar = Calendar.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    ).setApplicationName("GCal Search & Add").build()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * שולף את כל האירועים ליום נתון (מ-00:00 עד 23:59:59 של אותו יום, לפי אזור הזמן של המכשיר).
     */
    suspend fun getEventsForDate(dateMillisStartOfDay: Long): List<EventItem> = withContext(Dispatchers.IO) {
        val tz = TimeZone.getDefault()
        val timeMin = DateTime(dateMillisStartOfDay, tz)
        val timeMax = DateTime(dateMillisStartOfDay + 24L * 60 * 60 * 1000 - 1000, tz)

        val events: List<Event> = service.events().list("primary")
            .setTimeMin(timeMin)
            .setTimeMax(timeMax)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()
            .items ?: emptyList()

        events.map { toEventItem(it) }
    }

    /**
     * חיפוש חופשי לפי מילת מפתח (משתמש בפרמטר q של Google Calendar API), על פני חודש קדימה ואחורה.
     */
    suspend fun searchEventsByKeyword(keyword: String): List<EventItem> = withContext(Dispatchers.IO) {
        val events: List<Event> = service.events().list("primary")
            .setQ(keyword)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .setMaxResults(50)
            .execute()
            .items ?: emptyList()

        events.map { toEventItem(it) }
    }

    /**
     * הוספת אירוע חדש.
     * לפי דרישת האיפיון: אם לא סופקו שעות ספציפיות, ברירת המחדל היא 14:00 - 23:59.
     */
    suspend fun insertEvent(
        title: String,
        dateMillisStartOfDay: Long,
        startHour: Int?,
        startMinute: Int?,
        endHour: Int?,
        endMinute: Int?
    ): Event = withContext(Dispatchers.IO) {
        val tz = TimeZone.getDefault()

        val actualStartHour = startHour ?: 14
        val actualStartMinute = startMinute ?: 0
        val actualEndHour = endHour ?: 23
        val actualEndMinute = endMinute ?: 59

        val startMillis = dateMillisStartOfDay + (actualStartHour * 60 + actualStartMinute) * 60_000L
        val endMillis = dateMillisStartOfDay + (actualEndHour * 60 + actualEndMinute) * 60_000L

        val event = Event().apply {
            summary = title
            start = EventDateTime().setDateTime(DateTime(startMillis, tz)).setTimeZone(tz.id)
            end = EventDateTime().setDateTime(DateTime(endMillis, tz)).setTimeZone(tz.id)
        }

        // POST -> calendars/primary/events, כפי שמופיע במסמך האיפיון
        service.events().insert("primary", event).execute()
    }

    private fun toEventItem(event: Event): EventItem {
        val start = event.start?.dateTime ?: event.start?.date
        val end = event.end?.dateTime ?: event.end?.date

        val timeRange = if (event.start?.dateTime != null && event.end?.dateTime != null) {
            val startStr = timeFormat.format(java.util.Date(event.start.dateTime.value))
            val endStr = timeFormat.format(java.util.Date(event.end.dateTime.value))
            "$startStr - $endStr"
        } else {
            "כל היום"
        }

        return EventItem(
            id = event.id ?: "",
            title = event.summary ?: "(ללא כותרת)",
            timeRange = timeRange,
            location = event.location
        )
    }
}
