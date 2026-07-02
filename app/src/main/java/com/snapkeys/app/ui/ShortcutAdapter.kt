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
    private val onToggle: (Shortcut, Boolean) -> Unit,
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
            // Detach the listener before setting state: recycled rows would
            // otherwise fire the previous shortcut's toggle.
            binding.enabledSwitch.setOnCheckedChangeListener(null)
            binding.enabledSwitch.isChecked = shortcut.enabled
            binding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle(shortcut, checked)
            }
            val alpha = if (shortcut.enabled) 1f else 0.4f
            binding.trigger.alpha = alpha
            binding.expansion.alpha = alpha
            binding.root.setOnClickListener { onClick(shortcut) }
            binding.deleteButton.setOnClickListener { onDelete(shortcut) }
        }
    }
}
