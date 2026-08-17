package com.magic3d.gcalsearchadd.model

/**
 * מודל אירוע פשוט לשימוש ב-UI, נבנה מתוך אובייקט ה-Event שמוחזר מ-Google Calendar API.
 */
data class EventItem(
    val id: String,
    val title: String,
    val dateLabel: String,   // לדוגמה "29/07"
    val timeLabel: String,   // לדוגמה "14:00-15:30" או "כל היום"
    val location: String?
)
