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

package com.ichi2.anki.aimode.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import com.ichi2.anki.aimode.AiModePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Manages Speech-to-Text using Deepgram API via WebSocket
 */
class STTManager(
    private val context: Context,
) {
    companion object {
        private const val DEEPGRAM_WS_URL = "wss://api.deepgram.com/v1/listen"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 1024 * 4
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_DURATION_MS = 2000L

        // Available models: nova-3 (latest), nova-2, nova, whisper, etc.
        // nova-2 is more stable for single-language recognition
        private const val DEEPGRAM_MODEL = "nova-2"
    }

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isListening = false
    private val recognitionResults = Channel<String>(Channel.BUFFERED)
    private var silenceJob: Job? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Start listening for speech
     * @param onResult Callback when speech is recognized
     * @param onError Callback when an error occurs
     * @param autoStopOnSilence Whether to automatically stop after silence detected
     * @param expectedLanguage Optional language hint based on expected answer content ('it' or 'ru')
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit,
        autoStopOnSilence: Boolean = true,
        expectedLanguage: String? = null,
    ) {
        if (isListening) {
            Timber.w("STT already listening, forcing stop before restart")
            stopListening()
            // Add small delay to ensure cleanup completes
            Thread.sleep(200)
        }

        val apiKey = AiModePreferences.getDeepgramApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onError(IllegalStateException("Deepgram API key not configured"))
            return
        }

        isListening = true

        // Build WebSocket URL with parameters, using expected language if provided
        val url = buildDeepgramUrl(apiKey, expectedLanguage)
        Timber.d("Deepgram WebSocket URL: $url")
        Timber.d("API Key length: ${apiKey.length}")
        Timber.d("Expected language: ${expectedLanguage ?: "auto-detect"}")

        // Validate API key format
        if (apiKey.length < 10) {
            isListening = false
            onError(IllegalStateException("Invalid API key format. Key is too short."))
            return
        }

        // Use Sec-WebSocket-Protocol header for authentication as per Deepgram docs
        val request =
            Request
                .Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "token, $apiKey")
                .build()

        val webSocketListener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    Timber.d("Deepgram WebSocket opened successfully")
                    Timber.d("Response: ${response.code} - ${response.message}")
                    // Play beep sound to indicate listening has started
                    playBeepSound()
                    startAudioRecording(webSocket, autoStopOnSilence)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    handleDeepgramResponse(text, onResult, onError)
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Timber.d("Deepgram WebSocket closing: $code - $reason")
                    isListening = false
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Timber.d("Deepgram WebSocket closed: $code - $reason")
                    isListening = false
                }
            }

        webSocket = httpClient.newWebSocket(request, webSocketListener)
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        Timber.d("Stopping STT listening")
        isListening = false
        silenceJob?.cancel()
        silenceJob = null
        recordingJob?.cancel()
        recordingJob = null

        // Stop audio recording first to prevent further data
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                try {
                    stop()
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping audio record")
                }
            }
            release()
        }
        audioRecord = null

        // Send CloseStream message to Deepgram before closing
        // This ensures we get final transcription results
        try {
            webSocket?.send("""{"type":"CloseStream"}""")
            Timber.d("Sent CloseStream message to Deepgram")
        } catch (e: Exception) {
            Timber.w(e, "Failed to send CloseStream message")
        }

        // Close WebSocket gracefully
        webSocket?.close(1000, "User stopped listening")
        webSocket = null
    }

    /**
     * Check if currently listening
     */
    fun isListening(): Boolean = isListening

    /**
     * Build Deepgram WebSocket URL with required parameters
     * According to Deepgram docs:
     * - Requires encoding and sample_rate for raw audio
     * - nova-3 is the latest model with best multilingual support
     * - Authorization via Sec-WebSocket-Protocol header with "token", API_KEY
     */
    private fun buildDeepgramUrl(
        apiKey: String,
        language: String? = null,
    ): String {
        val targetLanguage = language ?: AiModePreferences.getTargetLanguage(context)

        // Build URL with REQUIRED parameters for raw audio streaming
        // encoding=linear16 and sample_rate=16000 are REQUIRED for PCM audio
        // endpointing=true lets Deepgram handle silence detection automatically
        // smart_format=true improves punctuation and formatting
        // punctuate=true adds punctuation
        // detect_language=false forces single language mode (no auto-detection)
        val params =
            buildString {
                append("?model=$DEEPGRAM_MODEL")
                append("&language=$targetLanguage")
                append("&detect_language=false")
                append("&encoding=linear16")
                append("&sample_rate=$SAMPLE_RATE")
                append("&channels=1")
                append("&interim_results=true")
                append("&endpointing=true")
                append("&smart_format=true")
                append("&punctuate=true")
                append("&filler_words=false")
                append("&profanity_filter=false")
            }

        val fullUrl = DEEPGRAM_WS_URL + params
        Timber.d("Deepgram full URL: $fullUrl (language: $targetLanguage)")
        return fullUrl
    }

    /**
     * Detect language from text content
     * Returns 'it' for Italian, 'ru' for Russian/Cyrillic
     */
    private fun detectLanguage(text: String): String {
        // Check for Cyrillic characters (Russian)
        return if (text.contains(Regex("[\\u0400-\\u04FF]"))) {
            "ru"
        } else {
            // Default to Italian for non-Cyrillic content
            "it"
        }
    }

    /**
     * Start recording audio and sending to Deepgram
     */
    private fun startAudioRecording(
        webSocket: WebSocket,
        autoStopOnSilence: Boolean,
    ) {
        try {
            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                )

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    maxOf(minBufferSize, BUFFER_SIZE),
                )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord failed to initialize")
                return
            }

            audioRecord?.startRecording()

            // Start audio streaming in a coroutine using managed scope
            recordingJob =
                scope.launch {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var consecutiveSilence = 0

                    while (isListening && isActive) {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (readSize > 0) {
                            // Send audio data to Deepgram - create ByteString from buffer slice
                            val audioData = buffer.copyOfRange(0, readSize)
                            webSocket.send(ByteString.of(*audioData))

                            // Check for silence if auto-stop is enabled
                            if (autoStopOnSilence) {
                                val amplitude = calculateAmplitude(buffer, readSize)
                                if (amplitude < SILENCE_THRESHOLD) {
                                    consecutiveSilence++
                                    if (consecutiveSilence > 10) { // ~500ms of silence
                                        startSilenceTimer()
                                    }
                                } else {
                                    consecutiveSilence = 0
                                    cancelSilenceTimer()
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio recording")
        }
    }

    /**
     * Handle Deepgram WebSocket response
     */
    private fun handleDeepgramResponse(
        text: String,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        try {
            Timber.d("Deepgram raw response: $text")
            val json = JSONObject(text)

            // Check for errors
            if (json.has("err_code")) {
                val errorMsg = json.optString("err_msg", "Unknown error")
                onError(Exception("Deepgram error: $errorMsg"))
                return
            }

            // Check if this is a final result (not interim)
            val isFinal = json.optBoolean("is_final", false)
            val speechFinal = json.optBoolean("speech_final", false)

            Timber.d("Deepgram response - is_final: $isFinal, speech_final: $speechFinal")

            if (!isFinal) {
                // Skip interim results - wait for final
                Timber.d("Skipping interim result")
                return
            }

            // Extract transcript from the correct nested structure
            // Deepgram format: channel -> alternatives[0] -> transcript
            val channel = json.optJSONObject("channel")
            val alternatives = channel?.optJSONArray("alternatives")
            val firstAlternative = alternatives?.optJSONObject(0)
            val transcript = firstAlternative?.optString("transcript", "")

            val confidence = firstAlternative?.optDouble("confidence", 0.0) ?: 0.0

            Timber.d("Deepgram transcript: '$transcript', confidence: $confidence")

            // Always call onResult with the transcript (even if empty)
            // This allows the controller to handle empty responses and ask to repeat
            onResult(transcript?.trim() ?: "")

            // Don't stop listening here - let the controller decide when to stop
            // This allows continuous listening for 15 seconds
            // stopListening() // Removed - controller manages the timeout
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Deepgram response: $text")
        }
    }

    /**
     * Calculate audio amplitude for silence detection
     */
    private fun calculateAmplitude(
        buffer: ByteArray,
        readSize: Int,
    ): Int {
        var sum = 0L
        for (i in 0 until readSize step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += kotlin.math.abs(sample)
        }
        return (sum / (readSize / 2)).toInt()
    }

    /**
     * Start silence timer for auto-stop
     */
    private fun startSilenceTimer() {
        if (silenceJob?.isActive == true) return

        silenceJob =
            CoroutineScope(Dispatchers.Main).launch {
                delay(SILENCE_DURATION_MS)
                if (isListening) {
                    Timber.d("Auto-stopping due to silence - calling onResult with empty string")
                    // Notify that we stopped due to silence (empty result)
                    // This allows the controller to handle the timeout
                }
            }
    }

    /**
     * Cancel silence timer
     */
    private fun cancelSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = null
    }

    /**
     * Play a beep sound to indicate listening has started
     * Uses STREAM_MUSIC for better audibility during media playback
     */
    private fun playBeepSound() {
        try {
            // Use STREAM_MUSIC instead of STREAM_NOTIFICATION for better audibility
            // Volume 100 is maximum
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            // Play a more noticeable beep (TONE_SUP_DIAL instead of TONE_PROP_BEEP)
            toneGenerator.startTone(ToneGenerator.TONE_SUP_DIAL, 300)
            // Release after playing
            CoroutineScope(Dispatchers.Main).launch {
                delay(400)
                toneGenerator.release()
            }
            Timber.d("Beep sound played (STREAM_MUSIC)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to play beep sound")
        }
    }

    /**
     * Clean up resources
     * Must be called from a background thread to avoid NetworkOnMainThreadException
     */
    fun cleanup() {
        stopListening()
        // Shutdown must happen on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            } catch (e: Exception) {
                Timber.w(e, "Error during STTManager cleanup")
            }
        }
    }
}
