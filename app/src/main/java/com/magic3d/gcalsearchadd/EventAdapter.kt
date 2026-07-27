package com.magic3d.gcalsearchadd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.magic3d.gcalsearchadd.model.EventItem

/**
 * אדפטר פשוט להצגת רשימת האירועים בכרטיסיות בסגנון Google Calendar.
 */
class EventAdapter(private var items: List<EventItem>) :
    RecyclerView.Adapter<EventAdapter.EventViewHolder>() {

    inner class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvEventTitle)
        val time: TextView = view.findViewById(R.id.tvEventTime)
        val location: TextView = view.findViewById(R.id.tvEventLocation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.time.text = item.timeRange
        if (!item.location.isNullOrBlank()) {
            holder.location.text = item.location
            holder.location.visibility = View.VISIBLE
        } else {
            holder.location.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<EventItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
