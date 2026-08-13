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
 * פרטים מלאים של אירוע קיים, כדי למלא מראש את טופס העריכה - שדות "גולמיים"
 * (לא מעוצבים לתצוגה כמו EventItem).
 */
data class EventDetails(
    val id: String,
    val title: String,
    val dateMillisStartOfDay: Long,
    val startHour: Int?,
    val startMinute: Int?,
    val endHour: Int?,
    val endMinute: Int?,
    val location: String?,
    val recurrenceRule: String?
)

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
    private val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

    /**
     * DateTime של Google API לא מקבל (Long, TimeZone) ישירות - צריך Date+TimeZone.
     */
    private fun dateTimeFor(millis: Long, tz: TimeZone): DateTime =
        DateTime(java.util.Date(millis), tz)

    /**
     * שולף את כל האירועים ליום נתון (מ-00:00 עד 23:59:59 של אותו יום, לפי אזור הזמן של המכשיר).
     */
    suspend fun getEventsForDate(dateMillisStartOfDay: Long): List<EventItem> = withContext(Dispatchers.IO) {
        val tz = TimeZone.getDefault()
        val timeMin = dateTimeFor(dateMillisStartOfDay, tz)
        val timeMax = dateTimeFor(dateMillisStartOfDay + 24L * 60 * 60 * 1000 - 1000, tz)

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
        endMinute: Int?,
        location: String? = null,
        recurrenceRule: String? = null
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
            if (!location.isNullOrBlank()) {
                setLocation(location)
            }
            if (!recurrenceRule.isNullOrBlank()) {
                recurrence = listOf(recurrenceRule)
            }
            start = EventDateTime().setDateTime(dateTimeFor(startMillis, tz)).setTimeZone(tz.id)
            end = EventDateTime().setDateTime(dateTimeFor(endMillis, tz)).setTimeZone(tz.id)
        }

        // POST -> calendars/primary/events, כפי שמופיע במסמך האיפיון
        service.events().insert("primary", event).execute()
    }

    /**
     * שולף את הפרטים המלאים של אירוע קיים, כדי למלא מראש את מסך העריכה.
     */
    suspend fun getEventDetails(eventId: String): EventDetails = withContext(Dispatchers.IO) {
        val event = service.events().get("primary", eventId).execute()
        val cal = java.util.Calendar.getInstance()

        val startMillis = event.start?.dateTime?.value
        val endMillis = event.end?.dateTime?.value

        var dateMillisStartOfDay = 0L
        var startHour: Int? = null
        var startMinute: Int? = null
        var endHour: Int? = null
        var endMinute: Int? = null

        if (startMillis != null) {
            cal.timeInMillis = startMillis
            startHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            startMinute = cal.get(java.util.Calendar.MINUTE)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            dateMillisStartOfDay = cal.timeInMillis
        }
        if (endMillis != null) {
            cal.timeInMillis = endMillis
            endHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            endMinute = cal.get(java.util.Calendar.MINUTE)
        }

        EventDetails(
            id = event.id ?: eventId,
            title = event.summary ?: "",
            dateMillisStartOfDay = dateMillisStartOfDay,
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute,
            location = event.location,
            recurrenceRule = event.recurrence?.firstOrNull()
        )
    }

    /**
     * מעדכן אירוע קיים (במקום ליצור חדש). אם לא סופקו שעות, ברירת המחדל היא 14:00-23:59,
     * בדיוק כמו בהוספת אירוע חדש.
     */
    suspend fun updateEvent(
        eventId: String,
        title: String,
        dateMillisStartOfDay: Long,
        startHour: Int?,
        startMinute: Int?,
        endHour: Int?,
        endMinute: Int?,
        location: String? = null,
        recurrenceRule: String? = null
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
            if (!location.isNullOrBlank()) {
                setLocation(location)
            }
            if (!recurrenceRule.isNullOrBlank()) {
                recurrence = listOf(recurrenceRule)
            }
            start = EventDateTime().setDateTime(dateTimeFor(startMillis, tz)).setTimeZone(tz.id)
            end = EventDateTime().setDateTime(dateTimeFor(endMillis, tz)).setTimeZone(tz.id)
        }

        service.events().update("primary", eventId, event).execute()
    }

    private fun toEventItem(event: Event): EventItem {
        val startMillisValue = event.start?.dateTime?.value ?: event.start?.date?.value
        val dateStr = if (startMillisValue != null) dateFormat.format(java.util.Date(startMillisValue)) else ""

        val timeLabel = if (event.start?.dateTime != null && event.end?.dateTime != null) {
            val startStr = timeFormat.format(java.util.Date(event.start.dateTime.value))
            val endStr = timeFormat.format(java.util.Date(event.end.dateTime.value))
            "$startStr-$endStr"
        } else {
            "כל היום"
        }

        return EventItem(
            id = event.id ?: "",
            title = event.summary ?: "(ללא כותרת)",
            dateLabel = dateStr,
            timeLabel = timeLabel,
            location = event.location
        )
    }
}
