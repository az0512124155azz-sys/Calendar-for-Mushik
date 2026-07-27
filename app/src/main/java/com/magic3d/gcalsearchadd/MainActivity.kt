package com.magic3d.gcalsearchadd

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var repository: CalendarRepository? = null
    private lateinit var adapter: EventAdapter

    // התאריך הנוכחי שנבחר בחיפוש (ברירת מחדל: היום)
    private var selectedDayStartMillis: Long = startOfDay(System.currentTimeMillis())

    private val displayDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // UI refs (נטענים ידנית כדי להימנע מתלות ב-view binding generation בסביבת התצוגה הזו)
    private lateinit var btnSignIn: View
    private lateinit var searchBar: View
    private lateinit var etSearch: android.widget.EditText
    private lateinit var btnPickDate: View
    private lateinit var btnSearch: View
    private lateinit var rvEvents: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvEmpty: android.widget.TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var fabAddEvent: View

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }
    }

    private val addEventLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // אירוע נשמר בהצלחה - נרענן את הרשימה ליום הנבחר
            loadEventsForSelectedDay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupGoogleSignIn()

        btnSignIn.setOnClickListener { signIn() }
        btnPickDate.setOnClickListener { showDatePicker() }
        btnSearch.setOnClickListener { performSearch() }
        fabAddEvent.setOnClickListener { openAddEvent() }

        adapter = EventAdapter(emptyList())
        rvEvents.layoutManager = LinearLayoutManager(this)
        rvEvents.adapter = adapter

        // ניסיון התחברות שקטה אם המשתמש כבר התחבר בעבר
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && hasCalendarScope(account)) {
            onSignedIn(account)
        } else {
            showSignedOutState()
        }
    }

    private fun bindViews() {
        btnSignIn = findViewById(R.id.btnSignIn)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnPickDate = findViewById(R.id.btnPickDate)
        btnSearch = findViewById(R.id.btnSearch)
        rvEvents = findViewById(R.id.rvEvents)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)
        fabAddEvent = findViewById(R.id.fabAddEvent)
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(CalendarScopes.CALENDAR))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun hasCalendarScope(account: GoogleSignInAccount): Boolean {
        return GoogleSignIn.hasPermissions(
            account,
            com.google.android.gms.common.api.Scope(CalendarScopes.CALENDAR)
        )
    }

    private fun signIn() {
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleSignInResult(task: com.google.android.gms.tasks.Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            onSignedIn(account)
        } catch (e: ApiException) {
            Toast.makeText(this, getString(R.string.error_generic, e.statusCode.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun onSignedIn(account: GoogleSignInAccount) {
        val androidAccount = account.account ?: run {
            Toast.makeText(this, "לא נמצא חשבון Google תקין", Toast.LENGTH_LONG).show()
            return
        }
        repository = CalendarRepository(this, androidAccount)
        showSignedInState()
        loadEventsForSelectedDay()
    }

    private fun showSignedOutState() {
        btnSignIn.visibility = View.VISIBLE
        searchBar.visibility = View.GONE
        rvEvents.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        fabAddEvent.visibility = View.GONE
    }

    private fun showSignedInState() {
        btnSignIn.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        rvEvents.visibility = View.VISIBLE
        fabAddEvent.visibility = View.VISIBLE
        etSearch.setText(displayDateFormat.format(selectedDayStartMillis))
    }

    private fun showDatePicker() {
        val cal = JavaCalendar.getInstance().apply { timeInMillis = selectedDayStartMillis }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = JavaCalendar.getInstance()
                picked.set(year, month, dayOfMonth, 0, 0, 0)
                picked.set(JavaCalendar.MILLISECOND, 0)
                selectedDayStartMillis = picked.timeInMillis
                etSearch.setText(displayDateFormat.format(selectedDayStartMillis))
                loadEventsForSelectedDay()
            },
            cal.get(JavaCalendar.YEAR),
            cal.get(JavaCalendar.MONTH),
            cal.get(JavaCalendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * מפעיל את החיפוש: אם הטקסט תואם פורמט תאריך dd/MM/yyyy או dd.MM.yyyy - מחפש לפי תאריך.
     * אחרת - מחפש לפי מילת מפתח בכל היומן.
     */
    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        val parsedDate = parseDateOrNull(query)
        if (parsedDate != null) {
            selectedDayStartMillis = parsedDate
            loadEventsForSelectedDay()
        } else if (query.isNotEmpty()) {
            searchByKeyword(query)
        } else {
            loadEventsForSelectedDay()
        }
    }

    private fun parseDateOrNull(text: String): Long? {
        val formats = listOf("dd/MM/yyyy", "dd.MM.yyyy", "dd-MM-yyyy")
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(text) ?: continue
                return startOfDay(date.time)
            } catch (_: Exception) {
                // ננסה את הפורמט הבא
            }
        }
        return null
    }

    private fun loadEventsForSelectedDay() {
        val repo = repository ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val events = repo.getEventsForDate(selectedDayStartMillis)
                renderEvents(events)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun searchByKeyword(keyword: String) {
        val repo = repository ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val events = repo.searchEventsByKeyword(keyword)
                renderEvents(events)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderEvents(events: List<com.magic3d.gcalsearchadd.model.EventItem>) {
        adapter.updateItems(events)
        tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        rvEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun openAddEvent() {
        val intent = Intent(this, AddEventActivity::class.java)
        intent.putExtra(AddEventActivity.EXTRA_DATE_MILLIS, selectedDayStartMillis)
        addEventLauncher.launch(intent)
    }

    companion object {
        fun startOfDay(millis: Long): Long {
            val cal = JavaCalendar.getInstance().apply {
                timeInMillis = millis
                set(JavaCalendar.HOUR_OF_DAY, 0)
                set(JavaCalendar.MINUTE, 0)
                set(JavaCalendar.SECOND, 0)
                set(JavaCalendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
