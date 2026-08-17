package com.magic3d.gcalsearchadd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * אדפטר פשוט לרשימת הצעות המיקום (כתובות מלאות שהוחזרו מ-NominatimClient).
 */
class LocationSuggestionAdapter(
    private var suggestions: List<String>,
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<LocationSuggestionAdapter.SuggestionViewHolder>() {

    inner class SuggestionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.tvSuggestionText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_suggestion, parent, false)
        return SuggestionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val suggestion = suggestions[position]
        holder.text.text = suggestion
        holder.itemView.setOnClickListener { onSelected(suggestion) }
    }

    override fun getItemCount(): Int = suggestions.size

    fun updateSuggestions(newSuggestions: List<String>) {
        suggestions = newSuggestions
        notifyDataSetChanged()
    }
}
