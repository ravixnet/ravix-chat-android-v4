package net.ravix.chatoperator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.ravix.chatoperator.databinding.ItemConversationBinding
import net.ravix.chatoperator.model.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onClick: (Conversation) -> Unit,
) : ListAdapter<Conversation, ConversationAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Conversation) = with(binding) {
            title.text = item.guestName
            preview.text = item.lastMessage
            site.visibility = if (item.siteLabel.isBlank()) View.GONE else View.VISIBLE
            site.text = item.siteLabel
            time.text = if (item.updatedAt > 0) formatConversationTime(item.updatedAt) else ""
            onlineDot.visibility = if (item.guestOnline && !item.closed) View.VISIBLE else View.INVISIBLE
            unread.visibility = if (item.unreadCount > 0) View.VISIBLE else View.INVISIBLE
            unread.text = if (item.unreadCount > 99) "+99" else item.unreadCount.toString()
            closedLabel.visibility = if (item.closed || !item.guestOnline) View.VISIBLE else View.GONE
            closedLabel.text = if (item.closed) "بسته‌شده" else "آفلاین"
            root.alpha = if (item.closed || !item.guestOnline) 0.88f else 1f
            root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation) = oldItem == newItem
    }

    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale("fa"))
        private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale("fa"))

        private fun formatConversationTime(timestamp: Long): String {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply { timeInMillis = timestamp }
            val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) timeFormat.format(Date(timestamp)) else dateFormat.format(Date(timestamp))
        }
    }
}
