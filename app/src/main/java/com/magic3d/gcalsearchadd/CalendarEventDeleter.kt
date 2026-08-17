package com.magic3d.gcalsearchadd

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * פעולה ממוקדת למחיקת אירוע מ-Google Calendar.
 * מופרדת מה-Repository הקיים כדי שהתיקון לא ישנה פעולות חיפוש, הוספה ועריכה.
 */
class CalendarEventDeleter(context: Context, account: Account) {

    private val credential = GoogleAccountCredential.usingOAuth2(
        context,
        listOf(CalendarScopes.CALENDAR)
    ).apply {
        selectedAccount = account
    }

    private val service = Calendar.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    ).setApplicationName("GCal Search & Add").build()

    suspend fun deleteEvent(eventId: String) = withContext(Dispatchers.IO) {
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
        service.events().delete("primary", eventId).execute()
    }
}
