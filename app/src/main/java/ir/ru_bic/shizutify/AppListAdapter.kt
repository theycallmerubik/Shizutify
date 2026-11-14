package ir.ru_bic.shizutify

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.ru_bic.shizutify.databinding.ItemAppBinding

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean
)

class AppListAdapter(
    private val allApps: List<AppEntry>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private val visibleApps = mutableListOf<AppEntry>().apply { addAll(allApps) }

    inner class AppViewHolder(val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = visibleApps[position]
        holder.binding.apply {
            ivAppIcon.setImageDrawable(item.icon)
            tvAppName.text = item.label
            tvPackageName.text = item.packageName
            cbAppSelected.isChecked = item.isSelected

            root.setOnClickListener {
                item.isSelected = !item.isSelected
                cbAppSelected.isChecked = item.isSelected
                val selectedSet = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
                onSelectionChanged(selectedSet)
            }
        }
    }

    override fun getItemCount(): Int = visibleApps.size

    fun filter(query: String) {
        visibleApps.clear()
        if (query.isBlank()) {
            visibleApps.addAll(allApps)
        } else {
            val lower = query.lowercase()
            visibleApps.addAll(
                allApps.filter {
                    it.label.lowercase().contains(lower) ||
                            it.packageName.lowercase().contains(lower)
                }
            )
        }
        notifyDataSetChanged()
    }
}