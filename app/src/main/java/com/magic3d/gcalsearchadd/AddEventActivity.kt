package com.magic3d.gcalsearchadd

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
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

        existingEventsAdapter = EventAdapter(emptyList())
        rvExistingEvents.layoutManager = LinearLayoutManager(this)
        rvExistingEvents.adapter = existingEventsAdapter

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

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTheme(R.style.ThemeOverlay_App_TimePicker)
            .setTitleText(if (isStart) getString(R.string.event_start_hint) else getString(R.string.event_end_hint))
            .build()

        picker.addOnPositiveButtonClickListener {
            val hour = picker.hour
            val minute = picker.minute
            if (isStart) {
                startHour = hour
                startMinute = minute
                tvStartTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            } else {
                endHour = hour
                endMinute = minute
                tvEndTimePicker.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            }
        }

        picker.show(supportFragmentManager, "time_picker")
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
                    endMinute = endMinute
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
