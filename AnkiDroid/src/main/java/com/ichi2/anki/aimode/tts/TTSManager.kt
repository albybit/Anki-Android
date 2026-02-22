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

package com.ichi2.anki.aimode.tts

import android.content.Context
import android.media.MediaPlayer
import com.ichi2.anki.aimode.AiModePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Manages Text-to-Speech using OpenAI API with local caching
 */
class TTSManager(
    private val context: Context,
) {
    companion object {
        private const val OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech"
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val REQUEST_TIMEOUT_MS = 30000L
    }

    private val cacheManager = TTSCacheManager(context)
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Speak text using OpenAI TTS with caching
     * @param text The text to speak
     * @param onComplete Callback when playback completes
     * @param onError Callback if an error occurs
     */
    suspend fun speak(
        text: String,
        voice: String = AiModePreferences.getTtsVoice(context),
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            // Stop any current playback
            stop()

            // Check cache first
            val cachedFile = cacheManager.getCachedAudio(text, voice)

            val audioFile =
                if (cachedFile != null) {
                    Timber.d("Using cached TTS for: ${text.take(50)}...")
                    cachedFile
                } else {
                    // Fetch from OpenAI API
                    Timber.d("Fetching TTS from OpenAI for: ${text.take(50)}...")
                    fetchTtsFromApi(text, voice)
                }

            // Play the audio
            playAudio(audioFile, onComplete, onError)
        } catch (e: Exception) {
            Timber.e(e, "TTS failed")
            onError?.invoke(e)
        }
    }

    /**
     * Pre-fetch TTS audio for upcoming cards (performance optimization)
     */
    suspend fun prefetch(
        text: String,
        voice: String = AiModePreferences.getTtsVoice(context),
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Only fetch if not already cached
                if (cacheManager.getCachedAudio(text, voice) == null) {
                    fetchTtsFromApi(text, voice)
                    Timber.d("Pre-fetched TTS for: ${text.take(50)}...")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to prefetch TTS")
                // Don't throw - prefetch failures are non-critical
            }
        }
    }

    /**
     * Stop current playback
     */
    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        currentPlayingFile = null
    }

    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    /**
     * Detect language from text content
     * Simple rule: Cyrillic = Russian, everything else = Italian
     */
    private fun detectLanguage(text: String): String {
        // Check for Cyrillic characters (Russian)
        return if (text.contains(Regex("[\\u0400-\\u04FF]"))) {
            "ru"
        } else {
            // Default to Italian for all non-Cyrillic text
            "it"
        }
    }

    /**
     * Split text into segments by language
     * Returns a list of pairs (text_segment, language_code)
     * Filters out segments that are only punctuation/special characters
     */
    fun splitTextByLanguage(text: String): List<Pair<String, String>> {
        val segments = mutableListOf<Pair<String, String>>()
        val currentSegment = StringBuilder()
        var currentLanguage: String? = null

        // Split by words to preserve spacing
        val words = text.split(Regex("(?<=\\s)|(?=\\s)"))

        for (word in words) {
            if (word.isBlank()) {
                currentSegment.append(word)
                continue
            }

            val wordLanguage = detectLanguage(word)

            if (currentLanguage == null) {
                currentLanguage = wordLanguage
                currentSegment.append(word)
            } else if (wordLanguage == currentLanguage) {
                currentSegment.append(word)
            } else {
                // Language changed, save current segment
                if (currentSegment.isNotBlank()) {
                    val segmentText = currentSegment.toString().trim()
                    // Only add segment if it contains actual letters/numbers, not just punctuation
                    if (segmentText.contains(Regex("[\\p{L}\\p{N}]"))) {
                        segments.add(segmentText to currentLanguage)
                    }
                }
                currentSegment.clear()
                currentLanguage = wordLanguage
                currentSegment.append(word)
            }
        }

        // Don't forget the last segment
        if (currentSegment.isNotBlank() && currentLanguage != null) {
            val segmentText = currentSegment.toString().trim()
            // Only add segment if it contains actual letters/numbers, not just punctuation
            if (segmentText.contains(Regex("[\\p{L}\\p{N}]"))) {
                segments.add(segmentText to currentLanguage)
            }
        }

        // If no segments were created, return the whole text as Italian (if it has content)
        if (segments.isEmpty() && text.contains(Regex("[\\p{L}\\p{N}]"))) {
            segments.add(text to "it")
        }

        return segments
    }

    /**
     * Speak text with automatic language switching for bilingual content
     * Splits the text by language and speaks each segment with the appropriate voice
     */
    suspend fun speakBilingual(
        text: String,
        onComplete: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            // Stop any current playback
            stop()

            // Split text by language
            val segments = splitTextByLanguage(text)
            Timber.d("Split text into ${segments.size} segments: $segments")

            if (segments.isEmpty()) {
                onComplete?.invoke()
                return@withContext
            }

            // If only one segment, use regular speak
            if (segments.size == 1) {
                val (segmentText, language) = segments[0]
                val voice = if (language == "ru") "nova" else AiModePreferences.getTtsVoice(context)
                speak(segmentText, voice, onComplete, onError)
                return@withContext
            }

            // For multiple segments, speak them sequentially
            speakSegmentsSequentially(segments, 0, onComplete, onError)
        } catch (e: Exception) {
            Timber.e(e, "Bilingual TTS failed")
            onError?.invoke(e)
        }
    }

    /**
     * Recursively speak segments sequentially
     */
    private suspend fun speakSegmentsSequentially(
        segments: List<Pair<String, String>>,
        index: Int,
        onComplete: (() -> Unit)?,
        onError: ((Exception) -> Unit)?,
    ) {
        if (index >= segments.size) {
            // All segments completed
            onComplete?.invoke()
            return
        }

        val (text, language) = segments[index]
        val voice = if (language == "ru") "nova" else AiModePreferences.getTtsVoice(context)

        speak(
            text = text,
            voice = voice,
            onComplete = {
                // Speak next segment
                CoroutineScope(Dispatchers.IO).launch {
                    speakSegmentsSequentially(segments, index + 1, onComplete, onError)
                }
            },
            onError = { error ->
                onError?.invoke(error)
            },
        )
    }

    /**
     * Fetch TTS audio from OpenAI API with automatic language detection
     */
    private suspend fun fetchTtsFromApi(
        text: String,
        voice: String,
    ): File {
        val apiKey =
            AiModePreferences.getOpenAiApiKey(context)
                ?: throw IllegalStateException("OpenAI API key not configured")

        // Detect language from text
        val language = detectLanguage(text)

        // Choose voice based on language
        val voiceForLanguage =
            when (language) {
                "ru" -> "nova" // Use nova for Russian (clear, natural)
                else -> voice // Use configured voice for Italian/other
            }

        val jsonBody =
            JSONObject().apply {
                put("model", "gpt-4o-mini-tts")
                put("input", text)
                put("voice", voiceForLanguage)
                put("response_format", "mp3")
                // Note: OpenAI TTS doesn't support language parameter directly,
                // but the voice and input text language should match
            }

        val request =
            Request
                .Builder()
                .url(OPENAI_TTS_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

        val response =
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                httpClient.newCall(request).execute()
            } ?: throw IOException("TTS request timeout")

        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw IOException("TTS API error: ${response.code} - $errorBody")
        }

        val audioData =
            response.body?.bytes()
                ?: throw IOException("Empty response from TTS API")

        // Save to cache with language info
        return cacheManager.saveToCache(text, voiceForLanguage, audioData)
    }

    /**
     * Play audio file using MediaPlayer
     */
    private suspend fun playAudio(
        file: File,
        onComplete: (() -> Unit)?,
        onError: ((Exception) -> Unit)?,
    ) = withContext(Dispatchers.Main) {
        try {
            stop()

            currentPlayingFile = file
            mediaPlayer =
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        onComplete?.invoke()
                    }
                    setOnErrorListener { _, what, extra ->
                        val error = Exception("MediaPlayer error: what=$what, extra=$extra")
                        onError?.invoke(error)
                        true
                    }
                    prepare()
                    start()
                }

            // Suspend until playback completes
            suspendCancellableCoroutine { continuation ->
                mediaPlayer?.setOnCompletionListener {
                    onComplete?.invoke()
                    continuation.resume(Unit)
                }

                mediaPlayer?.setOnErrorListener { _, what, extra ->
                    val error = Exception("MediaPlayer error: what=$what, extra=$extra")
                    onError?.invoke(error)
                    continuation.resume(Unit)
                    true
                }

                continuation.invokeOnCancellation {
                    stop()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio")
            onError?.invoke(e)
        }
    }

    /**
     * Clean up resources
     * Must be called from a background thread to avoid NetworkOnMainThreadException
     */
    fun cleanup() {
        stop()
        // Shutdown must happen on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            } catch (e: Exception) {
                Timber.w(e, "Error during TTSManager cleanup")
            }
        }
    }
}
