package com.magic3d.gcalsearchadd

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale

/**
 * מסך הוספת אירוע חדש.
 * לפי מסמך האיפיון: אם המשתמש לא בוחר שעות, ברירת המחדל היא 14:00 - 23:59.
 */
class AddEventActivity : AppCompatActivity() {

    private lateinit var etTitle: TextInputEditText
    private lateinit var tvDatePicker: TextView
    private lateinit var tvStartTimePicker: TextView
    private lateinit var tvEndTimePicker: TextView

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

        tvDatePicker.text = displayDateFormat.format(dateMillis)

        tvDatePicker.setOnClickListener { pickDate() }
        tvStartTimePicker.setOnClickListener { pickTime(isStart = true) }
        tvEndTimePicker.setOnClickListener { pickTime(isStart = false) }

        findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnSave).setOnClickListener { saveEvent() }
    }

    private fun pickDate() {
        val cal = JavaCalendar.getInstance().apply { timeInMillis = dateMillis }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = JavaCalendar.getInstance()
                picked.set(year, month, day, 0, 0, 0)
                picked.set(JavaCalendar.MILLISECOND, 0)
                dateMillis = picked.timeInMillis
                tvDatePicker.text = displayDateFormat.format(dateMillis)
            },
            cal.get(JavaCalendar.YEAR),
            cal.get(JavaCalendar.MONTH),
            cal.get(JavaCalendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime(isStart: Boolean) {
        val defaultHour = if (isStart) 14 else 23
        val defaultMinute = if (isStart) 0 else 59
        TimePickerDialog(
            this,
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
            defaultHour,
            defaultMinute,
            true
        ).show()
    }

    private fun saveEvent() {
        val title = etTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(this, getString(R.string.event_title_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)?.account
        if (account == null) {
            Toast.makeText(this, "יש להתחבר מחדש ל-Google", Toast.LENGTH_LONG).show()
            return
        }

        val repository = CalendarRepository(this, account)

        Toast.makeText(this, getString(R.string.saving_event), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                // אם לא נבחרו שעות, insertEvent יחיל אוטומטית את ברירת המחדל 14:00-23:59
                repository.insertEvent(
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
