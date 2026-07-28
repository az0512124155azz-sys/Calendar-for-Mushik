package com.magic3d.gcalsearchadd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.libraries.places.api.model.AutocompletePrediction

/**
 * אדפטר פשוט לרשימת הצעות המיקום מ-Google Places Autocomplete.
 */
class LocationSuggestionAdapter(
    private var predictions: List<AutocompletePrediction>,
    private val onSelected: (AutocompletePrediction) -> Unit
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
        val prediction = predictions[position]
        holder.text.text = prediction.getFullText(null).toString()
        holder.itemView.setOnClickListener { onSelected(prediction) }
    }

    override fun getItemCount(): Int = predictions.size

    fun updatePredictions(newPredictions: List<AutocompletePrediction>) {
        predictions = newPredictions
        notifyDataSetChanged()
    }
}
