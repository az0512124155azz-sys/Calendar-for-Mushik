package com.magic3d.gcalsearchadd

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.magic3d.gcalsearchadd.model.EventItem

/**
 * מציג אירועים ומנהל את הכרטיס הקדמי ואת פעולת המחיקה שנחשפת מאחוריו.
 */
class EventAdapter(
    initialItems: List<EventItem>,
    private var showDate: Boolean = true,
    private val onEditClick: ((EventItem) -> Unit)? = null,
    private val onDeleteClick: ((EventItem) -> Unit)? = null
) : RecyclerView.Adapter<EventAdapter.EventViewHolder>() {

    private val items = initialItems.toMutableList()

    inner class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val swipeForeground: View = view.findViewById(R.id.swipeForeground)
        val deleteButton: View = view.findViewById(R.id.btnDeleteEvent)
        val colorBar: View = view.findViewById(R.id.colorBar)
        val title: TextView = view.findViewById(R.id.tvEventTitle)
        val time: TextView = view.findViewById(R.id.tvEventTime)
        val location: TextView = view.findViewById(R.id.tvEventLocation)
        val editButton: View = view.findViewById(R.id.btnEditEvent)
    }

    private val accentColorResIds = listOf(
        R.color.accent_blue,
        R.color.accent_red,
        R.color.accent_green,
        R.color.accent_orange,
        R.color.accent_purple,
        R.color.accent_yellow,
        R.color.accent_teal
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val accentColor = ContextCompat.getColor(
            context,
            accentColorResIds[position % accentColorResIds.size]
        )

        holder.swipeForeground.translationX = 0f
        holder.deleteButton.visibility = View.VISIBLE
        holder.deleteButton.alpha = 0f
        holder.deleteButton.clipBounds = null
        holder.title.text = item.title
        holder.time.text = if (showDate) {
            "${item.timeLabel}  •  ${item.dateLabel}"
        } else {
            item.timeLabel
        }
        holder.time.setTextColor(ContextCompat.getColor(context, R.color.white))

        holder.time.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 200f
            setColor(accentColor)
        }

        holder.colorBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(accentColor)
        }

        if (!item.location.isNullOrBlank()) {
            holder.location.text = "— ${item.location}"
            holder.location.visibility = View.VISIBLE
        } else {
            holder.location.visibility = View.GONE
        }

        if (onEditClick != null) {
            holder.editButton.visibility = View.VISIBLE
            holder.editButton.setOnClickListener { onEditClick.invoke(item) }
        } else {
            holder.editButton.visibility = View.GONE
            holder.editButton.setOnClickListener(null)
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick?.invoke(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<EventItem>, showDate: Boolean = this.showDate) {
        items.clear()
        items.addAll(newItems)
        this.showDate = showDate
        notifyDataSetChanged()
    }

    fun removeItemById(eventId: String) {
        val index = items.indexOfFirst { it.id == eventId }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun closeAllSwipeActions() {
        notifyItemRangeChanged(0, itemCount)
    }
}
