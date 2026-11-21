package com.example.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CheatFileImportOutcome
import com.example.data.PreferencesRepository
import com.example.data.RomRepository
import com.example.data.db.AppDatabase
import com.example.data.db.CheatEntity
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.data.savestate.SaveStateRepository
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ChtFileParser
import com.example.emulator.cheats.ZipCheatCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RomLibraryCard(
    val rom: RomEntity,
    val saveSlotCount: Int,
    val hasSs0: Boolean,
    val hasSs1: Boolean,
    val hasSs2: Boolean,
    val totalCheats: Int,
    val enabledCheats: Int
)

data class LibraryUiState(
    val roms: List<RomLibraryCard> = emptyList(),
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val shaderProfiles: List<ShaderProfileEntity> = emptyList(),
    val activeShaderProfileId: Long = 0L,
    val masterCheatsEnabled: Boolean = true,
    val managingCheatsForRom: RomEntity? = null,
    val managedRomCheats: List<CheatEntity> = emptyList(),
    val zipCandidates: List<ZipCheatCandidate> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).emulatorDao()
    private val romRepo = RomRepository(application, dao)
    private val saveRepo = SaveStateRepository(application)
    private val prefsRepo = PreferencesRepository(application)

    private val searchQuery = MutableStateFlow("")
    private val isImporting = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val slotRefreshTick = MutableStateFlow(0)
    private val managingCheatsRom = MutableStateFlow<RomEntity?>(null)
    private val zipCandidates = MutableStateFlow<List<ZipCheatCandidate>>(emptyList())

    init {
        viewModelScope.launch {
            val profiles = romRepo.ensureBuiltInShaderProfiles()
            // Default active profile to LCD3x Grid if unset
            if (profiles.isNotEmpty()) {
                val lcd3x = profiles.firstOrNull { it.baseMode == "LCD3X" } ?: profiles.first()
                prefsRepo.preferences.collect { prefs ->
                    if (prefs.activeShaderProfileId == 0L) {
                        prefsRepo.setActiveShaderProfileId(lcd3x.id)
                    }
                    return@collect
                }
            }
        }
    }

    private val managedRomCheatsFlow = managingCheatsRom.flatMapLatest { rom ->
        if (rom == null) flowOf(emptyList()) else romRepo.observeCheatsForRom(rom.id)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        romRepo.observeAllRoms(),
        romRepo.observeAllRomCheatStats(),
        searchQuery,
        isImporting,
        statusMessage,
        slotRefreshTick,
        romRepo.observeShaderProfiles(),
        prefsRepo.preferences,
        managingCheatsRom,
        managedRomCheatsFlow,
        zipCandidates
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val roms = values[0] as List<RomEntity>
        @Suppress("UNCHECKED_CAST")
        val cheatStats = values[1] as List<com.example.data.db.RomCheatStats>
        val query = values[2] as String
        val importing = values[3] as Boolean
        val status = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val shaderProfiles = values[6] as List<ShaderProfileEntity>
        val prefs = values[7] as com.example.data.EmulatorPreferences
        val cheatRom = values[8] as RomEntity?
        @Suppress("UNCHECKED_CAST")
        val managedCheats = values[9] as List<CheatEntity>
        @Suppress("UNCHECKED_CAST")
        val zips = values[10] as List<ZipCheatCandidate>

        val statsMap = cheatStats.associateBy { it.romId }
        val filtered = if (query.isBlank()) roms else {
            val q = query.trim().lowercase()
            roms.filter {
                it.title.lowercase().contains(q) ||
                    it.gameCode.lowercase().contains(q) ||
                    it.crc32Hex.lowercase().contains(q)
            }
        }
        val cards = filtered.map { rom ->
            val count = saveRepo.countExistingSlots(rom.baseName)
            val has0 = saveRepo.getStateFile(rom.baseName, 0).exists()
            val has1 = saveRepo.getStateFile(rom.baseName, 1).exists()
            val has2 = saveRepo.getStateFile(rom.baseName, 2).exists()
            val st = statsMap[rom.id]
            RomLibraryCard(
                rom = rom,
                saveSlotCount = count,
                hasSs0 = has0,
                hasSs1 = has1,
                hasSs2 = has2,
                totalCheats = st?.totalCount ?: 0,
                enabledCheats = st?.enabledCount ?: 0
            )
        }
        LibraryUiState(
            roms = cards,
            searchQuery = query,
            isImporting = importing,
            statusMessage = status,
            shaderProfiles = shaderProfiles,
            activeShaderProfileId = prefs.activeShaderProfileId,
            masterCheatsEnabled = prefs.masterCheatsEnabled,
            managingCheatsForRom = cheatRom,
            managedRomCheats = managedCheats,
            zipCandidates = zips
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun refreshSlotBadges() {
        slotRefreshTick.value++
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun clearStatus() {
        statusMessage.value = null
    }

    fun importRom(uri: Uri, onImported: (Long) -> Unit) {
        viewModelScope.launch {
            isImporting.value = true
            val res = romRepo.importRomFromUri(uri)
            isImporting.value = false
            res.onSuccess { rom ->
                statusMessage.value = "Loaded ${rom.title} [${rom.gameCode}]"
                onImported(rom.id)
            }.onFailure { err ->
                statusMessage.value = err.message ?: "Failed to import ROM."
            }
        }
    }

    fun launchBuiltInDemo(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            isImporting.value = true
            val rom = romRepo.ensureBuiltInDemoRom()
            isImporting.value = false
            onReady(rom.id)
        }
    }

    fun deleteRom(rom: RomEntity) {
        viewModelScope.launch {
            romRepo.deleteRom(rom)
            statusMessage.value = "Removed ${rom.title} from shelf"
        }
    }

    // === Library Shaders Manager ===

    fun selectGlobalShaderProfile(profile: ShaderProfileEntity) {
        viewModelScope.launch {
            prefsRepo.setActiveShaderProfileId(profile.id)
            statusMessage.value = "Active shader: ${profile.name}"
        }
    }

    fun saveShaderProfile(profile: ShaderProfileEntity) {
        viewModelScope.launch {
            val savedId = romRepo.saveShaderProfile(profile)
            prefsRepo.setActiveShaderProfileId(savedId)
            statusMessage.value = "Saved shader profile: ${profile.name}"
        }
    }

    fun deleteCustomShaderProfile(profileId: Long) {
        viewModelScope.launch {
            romRepo.deleteCustomShaderProfile(profileId)
            val all = romRepo.ensureBuiltInShaderProfiles()
            if (uiState.value.activeShaderProfileId == profileId && all.isNotEmpty()) {
                prefsRepo.setActiveShaderProfileId(all.first().id)
            }
            statusMessage.value = "Deleted custom shader profile"
        }
    }

    fun importShaderFile(uri: Uri) {
        viewModelScope.launch {
            romRepo.importShaderFromUri(uri)
                .onSuccess { saved ->
                    prefsRepo.setActiveShaderProfileId(saved.id)
                    statusMessage.value = "Imported shader: ${saved.name}"
                }
                .onFailure { err ->
                    statusMessage.value = err.message ?: "Failed to import shader file."
                }
        }
    }

    fun exportShaderProfile(profile: ShaderProfileEntity, uri: Uri) {
        viewModelScope.launch {
            romRepo.exportShaderToUri(profile, uri)
                .onSuccess { statusMessage.value = "Exported shader profile: ${profile.name}" }
                .onFailure { err -> statusMessage.value = err.message ?: "Shader export failed." }
        }
    }

    // === Library Cheat Code Manager ===

    fun openCheatManager(rom: RomEntity) {
        managingCheatsRom.value = rom
    }

    fun closeCheatManager() {
        managingCheatsRom.value = null
        zipCandidates.value = emptyList()
    }

    fun setMasterCheatsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setMasterCheatsEnabled(enabled) }
    }

    fun toggleCheat(cheat: CheatEntity) {
        viewModelScope.launch { romRepo.setCheatEnabled(cheat, !cheat.enabled) }
    }

    fun toggleCheatGroup(groupName: String, enabled: Boolean) {
        val rom = managingCheatsRom.value ?: return
        viewModelScope.launch { romRepo.setGroupCheatsEnabled(rom.id, groupName, enabled) }
    }

    fun setAllCheatsEnabled(enabled: Boolean) {
        val rom = managingCheatsRom.value ?: return
        viewModelScope.launch { romRepo.setAllCheatsEnabled(rom.id, enabled) }
    }

    fun saveCheat(id: Long, name: String, code: String, format: CheatFormat, groupName: String) {
        val rom = managingCheatsRom.value ?: return
        viewModelScope.launch {
            romRepo.addOrUpdateCheat(
                CheatEntity(
                    id = id,
                    romId = rom.id,
                    name = name.trim(),
                    code = code.trim(),
                    formatName = format.name,
                    enabled = true,
                    sourceFormat = "Manual",
                    groupName = groupName.trim().ifBlank { "Manual" }
                )
            )
            statusMessage.value = "Saved cheat: ${name.trim()}"
        }
    }

    fun deleteCheat(cheatId: Long) {
        viewModelScope.launch { romRepo.deleteCheat(cheatId) }
    }

    fun importCheatFile(uri: Uri) {
        val rom = managingCheatsRom.value ?: return
        viewModelScope.launch {
            romRepo.importCheatFileFromUri(uri, rom)
                .onSuccess { outcome ->
                    when (outcome) {
                        is CheatFileImportOutcome.Imported -> {
                            val s = outcome.summary
                            val dupNote = if (s.skippedDuplicates > 0) " (${s.skippedDuplicates} duplicates skipped)" else ""
                            statusMessage.value = "Imported ${s.addedCount} cheats$dupNote"
                        }
                        is CheatFileImportOutcome.NeedsZipSelection -> {
                            zipCandidates.value = outcome.candidates
                        }
                    }
                }
                .onFailure { err ->
                    statusMessage.value = err.message ?: "Cheat import failed."
                }
        }
    }

    fun importZipCandidate(candidate: ZipCheatCandidate) {
        val rom = managingCheatsRom.value ?: return
        viewModelScope.launch {
            val group = candidate.entryName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "ZIP Pack" }
            val parsed = ChtFileParser.parse(candidate.contentText, rom.title, group)
            val summary = romRepo.importParsedCheats(rom.id, parsed, group)
            zipCandidates.value = emptyList()
            val dupNote = if (summary.skippedDuplicates > 0) " (${summary.skippedDuplicates} dup skipped)" else ""
            statusMessage.value = "Imported ${summary.addedCount} cheats from $group$dupNote"
        }
    }

    fun dismissZipCandidates() {
        zipCandidates.value = emptyList()
    }

    fun exportCheats(uri: Uri) {
        val cheats = uiState.value.managedRomCheats
        viewModelScope.launch {
            romRepo.exportCheatsToUri(cheats, uri)
                .onSuccess { count -> statusMessage.value = "Exported $count cheats (.cht)" }
                .onFailure { err -> statusMessage.value = err.message ?: "Export failed." }
        }
    }
}
