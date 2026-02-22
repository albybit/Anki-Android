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

import anki.scheduler.CardAnswer.Rating

/**
 * Sealed class representing all possible states in the AI Voice Mode state machine.
 * Each state tracks when it was entered for timing purposes.
 */
sealed class AiModeState {
    abstract val entryTime: Long

    /**
     * Initial state when AI mode is enabled but waiting for a card
     */
    data class Idle(
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * TTS is currently reading the question (front of card)
     */
    data class ReadingQuestion(
        val cardId: Long,
        val questionText: String,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * STT is listening for user's spoken answer
     */
    data class ListeningAnswer(
        val cardId: Long,
        val expectedAnswer: String,
        val repeatCount: Int = 0, // Track how many times we've asked to repeat
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Processing the spoken answer (STT completed, validating)
     */
    data class Validating(
        val cardId: Long,
        val spokenText: String,
        val expectedAnswer: String,
        val responseTimeMs: Long,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Answer was correct, determining rating based on response time
     */
    data class AnswerCorrect(
        val cardId: Long,
        val responseTimeMs: Long,
        val spokenText: String,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Answer was wrong or user said "не знаю"
     */
    data class AnswerWrong(
        val cardId: Long,
        val expectedAnswer: String,
        val spokenText: String? = null,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Listening for rating command (хорошо/плохо) after showing answer
     */
    data class ListeningForRating(
        val cardId: Long,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Ready to rate the card (auto-rating or waiting for manual override)
     */
    data class RatingCard(
        val cardId: Long,
        val rating: Rating,
        val isAutoRated: Boolean = true,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    /**
     * Error state when something goes wrong
     */
    data class Error(
        val message: String,
        val previousState: AiModeState? = null,
        override val entryTime: Long = System.currentTimeMillis(),
    ) : AiModeState()

    companion object {
        /**
         * Calculate the suggested rating based on response time
         * @param responseTimeMs Time taken to answer in milliseconds
         * @return Rating.EASY, Rating.GOOD, or Rating.HARD
         */
        fun calculateRating(responseTimeMs: Long): Rating =
            when {
                responseTimeMs < 3000 -> Rating.EASY
                responseTimeMs < 10000 -> Rating.GOOD
                else -> Rating.HARD
            }

        /**
         * Check if the spoken text indicates "don't know"
         * Supports Russian "не знаю" and variants
         */
        fun isDontKnow(spokenText: String): Boolean {
            val lower = spokenText.lowercase().trim()
            return lower.contains("не знаю") ||
                lower.contains("ne znayu") ||
                lower.contains("don't know") ||
                lower.contains("dont know") ||
                lower.contains("non lo so") ||
                lower == "не" ||
                lower == "no"
        }

        /**
         * Check if user wants AI validation
         * Triggered by saying "спросить ИИ" or similar
         */
        fun wantsAiValidation(spokenText: String): Boolean {
            val lower = spokenText.lowercase().trim()
            return lower.contains("спросить ии") ||
                lower.contains("ask ai") ||
                lower.contains("chiedi ai") ||
                lower.contains("проверь") ||
                lower.contains("проверить")
        }
    }
}

/**
 * Data class representing the result of voice evaluation
 */
data class VoiceEvaluationResult(
    val isCorrect: Boolean,
    val confidence: Float,
    val spokenText: String,
    val expectedAnswer: String,
    val responseTimeMs: Long,
    val suggestedRating: Rating,
    val isDontKnow: Boolean = false,
    val wantsAiValidation: Boolean = false,
)

/**
 * Listener interface for AI Mode state changes
 */
interface AiModeStateListener {
    fun onStateChanged(
        newState: AiModeState,
        previousState: AiModeState?,
    )

    fun onError(error: String)

    fun onTtsStart(text: String)

    fun onTtsComplete()

    fun onListeningStart()

    fun onListeningComplete(spokenText: String)

    fun onAnswerEvaluated(result: VoiceEvaluationResult)
}
