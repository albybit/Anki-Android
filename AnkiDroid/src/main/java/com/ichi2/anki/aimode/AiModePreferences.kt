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
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp

/**
 * Preferences manager for AI Voice Mode settings
 */
object AiModePreferences {
    private const val PREFS_NAME = "ai_mode_preferences"

    // Preference keys
    const val KEY_AI_MODE_ENABLED = "ai_mode_enabled"
    const val KEY_DEEPGRAM_API_KEY = "deepgram_api_key"
    const val KEY_OPENAI_API_KEY = "openai_api_key"
    const val KEY_SOURCE_LANGUAGE = "source_language"
    const val KEY_TARGET_LANGUAGE = "target_language"
    const val KEY_TTS_VOICE = "tts_voice"
    const val KEY_CACHE_SIZE_MB = "cache_size_mb"
    const val KEY_MAX_PRONUNCIATION_ATTEMPTS = "max_pronunciation_attempts"

    // Default values
    const val DEFAULT_SOURCE_LANGUAGE = "it" // Italian
    const val DEFAULT_TARGET_LANGUAGE = "ru" // Russian
    const val DEFAULT_TTS_VOICE = "alloy"
    const val DEFAULT_CACHE_SIZE_MB = 100
    const val DEFAULT_MAX_PRONUNCIATION_ATTEMPTS = 3

    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if AI Mode is enabled
     */
    fun isAiModeEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AI_MODE_ENABLED, false)

    /**
     * Set AI Mode enabled/disabled
     */
    fun setAiModeEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        getPrefs(context).edit { putBoolean(KEY_AI_MODE_ENABLED, enabled) }
    }

    /**
     * Get Deepgram API key
     */
    fun getDeepgramApiKey(context: Context): String? = getPrefs(context).getString(KEY_DEEPGRAM_API_KEY, null)

    /**
     * Set Deepgram API key
     */
    fun setDeepgramApiKey(
        context: Context,
        apiKey: String,
    ) {
        getPrefs(context).edit { putString(KEY_DEEPGRAM_API_KEY, apiKey) }
    }

    /**
     * Get OpenAI API key
     */
    fun getOpenAiApiKey(context: Context): String? = getPrefs(context).getString(KEY_OPENAI_API_KEY, null)

    /**
     * Set OpenAI API key
     */
    fun setOpenAiApiKey(
        context: Context,
        apiKey: String,
    ) {
        getPrefs(context).edit { putString(KEY_OPENAI_API_KEY, apiKey) }
    }

    /**
     * Get source language (language of the question)
     */
    fun getSourceLanguage(context: Context): String =
        getPrefs(context).getString(KEY_SOURCE_LANGUAGE, DEFAULT_SOURCE_LANGUAGE)
            ?: DEFAULT_SOURCE_LANGUAGE

    /**
     * Set source language
     */
    fun setSourceLanguage(
        context: Context,
        language: String,
    ) {
        getPrefs(context).edit { putString(KEY_SOURCE_LANGUAGE, language) }
    }

    /**
     * Get target language (language of the answer)
     */
    fun getTargetLanguage(context: Context): String =
        getPrefs(context).getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE)
            ?: DEFAULT_TARGET_LANGUAGE

    /**
     * Set target language
     */
    fun setTargetLanguage(
        context: Context,
        language: String,
    ) {
        getPrefs(context).edit { putString(KEY_TARGET_LANGUAGE, language) }
    }

    /**
     * Get TTS voice
     */
    fun getTtsVoice(context: Context): String =
        getPrefs(context).getString(KEY_TTS_VOICE, DEFAULT_TTS_VOICE)
            ?: DEFAULT_TTS_VOICE

    /**
     * Set TTS voice
     */
    fun setTtsVoice(
        context: Context,
        voice: String,
    ) {
        getPrefs(context).edit { putString(KEY_TTS_VOICE, voice) }
    }

    /**
     * Get max cache size in MB
     */
    fun getCacheSizeMb(context: Context): Int = getPrefs(context).getInt(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB)

    /**
     * Set max cache size in MB
     */
    fun setCacheSizeMb(
        context: Context,
        sizeMb: Int,
    ) {
        getPrefs(context).edit { putInt(KEY_CACHE_SIZE_MB, sizeMb) }
    }

    /**
     * Get max pronunciation training attempts
     */
    fun getMaxPronunciationAttempts(context: Context): Int =
        getPrefs(context).getInt(
            KEY_MAX_PRONUNCIATION_ATTEMPTS,
            DEFAULT_MAX_PRONUNCIATION_ATTEMPTS,
        )

    /**
     * Set max pronunciation training attempts
     */
    fun setMaxPronunciationAttempts(
        context: Context,
        attempts: Int,
    ) {
        getPrefs(context).edit { putInt(KEY_MAX_PRONUNCIATION_ATTEMPTS, attempts) }
    }

    /**
     * Check if all required API keys are configured
     */
    fun areApiKeysConfigured(context: Context): Boolean =
        !getDeepgramApiKey(context).isNullOrBlank() &&
            !getOpenAiApiKey(context).isNullOrBlank()

    /**
     * Clear all preferences (useful for logout/reset)
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit { clear() }
    }
}
