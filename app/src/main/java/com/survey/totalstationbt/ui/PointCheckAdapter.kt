package com.survey.totalstationbt.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.survey.totalstationbt.databinding.ItemPointCheckableBinding
import com.survey.totalstationbt.db.PointEntity

class PointCheckAdapter(
    private val onToggle: (Long, Boolean) -> Unit
) : ListAdapter<PointEntity, PointCheckAdapter.ViewHolder>(DiffCallback()) {

    private val visibleIds = mutableSetOf<Long>()
    private var fullList: List<PointEntity> = emptyList()
    private var filterQuery: String = ""

    fun setVisibleIds(ids: Set<Long>) {
        visibleIds.clear()
        visibleIds.addAll(ids)
        notifyDataSetChanged()
    }

    /** 提交完整清單（取代直接呼叫 submitList） */
    fun submitPoints(list: List<PointEntity>) {
        fullList = list
        applyFilter()
    }

    /** 依點號或編碼篩選，空字串顯示全部 */
    fun filter(query: String) {
        filterQuery = query.trim()
        applyFilter()
    }

    private fun applyFilter() {
        val result = if (filterQuery.isEmpty()) fullList
        else fullList.filter {
            it.pointName.contains(filterQuery, ignoreCase = true) ||
            it.code.contains(filterQuery, ignoreCase = true)
        }
        submitList(result)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPointCheckableBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPointCheckableBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PointEntity) {
            binding.tvPointName.text = item.pointName
            binding.tvPointCoords.text = buildString {
                append(String.format("%.1f", item.easting ?: 0.0))
                append(", ")
                append(String.format("%.1f", item.northing ?: 0.0))
                if (item.note.isNotEmpty()) append("  ✎")
            }

            binding.checkBox.setOnCheckedChangeListener(null)
            binding.checkBox.isChecked = visibleIds.contains(item.id)
            binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item.id, isChecked)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PointEntity>() {
        override fun areItemsTheSame(oldItem: PointEntity, newItem: PointEntity) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PointEntity, newItem: PointEntity) =
            oldItem == newItem
    }
}
