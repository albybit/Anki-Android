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
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages Speech-to-Text using dual Deepgram WebSocket connections.
 * Uses a single AudioRecord instance that duplicates audio to two separate
 * WebSocket connections for Italian and Russian recognition.
 * The first valid result received from either connection is used.
 */
class STTDualManager(
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
        private const val DEEPGRAM_MODEL = "nova-2"

        // Language codes
        private const val LANG_IT = "it"
        private const val LANG_RU = "ru"
    }

    // Single AudioRecord instance
    private var audioRecord: AudioRecord? = null

    // Shared audio buffer - thread-safe list that allows multiple readers
    // Using CopyOnWriteArrayList so multiple senders can read without consuming elements
    private val audioBuffer = CopyOnWriteArrayList<ByteArray>()

    // Track read indices for each sender
    private val readIndexIT = AtomicInteger(0)
    private val readIndexRU = AtomicInteger(0)

    // WebSocket connections for each language
    private var webSocketIT: WebSocket? = null
    private var webSocketRU: WebSocket? = null

    // Separate HTTP clients for each WebSocket connection - recreated for each session
    private var httpClientIT: OkHttpClient? = null
    private var httpClientRU: OkHttpClient? = null

    // Track bytes sent to each WebSocket for debugging
    private val bytesSentIT = AtomicLong(0)
    private val bytesSentRU = AtomicLong(0)

    // Track request IDs for each connection
    private var requestIdIT: String = ""
    private var requestIdRU: String = ""

    // Track audio buffer statistics
    private val audioChunksRecorded = AtomicLong(0)
    private val audioChunksReadIT = AtomicLong(0)
    private val audioChunksReadRU = AtomicLong(0)

    // Track empty buffer occurrences for debugging
    private val emptyBufferCountIT = AtomicLong(0)
    private val emptyBufferCountRU = AtomicLong(0)

    // Track last log times to prevent log spam
    private var lastBufferLogTime = 0L
    private var lastChunkLogTimeIT = 0L
    private var lastChunkLogTimeRU = 0L
    private val LOG_INTERVAL_MS = 1000L // Log every second at most for high-frequency events

    // Track connection open times
    private var connectionOpenTimeIT = 0L
    private var connectionOpenTimeRU = 0L

    // Coroutine scope for managing jobs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Jobs for recording and sending
    private var recordingJob: Job? = null
    private var senderJobIT: Job? = null
    private var senderJobRU: Job? = null
    private var silenceJob: Job? = null

    // Thread-safe state flags
    private val isListening = AtomicBoolean(false)
    private val matchFound = AtomicBoolean(false)

    // Callbacks
    private var onResult: ((String, String) -> Unit)? = null // (text, language)
    private var onError: ((Exception) -> Unit)? = null

    // Track WebSocket connection states
    private val webSocketITConnected = AtomicBoolean(false)
    private val webSocketRUConnected = AtomicBoolean(false)

    /**
     * Data class to hold STT result with language information
     */
    data class STTResult(
        val text: String,
        val language: String,
        val confidence: Double,
    )

    /**
     * Start listening for speech with dual WebSocket architecture.
     * Audio is captured once and sent to both Italian and Russian Deepgram endpoints.
     * The first valid result from either endpoint is used.
     *
     * @param onResult Callback when speech is recognized, receives (text, language)
     * @param onError Callback when an error occurs
     * @param autoStopOnSilence Whether to automatically stop after silence detected
     */
    fun startListening(
        onResult: (String, String) -> Unit,
        onError: (Exception) -> Unit,
        autoStopOnSilence: Boolean = true,
    ) {
        if (isListening.get()) {
            Timber.w("STT already listening, forcing stop before restart")
            stopListening()
            Thread.sleep(200)
        }

        val apiKey = AiModePreferences.getDeepgramApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onError(IllegalStateException("Deepgram API key not configured"))
            return
        }

        if (apiKey.length < 10) {
            onError(IllegalStateException("Invalid API key format. Key is too short."))
            return
        }

        // Reset state
        isListening.set(true)
        matchFound.set(false)
        webSocketITConnected.set(false)
        webSocketRUConnected.set(false)
        audioBuffer.clear()
        readIndexIT.set(0)
        readIndexRU.set(0)

        // Reset statistics
        bytesSentIT.set(0)
        bytesSentRU.set(0)
        audioChunksRecorded.set(0)
        audioChunksReadIT.set(0)
        audioChunksReadRU.set(0)
        emptyBufferCountIT.set(0)
        emptyBufferCountRU.set(0)
        connectionOpenTimeIT = 0
        connectionOpenTimeRU = 0

        // Generate unique request IDs for each WebSocket
        requestIdIT = "IT-${UUID.randomUUID()}-${System.currentTimeMillis()}"
        requestIdRU = "RU-${UUID.randomUUID()}-${System.currentTimeMillis()}"

        Timber.i("=== STT STARTING ===")
        Timber.i("Request ID IT: $requestIdIT")
        Timber.i("Request ID RU: $requestIdRU")

        // Store callbacks
        this.onResult = onResult
        this.onError = onError

        Timber.d("Starting dual STT listening")

        // Create new OkHttpClient instances for this session
        httpClientIT =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()

        httpClientRU =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()

        Timber.i("Created new OkHttpClient instances for IT and RU")

        // Start both WebSocket connections
        try {
            Timber.i("Creating IT WebSocket with request_id: $requestIdIT")
            webSocketIT = createWebSocket(LANG_IT, apiKey, requestIdIT)
            Timber.i("Creating RU WebSocket with request_id: $requestIdRU")
            webSocketRU = createWebSocket(LANG_RU, apiKey, requestIdRU)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create WebSocket connections")
            cleanup()
            onError(e)
            return
        }

        // Start audio recording
        startAudioRecording(autoStopOnSilence)
    }

    /**
     * Stop listening and clean up all resources
     */
    fun stopListening() {
        Timber.i("=== STT STOPPING ===")

        isListening.set(false)
        matchFound.set(true) // Signal threads to stop

        // Log final statistics
        Timber.i("=== FINAL STT STATISTICS ===")
        Timber.i("  Audio chunks recorded: ${audioChunksRecorded.get()}")
        Timber.i(
            "  IT - chunks read: ${audioChunksReadIT.get()}, bytes sent: ${bytesSentIT.get()}, empty buffer count: ${emptyBufferCountIT.get()}",
        )
        Timber.i(
            "  RU - chunks read: ${audioChunksReadRU.get()}, bytes sent: ${bytesSentRU.get()}, empty buffer count: ${emptyBufferCountRU.get()}",
        )
        Timber.i("  Buffer size: ${audioBuffer.size}")
        Timber.i("  Read indices - IT: ${readIndexIT.get()}, RU: ${readIndexRU.get()}")
        Timber.i(
            "  Connection times - IT: ${if (connectionOpenTimeIT > 0) "${System.currentTimeMillis() - connectionOpenTimeIT}ms" else "N/A"}, RU: ${if (connectionOpenTimeRU > 0) "${System.currentTimeMillis() - connectionOpenTimeRU}ms" else "N/A"}",
        )
        Timber.i("=============================")

        // Cancel all jobs
        silenceJob?.cancel()
        silenceJob = null
        recordingJob?.cancel()
        recordingJob = null
        senderJobIT?.cancel()
        senderJobIT = null
        senderJobRU?.cancel()
        senderJobRU = null

        // Stop and release AudioRecord
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                try {
                    stop()
                    Timber.i("AudioRecord stopped")
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping audio record")
                }
            }
            release()
            Timber.i("AudioRecord released")
        }
        audioRecord = null

        // Close WebSockets gracefully
        closeWebSocket(webSocketIT, "IT")
        closeWebSocket(webSocketRU, "RU")

        webSocketIT = null
        webSocketRU = null

        // Clear buffer
        audioBuffer.clear()

        Timber.i("=== STT STOPPED ===")
    }

    /**
     * Check if currently listening
     */
    fun isListening(): Boolean = isListening.get()

    /**
     * Create a WebSocket connection for the specified language
     */
    private fun createWebSocket(
        language: String,
        apiKey: String,
        requestId: String,
    ): WebSocket {
        val url = buildDeepgramUrl(language, requestId)
        Timber.i("[$language] Creating WebSocket with URL: $url")
        Timber.i("[$language] Using request_id: $requestId")

        val request =
            Request
                .Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "token, $apiKey")
                .build()

        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    Timber.i("[$language] ═══════════════════════════════════════════")
                    Timber.i("[$language] WebSocket OPENED successfully")
                    Timber.i("[$language] Response code: ${response.code}")
                    Timber.i("[$language] Response message: ${response.message}")
                    Timber.i("[$language] Response headers: ${response.headers}")
                    Timber.i("[$language] WebSocket hash: ${webSocket.hashCode()}")
                    Timber.i("[$language] ═══════════════════════════════════════════")

                    when (language) {
                        LANG_IT -> {
                            webSocketITConnected.set(true)
                            connectionOpenTimeIT = System.currentTimeMillis()
                        }
                        LANG_RU -> {
                            webSocketRUConnected.set(true)
                            connectionOpenTimeRU = System.currentTimeMillis()
                        }
                    }

                    // Start sender job for this WebSocket
                    startSenderJob(language, webSocket)

                    // Play beep sound when both connections are ready
                    if (webSocketITConnected.get() && webSocketRUConnected.get()) {
                        playBeepSound()
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    handleDeepgramResponse(text, language)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    Timber.d("[$language] WebSocket received binary message (length: ${bytes.size})")
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Timber.i("[$language] ═══════════════════════════════════════════")
                    Timber.i("[$language] WebSocket CLOSING: code=$code, reason='$reason'")
                    Timber.i(
                        "[$language] Connection duration: ${when (language) {
                            LANG_IT -> if (connectionOpenTimeIT > 0) "${System.currentTimeMillis() - connectionOpenTimeIT}ms" else "N/A"
                            LANG_RU -> if (connectionOpenTimeRU > 0) "${System.currentTimeMillis() - connectionOpenTimeRU}ms" else "N/A"
                            else -> "N/A"
                        }}",
                    )
                    Timber.i("[$language] ═══════════════════════════════════════════")
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Timber.i("[$language] ═══════════════════════════════════════════")
                    Timber.i("[$language] WebSocket CLOSED: code=$code, reason='$reason'")
                    Timber.i(
                        "[$language] Connection was open for: ${when (language) {
                            LANG_IT -> if (connectionOpenTimeIT > 0) "${System.currentTimeMillis() - connectionOpenTimeIT}ms" else "N/A"
                            LANG_RU -> if (connectionOpenTimeRU > 0) "${System.currentTimeMillis() - connectionOpenTimeRU}ms" else "N/A"
                            else -> "N/A"
                        }}",
                    )
                    Timber.i(
                        "[$language] Final stats - bytes sent: ${if (language == LANG_IT) bytesSentIT.get() else bytesSentRU.get()}, chunks: ${if (language == LANG_IT) {
                            audioChunksReadIT
                                .get()
                        } else {
                            audioChunksReadRU.get()
                        }}",
                    )
                    Timber.i("[$language] ═══════════════════════════════════════════")
                    when (language) {
                        LANG_IT -> webSocketITConnected.set(false)
                        LANG_RU -> webSocketRUConnected.set(false)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    Timber.e(t, "[$language] ═══════════════════════════════════════════")
                    Timber.e("[$language] WebSocket FAILURE!")
                    Timber.e("[$language] Failure type: ${t.javaClass.simpleName}")
                    Timber.e("[$language] Failure message: ${t.message}")
                    Timber.e("[$language] Response code: ${response?.code}")
                    Timber.e("[$language] Response message: ${response?.message}")
                    Timber.e("[$language] Response body: ${response?.body}")
                    Timber.e(
                        "[$language] Connection duration: ${when (language) {
                            LANG_IT -> if (connectionOpenTimeIT > 0) "${System.currentTimeMillis() - connectionOpenTimeIT}ms" else "N/A"
                            LANG_RU -> if (connectionOpenTimeRU > 0) "${System.currentTimeMillis() - connectionOpenTimeRU}ms" else "N/A"
                            else -> "N/A"
                        }}",
                    )
                    Timber.e("[$language] ═══════════════════════════════════════════")
                    when (language) {
                        LANG_IT -> webSocketITConnected.set(false)
                        LANG_RU -> webSocketRUConnected.set(false)
                    }

                    // Only report error if both WebSockets have failed and we're still listening
                    if (!webSocketITConnected.get() && !webSocketRUConnected.get() && isListening.get()) {
                        onError?.invoke(Exception("Both WebSocket connections failed: ${t.message}"))
                        stopListening()
                    }
                }
            }

        // Use the appropriate HTTP client based on language
        val client =
            when (language) {
                LANG_IT -> httpClientIT
                LANG_RU -> httpClientRU
                else -> httpClientIT
            } ?: throw IllegalStateException("HTTP client for $language is not initialized")

        Timber.i("[$language] Using separate OkHttpClient instance: ${client.hashCode()}")
        return client.newWebSocket(request, listener)
    }

    /**
     * Build Deepgram WebSocket URL with parameters
     */
    private fun buildDeepgramUrl(
        language: String,
        requestId: String,
    ): String {
        val params =
            buildString {
                append("?model=$DEEPGRAM_MODEL")
                append("&language=$language")
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
                append("&request_id=$requestId")
            }

        val url = DEEPGRAM_WS_URL + params
        Timber.d("[$language] Built URL: $url")
        return url
    }

    /**
     * Start audio recording and populate shared buffer
     */
    private fun startAudioRecording(autoStopOnSilence: Boolean) {
        try {
            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                )

            Timber.i("AudioRecord - minBufferSize: $minBufferSize, using: ${maxOf(minBufferSize, BUFFER_SIZE)}")

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
                onError?.invoke(Exception("AudioRecord failed to initialize"))
                return
            }

            Timber.i("AudioRecord initialized successfully, starting recording...")
            audioRecord?.startRecording()
            Timber.i("AudioRecord recording started")

            recordingJob =
                scope.launch {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var consecutiveSilence = 0
                    var totalBytesRead = 0

                    while (isListening.get() && !matchFound.get() && isActive) {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (readSize > 0) {
                            totalBytesRead += readSize
                            val chunkNumber = audioChunksRecorded.incrementAndGet()

                            // Copy audio data and add to shared buffer
                            val audioData = buffer.copyOfRange(0, readSize)
                            audioBuffer.add(audioData)

                            // Log every 50 chunks (about every ~1 second)
                            if (chunkNumber % 50 == 0L) {
                                val bufferSize = audioBuffer.size
                                val itConnected = webSocketITConnected.get()
                                val ruConnected = webSocketRUConnected.get()
                                val itReadIndex = readIndexIT.get()
                                val ruReadIndex = readIndexRU.get()
                                Timber.d(
                                    "Audio recording: chunk=$chunkNumber, totalBytes=$totalBytesRead, bufferSize=$bufferSize, IT connected=$itConnected (readIdx=$itReadIndex), RU connected=$ruConnected (readIdx=$ruReadIndex)",
                                )

                                // Warn if buffer is getting too large (indicates senders not keeping up)
                                if (bufferSize > 200) {
                                    Timber.w("Audio buffer growing large: $bufferSize chunks - senders may not be keeping up!")
                                    Timber.w("  IT read: ${audioChunksReadIT.get()} chunks, RU read: ${audioChunksReadRU.get()} chunks")
                                }
                            }

                            // Log first few chunks for debugging startup
                            if (chunkNumber <= 5) {
                                Timber.i("Audio chunk #$chunkNumber recorded - size: $readSize bytes, buffer size: ${audioBuffer.size}")
                            }

                            // Check for silence if auto-stop is enabled
                            if (autoStopOnSilence) {
                                val amplitude = calculateAmplitude(buffer, readSize)
                                if (amplitude < SILENCE_THRESHOLD) {
                                    consecutiveSilence++
                                    if (consecutiveSilence > 10) {
                                        startSilenceTimer()
                                    }
                                } else {
                                    consecutiveSilence = 0
                                    cancelSilenceTimer()
                                }
                            }
                        } else if (readSize < 0) {
                            Timber.e("AudioRecord read error: $readSize")
                        }
                    }

                    Timber.i("Audio recording stopped. Total chunks: ${audioChunksRecorded.get()}, total bytes: $totalBytesRead")
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio recording")
            onError?.invoke(e)
        }
    }

    /**
     * Start sender job for a specific language WebSocket
     * Each sender reads from the shared buffer using its own index
     */
    private fun startSenderJob(
        language: String,
        webSocket: WebSocket,
    ) {
        val job =
            scope.launch {
                Timber.i("[$language] ═══════════════════════════════════════════")
                Timber.i("[$language] Starting sender job")
                Timber.i(
                    "[$language] WebSocket connected: ${if (language == LANG_IT) webSocketITConnected.get() else webSocketRUConnected.get()}",
                )
                Timber.i("[$language] Audio buffer size at start: ${audioBuffer.size}")
                Timber.i("[$language] Starting read index: ${if (language == LANG_IT) readIndexIT.get() else readIndexRU.get()}")
                Timber.i("[$language] ═══════════════════════════════════════════")

                var chunksSent = 0L
                var totalBytesSent = 0L
                var consecutiveEmptyBuffers = 0
                val startTime = System.currentTimeMillis()

                while (isListening.get() && !matchFound.get() && isActive) {
                    // Get the current read index for this language
                    val currentIndex = if (language == LANG_IT) readIndexIT.get() else readIndexRU.get()
                    val bufferSize = audioBuffer.size

                    // Check if there's new data to read
                    if (currentIndex < bufferSize) {
                        consecutiveEmptyBuffers = 0

                        // Get the audio data at the current index
                        val audioData = audioBuffer[currentIndex]

                        try {
                            val byteString = ByteString.of(*audioData)
                            val sent = webSocket.send(byteString)

                            if (sent) {
                                chunksSent++
                                totalBytesSent += audioData.size

                                // Increment the read index for this language
                                if (language == LANG_IT) {
                                    readIndexIT.incrementAndGet()
                                    audioChunksReadIT.incrementAndGet()
                                    bytesSentIT.addAndGet(audioData.size.toLong())
                                } else {
                                    readIndexRU.incrementAndGet()
                                    audioChunksReadRU.incrementAndGet()
                                    bytesSentRU.addAndGet(audioData.size.toLong())
                                }

                                // Log every 50 chunks with detailed stats
                                if (chunksSent % 50 == 0L) {
                                    val currentTime = System.currentTimeMillis()
                                    val elapsed = currentTime - startTime
                                    val bytesPerSecond = if (elapsed > 0) (totalBytesSent * 1000 / elapsed) else 0
                                    val itIndex = readIndexIT.get()
                                    val ruIndex = readIndexRU.get()
                                    val bufSize = audioBuffer.size
                                    Timber.d(
                                        "[$language] Sender progress - chunks: $chunksSent, bytes: $totalBytesSent, rate: ${bytesPerSecond}B/s, readIndex: ${if (language == LANG_IT) itIndex else ruIndex}, bufferSize: $bufSize",
                                    )
                                }

                                // Log first few chunks for debugging
                                if (chunksSent <= 5) {
                                    val newIndex = if (language == LANG_IT) readIndexIT.get() else readIndexRU.get()
                                    Timber.i(
                                        "[$language] Chunk #$chunksSent sent - size: ${audioData.size} bytes, new read index: $newIndex, buffer size: $bufferSize",
                                    )
                                }
                            } else {
                                Timber.w("[$language] Failed to send chunk - WebSocket returned false (chunk #$chunksSent)")
                                Timber.w(
                                    "[$language] WebSocket state - connected: ${if (language == LANG_IT) {
                                        webSocketITConnected.get()
                                    } else {
                                        webSocketRUConnected
                                            .get()
                                    }}",
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "[$language] Exception sending chunk #$chunksSent to WebSocket")
                            Timber.e("[$language] Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
                        }
                    } else {
                        // Buffer is empty for this sender - track this
                        consecutiveEmptyBuffers++
                        when (language) {
                            LANG_IT -> emptyBufferCountIT.incrementAndGet()
                            LANG_RU -> emptyBufferCountRU.incrementAndGet()
                        }

                        // Log if we've had many consecutive empty buffers (possible issue)
                        if (consecutiveEmptyBuffers % 100 == 0) {
                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - startTime
                            val currentIdx = if (language == LANG_IT) readIndexIT.get() else readIndexRU.get()
                            val bufSize = audioBuffer.size
                            Timber.w(
                                "[$language] Buffer empty for $consecutiveEmptyBuffers consecutive reads (elapsed: ${elapsed}ms, chunks sent: $chunksSent, readIndex: $currentIdx, bufferSize: $bufSize)",
                            )
                        }

                        // Small delay if buffer is empty
                        delay(5)
                    }
                }

                val totalTime = System.currentTimeMillis() - startTime
                val finalIndex = if (language == LANG_IT) readIndexIT.get() else readIndexRU.get()
                Timber.i("[$language] ═══════════════════════════════════════════")
                Timber.i("[$language] Sender job stopped")
                Timber.i("[$language] Total chunks sent: $chunksSent")
                Timber.i("[$language] Total bytes sent: $totalBytesSent")
                Timber.i("[$language] Final read index: $finalIndex")
                Timber.i("[$language] Total time: ${totalTime}ms")
                Timber.i("[$language] Average rate: ${if (totalTime > 0) (totalBytesSent * 1000 / totalTime) else 0}B/s")
                Timber.i(
                    "[$language] Empty buffer occurrences: ${if (language == LANG_IT) emptyBufferCountIT.get() else emptyBufferCountRU.get()}",
                )
                Timber.i("[$language] ═══════════════════════════════════════════")
            }

        when (language) {
            LANG_IT -> senderJobIT = job
            LANG_RU -> senderJobRU = job
        }
    }

    /**
     * Handle Deepgram WebSocket response
     */
    private fun handleDeepgramResponse(
        text: String,
        sourceLanguage: String,
    ) {
        // Early exit if match already found (thread-safe)
        if (matchFound.get()) {
            Timber.d("[$sourceLanguage] Ignoring response - match already found")
            return
        }

        try {
            Timber.i("[$sourceLanguage] ═══════════════════════════════════════════")
            Timber.i("[$sourceLanguage] RAW RESPONSE RECEIVED (length: ${text.length})")
            Timber.i("[$sourceLanguage] $text")
            Timber.i("[$sourceLanguage] ═══════════════════════════════════════════")

            val json = JSONObject(text)

            // Check for errors first
            if (json.has("err_code")) {
                val errorCode = json.optInt("err_code", -1)
                val errorMsg = json.optString("err_msg", "Unknown error")
                Timber.e("[$sourceLanguage] ═══════════════════════════════════════════")
                Timber.e("[$sourceLanguage] Deepgram ERROR RESPONSE!")
                Timber.e("[$sourceLanguage] Error code: $errorCode")
                Timber.e("[$sourceLanguage] Error message: $errorMsg")
                Timber.e(
                    "[$sourceLanguage] Stats at error - bytes sent: ${if (sourceLanguage == LANG_IT) {
                        bytesSentIT.get()
                    } else {
                        bytesSentRU
                            .get()
                    }}, chunks: ${if (sourceLanguage == LANG_IT) audioChunksReadIT.get() else audioChunksReadRU.get()}",
                )
                Timber.e("[$sourceLanguage] ═══════════════════════════════════════════")
                return
            }

            // Check for type field (some responses might be metadata)
            val type = json.optString("type", "")
            if (type.isNotEmpty()) {
                Timber.d("[$sourceLanguage] Response type: $type")
            }

            // Check if this is a final result
            val isFinal = json.optBoolean("is_final", false)
            val speechFinal = json.optBoolean("speech_final", false)

            Timber.d("[$sourceLanguage] Response flags - is_final: $isFinal, speech_final: $speechFinal")

            if (!isFinal) {
                // Skip interim results but log them for debugging
                val interimChannel = json.optJSONObject("channel")
                val interimAlternatives = interimChannel?.optJSONArray("alternatives")
                val interimTranscript = interimAlternatives?.optJSONObject(0)?.optString("transcript", "") ?: ""
                val interimConfidence = interimAlternatives?.optJSONObject(0)?.optDouble("confidence", 0.0) ?: 0.0
                Timber.d("[$sourceLanguage] Interim result: '$interimTranscript' (confidence: $interimConfidence)")
                return
            }

            // Extract transcript
            val channel = json.optJSONObject("channel")
            val alternatives = channel?.optJSONArray("alternatives")
            val firstAlternative = alternatives?.optJSONObject(0)
            val transcript = firstAlternative?.optString("transcript", "") ?: ""
            val confidence = firstAlternative?.optDouble("confidence", 0.0) ?: 0.0

            // Log additional metadata if available
            val duration = json.optDouble("duration", 0.0)
            val startTime = json.optDouble("start", 0.0)

            Timber.i("[$sourceLanguage] ═══════════════════════════════════════════")
            Timber.i("[$sourceLanguage] FINAL TRANSCRIPT RECEIVED")
            Timber.i("[$sourceLanguage] Text: '$transcript'")
            Timber.i("[$sourceLanguage] Confidence: $confidence")
            Timber.i("[$sourceLanguage] Duration: ${duration}s, Start: ${startTime}s")
            Timber.i("[$sourceLanguage] Alternatives count: ${alternatives?.length() ?: 0}")

            // Log statistics for debugging
            val bytesSent = if (sourceLanguage == LANG_IT) bytesSentIT.get() else bytesSentRU.get()
            val chunksSent = if (sourceLanguage == LANG_IT) audioChunksReadIT.get() else audioChunksReadRU.get()
            val emptyCount = if (sourceLanguage == LANG_IT) emptyBufferCountIT.get() else emptyBufferCountRU.get()
            Timber.i("[$sourceLanguage] Stats - bytes sent: $bytesSent, chunks: $chunksSent, empty buffers: $emptyCount")
            Timber.i("[$sourceLanguage] ═══════════════════════════════════════════")

            // Process the result
            if (transcript.isNotBlank()) {
                processResult(transcript.trim(), sourceLanguage, confidence)
            } else {
                Timber.w("[$sourceLanguage] Empty transcript received in final result!")
                Timber.w("[$sourceLanguage] This may indicate no speech was detected in the audio stream")
            }
        } catch (e: Exception) {
            Timber.e(e, "[$sourceLanguage] ═══════════════════════════════════════════")
            Timber.e("[$sourceLanguage] Failed to parse Deepgram response!")
            Timber.e("[$sourceLanguage] Exception type: ${e.javaClass.simpleName}")
            Timber.e("[$sourceLanguage] Exception message: ${e.message}")
            Timber.e("[$sourceLanguage] Raw response that failed: $text")
            Timber.e("[$sourceLanguage] ═══════════════════════════════════════════")
        }
    }

    /**
     * Process a valid transcription result.
     * Sends all transcripts to the callback without stopping listening.
     * The controller decides when to stop listening based on the transcript content.
     */
    private fun processResult(
        text: String,
        language: String,
        confidence: Double,
    ) {
        // Try to atomically set matchFound from false to true
        // Only the first thread to succeed will process this specific result
        // This prevents duplicate processing of the same transcript
        if (matchFound.compareAndSet(false, true)) {
            Timber.i("=== TRANSCRIPT RECEIVED ===")
            Timber.i("Language: $language")
            Timber.i("Text: '$text'")
            Timber.i("Confidence: $confidence")
            Timber.i("===========================")

            // Call the result callback with text and detected language
            // DO NOT stop listening here - let the controller decide
            onResult?.invoke(text, language)

            // Reset matchFound to allow processing of subsequent transcripts
            // This enables continuous listening until explicitly stopped
            matchFound.set(false)
        } else {
            Timber.d("[$language] Result already being processed, ignoring duplicate")
        }
    }

    /**
     * Close WebSocket gracefully
     */
    private fun closeWebSocket(
        webSocket: WebSocket?,
        label: String,
    ) {
        try {
            // Send CloseStream message
            webSocket?.send("""{"type":"CloseStream"}""")
            Timber.d("Sent CloseStream to $label WebSocket")
        } catch (e: Exception) {
            Timber.w(e, "Error sending CloseStream to $label WebSocket")
        }

        // Cancel the WebSocket
        try {
            webSocket?.cancel()
            Timber.d("Cancelled $label WebSocket")
        } catch (e: Exception) {
            Timber.w(e, "Error cancelling $label WebSocket")
        }
    }

    /**
     * Calculate amplitude from audio buffer for silence detection
     */
    private fun calculateAmplitude(
        buffer: ByteArray,
        readSize: Int,
    ): Int {
        var sum = 0L
        var count = 0

        // Process as 16-bit PCM
        var i = 0
        while (i < readSize - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += kotlin.math.abs(sample)
            count++
            i += 2
        }

        return if (count > 0) (sum / count).toInt() else 0
    }

    /**
     * Start silence timer for auto-stop
     */
    private fun startSilenceTimer() {
        if (silenceJob?.isActive == true) return

        silenceJob =
            scope.launch {
                delay(SILENCE_DURATION_MS)
                if (isListening.get()) {
                    Timber.d("Silence detected for ${SILENCE_DURATION_MS}ms, stopping listening")
                    stopListening()
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
     * Play a beep sound to indicate recording has started
     */
    private fun playBeepSound() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Timber.d("Beep sound played")
        } catch (e: Exception) {
            Timber.w(e, "Failed to play beep sound")
        }
    }

    /**
     * Clean up resources without full stop
     */
    fun cleanup() {
        isListening.set(false)

        recordingJob?.cancel()
        recordingJob = null
        senderJobIT?.cancel()
        senderJobIT = null
        senderJobRU?.cancel()
        senderJobRU = null

        audioRecord?.release()
        audioRecord = null

        webSocketIT?.cancel()
        webSocketRU?.cancel()
        webSocketIT = null
        webSocketRU = null

        audioBuffer.clear()
    }
}
