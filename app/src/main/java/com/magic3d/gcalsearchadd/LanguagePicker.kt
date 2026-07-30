package com.magic3d.gcalsearchadd

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
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
 */
object LanguagePicker {

    private data class LangOption(val label: String, val tag: String?, val badge: String)

    private val options = listOf(
        LangOption("כמו בטלפון / System default", null, ""),
        LangOption("עברית", "he", "עב"),
        LangOption("English", "en", "EN"),
        LangOption("Français", "fr", "FR"),
        LangOption("Русский", "ru", "RU"),
        LangOption("العربية", "ar", "ع")
    )

    /** התג הקטן שמוצג בכפתור - לפי השפה הנוכחית בפועל של האפליקציה. */
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
        val btnCancel = view.findViewById<android.view.View>(R.id.btnDialogCancel)

        tvTitle.text = "שפת האפליקציה / App language"

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rippleValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        options.forEach { option ->
            val row = TextView(activity).apply {
                text = option.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(36, 30, 36, 30)
                background = ContextCompat.getDrawable(context, R.drawable.bg_rounded_field)
                foreground = ContextCompat.getDrawable(context, rippleValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    val localeList = if (option.tag == null) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(option.tag)
                    }
                    AppCompatDelegate.setApplicationLocales(localeList)
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 16
            container.addView(row, params)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
