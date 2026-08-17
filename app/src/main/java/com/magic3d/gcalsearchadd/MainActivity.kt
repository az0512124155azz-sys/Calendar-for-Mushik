@file:Suppress("DEPRECATION")

package com.magic3d.gcalsearchadd

import android.app.Activity
import android.app.Dialog
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar as JavaCalendar
import java.util.Locale
import java.util.TimeZone

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
    private lateinit var tvLanguageBadge: TextView
    private lateinit var profileButton: FrameLayout
    private lateinit var profileImage: ImageView
    private lateinit var profileInitial: TextView
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
        createProfileButton()
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
        OfflineSyncWorker.ensurePeriodicSync(this)
        OfflineSyncWorker.schedule(this)
        lifecycleScope.launch {
            runCatching { repository?.refreshCalendarCache() }
        }
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

    /** יוצר את כפתור הפרופיל בקוד כדי שלא יהיה צורך לשנות קובצי XML. */
    private fun createProfileButton() {
        val density = resources.displayMetrics.density
        profileButton = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = 8f * density
            setOnClickListener { showAccountMenu() }
        }
        profileInitial = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = circleDrawable(Color.rgb(54, 152, 245), Color.WHITE, 2f)
        }
        profileImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circleDrawable(Color.rgb(54, 152, 245), Color.WHITE, 2f)
            clipToOutline = true
            visibility = View.INVISIBLE
            contentDescription = accountText("settings")
        }
        profileButton.addView(profileInitial, FrameLayout.LayoutParams(-1, -1))
        profileButton.addView(profileImage, FrameLayout.LayoutParams(-1, -1))

        val size = (40f * density).toInt()
        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
            marginStart = (14f * density).toInt()
            topMargin = (11f * density).toInt()
        }
        findViewById<ViewGroup>(android.R.id.content).addView(profileButton, params)
    }

    private fun circleDrawable(fill: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0f) setStroke((strokeDp * resources.displayMetrics.density).toInt(), stroke)
        }

    private fun roundedDrawable(fill: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(fill)
            stroke?.let { setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), it) }
        }

    private fun updateProfileButton(account: GoogleSignInAccount) {
        profileInitial.text = account.displayName?.trim()?.firstOrNull()?.uppercase()
            ?: account.email?.trim()?.firstOrNull()?.uppercase()
                    ?: "?"
        profileInitial.visibility = View.VISIBLE
        profileImage.visibility = View.INVISIBLE
        profileImage.setImageDrawable(null)
        val photoUrl = account.photoUrl ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(photoUrl.toString()).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null && signedInAccount?.id == account.id) {
                profileImage.setImageBitmap(bitmap)
                profileImage.visibility = View.VISIBLE
                profileInitial.visibility = View.GONE
            }
        }
    }

    /** חלון חשבון מלא שנוצר בקוד, ללא layout או strings חדשים. */
    private fun showAccountMenu() {
        val account = signedInAccount ?: return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = roundedDrawable(Color.rgb(248, 248, 250), Color.WHITE)
        }
        val avatar = FrameLayout(this).apply {
            background = circleDrawable(Color.rgb(54, 152, 245), Color.WHITE, 2f)
        }
        val largeInitial = TextView(this).apply {
            text = profileInitial.text
            gravity = Gravity.CENTER
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val largeImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circleDrawable(Color.rgb(54, 152, 245), Color.WHITE, 2f)
            clipToOutline = true
        }
        avatar.addView(largeInitial, FrameLayout.LayoutParams(-1, -1))
        avatar.addView(largeImage, FrameLayout.LayoutParams(-1, -1))
        if (profileImage.drawable != null && profileImage.visibility == View.VISIBLE) {
            largeImage.setImageDrawable(profileImage.drawable)
            largeInitial.visibility = View.GONE
        } else largeImage.visibility = View.INVISIBLE
        root.addView(avatar, LinearLayout.LayoutParams(dp(72), dp(72)))

        fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = textValue
                gravity = Gravity.CENTER
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        root.addView(label(account.displayName ?: accountText("user"), 20f, Color.rgb(32,33,42), true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(label(account.email.orEmpty(), 14f, Color.rgb(104,107,122)), LinearLayout.LayoutParams(-1, -2))

        val manage = label(accountText("manage"), 15f, Color.rgb(54,152,245), true).apply {
            background = roundedDrawable(Color.rgb(238,246,255), Color.rgb(54,152,245))
            isClickable = true
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/"))) }
        }
        root.addView(manage, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(20) })

        val signOutButton = label(accountText("signout"), 15f, Color.WHITE, true).apply {
            background = roundedDrawable(Color.rgb(255,102,88))
            isClickable = true
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss(); signOut() }
        }
        root.addView(signOutButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        val cancel = label(accountText("cancel"), 14f, Color.rgb(104,107,122), true).apply {
            isClickable = true
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(cancel, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(6) })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun signOut() {
        setLoading(true)
        googleSignInClient.signOut().addOnCompleteListener {
            signedInAccount = null
            repository = null
            openedSwipeHolder = null
            adapter.updateItems(emptyList())
            profileImage.setImageDrawable(null)
            showSignedOutState()
            Toast.makeText(this, accountText("signedout"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun accountText(key: String): String {
        val lang = resources.configuration.locales[0].language
        val values = mapOf(
            "he" to mapOf("settings" to "הגדרות חשבון", "user" to "משתמש Google", "manage" to "ניהול חשבון Google", "signout" to "התנתקות", "signedout" to "התנתקת בהצלחה", "cancel" to "ביטול"),
            "iw" to mapOf("settings" to "הגדרות חשבון", "user" to "משתמש Google", "manage" to "ניהול חשבון Google", "signout" to "התנתקות", "signedout" to "התנתקת בהצלחה", "cancel" to "ביטול"),
            "ar" to mapOf("settings" to "إعدادات الحساب", "user" to "مستخدم Google", "manage" to "إدارة حساب Google", "signout" to "تسجيل الخروج", "signedout" to "تم تسجيل الخروج بنجاح", "cancel" to "إلغاء"),
            "ru" to mapOf("settings" to "Настройки аккаунта", "user" to "Пользователь Google", "manage" to "Управление аккаунтом Google", "signout" to "Выйти", "signedout" to "Вы вышли из аккаунта", "cancel" to "Отмена"),
            "fr" to mapOf("settings" to "Paramètres du compte", "user" to "Utilisateur Google", "manage" to "Gérer le compte Google", "signout" to "Se déconnecter", "signedout" to "Déconnexion réussie", "cancel" to "Annuler"),
            "en" to mapOf("settings" to "Account settings", "user" to "Google user", "manage" to "Manage Google Account", "signout" to "Sign out", "signedout" to "Signed out successfully", "cancel" to "Cancel")
        )
        return (values[lang] ?: values.getValue("en")).getValue(key)
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
        val repo = repository ?: return
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
                repo.deleteEvent(event.id)
                adapter.removeItemById(event.id)
                val isEmpty = adapter.itemCount == 0
                tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                rvEvents.visibility = if (isEmpty) View.GONE else View.VISIBLE
                Toast.makeText(
                    this@MainActivity,
                    if (repo.lastOperationQueuedOffline) R.string.event_delete_queued_offline else R.string.event_deleted,
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
