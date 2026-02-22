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
import com.ichi2.anki.aimode.AiModePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Manages local file-based caching for TTS audio files
 */
class TTSCacheManager(
    private val context: Context,
) {
    companion object {
        private const val CACHE_DIR_NAME = "tts_cache"
        private const val MAX_CACHE_AGE_DAYS = 30L
        private const val BYTES_PER_MB = 1024 * 1024
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Get cached audio file if it exists and is not expired
     * @param text The text that was synthesized
     * @param voice The TTS voice used
     * @return File if cached and valid, null otherwise
     */
    suspend fun getCachedAudio(
        text: String,
        voice: String,
    ): File? =
        withContext(Dispatchers.IO) {
            val cacheKey = generateCacheKey(text, voice)
            val cacheFile = File(cacheDir, "$cacheKey.mp3")

            if (!cacheFile.exists()) {
                Timber.d("TTS cache miss for key: $cacheKey")
                return@withContext null
            }

            // Check if cache is expired
            val maxAgeMs = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - cacheFile.lastModified() > maxAgeMs) {
                Timber.d("TTS cache expired for key: $cacheKey")
                cacheFile.delete()
                return@withContext null
            }

            Timber.d("TTS cache hit for key: $cacheKey")
            cacheFile
        }

    /**
     * Save audio data to cache
     * @param text The text that was synthesized
     * @param voice The TTS voice used
     * @param audioData The audio bytes to cache
     * @return The cached file
     */
    suspend fun saveToCache(
        text: String,
        voice: String,
        audioData: ByteArray,
    ): File =
        withContext(Dispatchers.IO) {
            // Check cache size and clean if needed
            enforceCacheSizeLimit()

            val cacheKey = generateCacheKey(text, voice)
            val cacheFile = File(cacheDir, "$cacheKey.mp3")

            cacheFile.writeBytes(audioData)
            Timber.d("TTS audio cached: ${cacheFile.name} (${audioData.size} bytes)")

            cacheFile
        }

    /**
     * Clear all cached TTS files
     */
    suspend fun clearCache() =
        withContext(Dispatchers.IO) {
            cacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
            Timber.i("TTS cache cleared")
        }

    /**
     * Get current cache size in bytes
     */
    suspend fun getCacheSize(): Long =
        withContext(Dispatchers.IO) {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        }

    /**
     * Get current cache size in MB
     */
    suspend fun getCacheSizeMb(): Int =
        withContext(Dispatchers.IO) {
            (getCacheSize() / BYTES_PER_MB).toInt()
        }

    /**
     * Enforce the cache size limit by removing oldest files first
     */
    private suspend fun enforceCacheSizeLimit() =
        withContext(Dispatchers.IO) {
            val maxSizeBytes = AiModePreferences.getCacheSizeMb(context) * BYTES_PER_MB
            var currentSize = getCacheSize()

            if (currentSize <= maxSizeBytes) return@withContext

            // Get files sorted by last modified (oldest first)
            val files =
                cacheDir
                    .listFiles()
                    ?.sortedBy { it.lastModified() }
                    ?: return@withContext

            // Remove oldest files until under limit
            for (file in files) {
                if (currentSize <= maxSizeBytes) break

                val fileSize = file.length()
                if (file.delete()) {
                    currentSize -= fileSize
                    Timber.d("Removed old TTS cache file: ${file.name}")
                }
            }

            Timber.i("TTS cache size after cleanup: ${currentSize / BYTES_PER_MB} MB")
        }

    /**
     * Generate a cache key from text and voice
     * Uses SHA-256 hash for uniqueness
     */
    private fun generateCacheKey(
        text: String,
        voice: String,
    ): String {
        val input = "$text|$voice"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Pre-cache TTS audio for upcoming cards
     * @param entries List of text/voice pairs to cache
     */
    suspend fun preCache(entries: List<Pair<String, String>>) =
        withContext(Dispatchers.IO) {
            Timber.d("Pre-caching ${entries.size} TTS entries")
            // This is a placeholder - actual pre-caching would require TTS generation
            // which should be done by TTSManager
        }
}
