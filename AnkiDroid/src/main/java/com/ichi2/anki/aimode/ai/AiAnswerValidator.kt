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

package com.ichi2.anki.aimode.ai

import android.content.Context
import com.ichi2.anki.aimode.AiModePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Validates user answers using OpenAI GPT-3.5 to check for synonyms and variants
 */
class AiAnswerValidator(
    private val context: Context,
) {
    companion object {
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val REQUEST_TIMEOUT_MS = 5000L
        private const val MODEL = "gpt-3.5-turbo"
    }

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Validate if the user's answer is correct or a valid synonym
     * @param question The card question
     * @param expectedAnswer The expected answer from the card
     * @param userAnswer What the user actually said
     * @return ValidationResult with isCorrect, explanation, and isSynonym
     */
    suspend fun validateAnswer(
        question: String,
        expectedAnswer: String,
        userAnswer: String,
    ): ValidationResult =
        withContext(Dispatchers.IO) {
            try {
                val apiKey =
                    AiModePreferences.getOpenAiApiKey(context)
                        ?: return@withContext ValidationResult(
                            isCorrect = false,
                            explanation = "OpenAI API key not configured",
                            isSynonym = false,
                        )

                val requestBody = buildValidationRequest(question, expectedAnswer, userAnswer)

                val request =
                    Request
                        .Builder()
                        .url(OPENAI_API_URL)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()

                val response =
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        httpClient.newCall(request).execute()
                    } ?: return@withContext ValidationResult(
                        isCorrect = false,
                        explanation = "AI validation timeout",
                        isSynonym = false,
                    )

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Timber.w("AI validation API error: ${response.code} - $errorBody")
                    return@withContext ValidationResult(
                        isCorrect = false,
                        explanation = "AI validation failed: ${response.code}",
                        isSynonym = false,
                    )
                }

                val responseBody =
                    response.body?.string()
                        ?: return@withContext ValidationResult(
                            isCorrect = false,
                            explanation = "Empty AI response",
                            isSynonym = false,
                        )

                parseValidationResponse(responseBody)
            } catch (e: Exception) {
                Timber.e(e, "AI validation failed")
                ValidationResult(
                    isCorrect = false,
                    explanation = "Validation error: ${e.message}",
                    isSynonym = false,
                )
            }
        }

    /**
     * Build the OpenAI API request for answer validation
     */
    private fun buildValidationRequest(
        question: String,
        expectedAnswer: String,
        userAnswer: String,
    ): String {
        val systemPrompt =
            """
            You are evaluating Russian vocabulary answers for a language learning app.
            The user is learning Russian vocabulary from Italian.
            
            Your task:
            1. Determine if the user's answer is CORRECT (matches expected answer or is a valid synonym/variant)
            2. Consider spelling variations, case differences, and common synonyms
            3. Be lenient with minor pronunciation/spelling errors
            4. Respond ONLY with a JSON object in this exact format:
            {
                "isCorrect": true/false,
                "isSynonym": true/false,
                "explanation": "brief explanation in Italian"
            }
            
            Rules:
            - isCorrect: true if the answer is right (exact match OR acceptable synonym)
            - isSynonym: true only if it's a synonym/variant, not exact match
            - explanation: brief feedback in Italian explaining why it's right or wrong
            """.trimIndent()

        val userPrompt =
            """
            Question (Italian): $question
            Expected Answer (Russian): $expectedAnswer
            User's Answer (Russian): $userAnswer
            
            Evaluate if the user's answer is correct.
            """.trimIndent()

        val messages =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    },
                )
            }

        return JSONObject()
            .apply {
                put("model", MODEL)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 150)
            }.toString()
    }

    /**
     * Parse the OpenAI API response
     */
    private fun parseValidationResponse(responseBody: String): ValidationResult =
        try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            val content = message.getString("content")

            // Extract JSON from the content (it might be wrapped in markdown code blocks)
            val jsonContent = extractJsonFromContent(content)
            val resultJson = JSONObject(jsonContent)

            ValidationResult(
                isCorrect = resultJson.optBoolean("isCorrect", false),
                explanation = resultJson.optString("explanation", ""),
                isSynonym = resultJson.optBoolean("isSynonym", false),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse AI validation response")
            ValidationResult(
                isCorrect = false,
                explanation = "Failed to parse validation result",
                isSynonym = false,
            )
        }

    /**
     * Extract JSON from content that might be wrapped in markdown code blocks
     */
    private fun extractJsonFromContent(content: String): String {
        val trimmed = content.trim()

        // Check if wrapped in markdown code block
        if (trimmed.startsWith("```json")) {
            return trimmed
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()
        }

        if (trimmed.startsWith("```")) {
            return trimmed
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        return trimmed
    }

    /**
     * Clean up resources
     * Must be called from a background thread to avoid NetworkOnMainThreadException
     */
    fun cleanup() {
        // Shutdown must happen on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
            } catch (e: Exception) {
                Timber.w(e, "Error during AiAnswerValidator cleanup")
            }
        }
    }
}

/**
 * Result of AI answer validation
 */
data class ValidationResult(
    val isCorrect: Boolean,
    val explanation: String,
    val isSynonym: Boolean,
)
