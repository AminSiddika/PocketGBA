package com.example.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.RomRepository
import com.example.data.db.AppDatabase
import com.example.data.db.RomEntity
import com.example.data.savestate.SaveStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RomLibraryCard(
    val rom: RomEntity,
    val saveSlotCount: Int,
    val hasSs1: Boolean,
    val hasSs2: Boolean
)

data class LibraryUiState(
    val roms: List<RomLibraryCard> = emptyList(),
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).emulatorDao()
    private val romRepo = RomRepository(application, dao)
    private val saveRepo = SaveStateRepository(application)

    private val searchQuery = MutableStateFlow("")
    private val isImporting = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val slotRefreshTick = MutableStateFlow(0)

    val uiState: StateFlow<LibraryUiState> = combine(
        romRepo.observeAllRoms(),
        searchQuery,
        isImporting,
        statusMessage,
        slotRefreshTick
    ) { roms, query, importing, status, _ ->
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
            val has1 = saveRepo.getStateFile(rom.baseName, 1).exists()
            val has2 = saveRepo.getStateFile(rom.baseName, 2).exists()
            RomLibraryCard(rom = rom, saveSlotCount = count, hasSs1 = has1, hasSs2 = has2)
        }
        LibraryUiState(
            roms = cards,
            searchQuery = query,
            isImporting = importing,
            statusMessage = status
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
}
