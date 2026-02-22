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
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.aimode.stt.STTDualManager
import com.ichi2.anki.aimode.tts.TTSManager
import com.ichi2.anki.libanki.Card
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main controller for AI Voice Mode
 * Simplified flow: 15s continuous listening, immediate response to commands
 */
class AiModeController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: AiModeListener,
) {
    private val tag = "AiModeController"

    // Managers
    private val ttsManager = TTSManager(context)
    private val sttManager = STTDualManager(context)

    // State
    private val _currentState = MutableStateFlow<AiModeState>(AiModeState.Idle())
    val currentState: StateFlow<AiModeState> = _currentState

    private var isEnabled = false
    private var currentCard: Card? = null
    private var currentQuestion: String = ""
    private var currentAnswer: String = ""

    interface AiModeListener {
        fun onStateChanged(state: AiModeState)

        fun onRequestAnswerCard(rating: Rating)

        fun onShowAnswer()

        fun onError(message: String)

        fun onDebugLog(message: String)
    }

    init {
        AiModeDebugLogger.i(tag, "Controller initialized")
    }

    /**
     * Enable/disable AI Mode
     */
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return

        isEnabled = enabled
        AiModeDebugLogger.i(tag, "AI Mode ${if (enabled) "enabled" else "disabled"}")

        if (!enabled) {
            stopAll()
            transitionTo(AiModeState.Idle())
        } else {
            // Check if API keys are configured
            if (!AiModePreferences.areApiKeysConfigured(context)) {
                listener.onError("API keys not configured. Please set up in settings.")
                isEnabled = false
                return
            }

            // Start with current card if available
            currentCard?.let { startCardReview(it, currentQuestion, currentAnswer) }
        }
    }

    /**
     * Check if AI Mode is enabled
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Called when a new card is shown
     */
    fun onCardChanged(
        card: Card?,
        question: String,
        answer: String,
    ) {
        if (card == null) {
            AiModeDebugLogger.w(tag, "Card is null, skipping")
            return
        }
        currentCard = card
        currentQuestion = question
        currentAnswer = answer
        AiModeDebugLogger.i(tag, "Card changed: ${card.id}")
        AiModeDebugLogger.d(tag, "Question: ${question.take(100)}")
        AiModeDebugLogger.d(tag, "Answer: ${answer.take(100)}")
        AiModeDebugLogger.d(tag, "AI Mode enabled: $isEnabled")

        if (isEnabled) {
            AiModeDebugLogger.i(tag, "Starting card review with AI Mode")
            startCardReview(card, question, answer)
        } else {
            AiModeDebugLogger.d(tag, "AI Mode is disabled, not starting review")
        }
    }

    /**
     * Start reviewing a card with AI Mode
     */
    private fun startCardReview(
        card: Card,
        question: String,
        answer: String,
    ) {
        if (!isEnabled) {
            AiModeDebugLogger.w(tag, "Cannot start review - AI Mode not enabled")
            return
        }

        // First, ensure everything is stopped and there's silence
        stopAll()

        AiModeDebugLogger.i(tag, "Starting card review: ${card.id}")
        val strippedQuestion = stripHtml(question)
        AiModeDebugLogger.d(tag, "Stripped question: $strippedQuestion")

        if (strippedQuestion.isBlank()) {
            AiModeDebugLogger.w(tag, "Question is blank after stripping HTML, skipping TTS")
            return
        }

        transitionTo(AiModeState.ReadingQuestion(card.id, strippedQuestion))

        // Read the question with delay before and after
        lifecycleOwner.lifecycleScope.launch {
            try {
                // Wait a moment before speaking (let user prepare)
                AiModeDebugLogger.i(tag, "Preparing to speak question...")
                delay(800)

                AiModeDebugLogger.i(tag, "Starting TTS for question")
                // Use bilingual TTS to handle mixed Italian/Russian content
                ttsManager.speakBilingual(
                    text = strippedQuestion,
                    onComplete = {
                        AiModeDebugLogger.i(tag, "TTS completed, preparing to listen...")
                        // After TTS completes, wait before starting to listen
                        lifecycleOwner.lifecycleScope.launch {
                            delay(500) // 0.5 second pause after speaking before listening
                            startListeningForAnswer(card, answer)
                        }
                    },
                    onError = { error ->
                        AiModeDebugLogger.e(tag, "TTS error: ${error.message}")
                        handleError("TTS error: ${error.message}")
                    },
                )
            } catch (e: Exception) {
                AiModeDebugLogger.e(tag, "Failed to start TTS: ${e.message}")
                handleError("Failed to start TTS: ${e.message}")
            }
        }
    }

    /**
     * Start listening for user's answer (first phase - 15s)
     */
    private fun startListeningForAnswer(
        card: Card,
        expectedAnswer: String,
    ) {
        if (!isEnabled) return

        AiModeDebugLogger.logStateChange(_currentState.value, AiModeState.ListeningAnswer(card.id, expectedAnswer, 0))
        transitionTo(AiModeState.ListeningAnswer(card.id, expectedAnswer, 0))

        // Start 15-second timeout
        val timeoutJob =
            lifecycleOwner.lifecycleScope.launch {
                delay(15000) // 15 seconds listening window
                if (_currentState.value is AiModeState.ListeningAnswer) {
                    AiModeDebugLogger.i(tag, "15s timeout reached - showing answer and reading it")
                    sttManager.stopListening()
                    // Show answer and read it
                    showAnswerAndRead(card, expectedAnswer)
                }
            }

        // Start dual STT listening - both Italian and Russian
        // STTDualManager will return the first valid result from either language
        sttManager.startListening(
            onResult = { spokenText, detectedLanguage ->
                AiModeDebugLogger.i(tag, "STT result - Language: $detectedLanguage, Text: '$spokenText'")
                handleAnswerResponse(card, expectedAnswer, spokenText, timeoutJob)
            },
            onError = { error ->
                timeoutJob.cancel()
                handleError("STT error: ${error.message}")
            },
            autoStopOnSilence = false, // Don't auto-stop on silence, wait for 15s or valid command
        )
    }

    /**
     * Handle response during answer listening phase
     */
    private fun handleAnswerResponse(
        card: Card,
        expectedAnswer: String,
        spokenText: String,
        timeoutJob: kotlinx.coroutines.Job,
    ) {
        AiModeDebugLogger.i(tag, "User spoke: '$spokenText'")

        // Check for empty response - continue listening without stopping
        if (spokenText.isBlank()) {
            AiModeDebugLogger.d(tag, "Empty response, continuing to listen")
            return // Continue listening
        }

        val normalized = spokenText.lowercase().trim()

        // Check for "хорошо" command - show answer and rate GOOD immediately (don't read answer)
        if (normalized.contains("хорошо")) {
            AiModeDebugLogger.i(tag, "'хорошо' command detected - showing answer and rating GOOD")
            timeoutJob.cancel()
            sttManager.stopListening()
            // Show answer and rate GOOD immediately without reading
            listener.onShowAnswer()
            lifecycleOwner.lifecycleScope.launch {
                delay(300)
                rateCard(Rating.GOOD)
            }
            return
        }

        // Check for "не знаю" command - show answer, read it, then rate AGAIN
        if (normalized.contains("не знаю")) {
            AiModeDebugLogger.i(tag, "'не знаю' command detected - showing answer, reading it, then rating AGAIN")
            timeoutJob.cancel()
            sttManager.stopListening()
            // Show answer, read it, then rate AGAIN (not HARD)
            showAnswerAndRead(card, expectedAnswer, Rating.AGAIN)
            return
        }

        // Check for correct answer
        if (isAnswerCorrect(spokenText, expectedAnswer)) {
            AiModeDebugLogger.i(tag, "Correct answer detected!")
            // Play success beep ONLY for correct answers (not commands)
            playSuccessBeep()
            timeoutJob.cancel()
            sttManager.stopListening()
            // Show answer, read it, then rate GOOD
            showAnswerAndRead(card, expectedAnswer, Rating.GOOD)
            return
        }

        // Unknown command - continue listening (STT keeps listening automatically)
        AiModeDebugLogger.d(tag, "Unknown response '$spokenText', continuing to listen")
    }

    /**
     * Show answer and read it, then listen for rating command
     */
    private fun showAnswerAndRead(
        card: Card,
        expectedAnswer: String,
        autoRating: Rating? = null,
    ) {
        // Show answer first
        listener.onShowAnswer()

        lifecycleOwner.lifecycleScope.launch {
            delay(500)
            // Read the full answer
            readCardContent(expectedAnswer) {
                // After reading, either auto-rate or listen for rating command
                lifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    if (autoRating != null) {
                        // Auto-rate if answer was correct
                        rateCard(autoRating)
                    } else {
                        // Listen for rating command (хорошо/плохо)
                        startListeningForRating(card)
                    }
                }
            }
        }
    }

    /**
     * Listen for rating command after reading answer
     */
    private fun startListeningForRating(
        card: Card,
        expectedAnswer: String = currentAnswer,
    ) {
        if (!isEnabled) return

        AiModeDebugLogger.i(tag, "Listening for rating command (хорошо/плохо/еще раз)")
        transitionTo(AiModeState.ListeningForRating(card.id))

        // Start timeout - if no command, default to HARD
        val timeoutJob =
            lifecycleOwner.lifecycleScope.launch {
                delay(10000) // 10 seconds to respond
                if (_currentState.value is AiModeState.ListeningForRating) {
                    AiModeDebugLogger.i(tag, "Timeout waiting for rating command, defaulting to HARD")
                    sttManager.stopListening()
                    rateCard(Rating.HARD)
                }
            }

        // Start dual STT listening for rating commands
        sttManager.startListening(
            onResult = { spokenText, detectedLanguage ->
                AiModeDebugLogger.i(tag, "Rating STT result - Language: $detectedLanguage, Text: '$spokenText'")
                handleRatingResponse(card, expectedAnswer, spokenText, timeoutJob)
            },
            onError = { error ->
                timeoutJob.cancel()
                // On error, default to HARD
                rateCard(Rating.HARD)
            },
            autoStopOnSilence = false,
        )
    }

    /**
     * Handle rating command
     */
    private fun handleRatingResponse(
        card: Card,
        expectedAnswer: String,
        spokenText: String,
        timeoutJob: kotlinx.coroutines.Job,
    ) {
        AiModeDebugLogger.i(tag, "Rating command received: '$spokenText'")

        // Check for empty response - continue listening without stopping
        if (spokenText.isBlank()) {
            AiModeDebugLogger.d(tag, "Empty rating response, continuing to listen")
            return // Continue listening
        }

        val normalized = spokenText.lowercase().trim()

        when {
            // "хорошо" = GOOD
            normalized.contains("хорошо") -> {
                AiModeDebugLogger.i(tag, "'хорошо' detected - rating GOOD")
                timeoutJob.cancel()
                sttManager.stopListening()
                rateCard(Rating.GOOD)
            }
            // "плохо" = AGAIN (not HARD)
            normalized.contains("плохо") -> {
                AiModeDebugLogger.i(tag, "'плохо' detected - rating AGAIN")
                timeoutJob.cancel()
                sttManager.stopListening()
                rateCard(Rating.AGAIN)
            }
            // "еще раз" / "ещё раз" / "ancora" / "di nuovo" = Repeat answer
            normalized.contains("еще раз") || normalized.contains("ещё раз") ||
                normalized.contains("ancora") || normalized.contains("di nuovo") -> {
                AiModeDebugLogger.i(tag, "'еще раз' detected - repeating answer")
                timeoutJob.cancel()
                sttManager.stopListening()
                // Repeat reading the answer and stay in rating phase
                lifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    readCardContent(expectedAnswer) {
                        // After repeating, start listening for rating again
                        lifecycleOwner.lifecycleScope.launch {
                            delay(500)
                            startListeningForRating(card, expectedAnswer)
                        }
                    }
                }
            }
            // Unknown command - continue listening (STT keeps listening automatically)
            else -> {
                AiModeDebugLogger.d(tag, "Unknown rating command '$spokenText', continuing to listen")
            }
        }
    }

    /**
     * Detect language from expected answer
     * Returns 'ru' if answer contains Cyrillic, 'it' otherwise
     * Note: With STTDualManager, this is no longer needed as both languages
     * are processed simultaneously. Kept for potential future use.
     */
    @Suppress("unused")
    private fun detectLanguageFromAnswer(answer: String): String =
        if (answer.contains(Regex("[\\u0400-\\u04FF]"))) {
            "ru"
        } else {
            "it"
        }

    /**
     * Centralized function to read card content with TTS
     * Strips HTML and filters special characters
     */
    private suspend fun readCardContent(
        text: String,
        onComplete: (() -> Unit)? = null,
    ) {
        val strippedText = stripHtml(text)
        if (strippedText.isBlank()) {
            onComplete?.invoke()
            return
        }

        ttsManager.speakBilingual(
            text = strippedText,
            onComplete = { onComplete?.invoke() },
            onError = { error ->
                AiModeDebugLogger.e(tag, "TTS error: ${error.message}")
                onComplete?.invoke()
            },
        )
    }

    /**
     * Rate the card and move to next
     */
    private fun rateCard(rating: Rating) {
        AiModeDebugLogger.i(tag, "Rating card: $rating")
        transitionTo(AiModeState.RatingCard(currentCard?.id ?: 0, rating))
        listener.onRequestAnswerCard(rating)
    }

    /**
     * Check if answer is correct (exact or close match)
     * Handles punctuation, case differences, and extracts text from HTML
     */
    private fun isAnswerCorrect(
        spoken: String,
        expected: String,
    ): Boolean {
        // First, strip HTML from expected answer if it contains HTML
        val expectedText =
            if (expected.contains("<")) {
                stripHtml(expected)
            } else {
                expected
            }

        val normalizedSpoken = spoken.lowercase().trim()
        val normalizedExpected = expectedText.lowercase().trim()

        AiModeDebugLogger.d(tag, "Comparing spoken: '$normalizedSpoken' with expected: '$normalizedExpected'")

        // Exact match
        if (normalizedSpoken == normalizedExpected) {
            AiModeDebugLogger.d(tag, "Exact match found")
            return true
        }

        // Remove punctuation and compare
        // For Cyrillic text, we need to be careful with word boundaries
        val cleanSpoken = normalizedSpoken.replace(Regex("[^\\w\\s\\u0400-\\u04FF]"), "").trim()
        val cleanExpected = normalizedExpected.replace(Regex("[^\\w\\s\\u0400-\\u04FF]"), "").trim()

        AiModeDebugLogger.d(tag, "Clean comparison: '$cleanSpoken' vs '$cleanExpected'")

        // Check if spoken is contained in expected or vice versa (for partial matches)
        if (cleanSpoken == cleanExpected) {
            AiModeDebugLogger.d(tag, "Clean match found")
            return true
        }

        // Check if the spoken text is contained within the expected answer
        // This handles cases where user says just the key word from a longer answer
        if (cleanExpected.contains(cleanSpoken) && cleanSpoken.length > 3) {
            AiModeDebugLogger.d(tag, "Spoken contained in expected")
            return true
        }

        // Check if expected is contained in spoken (user said extra words)
        if (cleanSpoken.contains(cleanExpected) && cleanExpected.length > 3) {
            AiModeDebugLogger.d(tag, "Expected contained in spoken")
            return true
        }

        return false
    }

    /**
     * Strip HTML tags and CSS from text for TTS
     * Removes HTML tags, CSS blocks, common HTML entities, special characters,
     * and content within brackets/parentheses
     */
    private fun stripHtml(html: String): String =
        html
            // Remove CSS style blocks
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // Remove HTML tags
            .replace(Regex("<[^>]*>"), "")
            // Remove HTML entities
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            // Remove content within parentheses (), brackets [], and braces {}
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\{.*?\\}"), "")
            // Remove special characters that TTS might read aloud
            .replace(Regex("[=\\-_\\*#@\\$%\\^&+\\|\\\\/<>]"), " ")
            // Remove extra whitespace
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Transition to new state
     */
    private fun transitionTo(newState: AiModeState) {
        val oldState = _currentState.value
        _currentState.value = newState
        listener.onStateChanged(newState)
    }

    /**
     * Handle errors
     */
    private fun handleError(message: String) {
        AiModeDebugLogger.e(tag, message)
        transitionTo(AiModeState.Error(message, _currentState.value))
        listener.onError(message)
    }

    /**
     * Play a success beep to indicate correct answer
     * Uses TONE_SUP_CONFIRM for a positive confirmation sound
     */
    private fun playSuccessBeep() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            // TONE_SUP_CONFIRM is a positive confirmation tone
            toneGenerator.startTone(ToneGenerator.TONE_SUP_CONFIRM, 300)
            // Release after 400ms (300ms tone + 100ms margin)
            lifecycleOwner.lifecycleScope.launch {
                delay(400)
                toneGenerator.release()
            }
        } catch (e: Exception) {
            AiModeDebugLogger.w(tag, "Failed to play success beep: ${e.message}")
        }
    }

    /**
     * Stop all operations
     */
    private fun stopAll() {
        ttsManager.stop()
        sttManager.stopListening()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        AiModeDebugLogger.i(tag, "Controller cleanup")
        stopAll()
        ttsManager.cleanup()
        sttManager.cleanup()
    }
}
