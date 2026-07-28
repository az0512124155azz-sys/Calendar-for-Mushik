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
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private var repository: CalendarRepository? = null

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

        etTitle = findViewById(R.id.etTitle)
        tvDatePicker = findViewById(R.id.tvDatePicker)
        tvStartTimePicker = findViewById(R.id.tvStartTimePicker)
        tvEndTimePicker = findViewById(R.id.tvEndTimePicker)
        tvExistingEventsLabel = findViewById(R.id.tvExistingEventsLabel)
        progressExistingEvents = findViewById(R.id.progressExistingEvents)
        rvExistingEvents = findViewById(R.id.rvExistingEvents)

        existingEventsAdapter = EventAdapter(emptyList(), showDate = false)
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

        tvDatePicker.text = displayDateFormat.format(dateMillis)

        tvDatePicker.setOnClickListener { pickDate() }
        tvStartTimePicker.setOnClickListener { pickTime(isStart = true) }
        tvEndTimePicker.setOnClickListener { pickTime(isStart = false) }

        findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnSave).setOnClickListener { saveEvent() }

        // מציג מיד את האירועים הקיימים בתאריך שהגיע מהמסך הראשי
        loadExistingEventsForDate()
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
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} - פנייה מהאפליקציה")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, email, Toast.LENGTH_SHORT).show()
        }
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
            Toast.makeText(this, "יש להתחבר מחדש ל-Google", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, getString(R.string.saving_event), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                // אם לא נבחרו שעות, insertEvent יחיל אוטומטית את ברירת המחדל 14:00-23:59
                repo.insertEvent(
                    title = title,
                    dateMillisStartOfDay = dateMillis,
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute,
                    location = selectedLocationText ?: etLocation.text?.toString()?.trim()?.ifBlank { null }
                )
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
    }
}
