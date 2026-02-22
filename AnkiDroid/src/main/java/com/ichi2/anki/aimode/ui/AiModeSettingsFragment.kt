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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ichi2.anki.R
import com.ichi2.anki.aimode.AiModePreferences
import com.ichi2.anki.aimode.tts.TTSCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings fragment for AI Voice Mode
 */
class AiModeSettingsFragment : Fragment() {
    private lateinit var switchEnableAiMode: Switch
    private lateinit var editDeepgramKey: EditText
    private lateinit var editOpenAiKey: EditText
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var spinnerTtsVoice: Spinner
    private lateinit var textCacheSize: TextView
    private lateinit var buttonClearCache: Button
    private lateinit var buttonViewLogs: Button
    private lateinit var buttonTestTts: Button
    private lateinit var buttonTestStt: Button
    private lateinit var textSttResult: TextView

    // Keep TTSManager as instance variable to prevent garbage collection during playback
    private var ttsManager: com.ichi2.anki.aimode.tts.TTSManager? = null
    private var sttManager: com.ichi2.anki.aimode.stt.STTManager? = null

    private val languages =
        mapOf(
            "it" to "Italian",
            "ru" to "Russian",
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
        )

    private val ttsVoices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_ai_mode_settings, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        loadSettings()
        setupListeners()
        updateCacheSize()
    }

    private fun initViews(view: View) {
        switchEnableAiMode = view.findViewById(R.id.switch_enable_ai_mode)
        editDeepgramKey = view.findViewById(R.id.edit_deepgram_key)
        editOpenAiKey = view.findViewById(R.id.edit_openai_key)
        spinnerSourceLang = view.findViewById(R.id.spinner_source_language)
        spinnerTargetLang = view.findViewById(R.id.spinner_target_language)
        spinnerTtsVoice = view.findViewById(R.id.spinner_tts_voice)
        textCacheSize = view.findViewById(R.id.text_cache_size)
        buttonClearCache = view.findViewById(R.id.button_clear_cache)
        buttonViewLogs = view.findViewById(R.id.button_view_logs)
        buttonTestTts = view.findViewById(R.id.button_test_tts)
        buttonTestStt = view.findViewById(R.id.button_test_stt)
        textSttResult = view.findViewById(R.id.text_stt_result)

        // Setup spinners
        setupLanguageSpinner(spinnerSourceLang)
        setupLanguageSpinner(spinnerTargetLang)
        setupVoiceSpinner()
    }

    private fun setupLanguageSpinner(spinner: Spinner) {
        val adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                languages.values.toList(),
            )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupVoiceSpinner() {
        val adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                ttsVoices,
            )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTtsVoice.adapter = adapter
    }

    private fun loadSettings() {
        val context = requireContext()

        switchEnableAiMode.isChecked = AiModePreferences.isAiModeEnabled(context)
        editDeepgramKey.setText(AiModePreferences.getDeepgramApiKey(context) ?: "")
        editOpenAiKey.setText(AiModePreferences.getOpenAiApiKey(context) ?: "")

        // Set language spinners
        val sourceLang = AiModePreferences.getSourceLanguage(context)
        val targetLang = AiModePreferences.getTargetLanguage(context)
        spinnerSourceLang.setSelection(languages.keys.indexOf(sourceLang).coerceAtLeast(0))
        spinnerTargetLang.setSelection(languages.keys.indexOf(targetLang).coerceAtLeast(0))

        // Set voice spinner
        val voice = AiModePreferences.getTtsVoice(context)
        spinnerTtsVoice.setSelection(ttsVoices.indexOf(voice).coerceAtLeast(0))
    }

    private fun setupListeners() {
        val context = requireContext()

        switchEnableAiMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !AiModePreferences.areApiKeysConfigured(context)) {
                switchEnableAiMode.isChecked = false
                Toast
                    .makeText(
                        context,
                        "Please configure API keys first",
                        Toast.LENGTH_LONG,
                    ).show()
                return@setOnCheckedChangeListener
            }
            AiModePreferences.setAiModeEnabled(context, isChecked)
            Toast
                .makeText(
                    context,
                    if (isChecked) "AI Mode enabled" else "AI Mode disabled",
                    Toast.LENGTH_SHORT,
                ).show()
        }

        spinnerSourceLang.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val langCode = languages.keys.elementAt(position)
                    AiModePreferences.setSourceLanguage(context, langCode)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        spinnerTargetLang.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val langCode = languages.keys.elementAt(position)
                    AiModePreferences.setTargetLanguage(context, langCode)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        spinnerTtsVoice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val voice = ttsVoices[position]
                    AiModePreferences.setTtsVoice(context, voice)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        buttonClearCache.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                TTSCacheManager(context).clearCache()
                withContext(Dispatchers.Main) {
                    updateCacheSize()
                    Toast.makeText(context, "Cache cleared. Test TTS again to fetch new audio.", Toast.LENGTH_LONG).show()
                }
            }
        }

        buttonViewLogs.setOnClickListener {
            AiModeDebugActivity.start(context)
        }

        buttonTestTts.setOnClickListener {
            testTts()
        }

        buttonTestStt.setOnClickListener {
            testStt()
        }
    }

    private fun updateCacheSize() {
        CoroutineScope(Dispatchers.IO).launch {
            val size = TTSCacheManager(requireContext()).getCacheSizeMb()
            withContext(Dispatchers.Main) {
                textCacheSize.text = "Cache size: $size MB"
            }
        }
    }

    private fun testTts() {
        val context = requireContext()
        val apiKey = editOpenAiKey.text.toString()

        if (apiKey.isBlank()) {
            Toast.makeText(context, "Please enter OpenAI API key", Toast.LENGTH_SHORT).show()
            return
        }

        // Save key first
        AiModePreferences.setOpenAiApiKey(context, apiKey)

        // Test TTS
        Toast.makeText(context, "Testing TTS...", Toast.LENGTH_SHORT).show()

        // Stop any existing TTS first
        ttsManager?.stop()

        // Create or reuse TTSManager instance
        if (ttsManager == null) {
            ttsManager =
                com.ichi2.anki.aimode.tts
                    .TTSManager(context)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ttsManager?.speak(
                    text = "Test di sintesi vocale riuscito",
                    onComplete = {
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "TTS test complete!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { error ->
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast
                                .makeText(
                                    context,
                                    "TTS error: ${error.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    },
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(
                            context,
                            "TTS test failed: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    private fun testStt() {
        val context = requireContext()
        val apiKey = editDeepgramKey.text.toString()

        if (apiKey.isBlank()) {
            Toast.makeText(context, "Please enter Deepgram API key", Toast.LENGTH_SHORT).show()
            return
        }

        // Save key first
        AiModePreferences.setDeepgramApiKey(context, apiKey)

        // Check if already listening
        if (sttManager?.isListening() == true) {
            sttManager?.stopListening()
            buttonTestStt.text = "Test STT (Speech Recognition)"
            textSttResult.text = "STT stopped"
            return
        }

        // Test STT
        Toast.makeText(context, "Listening... Speak now", Toast.LENGTH_SHORT).show()
        buttonTestStt.text = "Stop Listening"
        textSttResult.text = "Listening..."

        // Create STTManager instance
        if (sttManager == null) {
            sttManager =
                com.ichi2.anki.aimode.stt
                    .STTManager(context)
        }

        sttManager?.startListening(
            onResult = { result ->
                CoroutineScope(Dispatchers.Main).launch {
                    textSttResult.text = "Recognized: $result"
                    buttonTestStt.text = "Test STT (Speech Recognition)"
                    Toast.makeText(context, "STT Result: $result", Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                CoroutineScope(Dispatchers.Main).launch {
                    textSttResult.text = "Error: ${error.message}"
                    buttonTestStt.text = "Test STT (Speech Recognition)"
                    Toast.makeText(context, "STT Error: ${error.message}", Toast.LENGTH_LONG).show()
                }
            },
            autoStopOnSilence = true,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up TTSManager to prevent memory leaks
        ttsManager?.cleanup()
        ttsManager = null
        // Clean up STTManager
        sttManager?.stopListening()
        sttManager = null
    }

    override fun onPause() {
        super.onPause()
        // Save API keys
        val context = requireContext()
        AiModePreferences.setDeepgramApiKey(context, editDeepgramKey.text.toString())
        AiModePreferences.setOpenAiApiKey(context, editOpenAiKey.text.toString())
    }
}
