package com.magic3d.gcalsearchadd

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.magic3d.gcalsearchadd.model.EventItem
import org.json.JSONObject

data class PendingCalendarAction(
    val rowId: Long,
    val operation: String,
    val eventId: String,
    val details: EventDetails?
)

/** מסד מקומי קטן ללא הרשאות נוספות: מטמון אירועים ותור פעולות לסנכרון. */
class OfflineCalendarStore(context: Context, private val accountName: String) :
    SQLiteOpenHelper(context.applicationContext, "eventspot_offline.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(account TEXT NOT NULL,id TEXT NOT NULL,day INTEGER NOT NULL,title TEXT NOT NULL,date_label TEXT NOT NULL,time_label TEXT NOT NULL,location TEXT,details TEXT,PRIMARY KEY(account,id))")
        db.execSQL("CREATE INDEX events_day ON events(account,day)")
        db.execSQL("CREATE TABLE pending(row_id INTEGER PRIMARY KEY AUTOINCREMENT,account TEXT NOT NULL,operation TEXT NOT NULL,event_id TEXT NOT NULL,payload TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceDay(day: Long, items: List<EventItem>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("events", "account=? AND day=? AND id NOT LIKE 'offline:%'", arrayOf(accountName, day.toString()))
            items.forEach { saveItem(day, it, null) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveItem(day: Long, item: EventItem, details: EventDetails?) {
        val values = ContentValues().apply {
            put("account", accountName); put("id", item.id); put("day", day)
            put("title", item.title); put("date_label", item.dateLabel); put("time_label", item.timeLabel)
            put("location", item.location); details?.let { put("details", detailsToJson(it).toString()) }
        }
        writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun eventsForDay(day: Long): List<EventItem> = queryItems("account=? AND day=?", arrayOf(accountName, day.toString()))

    fun search(query: String): List<EventItem> = queryItems(
        "account=? AND (title LIKE ? OR location LIKE ?)",
        arrayOf(accountName, "%$query%", "%$query%")
    )

    private fun queryItems(selection: String, args: Array<String>): List<EventItem> {
        val result = mutableListOf<EventItem>()
        readableDatabase.query("events", arrayOf("id","title","date_label","time_label","location"), selection, args, null, null, "day,time_label").use { c ->
            while (c.moveToNext()) result += EventItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
        return result
    }

    fun details(eventId: String): EventDetails? {
        readableDatabase.query("events", arrayOf("details"), "account=? AND id=?", arrayOf(accountName, eventId), null, null, null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return jsonToDetails(JSONObject(c.getString(0)))
        }
        return null
    }

    fun queue(operation: String, eventId: String, details: EventDetails?) {
        if (eventId.startsWith("offline:") && operation == "DELETE") {
            writableDatabase.delete("pending", "account=? AND event_id=?", arrayOf(accountName, eventId))
            deleteCached(eventId)
            return
        }
        writableDatabase.delete("pending", "account=? AND event_id=?", arrayOf(accountName, eventId))
        writableDatabase.insert("pending", null, ContentValues().apply {
            put("account", accountName); put("operation", operation); put("event_id", eventId)
            details?.let { put("payload", detailsToJson(it).toString()) }
        })
    }

    fun pending(): List<PendingCalendarAction> {
        val result = mutableListOf<PendingCalendarAction>()
        readableDatabase.query("pending", arrayOf("row_id","operation","event_id","payload"), "account=?", arrayOf(accountName), null, null, "row_id").use { c ->
            while (c.moveToNext()) result += PendingCalendarAction(c.getLong(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else jsonToDetails(JSONObject(c.getString(3))))
        }
        return result
    }

    fun complete(rowId: Long) { writableDatabase.delete("pending", "row_id=?", arrayOf(rowId.toString())) }
    fun deleteCached(eventId: String) { writableDatabase.delete("events", "account=? AND id=?", arrayOf(accountName, eventId)) }

    fun replaceId(oldId: String, newId: String) {
        writableDatabase.execSQL("UPDATE events SET id=? WHERE account=? AND id=?", arrayOf(newId, accountName, oldId))
    }

    companion object {
        private fun detailsToJson(d: EventDetails) = JSONObject().apply {
            put("id", d.id); put("title", d.title); put("day", d.dateMillisStartOfDay)
            put("sh", d.startHour); put("sm", d.startMinute); put("eh", d.endHour); put("em", d.endMinute)
            put("location", d.location); put("recurrence", d.recurrenceRule)
        }
        private fun jsonToDetails(j: JSONObject) = EventDetails(
            j.getString("id"), j.getString("title"), j.getLong("day"),
            j.optIntOrNull("sh"), j.optIntOrNull("sm"), j.optIntOrNull("eh"), j.optIntOrNull("em"),
            j.optString("location").takeUnless { it.isBlank() || it == "null" },
            j.optString("recurrence").takeUnless { it.isBlank() || it == "null" }
        )
        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
    }
}
