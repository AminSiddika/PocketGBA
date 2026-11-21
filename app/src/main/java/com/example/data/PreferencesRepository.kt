package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.emulator.OrientationMode
import com.example.emulator.ShaderPreset
import com.example.emulator.SpeedToggleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_advance_prefs")

data class EmulatorPreferences(
    val shaderPreset: ShaderPreset = ShaderPreset.LCD3X,
    val fastForwardSpeed: Float = 4f,
    val speedToggleMode: SpeedToggleMode = SpeedToggleMode.TOGGLE,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val audioEnabled: Boolean = true,
    val audioVolume: Float = 0.8f,
    val hapticsEnabled: Boolean = true,
    val controlOpacity: Float = 0.82f,
    val controlScale: Float = 1.0f,
    val masterCheatsEnabled: Boolean = true,
    val lcdGridIntensity: Float = 0.35f,
    val customBiosPath: String? = null
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SHADER = stringPreferencesKey("shader_preset")
        val SPEED = floatPreferencesKey("fast_forward_speed")
        val SPEED_MODE = stringPreferencesKey("speed_toggle_mode")
        val ORIENTATION = stringPreferencesKey("orientation_mode")
        val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        val AUDIO_VOLUME = floatPreferencesKey("audio_volume")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val CONTROL_OPACITY = floatPreferencesKey("control_opacity")
        val CONTROL_SCALE = floatPreferencesKey("control_scale")
        val MASTER_CHEATS = booleanPreferencesKey("master_cheats_enabled")
        val LCD_GRID = floatPreferencesKey("lcd_grid_intensity")
        val BIOS_PATH = stringPreferencesKey("custom_bios_path")
    }

    val preferences: Flow<EmulatorPreferences> = context.dataStore.data.map { prefs ->
        EmulatorPreferences(
            shaderPreset = prefs[Keys.SHADER]?.let { runCatching { ShaderPreset.valueOf(it) }.getOrNull() }
                ?: ShaderPreset.LCD3X,
            fastForwardSpeed = (prefs[Keys.SPEED] ?: 4f).coerceIn(1.5f, 8f),
            speedToggleMode = prefs[Keys.SPEED_MODE]?.let { runCatching { SpeedToggleMode.valueOf(it) }.getOrNull() }
                ?: SpeedToggleMode.TOGGLE,
            orientationMode = prefs[Keys.ORIENTATION]?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
                ?: OrientationMode.AUTO,
            audioEnabled = prefs[Keys.AUDIO_ENABLED] ?: true,
            audioVolume = prefs[Keys.AUDIO_VOLUME] ?: 0.8f,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            controlOpacity = prefs[Keys.CONTROL_OPACITY] ?: 0.82f,
            controlScale = prefs[Keys.CONTROL_SCALE] ?: 1.0f,
            masterCheatsEnabled = prefs[Keys.MASTER_CHEATS] ?: true,
            lcdGridIntensity = prefs[Keys.LCD_GRID] ?: 0.35f,
            customBiosPath = prefs[Keys.BIOS_PATH]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun setShader(preset: ShaderPreset) = context.dataStore.edit { it[Keys.SHADER] = preset.name }
    suspend fun setFastForwardSpeed(speed: Float) = context.dataStore.edit { it[Keys.SPEED] = speed.coerceIn(1.5f, 8f) }
    suspend fun setSpeedToggleMode(mode: SpeedToggleMode) = context.dataStore.edit { it[Keys.SPEED_MODE] = mode.name }
    suspend fun setOrientationMode(mode: OrientationMode) = context.dataStore.edit { it[Keys.ORIENTATION] = mode.name }
    suspend fun setAudioEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUDIO_ENABLED] = enabled }
    suspend fun setAudioVolume(volume: Float) = context.dataStore.edit { it[Keys.AUDIO_VOLUME] = volume.coerceIn(0f, 1f) }
    suspend fun setHapticsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = enabled }
    suspend fun setControlOpacity(opacity: Float) = context.dataStore.edit { it[Keys.CONTROL_OPACITY] = opacity.coerceIn(0.3f, 1f) }
    suspend fun setControlScale(scale: Float) = context.dataStore.edit { it[Keys.CONTROL_SCALE] = scale.coerceIn(0.75f, 1.3f) }
    suspend fun setMasterCheatsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.MASTER_CHEATS] = enabled }
    suspend fun setLcdGridIntensity(intensity: Float) = context.dataStore.edit { it[Keys.LCD_GRID] = intensity.coerceIn(0f, 1f) }
    suspend fun setCustomBiosPath(path: String?) = context.dataStore.edit {
        if (path.isNullOrBlank()) it.remove(Keys.BIOS_PATH) else it[Keys.BIOS_PATH] = path
    }
}
