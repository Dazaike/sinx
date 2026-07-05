package com.sinx.notifbridge

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppEntry(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class AppPickerAdapter(
    private val allApps: List<AppEntry>,
    private val blocked: MutableSet<String>
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var filtered: List<AppEntry> = allApps

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivAppIcon)
        val name: TextView  = view.findViewById(R.id.tvAppName)
        val pkg:  TextView  = view.findViewById(R.id.tvPackageName)
        val check: CheckBox = view.findViewById(R.id.cbBlocked)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_picker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = filtered[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.name
        holder.pkg.text  = app.packageName
        holder.check.isChecked = app.packageName in blocked

        holder.itemView.setOnClickListener {
            val nowBlocked = app.packageName !in blocked
            if (nowBlocked) blocked.add(app.packageName) else blocked.remove(app.packageName)
            holder.check.isChecked = nowBlocked
        }
    }

    override fun getItemCount() = filtered.size

    fun filter(query: String) {
        filtered = if (query.isBlank()) allApps
        else allApps.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun getBlocked(): Set<String> = blocked.toSet()
}
