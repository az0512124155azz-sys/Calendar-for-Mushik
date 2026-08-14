package com.magic3d.gcalsearchadd

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale

/**
 * מסך הוספת אירוע חדש.
 * לפי מסמך האיפיון: אם המשתמש לא בוחר שעות, ברירת המחדל היא 14:00 - 23:59.
 * בנוסף: בכל פעם שנבחר תאריך, מוצגים כל האירועים הקיימים באותו יום כדי שהמשתמש יראה את הלו"ז לפני שהוא מוסיף אירוע חדש.
 */
class AddEventActivity : AppCompatActivity() {

    private lateinit var etTitle: TextInputEditText
    private lateinit var tvDatePicker: TextView
    private lateinit var tvStartTimePicker: TextView
    private lateinit var tvEndTimePicker: TextView
    private lateinit var tvExistingEventsLabel: TextView
    private lateinit var progressExistingEvents: ProgressBar
    private lateinit var rvExistingEvents: androidx.recyclerview.widget.RecyclerView
    private lateinit var existingEventsAdapter: EventAdapter

    private lateinit var etLocation: EditText
    private lateinit var locationSuggestionsCard: MaterialCardView
    private lateinit var rvLocationSuggestions: androidx.recyclerview.widget.RecyclerView
    private lateinit var locationAdapter: LocationSuggestionAdapter
    private var selectedLocationText: String? = null
    private var selectedRecurrenceRule: String? = null
    private lateinit var tvRecurrencePicker: TextView
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private var repository: CalendarRepository? = null

    private var editingEventId: String? = null
    private var dateMillis: Long = MainActivity.startOfDay(System.currentTimeMillis())
    private var startHour: Int? = null
    private var startMinute: Int? = null
    private var endHour: Int? = null
    private var endMinute: Int? = null

    private val displayDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_event)

        dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, dateMillis)
        editingEventId = intent.getStringExtra(EXTRA_EVENT_ID)

        etTitle = findViewById(R.id.etTitle)
        tvDatePicker = findViewById(R.id.tvDatePicker)
        tvStartTimePicker = findViewById(R.id.tvStartTimePicker)
        tvEndTimePicker = findViewById(R.id.tvEndTimePicker)
        tvExistingEventsLabel = findViewById(R.id.tvExistingEventsLabel)
        progressExistingEvents = findViewById(R.id.progressExistingEvents)
        rvExistingEvents = findViewById(R.id.rvExistingEvents)

        existingEventsAdapter = EventAdapter(emptyList(), showDate = false, onEditClick = { event -> switchToEditingEvent(event.id) })
        rvExistingEvents.layoutManager = LinearLayoutManager(this)
        rvExistingEvents.adapter = existingEventsAdapter

        etLocation = findViewById(R.id.etLocation)
        locationSuggestionsCard = findViewById(R.id.locationSuggestionsCard)
        rvLocationSuggestions = findViewById(R.id.rvLocationSuggestions)

        locationAdapter = LocationSuggestionAdapter(emptyList()) { suggestion -> selectLocationSuggestion(suggestion) }
        rvLocationSuggestions.layoutManager = LinearLayoutManager(this)
        rvLocationSuggestions.adapter = locationAdapter

        etLocation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                selectedLocationText = null
                pendingSearch?.let { debounceHandler.removeCallbacks(it) }
                val query = s?.toString().orEmpty()
                if (query.length < 2) {
                    locationSuggestionsCard.visibility = View.GONE
                    return
                }
                val runnable = Runnable { searchLocations(query) }
                pendingSearch = runnable
                debounceHandler.postDelayed(runnable, 250)
            }
        })

        findViewById<View>(R.id.btnSupportEmail).setOnClickListener { openSupportEmail() }

        val account = GoogleSignIn.getLastSignedInAccount(this)?.account
        if (account != null) {
            repository = CalendarRepository(this, account)
        }

        if (editingEventId != null) {
            findViewById<TextView>(R.id.tvScreenTitle).text = getString(R.string.edit_event_title)
        }

        tvDatePicker.text = displayDateFormat.format(dateMillis)

        tvDatePicker.setOnClickListener { pickDate() }
        tvStartTimePicker.setOnClickListener { pickTime(isStart = true) }
        tvEndTimePicker.setOnClickListener { pickTime(isStart = false) }

        tvRecurrencePicker = findViewById(R.id.tvRecurrencePicker)
        tvRecurrencePicker.setOnClickListener { pickRecurrence() }

        findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnSave).setOnClickListener { saveEvent() }

        // מציג מיד את האירועים הקיימים בתאריך שהגיע מהמסך הראשי
        loadExistingEventsForDate()

        // אם הגענו לכאן דרך העיפרון (עריכה) - טוענים את פרטי האירוע הקיים לתוך הטופס
        editingEventId?.let { loadEventForEditing(it) }
    }

    /**
     * טוען אירוע קיים לעריכה - ממלא את כל השדות בדיוק כמו שהם שמורים היום ביומן,
     * כדי שהמשתמש יוכל לשנות מה שהוא רוצה ולשמור בחזרה על אותו אירוע (לא ליצור חדש).
     */
    private fun loadEventForEditing(eventId: String) {
        val repo = repository ?: return
        lifecycleScope.launch {
            try {
                val details = repo.getEventDetails(eventId)
                etTitle.setText(details.title)
                dateMillis = details.dateMillisStartOfDay
                tvDatePicker.text = displayDateFormat.format(dateMillis)

                startHour = details.startHour
                startMinute = details.startMinute
                if (startHour != null && startMinute != null) {
                    tvStartTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", startHour, startMinute)
                }
                endHour = details.endHour
                endMinute = details.endMinute
                if (endHour != null && endMinute != null) {
                    tvEndTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute)
                }

                if (!details.location.isNullOrBlank()) {
                    etLocation.setText(details.location)
                    selectedLocationText = details.location
                }

                selectedRecurrenceRule = details.recurrenceRule
                tvRecurrencePicker.text = if (details.recurrenceRule.isNullOrBlank()) {
                    getString(R.string.recurrence_none)
                } else {
                    getString(R.string.recurrence_prefix, humanReadableRecurrence(details.recurrenceRule))
                }

                // התאריך יכול היה להשתנות מה-extra המקורי - מרעננים את רשימת "אירועים קיימים ביום זה"
                loadExistingEventsForDate()
            } catch (e: Exception) {
                Toast.makeText(this@AddEventActivity, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * מאפשר לעבור למצב עריכה של אירוע אחר בלי לפתוח מסך חדש - נקרא מהעיפרון ברשימת
     * "אירועים קיימים ביום זה" שבתוך מסך ההוספה עצמו.
     */
    private fun switchToEditingEvent(eventId: String) {
        editingEventId = eventId
        findViewById<TextView>(R.id.tvScreenTitle).text = getString(R.string.edit_event_title)
        loadEventForEditing(eventId)
    }

    /**
     * שולף מגוגל קלנדר את כל האירועים שכבר קיימים בתאריך הנבחר ומציג אותם מעל טופס ההוספה,
     * כדי שהמשתמש יראה את הלו"ז הקיים לפני שהוא מוסיף אירוע נוסף לאותו יום.
     */
    private fun loadExistingEventsForDate() {
        val repo = repository
        if (repo == null) {
            tvExistingEventsLabel.visibility = View.GONE
            rvExistingEvents.visibility = View.GONE
            return
        }

        tvExistingEventsLabel.visibility = View.VISIBLE
        progressExistingEvents.visibility = View.VISIBLE
        rvExistingEvents.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val events = repo.getEventsForDate(dateMillis)
                existingEventsAdapter.updateItems(events)
                rvExistingEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
                tvExistingEventsLabel.text = if (events.isEmpty()) {
                    getString(R.string.no_existing_events)
                } else {
                    getString(R.string.existing_events_label)
                }
            } catch (e: Exception) {
                tvExistingEventsLabel.text = getString(R.string.error_generic, e.message ?: "")
            } finally {
                progressExistingEvents.visibility = View.GONE
            }
        }
    }

    /**
     * מחפש הצעות מיקום דרך OpenStreetMap (חינמי, בלי API key ובלי חיוב).
     * שפת התוצאות נקבעת אוטומטית לפי שפת המכשיר.
     */
    private var searchRequestId = 0L

    private fun searchLocations(query: String) {
        val requestId = ++searchRequestId
        lifecycleScope.launch {
            val results = NominatimClient.search(query)
            if (requestId != searchRequestId) return@launch // תוצאה ישנה שהגיעה באיחור - מתעלמים
            locationAdapter.updateSuggestions(results)
            locationSuggestionsCard.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * הכתובת שמוחזרת כבר מלאה ומדויקת, כך שהיא נשמרת כמו שהיא באירוע -
     * וכש-Google Calendar יציג את האירוע, הוא יזהה אותה ככתובת אמיתית ויציג מפה.
     */
    private fun selectLocationSuggestion(address: String) {
        etLocation.setText(address)
        etLocation.setSelection(address.length)
        locationSuggestionsCard.visibility = View.GONE
        selectedLocationText = address
    }

    private fun openSupportEmail() {
        val email = getString(R.string.support_email_hint)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.support_email_subject, getString(R.string.app_name)))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, email, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * דיאלוג בחירה לחזרה על האירוע. אחרי שנבחרת תדירות (או ימים מותאמים אישית),
     * שואלים גם מתי החזרה נגמרת - אף פעם / אחרי מספר פעמים / עד תאריך.
     */
    /**
     * הופך RRULE גולמי (למשל "RRULE:FREQ=DAILY;COUNT=3") לתווית קריאה, כדי שמסך העריכה
     * לא יציג למשתמש טקסט טכני גולמי.
     */
    private fun humanReadableRecurrence(rule: String): String {
        val options = resources.getStringArray(R.array.recurrence_options)
        return when {
            rule.contains("BYDAY") -> options.getOrElse(5) { rule }
            rule.contains("FREQ=DAILY") && rule.contains("COUNT=") -> {
                val count = Regex("COUNT=(\\d+)").find(rule)?.groupValues?.get(1)?.toIntOrNull()
                if (count != null) getString(R.string.recurrence_custom_days_label, count) else options[1]
            }
            rule.contains("FREQ=DAILY") -> options[1]
            rule.contains("FREQ=WEEKLY") -> options[2]
            rule.contains("FREQ=MONTHLY") -> options[3]
            rule.contains("FREQ=YEARLY") -> options[4]
            else -> rule
        }
    }

    private fun pickRecurrence() {
        val options = resources.getStringArray(R.array.recurrence_options).toList()

        showOptionListDialog(getString(R.string.recurrence_title), options) { which ->
            when (which) {
                0 -> {
                    selectedRecurrenceRule = null
                    tvRecurrencePicker.text = getString(R.string.recurrence_none)
                }
                options.size - 1 -> pickCustomRecurrenceDays()
                else -> {
                    val freqRule = when (which) {
                        1 -> "RRULE:FREQ=DAILY"
                        2 -> "RRULE:FREQ=WEEKLY"
                        3 -> "RRULE:FREQ=MONTHLY"
                        else -> "RRULE:FREQ=YEARLY"
                    }
                    askRecurrenceEnd(freqRule, options[which])
                }
            }
        }
    }

    /**
     * דיאלוג קלט מספר בעיצוב מותאם אישית של האפליקציה (במקום דיאלוג ברירת המחדל הלבן והגנרי של אנדרואיד).
     */
    private fun showNumberInputDialog(
        title: String,
        message: String,
        prefill: String = "",
        onConfirm: (Int) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_number_input, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val etNumber = view.findViewById<EditText>(R.id.etDialogNumber)
        val btnCancel = view.findViewById<View>(R.id.btnDialogCancel)
        val btnSaveDialog = view.findViewById<View>(R.id.btnDialogSave)
        val btnStepDown = view.findViewById<View>(R.id.btnStepDown)
        val btnStepUp = view.findViewById<View>(R.id.btnStepUp)

        tvTitle.text = title
        tvMessage.text = message
        etNumber.setText(prefill.ifBlank { "1" })

        btnStepDown.setOnClickListener {
            val current = etNumber.text?.toString()?.trim()?.toIntOrNull() ?: 1
            val newValue = (current - 1).coerceAtLeast(1)
            etNumber.setText(newValue.toString())
        }
        btnStepUp.setOnClickListener {
            val current = etNumber.text?.toString()?.trim()?.toIntOrNull() ?: 0
            etNumber.setText((current + 1).toString())
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSaveDialog.setOnClickListener {
            val value = etNumber.text?.toString()?.trim()?.toIntOrNull()
            if (value == null || value <= 0) {
                Toast.makeText(this, getString(R.string.error_invalid_number), Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(value)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun pickCustomRecurrenceDays() {
        showNumberInputDialog(
            title = getString(R.string.recurrence_custom_title),
            message = getString(R.string.recurrence_custom_message),
            prefill = "3"
        ) { daysCount ->
            // "מותאם אישית (ימים)" = האירוע חוזר כל יום ברציפות, בסך הכל daysCount ימים כולל יום ההתחלה
            val baseRule = "RRULE:FREQ=DAILY;COUNT=$daysCount"
            val label = getString(R.string.recurrence_custom_days_label, daysCount)
            selectedRecurrenceRule = baseRule
            tvRecurrencePicker.text = getString(R.string.recurrence_prefix, label)
        }
    }

    /**
     * שלב שני: מתי החזרה נגמרת. מוסיף COUNT= או UNTIL= ל-RRULE הבסיסי לפי הבחירה.
     */
    private fun askRecurrenceEnd(baseRule: String, label: String) {
        val endOptions = listOf(
            getString(R.string.recurrence_end_never),
            getString(R.string.recurrence_end_count),
            getString(R.string.recurrence_end_until)
        )

        showOptionListDialog("$label - ${getString(R.string.recurrence_end_title)}", endOptions) { which ->
            when (which) {
                0 -> {
                    selectedRecurrenceRule = baseRule
                    tvRecurrencePicker.text = getString(R.string.recurrence_prefix, label)
                }
                1 -> askRecurrenceCount(baseRule, label)
                2 -> askRecurrenceUntilDate(baseRule, label)
            }
        }
    }

    private fun askRecurrenceCount(baseRule: String, label: String) {
        showNumberInputDialog(
            title = getString(R.string.recurrence_end_count),
            message = getString(R.string.recurrence_count_message)
        ) { count ->
            selectedRecurrenceRule = "$baseRule;COUNT=$count"
            tvRecurrencePicker.text = getString(R.string.recurrence_with_count, label, count)
        }
    }

    private fun askRecurrenceUntilDate(baseRule: String, label: String) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTheme(R.style.ThemeOverlay_App_DatePicker)
            .setSelection(MainActivity.localStartOfDayToUtcMillis(dateMillis))
            .build()

        picker.addOnPositiveButtonClickListener { utcMillis ->
            val localEndOfDay = MainActivity.utcMidnightToLocalStartOfDay(utcMillis) + (23 * 60 + 59) * 60_000L + 59_000L
            val untilFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val untilStr = untilFormat.format(java.util.Date(localEndOfDay))
            selectedRecurrenceRule = "$baseRule;UNTIL=$untilStr"
            val untilDisplay = displayDateFormat.format(localEndOfDay)
            tvRecurrencePicker.text = getString(R.string.recurrence_with_until, label, untilDisplay)
        }

        picker.show(supportFragmentManager, "recurrence_until_picker")
    }

    /**
     * דיאלוג רשימת אפשרויות בעיצוב מותאם אישית של האפליקציה (כרטיסייה לבנה מעוגלת,
     * לא חלון ברירת המחדל הלבן-מלבני של אנדרואיד).
     */
    private fun showOptionListDialog(title: String, options: List<String>, onSelect: (Int) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_option_list, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.optionsContainer)
        val btnCancel = view.findViewById<View>(R.id.btnDialogCancel)

        tvTitle.text = title

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        options.forEachIndexed { index, optionText ->
            val rippleValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

            val row = TextView(this).apply {
                text = optionText
                textSize = 15f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                setPadding(36, 30, 36, 30)
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_rounded_field)
                foreground = androidx.core.content.ContextCompat.getDrawable(context, rippleValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = if (index == 0) 0 else 20
            container.addView(row, params)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTheme(R.style.ThemeOverlay_App_DatePicker)
            .setSelection(MainActivity.localStartOfDayToUtcMillis(dateMillis))
            .setCalendarConstraints(CalendarConstraints.Builder().build())
            .build()

        picker.addOnPositiveButtonClickListener { utcMillis ->
            dateMillis = MainActivity.utcMidnightToLocalStartOfDay(utcMillis)
            tvDatePicker.text = displayDateFormat.format(dateMillis)
            loadExistingEventsForDate()
        }

        picker.show(supportFragmentManager, "date_picker")
    }

    private fun pickTime(isStart: Boolean) {
        val defaultHour = if (isStart) 14 else 23
        val defaultMinute = if (isStart) 0 else 59
        val currentHour = if (isStart) (startHour ?: defaultHour) else (endHour ?: defaultHour)
        val currentMinute = if (isStart) (startMinute ?: defaultMinute) else (endMinute ?: defaultMinute)

        TimePickerDialog(
            this,
            R.style.LightSpinnerTimePickerDialog,
            { _, hour, minute ->
                if (isStart) {
                    startHour = hour
                    startMinute = minute
                    tvStartTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                } else {
                    endHour = hour
                    endMinute = minute
                    tvEndTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                }
            },
            currentHour,
            currentMinute,
            true
        ).show()
    }

    private fun saveEvent() {
        val title = etTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(this, getString(R.string.event_title_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val repo = repository
        if (repo == null) {
            Toast.makeText(this, getString(R.string.error_reconnect_google), Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, getString(R.string.saving_event), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val currentEditingId = editingEventId
                // אם לא נבחרו שעות, ברירת המחדל היא 14:00-23:59 - גם בהוספה וגם בעריכה
                if (currentEditingId != null) {
                    repo.updateEvent(
                        eventId = currentEditingId,
                        title = title,
                        dateMillisStartOfDay = dateMillis,
                        startHour = startHour,
                        startMinute = startMinute,
                        endHour = endHour,
                        endMinute = endMinute,
                        location = selectedLocationText ?: etLocation.text?.toString()?.trim()?.ifBlank { null },
                        recurrenceRule = selectedRecurrenceRule
                    )
                } else {
                    repo.insertEvent(
                        title = title,
                        dateMillisStartOfDay = dateMillis,
                        startHour = startHour,
                        startMinute = startMinute,
                        endHour = endHour,
                        endMinute = endMinute,
                        location = selectedLocationText ?: etLocation.text?.toString()?.trim()?.ifBlank { null },
                        recurrenceRule = selectedRecurrenceRule
                    )
                }
                Toast.makeText(this@AddEventActivity, getString(R.string.event_saved), Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddEventActivity, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_DATE_MILLIS = "extra_date_millis"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
