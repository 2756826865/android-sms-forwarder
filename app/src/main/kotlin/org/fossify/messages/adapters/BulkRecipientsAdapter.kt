package org.fossify.messages.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.messages.databinding.ItemBulkRecipientBinding
import org.fossify.messages.models.BulkRecipient

class BulkRecipientsAdapter(
    private val selectedNumbers: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<BulkRecipientsAdapter.ViewHolder>() {
    private var items = emptyList<BulkRecipient>()

    fun submitList(newItems: List<BulkRecipient>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun visibleNumbers(): List<String> = items.map { it.number }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBulkRecipientBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemBulkRecipientBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(recipient: BulkRecipient) = with(binding) {
            bulkRecipientName.text = recipient.name.ifBlank { recipient.number }
            bulkRecipientNumber.text = recipient.number
            bulkRecipientCheckbox.isChecked = recipient.number in selectedNumbers
            root.setOnClickListener {
                if (!selectedNumbers.add(recipient.number)) selectedNumbers.remove(recipient.number)
                bulkRecipientCheckbox.isChecked = recipient.number in selectedNumbers
                onSelectionChanged()
            }
        }
    }
}
