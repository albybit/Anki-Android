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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ichi2.anki.R
import com.ichi2.anki.aimode.AiModeDebugLogger
import com.ichi2.anki.aimode.AiModePreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Debug activity to view AI Mode logs
 * Accessible from settings or via developer options
 */
class AiModeDebugActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LogAdapter
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_mode_debug)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Mode Debug Logs"

        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter()
        recyclerView.adapter = adapter

        // Collect logs
        lifecycleScope.launch {
            AiModeDebugLogger.logsFlow.collectLatest { logs ->
                adapter.submitList(logs.toList())
                // Auto-scroll to bottom
                if (logs.isNotEmpty()) {
                    recyclerView.scrollToPosition(logs.size - 1)
                }
            }
        }

        // FAB to scroll to bottom
        findViewById<FloatingActionButton>(R.id.fab_scroll_bottom).setOnClickListener {
            val position = adapter.itemCount - 1
            if (position >= 0) {
                recyclerView.smoothScrollToPosition(position)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_mode_debug_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_copy_all -> {
                copyAllLogs()
                true
            }
            R.id.action_share -> {
                shareLogs()
                true
            }
            R.id.action_clear -> {
                clearLogs()
                true
            }
            R.id.action_filter_info -> {
                adapter.setMinLevel(AiModeDebugLogger.LogLevel.INFO)
                true
            }
            R.id.action_filter_debug -> {
                adapter.setMinLevel(AiModeDebugLogger.LogLevel.DEBUG)
                true
            }
            R.id.action_filter_verbose -> {
                adapter.setMinLevel(AiModeDebugLogger.LogLevel.VERBOSE)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun copyAllLogs() {
        val logs = AiModeDebugLogger.getAllLogsAsString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AI Mode Logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val file = AiModeDebugLogger.exportToFile(this)
        if (file != null) {
            val uri =
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file,
                )
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "AI Mode Debug Logs")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(Intent.createChooser(intent, "Share Logs"))
        } else {
            Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        AiModeDebugLogger.clearLogs()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, AiModeDebugActivity::class.java)
            context.startActivity(intent)
        }
    }
}
