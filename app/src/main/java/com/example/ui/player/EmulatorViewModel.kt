package com.example.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CheatFileImportOutcome
import com.example.data.EmulatorPreferences
import com.example.data.PreferencesRepository
import com.example.data.RomRepository
import com.example.data.db.AppDatabase
import com.example.data.db.CheatEntity
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.data.savestate.SaveStateRepository
import com.example.data.savestate.SaveStateSlotInfo
import com.example.emulator.BiosMode
import com.example.emulator.BiosStatusInfo
import com.example.emulator.CoreTelemetry
import com.example.emulator.GbaConstants
import com.example.emulator.GbaCore
import com.example.emulator.GbaRomMetadata
import com.example.emulator.OrientationMode
import com.example.emulator.RumbleHapticEngine
import com.example.emulator.SaveType
import com.example.emulator.SpeedToggleMode
import com.example.emulator.cheats.CheatDecoder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ChtFileParser
import com.example.emulator.cheats.CompiledCheat
import com.example.emulator.cheats.ZipCheatCandidate
import com.example.emulator.shaders.ShaderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class EmulatorUiState(
    val rom: RomEntity? = null,
    val metadata: GbaRomMetadata? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isPaused: Boolean = false,
    val isTurboActive: Boolean = false,
    val isRumbleActive: Boolean = false,
    val currentSpeedMultiplier: Float = 1f,
    val speedStepBanner: String? = null,
    val fps: Int = 0,
    val telemetry: CoreTelemetry? = null,
    val slots: List<SaveStateSlotInfo> = emptyList(),
    val cheats: List<CheatEntity> = emptyList(),
    val zipCandidates: List<ZipCheatCandidate> = emptyList(),
    val shaderProfiles: List<ShaderProfileEntity> = emptyList(),
    val activeShaderProfile: ShaderProfileEntity = ShaderEngine.builtInProfiles()[1],
    val biosStatus: BiosStatusInfo = BiosStatusInfo(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val toastMessage: String? = null
)

class EmulatorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).emulatorDao()
    private val romRepo = RomRepository(application, dao)
    private val saveRepo = SaveStateRepository(application)
    private val prefsRepo = PreferencesRepository(application)

    private val core = GbaCore()
    private val rumbleEngine = RumbleHapticEngine(application)
    private val lock = Any()
    private val exitSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(EmulatorUiState())
    val uiState: StateFlow<EmulatorUiState> = _uiState.asStateFlow()

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    private val frameBitmapA = Bitmap.createBitmap(
        GbaConstants.SCREEN_WIDTH,
        GbaConstants.SCREEN_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private val frameBitmapB = Bitmap.createBitmap(
        GbaConstants.SCREEN_WIDTH,
        GbaConstants.SCREEN_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private var useBitmapA = true

    private fun publishFrameBuffer() {
        val target = if (useBitmapA) frameBitmapA else frameBitmapB
        useBitmapA = !useBitmapA
        target.setPixels(
            core.ppu.frameBuffer,
            0,
            GbaConstants.SCREEN_WIDTH,
            0,
            0,
            GbaConstants.SCREEN_WIDTH,
            GbaConstants.SCREEN_HEIGHT
        )
        _frameBitmap.value = target
    }

    private var compiledCheats: List<CompiledCheat> = emptyList()
    private var emuJob: Job? = null
    private var cheatsObserveJob: Job? = null
    private var bannerJob: Job? = null
    private var loadedRomId: Long = -1L
    private var sessionPlaySeconds: Long = 0L
    private var lastAutoSaveAtMs: Long = 0L

    private val speedLadder = listOf(1.5f, 2f, 3f, 4f, 6f, 8f)

    init {
        viewModelScope.launch {
            val seeded = romRepo.ensureBuiltInShaderProfiles()
            val defaultLcd = seeded.firstOrNull { it.baseMode == "LCD3X" } ?: seeded.firstOrNull()

            combine(
                prefsRepo.preferences,
                romRepo.observeShaderProfiles()
            ) { prefs, profiles -> prefs to profiles }.collectLatest { (prefs, profiles) ->
                if (prefs.activeShaderProfileId == 0L && defaultLcd != null) {
                    prefsRepo.setActiveShaderProfileId(defaultLcd.id)
                }

                val biosStatus = romRepo.resolveBios(prefs.biosMode, prefs.customBiosPath)
                if (biosStatus.storedFilePath != null && prefs.customBiosPath != biosStatus.storedFilePath) {
                    prefsRepo.setCustomBiosPath(biosStatus.storedFilePath)
                }

                _uiState.update { state ->
                    val targetSpeed = effectiveTargetSpeed(state.rom, prefs)
                    val mult = if (state.isTurboActive) targetSpeed else 1f
                    val resolvedProfile = resolveShaderProfile(state.rom, prefs, profiles)
                    syncApuOutput(prefs, mult)
                    if (!prefs.rumbleEnabled || state.isPaused) {
                        rumbleEngine.stop()
                    } else if (core.mmu.rumbleActive) {
                        rumbleEngine.setRumble(true, true, prefs.rumbleIntensity)
                    }
                    state.copy(
                        preferences = prefs,
                        shaderProfiles = profiles,
                        activeShaderProfile = resolvedProfile,
                        biosStatus = biosStatus,
                        currentSpeedMultiplier = mult
                    )
                }
            }
        }
    }

    private fun effectiveTargetSpeed(rom: RomEntity?, prefs: EmulatorPreferences): Float =
        (rom?.preferredSpeedMultiplier ?: prefs.fastForwardSpeed).coerceIn(1.5f, 8f)

    private fun resolveShaderProfile(
        rom: RomEntity?,
        prefs: EmulatorPreferences,
        profiles: List<ShaderProfileEntity>
    ): ShaderProfileEntity {
        val preferredId = rom?.preferredShaderProfileId ?: prefs.activeShaderProfileId
        return profiles.firstOrNull { it.id == preferredId }
            ?: profiles.firstOrNull { it.baseMode == "LCD3X" }
            ?: profiles.firstOrNull()
            ?: ShaderEngine.builtInProfiles()[1]
    }

    private fun syncApuOutput(prefs: EmulatorPreferences, speedMultiplier: Float) {
        val muteForTurbo = prefs.muteTurboAudioAbove2x && speedMultiplier > 2.0f
        core.apu.enabled = prefs.audioEnabled && !muteForTurbo
        core.apu.volume = prefs.audioVolume
    }

    fun loadRomSession(romId: Long, initialLoadSlot: Int = -1) {
        if (loadedRomId == romId && core.isLoaded) {
            if (initialLoadSlot in 0..9) {
                loadFromSlot(initialLoadSlot)
            }
            return
        }
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
                val prefs = _uiState.value.preferences
                val romBytes = romRepo.loadRomBytes(rom)
                val saveType = runCatching { SaveType.valueOf(rom.saveType) }.getOrDefault(SaveType.SRAM)
                val sramBytes = saveRepo.loadBatterySram(rom.baseName)
                val biosStatus = romRepo.resolveBios(prefs.biosMode, prefs.customBiosPath)
                if (biosStatus.storedFilePath != null && prefs.customBiosPath != biosStatus.storedFilePath) {
                    prefsRepo.setCustomBiosPath(biosStatus.storedFilePath)
                }

                val meta = GbaRomMetadata(
                    title = rom.displayTitle ?: rom.title,
                    gameCode = rom.gameCode,
                    makerCode = rom.makerCode,
                    crc32Hex = rom.crc32Hex,
                    sizeBytes = rom.sizeBytes,
                    saveType = saveType,
                    validHeader = true
                )

                synchronized(lock) {
                    core.mmu.onRumbleChanged = { active ->
                        val p = _uiState.value.preferences
                        rumbleEngine.setRumble(active, p.rumbleEnabled && !_uiState.value.isPaused, p.rumbleIntensity)
                        _uiState.update { it.copy(isRumbleActive = active) }
                    }
                    core.loadRom(
                        romBytes = romBytes,
                        saveType = saveType,
                        initialSram = sramBytes,
                        customBios = biosStatus.bytes,
                        skipBiosIntro = prefs.skipBiosIntro
                    )
                    core.onVBlankCheatHook = { mmu ->
                        if (_uiState.value.preferences.masterCheatsEnabled && compiledCheats.isNotEmpty()) {
                            CheatDecoder.executeAll(compiledCheats, mmu)
                        }
                    }
                }

                var resumeMessage: String? = null
                if (initialLoadSlot in 0..9) {
                    val slotRes = withContext(Dispatchers.Default) {
                        synchronized(lock) {
                            kotlinx.coroutines.runBlocking {
                                saveRepo.loadFromSlot(rom.baseName, initialLoadSlot, rom.crc32Hex, core)
                            }
                        }
                    }
                    slotRes.onSuccess { info ->
                        resumeMessage = "Resumed ${info.fileName} (PC ${info.pcHex})"
                    }.onFailure { err ->
                        resumeMessage = err.message
                    }
                }

                core.apu.startAudioTrack()
                romRepo.markPlayed(rom.id)

                val slots = saveRepo.inspectAllSlots(rom.baseName, rom.crc32Hex)
                synchronized(lock) {
                    publishFrameBuffer()
                }

                _uiState.update { state ->
                    val resolvedProfile = resolveShaderProfile(rom, state.preferences, state.shaderProfiles)
                    syncApuOutput(state.preferences, 1f)
                    state.copy(
                        rom = rom,
                        metadata = meta,
                        isLoading = false,
                        isPaused = false,
                        isRumbleActive = core.mmu.rumbleActive,
                        biosStatus = biosStatus,
                        slots = slots,
                        activeShaderProfile = resolvedProfile,
                        telemetry = core.getTelemetry(),
                        toastMessage = resumeMessage ?: state.toastMessage
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
            var frameAccumulator = 0f
            var nextDeadlineNs = System.nanoTime()
            val framePeriodNs = 16_742_706L // GBA 59.7275 Hz native cadence

            while (isActive) {
                val state = _uiState.value
                if (state.isPaused || !core.isLoaded) {
                    nextDeadlineNs = System.nanoTime()
                    frameAccumulator = 0f
                    delay(16L)
                    continue
                }

                val targetSpeed = state.currentSpeedMultiplier.coerceIn(1f, 8f)
                frameAccumulator = (frameAccumulator + targetSpeed).coerceAtMost(8f)
                val steps = frameAccumulator.toInt().coerceIn(1, 8)
                frameAccumulator -= steps

                val batchStartNs = System.nanoTime()
                var executedSteps = 0
                synchronized(lock) {
                    for (s in 0 until steps) {
                        val isFinal = (s == steps - 1) ||
                            (s > 0 && (System.nanoTime() - batchStartNs) > 18_000_000L)
                        core.stepFrame(renderPixels = isFinal, speedMultiplier = targetSpeed)
                        executedSteps++
                        if (isFinal) break
                    }
                    publishFrameBuffer()
                }

                frameCounter += executedSteps
                val now = System.currentTimeMillis()
                if (now - lastFpsAt >= 500L) {
                    val elapsedSec = (now - lastFpsAt) / 1000.0
                    val measuredFps = (frameCounter / elapsedSec).toInt()
                    val tel = synchronized(lock) { core.getTelemetry() }
                    _uiState.update {
                        it.copy(
                            fps = measuredFps,
                            telemetry = tel,
                            isRumbleActive = tel.rumbleActive
                        )
                    }
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

                nextDeadlineNs += framePeriodNs
                val afterWorkNs = System.nanoTime()
                val remainingNs = nextDeadlineNs - afterWorkNs
                if (remainingNs > 1_500_000L) {
                    delay(remainingNs / 1_000_000L)
                } else if (remainingNs < -framePeriodNs * 3L) {
                    nextDeadlineNs = afterWorkNs
                }
            }
        }
    }

    fun updateKeyMask(mask: Int) {
        core.setKeys(mask)
    }

    fun togglePause() {
        val next = !_uiState.value.isPaused
        setPaused(next)
    }

    fun setPaused(paused: Boolean) {
        core.apu.setPlaybackPaused(paused)
        if (paused) {
            rumbleEngine.stop()
        } else {
            val prefs = _uiState.value.preferences
            if (core.mmu.rumbleActive && prefs.rumbleEnabled) {
                rumbleEngine.setRumble(true, true, prefs.rumbleIntensity)
            }
        }
        _uiState.update { it.copy(isPaused = paused) }
    }

    fun toggleTurbo() {
        _uiState.update { state ->
            val nextTurbo = !state.isTurboActive
            val target = effectiveTargetSpeed(state.rom, state.preferences)
            val mult = if (nextTurbo) target else 1f
            syncApuOutput(state.preferences, mult)
            state.copy(
                isTurboActive = nextTurbo,
                currentSpeedMultiplier = mult
            )
        }
        showSpeedBanner(
            if (_uiState.value.isTurboActive) "TURBO ${formatSpeed(_uiState.value.currentSpeedMultiplier)}"
            else "NORMAL 1x"
        )
    }

    fun setTurboHeld(held: Boolean) {
        _uiState.update { state ->
            val target = effectiveTargetSpeed(state.rom, state.preferences)
            val mult = if (held) target else 1f
            syncApuOutput(state.preferences, mult)
            state.copy(
                isTurboActive = held,
                currentSpeedMultiplier = mult
            )
        }
    }

    fun stepFastForwardSpeed(delta: Int) {
        val state = _uiState.value
        val cur = effectiveTargetSpeed(state.rom, state.preferences)
        val curIdx = speedLadder.indices.minByOrNull { abs(speedLadder[it] - cur) } ?: 3
        val stepOffset = if (delta >= 0) 1 else -1
        val nextIdx = (curIdx + stepOffset + speedLadder.size) % speedLadder.size
        val nextSpeed = speedLadder[nextIdx]

        viewModelScope.launch {
            val rom = _uiState.value.rom
            if (rom?.preferredSpeedMultiplier != null) {
                romRepo.updateRomOverrides(rom.id, rom.preferredShaderProfileId, nextSpeed)
                val updatedRom = rom.copy(preferredSpeedMultiplier = nextSpeed)
                _uiState.update { s ->
                    syncApuOutput(s.preferences, nextSpeed)
                    s.copy(
                        rom = updatedRom,
                        isTurboActive = true,
                        currentSpeedMultiplier = nextSpeed
                    )
                }
            } else {
                prefsRepo.setFastForwardSpeed(nextSpeed)
                _uiState.update { s ->
                    syncApuOutput(s.preferences, nextSpeed)
                    s.copy(
                        isTurboActive = true,
                        currentSpeedMultiplier = nextSpeed
                    )
                }
            }
            showSpeedBanner("TURBO ${formatSpeed(nextSpeed)}")
        }
    }

    fun setRomSpeedOverride(speed: Float?) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            romRepo.updateRomOverrides(rom.id, rom.preferredShaderProfileId, speed)
            val updatedRom = rom.copy(preferredSpeedMultiplier = speed)
            _uiState.update { state ->
                val target = effectiveTargetSpeed(updatedRom, state.preferences)
                val mult = if (state.isTurboActive) target else 1f
                syncApuOutput(state.preferences, mult)
                state.copy(
                    rom = updatedRom,
                    currentSpeedMultiplier = mult,
                    toastMessage = if (speed != null) {
                        "Pinned ${formatSpeed(speed)} speed to ${rom.title}"
                    } else {
                        "Using global turbo speed for ${rom.title}"
                    }
                )
            }
        }
    }

    private fun showSpeedBanner(label: String) {
        bannerJob?.cancel()
        _uiState.update { it.copy(speedStepBanner = label) }
        bannerJob = viewModelScope.launch {
            delay(1200L)
            _uiState.update { it.copy(speedStepBanner = null) }
        }
    }

    fun resetConsole() {
        viewModelScope.launch {
            val rom = _uiState.value.rom ?: return@launch
            rumbleEngine.stop()
            synchronized(lock) {
                core.apu.reset()
                core.apu.flushTrack()
                core.cpu.resetToRomEntry(_uiState.value.preferences.skipBiosIntro)
            }
            _uiState.update {
                it.copy(
                    isPaused = false,
                    isRumbleActive = false,
                    toastMessage = "Console reset (${rom.displayTitle ?: rom.title})"
                )
            }
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
                synchronized(lock) {
                    core.apu.flushTrack()
                    core.apu.setPlaybackPaused(false)
                    publishFrameBuffer()
                }
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

    fun addOrEditCheat(
        existingId: Long = 0L,
        name: String,
        code: String,
        format: CheatFormat,
        groupName: String = "Manual"
    ) {
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
                    sourceFormat = "Manual",
                    groupName = groupName.trim().ifBlank { "Manual" }
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

    fun toggleCheatGroup(groupName: String, enabled: Boolean) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            romRepo.setGroupCheatsEnabled(rom.id, groupName, enabled)
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
        viewModelScope.launch {
            romRepo.importCheatFileFromUri(uri, rom)
                .onSuccess { outcome ->
                    when (outcome) {
                        is CheatFileImportOutcome.Imported -> {
                            val s = outcome.summary
                            val dupNote = if (s.skippedDuplicates > 0) " (${s.skippedDuplicates} duplicates skipped)" else ""
                            _uiState.update { it.copy(toastMessage = "Imported ${s.addedCount} cheats (${s.sourceLabel})$dupNote") }
                        }
                        is CheatFileImportOutcome.NeedsZipSelection -> {
                            _uiState.update { it.copy(zipCandidates = outcome.candidates) }
                        }
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(toastMessage = err.message ?: "Cheat import failed.") }
                }
        }
    }

    fun importZipCandidate(candidate: ZipCheatCandidate) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            val group = candidate.entryName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "ZIP Pack" }
            val parsed = ChtFileParser.parse(candidate.contentText, rom.title, group)
            val summary = romRepo.importParsedCheats(rom.id, parsed, group)
            val dupNote = if (summary.skippedDuplicates > 0) " (${summary.skippedDuplicates} dup skipped)" else ""
            _uiState.update {
                it.copy(
                    zipCandidates = emptyList(),
                    toastMessage = "Imported ${summary.addedCount} cheats from $group$dupNote"
                )
            }
        }
    }

    fun dismissZipCandidates() {
        _uiState.update { it.copy(zipCandidates = emptyList()) }
    }

    fun exportCheatsToUri(uri: Uri) {
        val cheats = _uiState.value.cheats
        viewModelScope.launch {
            romRepo.exportCheatsToUri(cheats, uri)
                .onSuccess { count ->
                    _uiState.update { it.copy(toastMessage = "Exported $count cheats (.cht)") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(toastMessage = err.message ?: "Export failed.") }
                }
        }
    }

    // === SHADERS STUDIO, RUMBLE, BIOS AUTO & SETTINGS ===

    fun selectGlobalShaderProfile(profile: ShaderProfileEntity) {
        viewModelScope.launch {
            prefsRepo.setActiveShaderProfileId(profile.id)
            _uiState.update { state ->
                val resolved = if (state.rom?.preferredShaderProfileId == null) profile else state.activeShaderProfile
                state.copy(
                    activeShaderProfile = resolved,
                    toastMessage = "Global shader: ${profile.name}"
                )
            }
        }
    }

    fun pinShaderToCurrentRom(shaderProfileId: Long?) {
        val rom = _uiState.value.rom ?: return
        viewModelScope.launch {
            romRepo.updateRomOverrides(rom.id, shaderProfileId, rom.preferredSpeedMultiplier)
            val updatedRom = rom.copy(preferredShaderProfileId = shaderProfileId)
            _uiState.update { state ->
                val resolved = resolveShaderProfile(updatedRom, state.preferences, state.shaderProfiles)
                state.copy(
                    rom = updatedRom,
                    activeShaderProfile = resolved,
                    toastMessage = if (shaderProfileId != null) {
                        "Pinned ${resolved.name} to ${rom.title}"
                    } else {
                        "Using global shader for ${rom.title}"
                    }
                )
            }
        }
    }

    fun saveShaderProfile(profile: ShaderProfileEntity) {
        viewModelScope.launch {
            val savedId = romRepo.saveShaderProfile(profile)
            prefsRepo.setActiveShaderProfileId(savedId)
            _uiState.update { it.copy(toastMessage = "Saved shader profile: ${profile.name}") }
        }
    }

    fun deleteCustomShaderProfile(profileId: Long) {
        viewModelScope.launch {
            val rom = _uiState.value.rom
            if (rom?.preferredShaderProfileId == profileId) {
                romRepo.updateRomOverrides(rom.id, null, rom.preferredSpeedMultiplier)
                _uiState.update { it.copy(rom = rom.copy(preferredShaderProfileId = null)) }
            }
            romRepo.deleteCustomShaderProfile(profileId)
            val all = romRepo.ensureBuiltInShaderProfiles()
            if (_uiState.value.preferences.activeShaderProfileId == profileId && all.isNotEmpty()) {
                prefsRepo.setActiveShaderProfileId(all.first().id)
            }
            _uiState.update { it.copy(toastMessage = "Deleted custom shader profile") }
        }
    }

    fun importShaderFile(uri: Uri) {
        viewModelScope.launch {
            romRepo.importShaderFromUri(uri)
                .onSuccess { saved ->
                    prefsRepo.setActiveShaderProfileId(saved.id)
                    _uiState.update { it.copy(toastMessage = "Imported shader: ${saved.name}") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(toastMessage = err.message ?: "Failed to import shader.") }
                }
        }
    }

    fun exportShaderProfile(profile: ShaderProfileEntity, uri: Uri) {
        viewModelScope.launch {
            romRepo.exportShaderToUri(profile, uri)
                .onSuccess { _uiState.update { it.copy(toastMessage = "Exported shader profile: ${profile.name}") } }
                .onFailure { err -> _uiState.update { it.copy(toastMessage = err.message ?: "Export failed.") } }
        }
    }

    fun setFastForwardSpeed(speed: Float) = viewModelScope.launch {
        prefsRepo.setFastForwardSpeed(speed)
    }

    fun setSpeedToggleMode(mode: SpeedToggleMode) = viewModelScope.launch {
        prefsRepo.setSpeedToggleMode(mode)
    }

    fun setOrientationMode(mode: OrientationMode) = viewModelScope.launch {
        prefsRepo.setOrientationMode(mode)
    }

    fun setAudioEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAudioEnabled(enabled)
    }

    fun setMuteTurboAudioAbove2x(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setMuteTurboAudioAbove2x(enabled)
    }

    fun setAutoSaveOnExit(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAutoSaveOnExit(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setHapticsEnabled(enabled)
    }

    fun setRumbleEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setRumbleEnabled(enabled)
        if (!enabled) rumbleEngine.stop()
    }

    fun setRumbleIntensity(intensity: Float) = viewModelScope.launch {
        prefsRepo.setRumbleIntensity(intensity)
    }

    fun testRumble() {
        rumbleEngine.pulseTest(_uiState.value.preferences.rumbleIntensity)
        _uiState.update {
            it.copy(
                toastMessage = if (rumbleEngine.hasVibrator) "Rumble motor pulse sent"
                else "Device has no hardware vibrator"
            )
        }
    }

    fun setControlOpacity(opacity: Float) = viewModelScope.launch {
        prefsRepo.setControlOpacity(opacity)
    }

    fun setControlScale(scale: Float) = viewModelScope.launch {
        prefsRepo.setControlScale(scale)
    }

    fun setMasterCheatsEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setMasterCheatsEnabled(enabled)
    }

    fun setBiosMode(mode: BiosMode) = viewModelScope.launch {
        prefsRepo.setBiosMode(mode)
        _uiState.update { it.copy(toastMessage = "BIOS mode: ${mode.label} (takes effect on reset)") }
    }

    fun setSkipBiosIntro(skip: Boolean) = viewModelScope.launch {
        prefsRepo.setSkipBiosIntro(skip)
    }

    fun autoDetectBios() {
        viewModelScope.launch {
            prefsRepo.setBiosMode(BiosMode.AUTO)
            val status = romRepo.resolveBios(BiosMode.AUTO, _uiState.value.preferences.customBiosPath)
            if (status.storedFilePath != null) {
                prefsRepo.setCustomBiosPath(status.storedFilePath)
            }
            _uiState.update {
                it.copy(
                    biosStatus = status,
                    toastMessage = if (status.hasCustomBios) "Auto-detected ${status.sourceLabel}"
                    else "No custom gba_bios.bin found — using HLE BIOS"
                )
            }
        }
    }

    fun scanBiosFolder(treeUri: Uri) {
        viewModelScope.launch {
            romRepo.scanFolderForBios(treeUri)
                .onSuccess { status ->
                    prefsRepo.setBiosMode(BiosMode.AUTO)
                    prefsRepo.setCustomBiosPath(status.storedFilePath)
                    _uiState.update {
                        it.copy(
                            biosStatus = status,
                            toastMessage = "Found & loaded ${status.sourceLabel} (takes effect on reset)"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(toastMessage = err.message ?: "BIOS folder scan failed.") }
                }
        }
    }

    fun importBios(uri: Uri) {
        viewModelScope.launch {
            romRepo.importBiosStatusFromUri(uri)
                .onSuccess { status ->
                    prefsRepo.setBiosMode(BiosMode.AUTO)
                    prefsRepo.setCustomBiosPath(status.storedFilePath)
                    _uiState.update {
                        it.copy(
                            biosStatus = status,
                            toastMessage = "Imported ${status.sourceLabel} (takes effect on reset)"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(toastMessage = err.message ?: "BIOS import failed.") }
                }
        }
    }

    fun clearCustomBios() {
        viewModelScope.launch {
            _uiState.value.preferences.customBiosPath?.let { File(it).delete() }
            prefsRepo.setCustomBiosPath(null)
            _uiState.update {
                it.copy(
                    biosStatus = BiosStatusInfo(resolvedMode = BiosMode.HLE, sourceLabel = "Auto: Built-in HLE BIOS"),
                    toastMessage = "Cleared custom BIOS — using built-in HLE BIOS"
                )
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun flushAndPause() {
        rumbleEngine.stop()
        core.apu.setPlaybackPaused(true)
        val state = _uiState.value
        val rom = state.rom ?: return
        val meta = state.metadata
        _uiState.update { it.copy(isPaused = true, isRumbleActive = false) }

        val now = System.currentTimeMillis()
        val shouldAutoSave = state.preferences.autoSaveOnExit &&
            core.isLoaded &&
            meta != null &&
            (now - lastAutoSaveAtMs > 500L)

        if (shouldAutoSave) {
            lastAutoSaveAtMs = now
        }

        exitSaveScope.launch {
            if (core.mmu.sramDirty) {
                val copy = synchronized(lock) {
                    core.mmu.sramDirty = false
                    core.mmu.sram.copyOf()
                }
                saveRepo.saveBatterySram(rom.baseName, copy)
            }
            if (shouldAutoSave && meta != null) {
                val totalPlay = rom.totalPlaySeconds + sessionPlaySeconds
                val saveRes = synchronized(lock) {
                    kotlinx.coroutines.runBlocking {
                        saveRepo.saveToSlot(rom.baseName, 0, meta, totalPlay, core)
                    }
                }
                saveRes.onSuccess {
                    romRepo.recordSaveCreated(rom.id, 0)
                }
            }
        }
    }

    override fun onCleared() {
        emuJob?.cancel()
        cheatsObserveJob?.cancel()
        bannerJob?.cancel()
        rumbleEngine.stop()
        core.apu.release()
        super.onCleared()
    }

    private fun formatSpeed(spd: Float): String =
        if (spd % 1f == 0f) "${spd.toInt()}x" else "${spd}x"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
