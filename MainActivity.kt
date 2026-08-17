package com.magic3d.gcalsearchadd

import android.app.Activity
import android.app.Dialog
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale
import java.util.TimeZone
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var repository: CalendarRepository? = null
    private var eventDeleter: CalendarEventDeleter? = null
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
    private lateinit var tvLanguageBadge: TextView
    private lateinit var profileButton: View
    private lateinit var ivGoogleProfile: ImageView
    private lateinit var tvProfileInitial: TextView
    private var signedInAccount: GoogleSignInAccount? = null
    private var openedSwipeHolder: EventAdapter.EventViewHolder? = null

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

        selectedDayStartMillis = if (
            savedInstanceState?.containsKey(STATE_SELECTED_DAY_START_MILLIS) == true
        ) {
            savedInstanceState.getLong(STATE_SELECTED_DAY_START_MILLIS)
        } else {
            startOfDay(System.currentTimeMillis())
        }

        bindViews()
        setupGoogleSignIn()

        btnSignIn.setOnClickListener { signIn() }
        btnPickDate.setOnClickListener { showDatePicker() }
        btnSearch.setOnClickListener { performSearch() }
        fabAddEvent.setOnClickListener { openAddEvent() }
        findViewById<View>(R.id.btnSupportEmail).setOnClickListener { openSupportEmail() }

        tvLanguageBadge.text = LanguagePicker.currentBadgeText()
        tvLanguageBadge.setOnClickListener { LanguagePicker.show(this) }
        tvLanguageBadge.setOnLongClickListener {
            LanguagePicker.showDebugInfo(this)
            true
        }
        profileButton.setOnClickListener { showAccountMenu() }

        adapter = EventAdapter(
            emptyList(),
            onEditClick = { event -> openEditEvent(event) },
            onDeleteClick = { event -> showDeleteConfirmation(event) }
        )
        rvEvents.layoutManager = LinearLayoutManager(this)
        rvEvents.adapter = adapter
        setupSwipeToDelete()

        // ניסיון התחברות שקטה אם המשתמש כבר התחבר בעבר
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && hasCalendarScope(account)) {
            onSignedIn(account)
        } else {
            showSignedOutState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_SELECTED_DAY_START_MILLIS, selectedDayStartMillis)
        super.onSaveInstanceState(outState)
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
        tvLanguageBadge = findViewById(R.id.tvLanguageBadge)
        profileButton = findViewById(R.id.profileButton)
        ivGoogleProfile = findViewById(R.id.ivGoogleProfile)
        tvProfileInitial = findViewById(R.id.tvProfileInitial)
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
            Toast.makeText(this, getString(R.string.error_no_valid_account), Toast.LENGTH_LONG).show()
            return
        }
        repository = CalendarRepository(this, androidAccount)
        eventDeleter = CalendarEventDeleter(this, androidAccount)
        signedInAccount = account
        updateProfileButton(account)
        showSignedInState()
        loadEventsForSelectedDay()
    }

    private fun showSignedOutState() {
        btnSignIn.visibility = View.VISIBLE
        searchBar.visibility = View.GONE
        rvEvents.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        fabAddEvent.visibility = View.GONE
        profileButton.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun showSignedInState() {
        btnSignIn.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        rvEvents.visibility = View.VISIBLE
        fabAddEvent.visibility = View.VISIBLE
        profileButton.visibility = View.VISIBLE
        etSearch.setText(displayDateFormat.format(selectedDayStartMillis))
    }

    private fun updateProfileButton(account: GoogleSignInAccount) {
        val fallback = account.displayName?.trim()?.firstOrNull()?.uppercase()
            ?: account.email?.trim()?.firstOrNull()?.uppercase()
            ?: "?"
        tvProfileInitial.text = fallback
        tvProfileInitial.visibility = View.VISIBLE
        ivGoogleProfile.visibility = View.INVISIBLE
        ivGoogleProfile.setImageDrawable(null)

        val photoUrl = account.photoUrl ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(photoUrl.toString()).openStream().use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                    .getOrNull()
            }
            if (bitmap != null && signedInAccount?.id == account.id) {
                ivGoogleProfile.setImageBitmap(bitmap)
                ivGoogleProfile.visibility = View.VISIBLE
                tvProfileInitial.visibility = View.GONE
            }
        }
    }

    private fun showAccountMenu() {
        val account = signedInAccount ?: return
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_account_menu)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.let { window ->
            val params = window.attributes
            params.dimAmount = 0.45f
            window.attributes = params
        }

        dialog.findViewById<TextView>(R.id.tvAccountName).text =
            account.displayName ?: getString(R.string.account_default_name)
        dialog.findViewById<TextView>(R.id.tvAccountEmail).text = account.email.orEmpty()

        val dialogImage = dialog.findViewById<ImageView>(R.id.ivAccountProfile)
        val dialogInitial = dialog.findViewById<TextView>(R.id.tvAccountInitial)
        dialogInitial.text = tvProfileInitial.text
        if (ivGoogleProfile.drawable != null && ivGoogleProfile.visibility == View.VISIBLE) {
            dialogImage.setImageDrawable(ivGoogleProfile.drawable)
            dialogImage.visibility = View.VISIBLE
            dialogInitial.visibility = View.GONE
        }

        dialog.findViewById<View>(R.id.btnManageGoogleAccount).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/")))
        }
        dialog.findViewById<View>(R.id.btnAccountCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnSignOut).setOnClickListener {
            dialog.dismiss()
            signOut()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun signOut() {
        setLoading(true)
        googleSignInClient.signOut().addOnCompleteListener {
            signedInAccount = null
            repository = null
            eventDeleter = null
            openedSwipeHolder = null
            adapter.updateItems(emptyList())
            ivGoogleProfile.setImageDrawable(null)
            showSignedOutState()
            Toast.makeText(this, R.string.signed_out_successfully, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTheme(R.style.ThemeOverlay_App_DatePicker)
            .setSelection(localStartOfDayToUtcMillis(selectedDayStartMillis))
            .setCalendarConstraints(CalendarConstraints.Builder().build())
            .build()

        picker.addOnPositiveButtonClickListener { utcMillis ->
            selectedDayStartMillis = utcMidnightToLocalStartOfDay(utcMillis)
            etSearch.setText(displayDateFormat.format(selectedDayStartMillis))
            loadEventsForSelectedDay()
        }

        picker.show(supportFragmentManager, "date_picker")
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
                renderEvents(events, showDate = false)
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
                renderEvents(events, showDate = true)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderEvents(events: List<com.magic3d.gcalsearchadd.model.EventItem>, showDate: Boolean) {
        adapter.updateItems(events, showDate)
        tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        rvEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /**
     * החלקה פיזית ימינה מזיזה רק את הכרטיס הקדמי וחושפת את כפתור המחיקה האדום.
     * המחיקה עצמה מתבצעת רק בלחיצה על הכפתור ולא בזמן ההחלקה.
     */
    private fun setupSwipeToDelete() {
        val actionWidth = deleteActionWidth()
        var activeHolder: EventAdapter.EventViewHolder? = null
        var shouldRemainOpen = false

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val holder = viewHolder as? EventAdapter.EventViewHolder ?: return
                setSwipeReveal(holder, actionWidth, actionWidth)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

            override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val holder = viewHolder as? EventAdapter.EventViewHolder ?: return
                    if (openedSwipeHolder !== holder) {
                        openedSwipeHolder?.let { animateSwipeReveal(it, 0f, actionWidth) }
                        openedSwipeHolder = null
                    }
                    activeHolder = holder
                    shouldRemainOpen = false
                }
            }

            override fun onChildDraw(
                canvas: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val holder = viewHolder as? EventAdapter.EventViewHolder ?: return
                val translation = dX.coerceIn(0f, actionWidth)
                setSwipeReveal(holder, translation, actionWidth)
                if (isCurrentlyActive) {
                    activeHolder = holder
                    shouldRemainOpen = translation >= actionWidth * 0.4f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                val holder = viewHolder as? EventAdapter.EventViewHolder ?: return
                if (activeHolder === holder && shouldRemainOpen) {
                    animateSwipeReveal(holder, actionWidth, actionWidth)
                    openedSwipeHolder = holder
                } else {
                    animateSwipeReveal(holder, 0f, actionWidth)
                    if (openedSwipeHolder === holder) openedSwipeHolder = null
                }
                activeHolder = null
                shouldRemainOpen = false
            }
        }

        ItemTouchHelper(callback).attachToRecyclerView(rvEvents)
    }

    private fun deleteActionWidth(): Float = 120f * resources.displayMetrics.density

    /** מציג רק את החלק האדום שנחשף בפועל, גם כשהכרטיס הקדמי שקוף. */
    private fun setSwipeReveal(
        holder: EventAdapter.EventViewHolder,
        translation: Float,
        actionWidth: Float
    ) {
        val revealedWidth = translation.coerceIn(0f, actionWidth)
        holder.swipeForeground.translationX = revealedWidth
        holder.deleteButton.visibility = if (revealedWidth > 0f) View.VISIBLE else View.INVISIBLE
        holder.deleteButton.clipBounds = Rect(
            0,
            0,
            revealedWidth.toInt(),
            holder.deleteButton.height.coerceAtLeast(holder.itemView.height)
        )
    }

    private fun animateSwipeReveal(
        holder: EventAdapter.EventViewHolder,
        target: Float,
        actionWidth: Float
    ) {
        ValueAnimator.ofFloat(holder.swipeForeground.translationX, target).apply {
            duration = 150L
            addUpdateListener {
                setSwipeReveal(holder, it.animatedValue as Float, actionWidth)
            }
            start()
        }
    }

    private fun showDeleteConfirmation(event: com.magic3d.gcalsearchadd.model.EventItem) {
        openedSwipeHolder?.let { animateSwipeReveal(it, 0f, deleteActionWidth()) }
        openedSwipeHolder = null

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_delete_event)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.let { window ->
            val params = window.attributes
            params.dimAmount = 0.45f
            window.attributes = params
        }
        dialog.findViewById<TextView>(R.id.tvDeleteEventTitle).text =
            getString(R.string.delete_event_title)
        dialog.findViewById<TextView>(R.id.tvDeleteEventMessage).text =
            getString(R.string.delete_event_message, event.title)
        dialog.findViewById<View>(R.id.btnDeleteCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnDeleteConfirm).setOnClickListener {
            dialog.dismiss()
            deleteEvent(event)
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun deleteEvent(event: com.magic3d.gcalsearchadd.model.EventItem) {
        val deleter = eventDeleter ?: return
        if (event.id.isBlank()) {
            Toast.makeText(
                this,
                getString(R.string.error_generic, "Missing event ID"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                deleter.deleteEvent(event.id)
                adapter.removeItemById(event.id)
                val isEmpty = adapter.itemCount == 0
                tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rvEvents.visibility = if (isEmpty) View.GONE else View.VISIBLE
                Toast.makeText(
                    this@MainActivity,
                    R.string.event_deleted,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                adapter.closeAllSwipeActions()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_generic, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
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

    private fun openAddEvent() {
        val intent = Intent(this, AddEventActivity::class.java)
        intent.putExtra(AddEventActivity.EXTRA_DATE_MILLIS, selectedDayStartMillis)
        addEventLauncher.launch(intent)
    }

    private fun openEditEvent(event: com.magic3d.gcalsearchadd.model.EventItem) {
        val intent = Intent(this, AddEventActivity::class.java)
        intent.putExtra(AddEventActivity.EXTRA_DATE_MILLIS, selectedDayStartMillis)
        intent.putExtra(AddEventActivity.EXTRA_EVENT_ID, event.id)
        addEventLauncher.launch(intent)
    }

    companion object {
        private const val STATE_SELECTED_DAY_START_MILLIS = "selected_day_start_millis"

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

        /**
         * MaterialDatePicker עובד תמיד ב-UTC. הפונקציות הבאות ממירות בין "תחילת יום מקומי"
         * (המשמש בכל שאר האפליקציה) ל"חצות UTC" של אותו תאריך קלנדרי (המשמש רק לתקשורת עם הבורר).
         */
        fun localStartOfDayToUtcMillis(localMillis: Long): Long {
            val local = JavaCalendar.getInstance().apply { timeInMillis = localMillis }
            val utc = JavaCalendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.clear()
            utc.set(
                local.get(JavaCalendar.YEAR),
                local.get(JavaCalendar.MONTH),
                local.get(JavaCalendar.DAY_OF_MONTH),
                0, 0, 0
            )
            return utc.timeInMillis
        }

        fun utcMidnightToLocalStartOfDay(utcMillis: Long): Long {
            val utc = JavaCalendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
            val local = JavaCalendar.getInstance()
            local.set(
                utc.get(JavaCalendar.YEAR),
                utc.get(JavaCalendar.MONTH),
                utc.get(JavaCalendar.DAY_OF_MONTH),
                0, 0, 0
            )
            local.set(JavaCalendar.MILLISECOND, 0)
            return local.timeInMillis
        }

        /**
         * שומר על כל שאר הפורמט של השפה (חודשים, שמות ימים וכו') אבל מכריח את היומן
         * להתחיל ביום ראשון - בלי קשר להגדרת "יום ראשון בשבוע" שמגיעה עם השפה של המכשיר.
         */
        fun sundayStartLocale(base: Locale): Locale {
            return Locale.Builder()
                .setLocale(base)
                .setExtension(Locale.UNICODE_LOCALE_EXTENSION, "fw-sun")
                .build()
        }
    }
}
