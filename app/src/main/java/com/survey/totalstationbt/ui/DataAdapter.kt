package com.survey.totalstationbt.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.survey.totalstationbt.R
import com.survey.totalstationbt.bluetooth.BluetoothSerialService
import java.text.SimpleDateFormat
import java.util.*

import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout

class DataAdapter(
    private val onAction: (Action) -> Unit
) : ListAdapter<BluetoothSerialService.ReceivedLine, DataAdapter.ViewHolder>(DIFF) {

    sealed class Action {
        data class Photo(val line: BluetoothSerialService.ReceivedLine) : Action()
        data class Share(val line: BluetoothSerialService.ReceivedLine) : Action()
        data class Edit(val line: BluetoothSerialService.ReceivedLine) : Action()
        data class SelectionChanged(val count: Int) : Action()
    }

    private val selectedLines = mutableSetOf<BluetoothSerialService.ReceivedLine>()

    fun getSelectedLines(): List<BluetoothSerialService.ReceivedLine> = selectedLines.toList()

    fun clearSelection() {
        selectedLines.clear()
        notifyDataSetChanged()
        onAction(Action.SelectionChanged(0))
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkItem: CheckBox = view.findViewById(R.id.checkItem)
        val tvTime: TextView   = view.findViewById(R.id.tvTime)
        val tvRaw: TextView    = view.findViewById(R.id.tvRaw)
        val tvParsed: TextView = view.findViewById(R.id.tvParsed)
        val indicator: View    = view.findViewById(R.id.indicator)
        val layoutActions: LinearLayout = view.findViewById(R.id.layoutActions)
        val btnRowPhoto: ImageView = view.findViewById(R.id.btnRowPhoto)
        val btnRowShare: ImageView = view.findViewById(R.id.btnRowShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_data_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx  = holder.itemView.context

        holder.checkItem.setOnCheckedChangeListener(null)
        holder.checkItem.isChecked = selectedLines.contains(item)
        holder.checkItem.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedLines.add(item) else selectedLines.remove(item)
            onAction(Action.SelectionChanged(selectedLines.size))
        }

        holder.itemView.setOnClickListener {
            onAction(Action.Edit(item))
        }

        holder.tvTime.text = timeFmt.format(Date(item.timestamp))
        holder.tvRaw.text  = item.raw

        if (item.parsed != null) {
            val pt = item.parsed
            holder.tvParsed.visibility = View.VISIBLE
            holder.tvParsed.text = buildString {
                append(pt.pointName)
                pt.easting?.let    { append("  E:${String.format("%.3f", it)}") }
                pt.northing?.let   { append("  N:${String.format("%.3f", it)}") }
                pt.elevation?.let  { append("  Z:${String.format("%.3f", it)}") }
                if (pt.code.isNotEmpty()) append("\n[${pt.code}]")
            }
            holder.indicator.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_connected))
            holder.layoutActions.visibility = View.VISIBLE
            
            holder.btnRowPhoto.visibility = View.VISIBLE
            holder.btnRowPhoto.setOnClickListener { onAction(Action.Photo(item)) }
            holder.btnRowShare.setOnClickListener { onAction(Action.Share(item)) }
        } else {
            holder.tvParsed.visibility = View.GONE
            holder.indicator.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_error))
            holder.layoutActions.visibility = View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BluetoothSerialService.ReceivedLine>() {
            override fun areItemsTheSame(a: BluetoothSerialService.ReceivedLine,
                                         b: BluetoothSerialService.ReceivedLine) = a.timestamp == b.timestamp
            override fun areContentsTheSame(a: BluetoothSerialService.ReceivedLine,
                                             b: BluetoothSerialService.ReceivedLine) = a == b
        }
    }
}
