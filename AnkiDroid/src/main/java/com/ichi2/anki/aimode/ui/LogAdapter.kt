/*
 * Copyright (c) 2025 Alberto Vargas
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.aimode.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.R
import com.ichi2.anki.aimode.AiModeDebugLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying log entries
 */
class LogAdapter : ListAdapter<AiModeDebugLogger.LogEntry, LogAdapter.LogViewHolder>(DiffCallback()) {
    private var minLevel = AiModeDebugLogger.LogLevel.VERBOSE

    fun setMinLevel(level: AiModeDebugLogger.LogLevel) {
        minLevel = level
        // Refresh the list
        submitList(currentList.filter { it.level.ordinal >= level.ordinal })
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LogViewHolder {
        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: LogViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    override fun submitList(list: List<AiModeDebugLogger.LogEntry>?) {
        val filtered = list?.filter { it.level.ordinal >= minLevel.ordinal }
        super.submitList(filtered)
    }

    class LogViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val timeText: TextView = itemView.findViewById(R.id.log_time)
        private val levelText: TextView = itemView.findViewById(R.id.log_level)
        private val tagText: TextView = itemView.findViewById(R.id.log_tag)
        private val messageText: TextView = itemView.findViewById(R.id.log_message)

        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        fun bind(entry: AiModeDebugLogger.LogEntry) {
            timeText.text = timeFormat.format(Date(entry.timestamp))
            levelText.text = entry.level.name
            tagText.text = entry.tag
            messageText.text = entry.message

            // Color code by level
            val color =
                when (entry.level) {
                    AiModeDebugLogger.LogLevel.VERBOSE -> Color.GRAY
                    AiModeDebugLogger.LogLevel.DEBUG -> Color.parseColor("#2196F3") // Blue
                    AiModeDebugLogger.LogLevel.INFO -> Color.parseColor("#4CAF50") // Green
                    AiModeDebugLogger.LogLevel.WARN -> Color.parseColor("#FF9800") // Orange
                    AiModeDebugLogger.LogLevel.ERROR -> Color.parseColor("#F44336") // Red
                }
            levelText.setTextColor(color)

            // Show error details if present
            if (entry.throwable != null) {
                messageText.text = "${entry.message}\n${entry.throwable.stackTraceToString()}"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AiModeDebugLogger.LogEntry>() {
        override fun areItemsTheSame(
            oldItem: AiModeDebugLogger.LogEntry,
            newItem: AiModeDebugLogger.LogEntry,
        ): Boolean =
            oldItem.timestamp == newItem.timestamp &&
                oldItem.tag == newItem.tag &&
                oldItem.message == newItem.message

        override fun areContentsTheSame(
            oldItem: AiModeDebugLogger.LogEntry,
            newItem: AiModeDebugLogger.LogEntry,
        ): Boolean = oldItem == newItem
    }
}
