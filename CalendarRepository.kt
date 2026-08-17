package com.magic3d.gcalsearchadd

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.magic3d.gcalsearchadd.model.EventItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.UUID
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
class CalendarRepository(context: Context, private val account: Account) {

    private val appContext = context.applicationContext
    private val offlineStore = OfflineCalendarStore(appContext, account.name)
    @Volatile var lastOperationQueuedOffline: Boolean = false
        private set

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
        try {
            syncPendingActionsInternal()
            val tz = TimeZone.getDefault()
            val events: List<Event> = service.events().list("primary")
                .setTimeMin(dateTimeFor(dateMillisStartOfDay, tz))
                .setTimeMax(dateTimeFor(dateMillisStartOfDay + 24L * 60 * 60 * 1000 - 1000, tz))
                .setOrderBy("startTime").setSingleEvents(true).execute().items ?: emptyList()
            val items = events.map { toEventItem(it) }
            offlineStore.replaceDay(dateMillisStartOfDay, items)
            events.forEach { event ->
                offlineStore.saveItem(dateMillisStartOfDay, toEventItem(event), detailsFromGoogleEvent(event))
            }
            items
        } catch (e: IOException) {
            offlineStore.eventsForDay(dateMillisStartOfDay)
        }
    }

    /**
     * חיפוש חופשי לפי מילת מפתח (משתמש בפרמטר q של Google Calendar API), על פני חודש קדימה ואחורה.
     */
    suspend fun searchEventsByKeyword(keyword: String): List<EventItem> = withContext(Dispatchers.IO) {
        try {
            syncPendingActionsInternal()
            val events: List<Event> = service.events().list("primary").setQ(keyword)
                .setOrderBy("startTime").setSingleEvents(true).setMaxResults(50).execute().items ?: emptyList()
            events.map { toEventItem(it) }
        } catch (e: IOException) {
            offlineStore.search(keyword)
        }
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
        lastOperationQueuedOffline = false
        val tz = TimeZone.getDefault()

        val actualStartHour = startHour ?: 14
        val actualStartMinute = startMinute ?: 0
        val actualEndHour = endHour ?: 23
        val actualEndMinute = endMinute ?: 59

        val startMillis = dateMillisStartOfDay + (actualStartHour * 60 + actualStartMinute) * 60_000L
        var endMillis = dateMillisStartOfDay + (actualEndHour * 60 + actualEndMinute) * 60_000L
        // אם שעת הסיום "לפני" שעת ההתחלה (למשל 14:00-01:59), האירוע חוצה חצות - מזיזים ליום למחרת
        if (endMillis <= startMillis) {
            endMillis += 24L * 60 * 60 * 1000
        }

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
        try {
            val saved = service.events().insert("primary", event).execute()
            cacheDetails(detailsFromInput(saved.id ?: "", title, dateMillisStartOfDay, startHour, startMinute, endHour, endMinute, location, recurrenceRule))
            saved
        } catch (e: IOException) {
            val localId = "offline:${UUID.randomUUID()}"
            val details = detailsFromInput(localId, title, dateMillisStartOfDay, startHour, startMinute, endHour, endMinute, location, recurrenceRule)
            offlineStore.queue("INSERT", localId, details)
            cacheDetails(details)
            OfflineSyncWorker.schedule(appContext)
            lastOperationQueuedOffline = true
            event.id = localId
            event
        }
    }

    /**
     * שולף את הפרטים המלאים של אירוע קיים, כדי למלא מראש את מסך העריכה.
     */
    suspend fun getEventDetails(eventId: String): EventDetails = withContext(Dispatchers.IO) {
        if (eventId.startsWith("offline:")) return@withContext offlineStore.details(eventId)
            ?: throw IllegalStateException("Offline event was not found")
        val event = try {
            service.events().get("primary", eventId).execute()
        } catch (e: IOException) {
            return@withContext offlineStore.details(eventId) ?: throw e
        }
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

        val details = EventDetails(
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
        cacheDetails(details)
        details
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
        lastOperationQueuedOffline = false
        val tz = TimeZone.getDefault()

        val actualStartHour = startHour ?: 14
        val actualStartMinute = startMinute ?: 0
        val actualEndHour = endHour ?: 23
        val actualEndMinute = endMinute ?: 59

        val startMillis = dateMillisStartOfDay + (actualStartHour * 60 + actualStartMinute) * 60_000L
        var endMillis = dateMillisStartOfDay + (actualEndHour * 60 + actualEndMinute) * 60_000L
        // אם שעת הסיום "לפני" שעת ההתחלה (למשל 14:00-01:59), האירוע חוצה חצות - מזיזים ליום למחרת
        if (endMillis <= startMillis) {
            endMillis += 24L * 60 * 60 * 1000
        }

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

        val details = detailsFromInput(eventId, title, dateMillisStartOfDay, startHour, startMinute, endHour, endMinute, location, recurrenceRule)
        if (eventId.startsWith("offline:")) {
            offlineStore.queue("INSERT", eventId, details)
            cacheDetails(details)
            OfflineSyncWorker.schedule(appContext)
            lastOperationQueuedOffline = true
            event.id = eventId
            return@withContext event
        }
        try {
            val saved = service.events().update("primary", eventId, event).execute()
            cacheDetails(details)
            saved
        } catch (e: IOException) {
            offlineStore.queue("UPDATE", eventId, details)
            cacheDetails(details)
            OfflineSyncWorker.schedule(appContext)
            lastOperationQueuedOffline = true
            event.id = eventId
            event
        }
    }

    suspend fun deleteEvent(eventId: String) = withContext(Dispatchers.IO) {
        lastOperationQueuedOffline = false
        if (eventId.startsWith("offline:")) {
            offlineStore.queue("DELETE", eventId, null)
            return@withContext
        }
        try {
            service.events().delete("primary", eventId).execute()
            offlineStore.deleteCached(eventId)
        } catch (e: IOException) {
            offlineStore.queue("DELETE", eventId, null)
            offlineStore.deleteCached(eventId)
            OfflineSyncWorker.schedule(appContext)
            lastOperationQueuedOffline = true
        }
    }

    suspend fun syncPendingActions() = withContext(Dispatchers.IO) { syncPendingActionsInternal() }

    /**
     * מסנכרן את כל המטמון מול היומן הראשי. בפעם הראשונה מתבצע סנכרון מלא,
     * ובהמשך Google מחזירה רק אירועים שהשתנו או נמחקו מאז ה-syncToken האחרון.
     */
    suspend fun refreshCalendarCache() = withContext(Dispatchers.IO) {
        syncPendingActionsInternal()
        try {
            refreshCalendarCacheInternal()
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 410) {
                offlineStore.clearRemoteEvents()
                refreshCalendarCacheInternal()
            } else throw e
        }
    }

    private fun refreshCalendarCacheInternal() {
        val savedToken = offlineStore.syncToken()
        var pageToken: String? = null
        var nextSyncToken: String? = null
        do {
            val request = service.events().list("primary")
                .setSingleEvents(true)
                .setShowDeleted(true)
                .setMaxResults(2500)
            if (savedToken != null) {
                request.syncToken = savedToken
            }
            request.pageToken = pageToken
            val response = request.execute()
            response.items.orEmpty().forEach { event ->
                val id = event.id ?: return@forEach
                if (event.status == "cancelled") {
                    offlineStore.deleteCached(id)
                } else {
                    val details = detailsFromGoogleEvent(event)
                    offlineStore.saveItem(details.dateMillisStartOfDay, toEventItem(event), details)
                }
            }
            pageToken = response.nextPageToken
            nextSyncToken = response.nextSyncToken ?: nextSyncToken
        } while (pageToken != null)
        if (nextSyncToken != null) offlineStore.saveSyncToken(nextSyncToken)
    }

    private fun syncPendingActionsInternal() {
        offlineStore.pending().forEach { action ->
            when (action.operation) {
                "INSERT" -> {
                    val details = action.details ?: return@forEach
                    val stableGoogleId = stableGoogleEventId(action.eventId)
                    val event = eventFromDetails(details).apply { id = stableGoogleId }
                    try {
                        service.events().insert("primary", event).execute()
                    } catch (e: GoogleJsonResponseException) {
                        // 409 אומר שהניסיון הקודם הצליח אך האפליקציה נסגרה לפני ניקוי התור.
                        if (e.statusCode != 409) throw e
                    }
                    offlineStore.replaceId(action.eventId, stableGoogleId)
                }
                "UPDATE" -> action.details?.let {
                    service.events().update("primary", action.eventId, eventFromDetails(it)).execute()
                }
                "DELETE" -> service.events().delete("primary", action.eventId).execute()
            }
            offlineStore.complete(action.rowId)
        }
    }

    private fun detailsFromInput(
        id: String, title: String, day: Long, sh: Int?, sm: Int?, eh: Int?, em: Int?,
        location: String?, recurrence: String?
    ) = EventDetails(id, title, day, sh, sm, eh, em, location, recurrence)

    /** Google מקבלת מזהי אירוע באלפבית base32hex; UUID הקסדצימלי מאפשר ניסיון חוזר בטוח. */
    private fun stableGoogleEventId(localId: String): String =
        "offline" + localId.substringAfter(':').replace("-", "").lowercase(Locale.US)

    private fun detailsFromGoogleEvent(event: Event): EventDetails {
        val startMillis = event.start?.dateTime?.value ?: event.start?.date?.value ?: 0L
        val endMillis = event.end?.dateTime?.value
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
        val dayCal = java.util.Calendar.getInstance().apply {
            timeInMillis = startMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val endCal = endMillis?.let { millis -> java.util.Calendar.getInstance().apply { timeInMillis = millis } }
        return EventDetails(
            event.id ?: "", event.summary ?: "", dayCal.timeInMillis,
            if (event.start?.dateTime != null) startCal.get(java.util.Calendar.HOUR_OF_DAY) else null,
            if (event.start?.dateTime != null) startCal.get(java.util.Calendar.MINUTE) else null,
            endCal?.get(java.util.Calendar.HOUR_OF_DAY), endCal?.get(java.util.Calendar.MINUTE),
            event.location, event.recurrence?.firstOrNull()
        )
    }

    private fun eventFromDetails(details: EventDetails): Event {
        val tz = TimeZone.getDefault()
        val start = details.dateMillisStartOfDay + ((details.startHour ?: 14) * 60 + (details.startMinute ?: 0)) * 60_000L
        var end = details.dateMillisStartOfDay + ((details.endHour ?: 23) * 60 + (details.endMinute ?: 59)) * 60_000L
        if (end <= start) end += 24L * 60 * 60 * 1000
        return Event().apply {
            summary = details.title
            if (!details.location.isNullOrBlank()) location = details.location
            if (!details.recurrenceRule.isNullOrBlank()) recurrence = listOf(details.recurrenceRule)
            this.start = EventDateTime().setDateTime(dateTimeFor(start, tz)).setTimeZone(tz.id)
            this.end = EventDateTime().setDateTime(dateTimeFor(end, tz)).setTimeZone(tz.id)
        }
    }

    private fun cacheDetails(details: EventDetails) {
        val sh = details.startHour ?: 14
        val sm = details.startMinute ?: 0
        val eh = details.endHour ?: 23
        val em = details.endMinute ?: 59
        offlineStore.saveItem(
            details.dateMillisStartOfDay,
            EventItem(
                details.id,
                details.title,
                dateFormat.format(java.util.Date(details.dateMillisStartOfDay)),
                String.format(Locale.getDefault(), "%02d:%02d-%02d:%02d", sh, sm, eh, em),
                details.location
            ),
            details
        )
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
