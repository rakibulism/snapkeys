package com.snapkeys.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.databinding.ItemShortcutBinding

/** Renders the list of shortcuts on the home screen. */
class ShortcutAdapter(
    private val onClick: (Shortcut) -> Unit,
    private val onDelete: (Shortcut) -> Unit,
) : RecyclerView.Adapter<ShortcutAdapter.Holder>() {

    private val items = mutableListOf<Shortcut>()

    fun submit(shortcuts: List<Shortcut>) {
        items.clear()
        items.addAll(shortcuts)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemShortcutBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemShortcutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(shortcut: Shortcut) {
            binding.trigger.text = shortcut.trigger
            binding.expansion.text = shortcut.expansion
            binding.root.setOnClickListener { onClick(shortcut) }
            binding.deleteButton.setOnClickListener { onDelete(shortcut) }
        }
    }
}
