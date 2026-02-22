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

package com.ichi2.anki.aimode

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug logger for AI Mode that stores logs in memory and persists critical logs
 * Allows viewing logs inside the app and copying them
 */
object AiModeDebugLogger {
    private const val MAX_LOG_ENTRIES = 1000
    private const val PREFS_NAME = "ai_mode_debug_logs"
    private const val KEY_LOGS = "stored_logs"

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    ) {
        fun toFormattedString(): String {
            val timeStr =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date(timestamp))
            val levelStr = level.name.padEnd(5)
            val errorStr = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            return "[$timeStr] $levelStr [$tag] $message$errorStr"
        }
    }

    enum class LogLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    private val logEntries = CopyOnWriteArrayList<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow

    private var isEnabled = true

    /**
     * Log a verbose message
     */
    fun v(
        tag: String,
        message: String,
    ) {
        log(LogLevel.VERBOSE, tag, message)
    }

    /**
     * Log a debug message
     */
    fun d(
        tag: String,
        message: String,
    ) {
        log(LogLevel.DEBUG, tag, message)
    }

    /**
     * Log an info message
     */
    fun i(
        tag: String,
        message: String,
    ) {
        log(LogLevel.INFO, tag, message)
    }

    /**
     * Log a warning message
     */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    /**
     * Log an error message
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    /**
     * Log state change for debugging
     */
    fun logStateChange(
        oldState: AiModeState?,
        newState: AiModeState,
        reason: String = "",
    ) {
        val oldName = oldState?.javaClass?.simpleName ?: "null"
        val newName = newState.javaClass.simpleName
        val extra = if (reason.isNotEmpty()) " ($reason)" else ""
        i("StateMachine", "State: $oldName -> $newName$extra")

        // Log state-specific details
        when (newState) {
            is AiModeState.ListeningAnswer -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
                d("StateMachine", "  Expected: ${newState.expectedAnswer}")
            }
            is AiModeState.Validating -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
                d("StateMachine", "  Spoken: ${newState.spokenText}")
                d("StateMachine", "  Response time: ${newState.responseTimeMs}ms")
            }
            is AiModeState.AnswerCorrect -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
                d("StateMachine", "  Response time: ${newState.responseTimeMs}ms")
                d("StateMachine", "  Spoken: ${newState.spokenText}")
            }
            is AiModeState.AnswerWrong -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
                d("StateMachine", "  Expected: ${newState.expectedAnswer}")
                d("StateMachine", "  Spoken: ${newState.spokenText}")
            }
            is AiModeState.ListeningForRating -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
            }
            is AiModeState.RatingCard -> {
                d("StateMachine", "  Card ID: ${newState.cardId}")
                d("StateMachine", "  Rating: ${newState.rating}")
                d("StateMachine", "  Auto-rated: ${newState.isAutoRated}")
            }
            is AiModeState.Error -> {
                e("StateMachine", "  Error: ${newState.message}")
            }
            else -> {}
        }
    }

    /**
     * Log API request/response for debugging
     */
    fun logApiCall(
        apiName: String,
        endpoint: String,
        requestBody: String? = null,
        responseBody: String? = null,
        error: Throwable? = null,
    ) {
        d("API", "[$apiName] Endpoint: $endpoint")
        requestBody?.let { d("API", "[$apiName] Request: ${it.take(500)}") }
        responseBody?.let { d("API", "[$apiName] Response: ${it.take(500)}") }
        error?.let { e("API", "[$apiName] Error: ${it.message}", it) }
    }

    /**
     * Log TTS operations
     */
    fun logTTS(
        text: String,
        voice: String,
        cached: Boolean,
        durationMs: Long? = null,
    ) {
        val cacheStr = if (cached) "[CACHED]" else "[API]"
        val durationStr = durationMs?.let { " (${it}ms)" } ?: ""
        i("TTS", "$cacheStr Voice: $voice$durationStr")
        d("TTS", "Text: ${text.take(100)}")
    }

    /**
     * Log STT operations
     */
    fun logSTT(
        event: String,
        details: String = "",
    ) {
        when (event) {
            "START" -> i("STT", "Started listening")
            "STOP" -> i("STT", "Stopped listening")
            "RESULT" -> i("STT", "Result: $details")
            "ERROR" -> e("STT", "Error: $details")
            "SILENCE" -> d("STT", "Silence detected")
            else -> d("STT", "$event: $details")
        }
    }

    /**
     * Get all logs as formatted string for copying
     */
    fun getAllLogsAsString(): String = logEntries.joinToString("\n") { it.toFormattedString() }

    /**
     * Get logs filtered by level
     */
    fun getLogsByLevel(level: LogLevel): List<LogEntry> = logEntries.filter { it.level.ordinal >= level.ordinal }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        logEntries.clear()
        _logsFlow.value = emptyList()
        i("Logger", "Logs cleared")
    }

    /**
     * Enable/disable logging
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /**
     * Export logs to file (for sharing)
     */
    fun exportToFile(context: Context): java.io.File? =
        try {
            val file = java.io.File(context.cacheDir, "ai_mode_logs_${System.currentTimeMillis()}.txt")
            file.writeText(getAllLogsAsString())
            i("Logger", "Logs exported to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            e("Logger", "Failed to export logs", e)
            null
        }

    private fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!isEnabled) return

        val entry =
            LogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
            )

        // Add to in-memory list
        logEntries.add(entry)

        // Trim if too many
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.removeAt(0)
        }

        // Update flow
        _logsFlow.value = logEntries.toList()

        // Also log to Timber
        when (level) {
            LogLevel.VERBOSE -> Timber.v("[$tag] $message")
            LogLevel.DEBUG -> Timber.d("[$tag] $message")
            LogLevel.INFO -> Timber.i("[$tag] $message")
            LogLevel.WARN -> Timber.w(throwable, "[$tag] $message")
            LogLevel.ERROR -> Timber.e(throwable, "[$tag] $message")
        }
    }
}
