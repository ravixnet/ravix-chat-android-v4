package net.ravix.chatoperator.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.ravix.chatoperator.databinding.ItemMessageGuestBinding
import net.ravix.chatoperator.databinding.ItemMessageOperatorBinding
import net.ravix.chatoperator.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(Diff) {
    override fun getItemViewType(position: Int) = if (getItem(position).isOperator) OPERATOR else GUEST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == OPERATOR) {
            OperatorHolder(ItemMessageOperatorBinding.inflate(inflater, parent, false))
        } else {
            GuestHolder(ItemMessageGuestBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is OperatorHolder -> holder.bind(getItem(position))
            is GuestHolder -> holder.bind(getItem(position))
        }
    }

    class OperatorHolder(private val binding: ItemMessageOperatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            binding.messageText.text = item.text
            binding.messageTime.text = formatMessageTime(item.createdAt)
            binding.messageState.text = if (item.seen) "✓✓ دیده شد" else "✓ ارسال شد"
        }
    }

    class GuestHolder(private val binding: ItemMessageGuestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            binding.messageText.text = item.text
            binding.messageTime.text = formatMessageTime(item.createdAt)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }

    companion object {
        private const val GUEST = 0
        private const val OPERATOR = 1
        private val timeFormat = SimpleDateFormat("HH:mm", Locale("fa"))
        private val dateTimeFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale("fa"))

        private fun formatMessageTime(timestamp: Long): String {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply { timeInMillis = timestamp }
            val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) timeFormat.format(Date(timestamp)) else dateTimeFormat.format(Date(timestamp))
        }
    }
}
