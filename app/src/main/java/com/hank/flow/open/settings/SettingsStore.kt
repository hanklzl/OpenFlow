package com.hank.flow.open.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hank.flow.open.llm.InferenceBackendPreference
import com.hank.flow.open.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "openflow_settings")

data class FlowSettings(
    val polishEnabled: Boolean,
    val swipeUpCancelEnabled: Boolean,
    val waveformEnabled: Boolean,
    val editBeforeInsertEnabled: Boolean,
    val polishWarmupEnabled: Boolean,
    val polishStreamingEnabled: Boolean,
    val llmAccelerationEnabled: Boolean,
    val llmInferenceBackend: InferenceBackendPreference,
    val customDictionaryEntries: List<String>,
    val whisperModelId: String,
    val llmModelId: String,
    val mirrorBase: String,
)

class SettingsStore(private val context: Context) {

    val flow: Flow<FlowSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): FlowSettings = flow.first()

    suspend fun setPolishEnabled(enabled: Boolean) = update { it[K_POLISH] = enabled }
    suspend fun setSwipeUpCancel(enabled: Boolean) = update { it[K_SWIPE_CANCEL] = enabled }
    suspend fun setWaveform(enabled: Boolean) = update { it[K_WAVEFORM] = enabled }
    suspend fun setEditBeforeInsert(enabled: Boolean) = update { it[K_EDIT_BEFORE] = enabled }
    suspend fun setPolishWarmupEnabled(enabled: Boolean) = update { it[K_POLISH_WARMUP] = enabled }
    suspend fun setPolishStreamingEnabled(enabled: Boolean) = update { it[K_POLISH_STREAMING] = enabled }
    suspend fun setLlmAccelerationEnabled(enabled: Boolean) = update { it[K_LLM_ACCELERATION] = enabled }
    suspend fun setLlmInferenceBackend(preference: InferenceBackendPreference) =
        update { it[K_LLM_BACKEND] = preference.id }
    suspend fun setCustomDictionaryEntries(entries: List<String>) =
        update { it[K_CUSTOM_DICTIONARY] = CustomDictionary.serialize(entries) }
    suspend fun setWhisperModelId(id: String) = update { it[K_WHISPER_ID] = id }
    suspend fun setLlmModelId(id: String) = update { it[K_LLM_ID] = id }
    suspend fun setMirrorBase(url: String) = update { it[K_MIRROR_BASE] = url }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSettings(): FlowSettings = FlowSettings(
        polishEnabled = this[K_POLISH] ?: true,
        swipeUpCancelEnabled = this[K_SWIPE_CANCEL] ?: true,
        waveformEnabled = this[K_WAVEFORM] ?: true,
        editBeforeInsertEnabled = this[K_EDIT_BEFORE] ?: false,
        polishWarmupEnabled = this[K_POLISH_WARMUP] ?: true,
        polishStreamingEnabled = this[K_POLISH_STREAMING] ?: true,
        llmAccelerationEnabled = this[K_LLM_ACCELERATION] ?: false,
        llmInferenceBackend = InferenceBackendPreference.fromId(this[K_LLM_BACKEND]),
        customDictionaryEntries = CustomDictionary.parse(this[K_CUSTOM_DICTIONARY].orEmpty()),
        whisperModelId = this[K_WHISPER_ID] ?: ModelCatalog.whisperDefault.id,
        llmModelId = this[K_LLM_ID] ?: ModelCatalog.llmDefault.id,
        mirrorBase = this[K_MIRROR_BASE] ?: DEFAULT_MIRROR,
    )

    companion object {
        const val DEFAULT_MIRROR = "https://huggingface.co"
        const val ALT_MIRROR = "https://hf-mirror.com"

        private val K_POLISH = booleanPreferencesKey("polish_enabled")
        private val K_SWIPE_CANCEL = booleanPreferencesKey("swipe_cancel")
        private val K_WAVEFORM = booleanPreferencesKey("waveform")
        private val K_EDIT_BEFORE = booleanPreferencesKey("edit_before_insert")
        private val K_POLISH_WARMUP = booleanPreferencesKey("polish_warmup_enabled")
        private val K_POLISH_STREAMING = booleanPreferencesKey("polish_streaming_enabled")
        private val K_LLM_ACCELERATION = booleanPreferencesKey("llm_acceleration_enabled")
        private val K_LLM_BACKEND = stringPreferencesKey("llm_inference_backend")
        private val K_CUSTOM_DICTIONARY = stringPreferencesKey("custom_dictionary")
        private val K_WHISPER_ID = stringPreferencesKey("whisper_model_id")
        private val K_LLM_ID = stringPreferencesKey("llm_model_id")
        private val K_MIRROR_BASE = stringPreferencesKey("mirror_base")
    }
}
