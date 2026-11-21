package com.example.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EmulatorPreferences
import com.example.data.PreferencesRepository
import com.example.data.RomRepository
import com.example.data.db.AppDatabase
import com.example.data.db.CheatEntity
import com.example.data.db.RomEntity
import com.example.data.savestate.SaveStateRepository
import com.example.data.savestate.SaveStateSlotInfo
import com.example.emulator.CoreTelemetry
import com.example.emulator.GbaConstants
import com.example.emulator.GbaCore
import com.example.emulator.GbaRomMetadata
import com.example.emulator.OrientationMode
import com.example.emulator.SaveType
import com.example.emulator.ShaderPreset
import com.example.emulator.SpeedToggleMode
import com.example.emulator.cheats.CheatDecoder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ChtFileParser
import com.example.emulator.cheats.CompiledCheat
import com.example.emulator.cheats.ParsedChtEntry
import com.example.emulator.cheats.ZipCheatCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EmulatorUiState(
    val rom: RomEntity? = null,
    val metadata: GbaRomMetadata? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isPaused: Boolean = false,
    val isTurboActive: Boolean = false,
    val currentSpeedMultiplier: Float = 1f,
    val fps: Int = 0,
    val telemetry: CoreTelemetry? = null,
    val slots: List<SaveStateSlotInfo> = emptyList(),
    val cheats: List<CheatEntity> = emptyList(),
    val zipCandidates: List<ZipCheatCandidate> = emptyList(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val toastMessage: String? = null
)

class EmulatorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).emulatorDao()
    private val romRepo = RomRepository(application, dao)
    private val saveRepo = SaveStateRepository(application)
    private val prefsRepo = PreferencesRepository(application)

    private val core = GbaCore()
    private val lock = Any()

    private val _uiState = MutableStateFlow(EmulatorUiState())
    val uiState: StateFlow<EmulatorUiState> = _uiState.asStateFlow()

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    private var compiledCheats: List<CompiledCheat> = emptyList()
    private var emuJob: Job? = null
    private var cheatsObserveJob: Job? = null
    private var loadedRomId: Long = -1L
    private var sessionPlaySeconds: Long = 0L

    init {
        viewModelScope.launch {
            prefsRepo.preferences.collectLatest { prefs ->
                core.apu.enabled = prefs.audioEnabled
                core.apu.volume = prefs.audioVolume
                _uiState.update { state ->
                    val mult = if (state.isTurboActive) prefs.fastForwardSpeed else 1f
                    state.copy(preferences = prefs, currentSpeedMultiplier = mult)
                }
            }
        }
    }

    fun loadRomSession(romId: Long) {
        if (loadedRomId == romId && core.isLoaded) return
        loadedRomId = romId
        sessionPlaySeconds = 0L
        emuJob?.cancel()
        cheatsObserveJob?.cancel()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val rom = romRepo.getRomById(romId)
            if (rom == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "ROM not found.") }
                return@launch
            }

            try {
                val romBytes = romRepo.loadRomBytes(rom)
                val saveType = runCatching { SaveType.valueOf(rom.saveType) }.getOrDefault(SaveType.SRAM)
                val sramBytes = saveRepo.loadBatterySram(rom.baseName)
                val customBios = romRepo.loadCustomBios(_uiState.value.preferences.customBiosPath)

                val meta = GbaRomMetadata(
                    title = rom.title,
                    gameCode = rom.gameCode,
                    makerCode = rom.makerCode,
                    crc32Hex = rom.crc32Hex,
                    sizeBytes = rom.sizeBytes,
                    saveType = saveType,
                    validHeader = true
                )

                synchronized(lock) {
                    core.loadRom(romBytes, saveType, sramBytes, customBios)
                    core.onVBlankCheatHook = { mmu ->
                        if (_uiState.value.preferences.masterCheatsEnabled && compiledCheats.isNotEmpty()) {
                            CheatDecoder.executeAll(compiledCheats, mmu)
                        }
                    }
                }
                core.apu.startAudioTrack()
                romRepo.markPlayed(rom.id)

                val slots = saveRepo.inspectAllSlots(rom.baseName, rom.crc32Hex)
                _uiState.update {
                    it.copy(
                        rom = rom,
                        metadata = meta,
                        isLoading = false,
                        isPaused = false,
                        slots = slots,
                        telemetry = core.getTelemetry()
                    )
                }

                observeCheatsForRom(rom.id)
                startEmulationLoop(rom)
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load ROM.")
                }
            }
        }
    }

    private fun observeCheatsForRom(romId: Long) {
        cheatsObserveJob?.cancel()
        cheatsObserveJob = viewModelScope.launch {
            romRepo.observeCheatsForRom(romId).collectLatest { list ->
                compiledCheats = list
                    .filter { it.enabled }
                    .map {
                        val fmt = runCatching { CheatFormat.valueOf(it.formatName) }.getOrDefault(CheatFormat.AUTO)
                        CheatDecoder.compile(it.id, it.name, it.code, fmt)
                    }
                _uiState.update { it.copy(cheats = list) }
            }
        }
    }

    private fun startEmulationLoop(rom: RomEntity) {
        emuJob?.cancel()
        emuJob = viewModelScope.launch(Dispatchers.Default) {
            var frameCounter = 0
            var lastFpsAt = System.currentTimeMillis()
            var lastPlayFlushAt = lastFpsAt

            while (isActive) {
                val state = _uiState.value
                if (state.isPaused || !core.isLoaded) {
                    delay(16L)
                    continue
                }

                val targetSpeed = state.currentSpeedMultiplier.coerceIn(1f, 8f)
                val steps = when {
                    targetSpeed >= 7.5f -> 8
                    targetSpeed >= 5.5f -> 6
                    targetSpeed >= 3.5f -> 4
                    targetSpeed >= 2.5f -> 3
                    targetSpeed >= 1.75f -> 2
                    else -> 1
                }

                val frameStartNs = System.nanoTime()
                synchronized(lock) {
                    if (steps == 1 && targetSpeed > 1.2f) {
                        // 1.5x fractional step
                        core.stepFrame(renderPixels = true, speedMultiplier = targetSpeed)
                        if ((frameCounter and 1) == 0) {
                            core.stepFrame(renderPixels = false, speedMultiplier = targetSpeed)
                        }
                    } else {
                        for (s in 0 until steps) {
                            val render = (s == steps - 1)
                            core.stepFrame(renderPixels = render, speedMultiplier = targetSpeed)
                        }
                    }
                }

                val bmp = Bitmap.createBitmap(
                    core.ppu.frameBuffer,
                    GbaConstants.SCREEN_WIDTH,
                    GbaConstants.SCREEN_HEIGHT,
                    Bitmap.Config.ARGB_8888
                )
                _frameBitmap.value = bmp

                frameCounter += steps
                val now = System.currentTimeMillis()
                if (now - lastFpsAt >= 500L) {
                    val elapsedSec = (now - lastFpsAt) / 1000.0
                    val measuredFps = (frameCounter / elapsedSec).toInt()
                    val tel = synchronized(lock) { core.getTelemetry() }
                    _uiState.update { it.copy(fps = measuredFps, telemetry = tel) }
                    frameCounter = 0
                    lastFpsAt = now
                }

                if (now - lastPlayFlushAt >= 5000L) {
                    val deltaSec = (now - lastPlayFlushAt) / 1000L
                    lastPlayFlushAt = now
                    sessionPlaySeconds += deltaSec
                    romRepo.addPlayTime(rom.id, deltaSec)
                    if (core.mmu.sramDirty) {
                        val sramCopy = synchronized(lock) {
                            core.mmu.sramDirty = false
                            core.mmu.sram.copyOf()
                        }
                        saveRepo.saveBatterySram(rom.baseName, sramCopy)
                    }
                }

                // Target ~16.67ms frame pacing (for 1.5x -> ~11ms, for 2x..8x run max steps per vsync ~16ms)
                val targetFrameNs = if (steps == 1 && targetSpeed > 1.2f) 11_111_000L else 16_666_000L
                val elapsedNs = System.nanoTime() - frameStartNs
                val sleepMs = (targetFrameNs - elapsedNs) / 1_000_000L
                if (sleepMs > 1L) delay(sleepMs)
            }
        }
    }

    fun updateKeyMask(mask: Int) {
        core.setKeys(mask)
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun setPaused(paused: Boolean) {
        _uiState.update { it.copy(isPaused = paused) }
    }

    fun toggleTurbo() {
        _uiState.update { state ->
            val nextTurbo = !state.isTurboActive
            state.copy(
                isTurboActive = nextTurbo,
                currentSpeedMultiplier = if (nextTurbo) state.preferences.fastForwardSpeed else 1f
            )
        }
    }

    fun setTurboHeld(held: Boolean) {
        _uiState.update { state ->
            state.copy(
                isTurboActive = held,
                currentSpeedMultiplier = if (held) state.preferences.fastForwardSpeed else 1f
            )
        }
    }

    fun cycleFastForwardSpeed() {
        val steps = listOf(2f, 4f, 6f, 8f)
        val cur = _uiState.value.preferences.fastForwardSpeed
        val nextIdx = (steps.indexOfFirst { kotlin.math.abs(it - cur) < 0.25f } + 1) % steps.size
        val nextSpeed = steps[nextIdx]
        viewModelScope.launch {
            prefsRepo.setFastForwardSpeed(nextSpeed)
            _uiState.update { state ->
                state.copy(
                    isTurboActive = true,
                    currentSpeedMultiplier = nextSpeed,
                    toastMessage = "Fast-Forward speed set to ${nextSpeed.toInt()}x"
                )
            }
        }
    }

    fun resetConsole() {
        viewModelScope.launch {
            val rom = _uiState.value.rom ?: return@launch
            synchronized(lock) {
                core.cpu.resetToRomEntry()
            }
            _uiState.update { it.copy(isPaused = false, toastMessage = "Console reset (${rom.title})") }
        }
    }

    // === SAVE STATES (.ss1, .ss2, ..., .ss9, .ss0) ===

    fun saveToSlot(slot: Int) {
        val rom = _uiState.value.rom ?: return
        val meta = _uiState.value.metadata ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                synchronized(lock) {
                    val totalPlay = rom.totalPlaySeconds + sessionPlaySeconds
                    kotlinx.coroutines.runBlocking {
                        saveRepo.saveToSlot(rom.baseName, slot, meta, totalPlay, core)
                    }
                }
            }
            result.onSuccess { info ->
                romRepo.recordSaveCreated(rom.id, slot)
                val updatedRom = romRepo.getRomById(rom.id)
                val slots = saveRepo.inspectAllSlots(rom.baseName, rom.crc32Hex)
                _uiState.update {
                    it.copy(
                        rom = updatedRom ?: it.rom,
                        slots = slots,
                        toastMessage = "Saved ${info.fileName} (${formatBytes(info.fileSizeBytes)})"
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Save failed.") }
            }
        }
    }

    fun loadFromSlot(slot: Int) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                synchronized(lock) {
                    kotlinx.coroutines.runBlocking {
                        saveRepo.loadFromSlot(rom.baseName, slot, rom.crc32Hex, core)
                    }
                }
            }
            result.onSuccess { info ->
                val bmp = Bitmap.createBitmap(
                    core.ppu.frameBuffer,
                    GbaConstants.SCREEN_WIDTH,
                    GbaConstants.SCREEN_HEIGHT,
                    Bitmap.Config.ARGB_8888
                )
                _frameBitmap.value = bmp
                _uiState.update {
                    it.copy(
                        isPaused = false,
                        telemetry = core.getTelemetry(),
                        toastMessage = "Loaded ${info.fileName} (PC ${info.pcHex})"
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Load failed.") }
            }
        }
    }

    fun deleteSlot(slot: Int) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            saveRepo.deleteSlot(rom.baseName, slot)
            val slots = saveRepo.inspectAllSlots(rom.baseName, rom.crc32Hex)
            _uiState.update { it.copy(slots = slots, toastMessage = "Deleted SS$slot (${rom.baseName}.ss$slot)") }
        }
    }

    fun exportSlotToUri(slot: Int, uri: Uri) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = saveRepo.readRawStateBytes(rom.baseName, slot)
            if (bytes == null) {
                _uiState.update { it.copy(toastMessage = "Slot SS$slot is empty.") }
                return@launch
            }
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Cannot open destination.")
            }.onSuccess {
                _uiState.update { it.copy(toastMessage = "Exported ${rom.baseName}.ss$slot") }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Export failed.") }
            }
        }
    }

    fun importSlotFromUri(slot: Int, uri: Uri) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read save state file.")
            }.getOrElse { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Import failed.") }
                return@launch
            }
            val res = saveRepo.importRawStateBytes(rom.baseName, slot, bytes, rom.crc32Hex)
            res.onSuccess { info ->
                val slots = saveRepo.inspectAllSlots(rom.baseName, rom.crc32Hex)
                _uiState.update {
                    it.copy(slots = slots, toastMessage = "Imported ${info.fileName} into SS$slot")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Invalid save state file.") }
            }
        }
    }

    // === CHEATS & .CHT PACKAGES ===

    fun addOrEditCheat(existingId: Long = 0L, name: String, code: String, format: CheatFormat) {
        val rom = _uiState.value.rom ?: return
        if (name.isBlank() || code.isBlank()) return
        viewModelScope.launch {
            romRepo.addOrUpdateCheat(
                CheatEntity(
                    id = existingId,
                    romId = rom.id,
                    name = name.trim(),
                    code = code.trim(),
                    formatName = format.name,
                    enabled = true,
                    sourceFormat = "Manual"
                )
            )
            _uiState.update { it.copy(toastMessage = "Saved cheat: ${name.trim()}") }
        }
    }

    fun toggleCheat(cheat: CheatEntity) {
        viewModelScope.launch {
            romRepo.setCheatEnabled(cheat, !cheat.enabled)
        }
    }

    fun setAllCheatsEnabled(enabled: Boolean) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            romRepo.setAllCheatsEnabled(rom.id, enabled)
        }
    }

    fun deleteCheat(cheatId: Long) {
        viewModelScope.launch {
            romRepo.deleteCheat(cheatId)
        }
    }

    fun importCheatFileFromUri(uri: Uri) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rawBytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read cheat file.")

                val isZip = rawBytes.size >= 4 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()
                if (isZip) {
                    val candidates = ChtFileParser.scanZipCheatPack(rawBytes, rom.title, rom.gameCode)
                    if (candidates.isEmpty()) {
                        error("No .cht / .cheats files found inside archive.")
                    } else if (candidates.size == 1) {
                        val parsed = ChtFileParser.parse(candidates.first().contentText)
                        romRepo.importParsedCheats(rom.id, parsed)
                        _uiState.update { it.copy(toastMessage = "Imported ${parsed.size} cheats from ${candidates.first().entryName}") }
                    } else {
                        _uiState.update { it.copy(zipCandidates = candidates) }
                    }
                } else {
                    val text = rawBytes.toString(Charsets.UTF_8)
                    val parsed = ChtFileParser.parse(text, rom.title)
                    require(parsed.isNotEmpty()) { "No valid cheat codes found in file." }
                    romRepo.importParsedCheats(rom.id, parsed)
                    _uiState.update { it.copy(toastMessage = "Imported ${parsed.size} cheats (${parsed.first().sourceFormat})") }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Cheat import failed.") }
            }
        }
    }

    fun importZipCandidate(candidate: ZipCheatCandidate) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            val parsed = ChtFileParser.parse(candidate.contentText)
            romRepo.importParsedCheats(rom.id, parsed)
            _uiState.update {
                it.copy(
                    zipCandidates = emptyList(),
                    toastMessage = "Imported ${parsed.size} cheats from ${candidate.entryName.substringAfterLast('/')}"
                )
            }
        }
    }

    fun dismissZipCandidates() {
        _uiState.update { it.copy(zipCandidates = emptyList()) }
    }

    fun exportCheatsToUri(uri: Uri) {
        val cheats = _uiState.value.cheats
        viewModelScope.launch(Dispatchers.IO) {
            if (cheats.isEmpty()) {
                _uiState.update { it.copy(toastMessage = "No cheats to export.") }
                return@launch
            }
            val parsed = cheats.map {
                ParsedChtEntry(
                    name = it.name,
                    code = it.code,
                    format = runCatching { CheatFormat.valueOf(it.formatName) }.getOrDefault(CheatFormat.AUTO),
                    enabled = it.enabled,
                    sourceFormat = it.sourceFormat
                )
            }
            val text = ChtFileParser.exportToLibretroCht(parsed)
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot write .cht file.")
            }.onSuccess {
                _uiState.update { it.copy(toastMessage = "Exported ${cheats.size} cheats (.cht)") }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "Export failed.") }
            }
        }
    }

    // === SHADERS & SETTINGS ===

    fun setShader(preset: ShaderPreset) = viewModelScope.launch { prefsRepo.setShader(preset) }
    fun setFastForwardSpeed(speed: Float) = viewModelScope.launch { prefsRepo.setFastForwardSpeed(speed) }
    fun setSpeedToggleMode(mode: SpeedToggleMode) = viewModelScope.launch { prefsRepo.setSpeedToggleMode(mode) }
    fun setOrientationMode(mode: OrientationMode) = viewModelScope.launch { prefsRepo.setOrientationMode(mode) }
    fun setAudioEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAudioEnabled(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHapticsEnabled(enabled) }
    fun setControlOpacity(opacity: Float) = viewModelScope.launch { prefsRepo.setControlOpacity(opacity) }
    fun setControlScale(scale: Float) = viewModelScope.launch { prefsRepo.setControlScale(scale) }
    fun setMasterCheatsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setMasterCheatsEnabled(enabled) }
    fun setLcdGridIntensity(intensity: Float) = viewModelScope.launch { prefsRepo.setLcdGridIntensity(intensity) }

    fun importBios(uri: Uri) {
        viewModelScope.launch {
            val res = romRepo.importBiosFromUri(uri)
            res.onSuccess { path ->
                prefsRepo.setCustomBiosPath(path)
                _uiState.update { it.copy(toastMessage = "Imported 16KB GBA BIOS (takes effect on reset)") }
            }.onFailure { err ->
                _uiState.update { it.copy(toastMessage = err.message ?: "BIOS import failed.") }
            }
        }
    }

    fun clearCustomBios() {
        viewModelScope.launch {
            _uiState.value.preferences.customBiosPath?.let { File(it).delete() }
            prefsRepo.setCustomBiosPath(null)
            _uiState.update { it.copy(toastMessage = "Reverted to built-in HLE BIOS") }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun flushAndPause() {
        val rom = _uiState.value.rom ?: return
        _uiState.update { it.copy(isPaused = true) }
        viewModelScope.launch {
            if (core.mmu.sramDirty) {
                val copy = synchronized(lock) {
                    core.mmu.sramDirty = false
                    core.mmu.sram.copyOf()
                }
                saveRepo.saveBatterySram(rom.baseName, copy)
            }
        }
    }

    override fun onCleared() {
        emuJob?.cancel()
        cheatsObserveJob?.cancel()
        core.apu.release()
        super.onCleared()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
