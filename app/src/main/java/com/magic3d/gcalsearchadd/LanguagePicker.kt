package com.magic3d.gcalsearchadd

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * בורר שפה ידני לאפליקציה - עצמאי משפת המכשיר. הבחירה נשמרת אוטומטית
 * (AppCompatDelegate) ותחול על כל המסכים באפליקציה מיידית.
 * שמות השפות עצמן תמיד מוצגים בשפתם המקורית (מוסכמה סטנדרטית) - שאר טקסטי הדיאלוג
 * מתורגמים לפי השפה הפעילה הנוכחית, כדי שלא ייראו מעורבבים.
 */
object LanguagePicker {

    // צבע הדגשה קטן לכל שפה - לזיהוי ויזואלי מהיר, תואם לפלטת האפליקציה
    private data class LangOption(val label: String, val tag: String?, val accentColorRes: Int)

    private fun buildOptions(activity: Activity): List<LangOption> = listOf(
        LangOption(activity.getString(R.string.language_system_default), null, R.color.text_secondary),
        LangOption("עברית", "he", R.color.accent_blue),
        LangOption("English", "en", R.color.accent_green),
        LangOption("Français", "fr", R.color.accent_purple),
        LangOption("Русский", "ru", R.color.accent_orange),
        LangOption("العربية", "ar", R.color.accent_red)
    )

    fun currentBadgeText(): String {
        val applied = AppCompatDelegate.getApplicationLocales()
        val lang = if (!applied.isEmpty) applied[0]?.language else Locale.getDefault().language
        return when (lang) {
            "he", "iw" -> "עב"
            "en" -> "EN"
            "fr" -> "FR"
            "ru" -> "RU"
            "ar" -> "ع"
            else -> "EN"
        }
    }

    fun show(activity: Activity) {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_option_list, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val container = view.findViewById<LinearLayout>(R.id.optionsContainer)
        val btnCancel = view.findViewById<View>(R.id.btnDialogCancel)

        tvTitle.text = activity.getString(R.string.language_picker_title)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rippleValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val currentTag: String? = run {
            val applied = AppCompatDelegate.getApplicationLocales()
            if (applied.isEmpty) null else applied[0]?.language
        }

        buildOptions(activity).forEachIndexed { index, option ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_option_row)
                foreground = ContextCompat.getDrawable(context, rippleValue.resourceId)
                isClickable = true
                isFocusable = true
                setPadding(36, 32, 36, 32)
            }

            val dot = View(activity).apply {
                val size = 20
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = ContextCompat.getDrawable(context, R.drawable.bg_widget_circle)
                backgroundTintList = ContextCompat.getColorStateList(context, option.accentColorRes)
            }

            val label = TextView(activity).apply {
                text = option.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                params.marginStart = 24
                layoutParams = params
            }

            row.addView(dot)
            row.addView(label)

            // וי ✓ ליד השפה הפעילה כרגע
            val isSelected = (option.tag == null && currentTag == null) || (option.tag != null && option.tag == currentTag)
            if (isSelected) {
                val check = TextView(activity).apply {
                    text = "✓"
                    textSize = 17f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                row.addView(check)
            }

            row.setOnClickListener {
                dialog.dismiss()
                val localeList = if (option.tag == null) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(option.tag)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
            }

            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.topMargin = if (index == 0) 0 else 14
            container.addView(row, rowParams)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
