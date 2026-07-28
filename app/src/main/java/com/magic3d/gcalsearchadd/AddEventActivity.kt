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
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
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
    private lateinit var placesClient: PlacesClient
    private var sessionToken: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()
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

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.places_api_key))
        }
        placesClient = Places.createClient(this)

        locationAdapter = LocationSuggestionAdapter(emptyList()) { prediction -> selectLocationPrediction(prediction) }
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
                debounceHandler.postDelayed(runnable, 300)
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
     * מחפש הצעות מיקום מ-Google Places תוך כדי הקלדה. השפה נקבעת אוטומטית לפי שפת המכשיר.
     */
    private fun searchLocations(query: String) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setSessionToken(sessionToken)
            .setQuery(query)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val predictions = response.autocompletePredictions
                locationAdapter.updatePredictions(predictions)
                locationSuggestionsCard.visibility = if (predictions.isEmpty()) View.GONE else View.VISIBLE
            }
            .addOnFailureListener {
                locationSuggestionsCard.visibility = View.GONE
            }
    }

    /**
     * כשנבחרת הצעה, שולפים את הכתובת המלאה מ-Google Places - כדי שהמיקום השמור באירוע
     * יהיה כתובת מדויקת שמסתנכרנת עם המפה בתוך Google Calendar, ולא רק שם מקום כללי.
     */
    private fun selectLocationPrediction(prediction: AutocompletePrediction) {
        val displayText = prediction.getFullText(null).toString()
        etLocation.setText(displayText)
        etLocation.setSelection(displayText.length)
        locationSuggestionsCard.visibility = View.GONE
        selectedLocationText = displayText

        val placeFields = listOf(Place.Field.ADDRESS, Place.Field.NAME)
        val request = FetchPlaceRequest.newInstance(prediction.placeId, placeFields)
        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val address = response.place.address
                if (!address.isNullOrBlank()) {
                    selectedLocationText = address
                }
            }

        // טוקן חדש לחיפוש הבא, לפי הנחיות החיוב של Google Places
        sessionToken = AutocompleteSessionToken.newInstance()
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
