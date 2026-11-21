package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.emulator.BiosMode
import com.example.emulator.OrientationMode
import com.example.emulator.ShaderPreset
import com.example.emulator.SpeedToggleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_advance_prefs")

data class EmulatorPreferences(
    val shaderPreset: ShaderPreset = ShaderPreset.LCD3X,
    val activeShaderProfileId: Long = 0L,
    val fastForwardSpeed: Float = 4f,
    val speedToggleMode: SpeedToggleMode = SpeedToggleMode.TOGGLE,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val audioEnabled: Boolean = true,
    val audioVolume: Float = 0.8f,
    val muteTurboAudioAbove2x: Boolean = true,
    val autoSaveOnExit: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val rumbleEnabled: Boolean = true,
    val rumbleIntensity: Float = 0.85f,
    val controlOpacity: Float = 0.82f,
    val controlScale: Float = 1.0f,
    val masterCheatsEnabled: Boolean = true,
    val lcdGridIntensity: Float = 0.35f,
    val customBiosPath: String? = null,
    val biosMode: BiosMode = BiosMode.AUTO,
    val skipBiosIntro: Boolean = true
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SHADER = stringPreferencesKey("shader_preset")
        val ACTIVE_SHADER_PROFILE_ID = longPreferencesKey("active_shader_profile_id")
        val SPEED = floatPreferencesKey("fast_forward_speed")
        val SPEED_MODE = stringPreferencesKey("speed_toggle_mode")
        val ORIENTATION = stringPreferencesKey("orientation_mode")
        val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        val AUDIO_VOLUME = floatPreferencesKey("audio_volume")
        val MUTE_TURBO_AUDIO = booleanPreferencesKey("mute_turbo_audio_above_2x")
        val AUTO_SAVE_EXIT = booleanPreferencesKey("auto_save_on_exit")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val RUMBLE_ENABLED = booleanPreferencesKey("rumble_enabled")
        val RUMBLE_INTENSITY = floatPreferencesKey("rumble_intensity")
        val CONTROL_OPACITY = floatPreferencesKey("control_opacity")
        val CONTROL_SCALE = floatPreferencesKey("control_scale")
        val MASTER_CHEATS = booleanPreferencesKey("master_cheats_enabled")
        val LCD_GRID = floatPreferencesKey("lcd_grid_intensity")
        val BIOS_PATH = stringPreferencesKey("custom_bios_path")
        val BIOS_MODE = stringPreferencesKey("bios_mode")
        val SKIP_BIOS_INTRO = booleanPreferencesKey("skip_bios_intro")
    }

    val preferences: Flow<EmulatorPreferences> = context.dataStore.data.map { prefs ->
        EmulatorPreferences(
            shaderPreset = prefs[Keys.SHADER]?.let { runCatching { ShaderPreset.valueOf(it) }.getOrNull() }
                ?: ShaderPreset.LCD3X,
            activeShaderProfileId = prefs[Keys.ACTIVE_SHADER_PROFILE_ID] ?: 0L,
            fastForwardSpeed = (prefs[Keys.SPEED] ?: 4f).coerceIn(1.5f, 8f),
            speedToggleMode = prefs[Keys.SPEED_MODE]?.let { runCatching { SpeedToggleMode.valueOf(it) }.getOrNull() }
                ?: SpeedToggleMode.TOGGLE,
            orientationMode = prefs[Keys.ORIENTATION]?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
                ?: OrientationMode.AUTO,
            audioEnabled = prefs[Keys.AUDIO_ENABLED] ?: true,
            audioVolume = prefs[Keys.AUDIO_VOLUME] ?: 0.8f,
            muteTurboAudioAbove2x = prefs[Keys.MUTE_TURBO_AUDIO] ?: true,
            autoSaveOnExit = prefs[Keys.AUTO_SAVE_EXIT] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            rumbleEnabled = prefs[Keys.RUMBLE_ENABLED] ?: true,
            rumbleIntensity = (prefs[Keys.RUMBLE_INTENSITY] ?: 0.85f).coerceIn(0.25f, 1.0f),
            controlOpacity = prefs[Keys.CONTROL_OPACITY] ?: 0.82f,
            controlScale = prefs[Keys.CONTROL_SCALE] ?: 1.0f,
            masterCheatsEnabled = prefs[Keys.MASTER_CHEATS] ?: true,
            lcdGridIntensity = prefs[Keys.LCD_GRID] ?: 0.35f,
            customBiosPath = prefs[Keys.BIOS_PATH]?.takeIf { it.isNotBlank() },
            biosMode = prefs[Keys.BIOS_MODE]?.let { runCatching { BiosMode.valueOf(it) }.getOrNull() }
                ?: BiosMode.AUTO,
            skipBiosIntro = prefs[Keys.SKIP_BIOS_INTRO] ?: true
        )
    }

    suspend fun setShader(preset: ShaderPreset) = context.dataStore.edit { it[Keys.SHADER] = preset.name }
    suspend fun setActiveShaderProfileId(id: Long) = context.dataStore.edit { it[Keys.ACTIVE_SHADER_PROFILE_ID] = id }
    suspend fun setFastForwardSpeed(speed: Float) = context.dataStore.edit { it[Keys.SPEED] = speed.coerceIn(1.5f, 8f) }
    suspend fun setSpeedToggleMode(mode: SpeedToggleMode) = context.dataStore.edit { it[Keys.SPEED_MODE] = mode.name }
    suspend fun setOrientationMode(mode: OrientationMode) = context.dataStore.edit { it[Keys.ORIENTATION] = mode.name }
    suspend fun setAudioEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUDIO_ENABLED] = enabled }
    suspend fun setAudioVolume(volume: Float) = context.dataStore.edit { it[Keys.AUDIO_VOLUME] = volume.coerceIn(0f, 1f) }
    suspend fun setMuteTurboAudioAbove2x(enabled: Boolean) = context.dataStore.edit { it[Keys.MUTE_TURBO_AUDIO] = enabled }
    suspend fun setAutoSaveOnExit(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_SAVE_EXIT] = enabled }
    suspend fun setHapticsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = enabled }
    suspend fun setRumbleEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.RUMBLE_ENABLED] = enabled }
    suspend fun setRumbleIntensity(intensity: Float) = context.dataStore.edit { it[Keys.RUMBLE_INTENSITY] = intensity.coerceIn(0.25f, 1.0f) }
    suspend fun setControlOpacity(opacity: Float) = context.dataStore.edit { it[Keys.CONTROL_OPACITY] = opacity.coerceIn(0.3f, 1f) }
    suspend fun setControlScale(scale: Float) = context.dataStore.edit { it[Keys.CONTROL_SCALE] = scale.coerceIn(0.75f, 1.3f) }
    suspend fun setMasterCheatsEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.MASTER_CHEATS] = enabled }
    suspend fun setLcdGridIntensity(intensity: Float) = context.dataStore.edit { it[Keys.LCD_GRID] = intensity.coerceIn(0f, 1f) }
    suspend fun setBiosMode(mode: BiosMode) = context.dataStore.edit { it[Keys.BIOS_MODE] = mode.name }
    suspend fun setSkipBiosIntro(skip: Boolean) = context.dataStore.edit { it[Keys.SKIP_BIOS_INTRO] = skip }
    suspend fun setCustomBiosPath(path: String?) = context.dataStore.edit {
        if (path.isNullOrBlank()) it.remove(Keys.BIOS_PATH) else it[Keys.BIOS_PATH] = path
    }
}
