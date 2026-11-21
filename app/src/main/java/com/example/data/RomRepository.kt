package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.db.CheatEntity
import com.example.data.db.EmulatorDao
import com.example.data.db.RomCheatStats
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.GbaRomHeaderParser
import com.example.emulator.HomebrewRomBuilder
import com.example.emulator.cheats.CheatDecoder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ChtFileParser
import com.example.emulator.cheats.ParsedChtEntry
import com.example.emulator.cheats.ZipCheatCandidate
import com.example.emulator.shaders.ShaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

data class CheatImportSummary(
    val addedCount: Int,
    val skippedDuplicates: Int,
    val sourceLabel: String
)

sealed class CheatFileImportOutcome {
    data class Imported(val summary: CheatImportSummary) : CheatFileImportOutcome()
    data class NeedsZipSelection(val candidates: List<ZipCheatCandidate>, val archiveName: String) : CheatFileImportOutcome()
}

class RomRepository(
    private val context: Context,
    private val dao: EmulatorDao
) {
    private val romsDir: File by lazy {
        File(context.filesDir, "roms").apply { mkdirs() }
    }

    private val biosFile: File by lazy {
        File(context.filesDir, "gba_bios.bin")
    }

    fun observeAllRoms(): Flow<List<RomEntity>> = dao.observeAllRoms()

    fun observeAllRomCheatStats(): Flow<List<RomCheatStats>> = dao.observeAllRomCheatStats()

    fun observeCheatsForRom(romId: Long): Flow<List<CheatEntity>> = dao.observeCheatsForRom(romId)

    fun observeShaderProfiles(): Flow<List<ShaderProfileEntity>> = dao.observeShaderProfiles()

    suspend fun getRomById(id: Long): RomEntity? = dao.getRomById(id)

    suspend fun getCheatsForRom(romId: Long): List<CheatEntity> = dao.getCheatsForRom(romId)

    suspend fun ensureBuiltInShaderProfiles(): List<ShaderProfileEntity> = withContext(Dispatchers.IO) {
        if (dao.countShaderProfiles() == 0) {
            dao.insertShaderProfiles(ShaderEngine.builtInProfiles())
        }
        dao.getAllShaderProfiles()
    }

    suspend fun getShaderProfileById(id: Long): ShaderProfileEntity? = dao.getShaderProfileById(id)

    suspend fun saveShaderProfile(profile: ShaderProfileEntity): Long = withContext(Dispatchers.IO) {
        dao.insertShaderProfile(profile)
    }

    suspend fun deleteCustomShaderProfile(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteCustomShaderProfile(id)
    }

    suspend fun importShaderFromUri(uri: Uri): Result<ShaderProfileEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "custom_shader.agsl"
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("Cannot read shader file.")

            val parsed = ShaderEngine.parseImportedShaderFile(fileName, text).getOrThrow()
            val id = dao.insertShaderProfile(parsed)
            parsed.copy(id = id)
        }
    }

    suspend fun exportShaderToUri(profile: ShaderProfileEntity, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = if (!profile.agslSource.isNullOrBlank() && profile.baseMode == "CUSTOM_AGSL") {
                ShaderEngine.exportProfileToJson(profile)
            } else {
                ShaderEngine.exportProfileToJson(profile)
            }
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open destination.")
        }
    }

    suspend fun importRomFromUri(uri: Uri): Result<RomEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "imported_game.gba"
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read selected file.")

            val (romBytes, resolvedName) = extractGbaFromBytes(rawBytes, fileName)
            require(romBytes.size >= 0xC0) { "File is too small to be a valid GBA ROM." }

            val meta = GbaRomHeaderParser.parse(romBytes, resolvedName)
            val existing = dao.getRomByCrc(meta.crc32Hex)
            if (existing != null && File(existing.filePath).exists()) {
                return@runCatching existing
            }

            val baseName = resolvedName.substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { meta.gameCode }
            val destFile = File(romsDir, "${baseName}_${meta.crc32Hex.take(6)}.gba")
            destFile.writeBytes(romBytes)

            val entity = RomEntity(
                title = meta.title,
                gameCode = meta.gameCode,
                makerCode = meta.makerCode,
                crc32Hex = meta.crc32Hex,
                baseName = "${baseName}_${meta.crc32Hex.take(6)}",
                filePath = destFile.absolutePath,
                sizeBytes = romBytes.size.toLong(),
                saveType = meta.saveType.name,
                isBuiltInDemo = false
            )
            val id = dao.insertRom(entity)
            entity.copy(id = id)
        }
    }

    suspend fun ensureBuiltInDemoRom(): RomEntity = withContext(Dispatchers.IO) {
        val romBytes = HomebrewRomBuilder.buildPixelQuestRom()
        val meta = GbaRomHeaderParser.parse(romBytes, "PixelQuestDemo.gba")
        val existing = dao.getRomByCrc(meta.crc32Hex)
        if (existing != null) {
            File(existing.filePath).writeBytes(romBytes)
            return@withContext existing
        }

        val destFile = File(romsDir, "pixel_quest_demo.gba")
        destFile.writeBytes(romBytes)
        val entity = RomEntity(
            title = meta.title,
            gameCode = meta.gameCode,
            makerCode = meta.makerCode,
            crc32Hex = meta.crc32Hex,
            baseName = "pixel_quest_demo",
            filePath = destFile.absolutePath,
            sizeBytes = romBytes.size.toLong(),
            saveType = meta.saveType.name,
            isBuiltInDemo = true
        )
        val id = dao.insertRom(entity)

        dao.insertCheat(
            CheatEntity(
                romId = id,
                name = "Score Boost (10 Pips) [CodeBreaker]",
                code = "83000110 000A",
                formatName = CheatFormat.CODEBREAKER.name,
                enabled = false,
                sourceFormat = "Demo Sample",
                groupName = "Demo Starter Pack"
            )
        )
        entity.copy(id = id)
    }

    suspend fun loadRomBytes(rom: RomEntity): ByteArray = withContext(Dispatchers.IO) {
        val f = File(rom.filePath)
        if (f.exists()) f.readBytes()
        else if (rom.isBuiltInDemo) HomebrewRomBuilder.buildPixelQuestRom()
        else error("ROM file missing from internal storage.")
    }

    suspend fun deleteRom(rom: RomEntity) = withContext(Dispatchers.IO) {
        File(rom.filePath).delete()
        dao.deleteCheatsForRom(rom.id)
        dao.deleteRomById(rom.id)
    }

    suspend fun addPlayTime(romId: Long, deltaSeconds: Long) {
        if (deltaSeconds > 0) dao.addPlayTime(romId, deltaSeconds, System.currentTimeMillis())
    }

    suspend fun markPlayed(romId: Long) {
        dao.markPlayed(romId, System.currentTimeMillis())
    }

    suspend fun recordSaveCreated(romId: Long, slot: Int) {
        dao.recordSaveCreated(romId, slot)
    }

    suspend fun updateRomOverrides(romId: Long, shaderProfileId: Long?, speedMultiplier: Float?) {
        dao.updateRomOverrides(romId, shaderProfileId, speedMultiplier)
    }

    suspend fun addOrUpdateCheat(cheat: CheatEntity): Long = dao.insertCheat(cheat)

    suspend fun importParsedCheats(
        romId: Long,
        entries: List<ParsedChtEntry>,
        fallbackGroupName: String? = null
    ): CheatImportSummary = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext CheatImportSummary(0, 0, "Empty")
        val existing = dao.getCheatsForRom(romId)
        val existingKeys = existing.map {
            "${it.name.trim().lowercase()}|${CheatDecoder.normalizeCodeKey(it.code)}"
        }.toHashSet()

        val toInsert = ArrayList<CheatEntity>()
        var skipped = 0
        for (entry in entries) {
            val key = "${entry.name.trim().lowercase()}|${CheatDecoder.normalizeCodeKey(entry.code)}"
            if (!existingKeys.add(key)) {
                skipped++
                continue
            }
            toInsert.add(
                CheatEntity(
                    romId = romId,
                    name = entry.name,
                    code = entry.code,
                    formatName = entry.format.name,
                    enabled = entry.enabled,
                    sourceFormat = entry.sourceFormat,
                    groupName = fallbackGroupName?.takeIf { it.isNotBlank() } ?: entry.groupName
                )
            )
        }
        if (toInsert.isNotEmpty()) {
            dao.insertCheats(toInsert)
        }
        CheatImportSummary(
            addedCount = toInsert.size,
            skippedDuplicates = skipped,
            sourceLabel = entries.first().sourceFormat
        )
    }

    suspend fun importCheatFileFromUri(uri: Uri, rom: RomEntity): Result<CheatFileImportOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = queryDisplayName(uri) ?: "imported_cheats.cht"
                val packGroup = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Imported Pack" }
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read cheat file.")

                val isZip = rawBytes.size >= 4 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()
                if (isZip) {
                    val candidates = ChtFileParser.scanZipCheatPack(rawBytes, rom.title, rom.gameCode)
                    if (candidates.isEmpty()) {
                        error("No .cht / .cheats files found inside archive.")
                    } else if (candidates.size == 1) {
                        val cand = candidates.first()
                        val candGroup = cand.entryName.substringAfterLast('/').substringBeforeLast('.').ifBlank { packGroup }
                        val parsed = ChtFileParser.parse(cand.contentText, rom.title, candGroup)
                        val summary = importParsedCheats(rom.id, parsed, candGroup)
                        CheatFileImportOutcome.Imported(summary)
                    } else {
                        CheatFileImportOutcome.NeedsZipSelection(candidates, fileName)
                    }
                } else {
                    val text = rawBytes.toString(Charsets.UTF_8)
                    val parsed = ChtFileParser.parse(text, rom.title, packGroup)
                    require(parsed.isNotEmpty()) { "No valid cheat codes found in file." }
                    val summary = importParsedCheats(rom.id, parsed, packGroup)
                    CheatFileImportOutcome.Imported(summary)
                }
            }
        }

    suspend fun exportCheatsToUri(cheats: List<CheatEntity>, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(cheats.isNotEmpty()) { "No cheats to export." }
            val parsed = cheats.map {
                ParsedChtEntry(
                    name = it.name,
                    code = it.code,
                    format = runCatching { CheatFormat.valueOf(it.formatName) }.getOrDefault(CheatFormat.AUTO),
                    enabled = it.enabled,
                    sourceFormat = it.sourceFormat,
                    groupName = it.groupName
                )
            }
            val text = ChtFileParser.exportToLibretroCht(parsed)
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot write .cht file.")
            cheats.size
        }
    }

    suspend fun setCheatEnabled(cheat: CheatEntity, enabled: Boolean) {
        dao.updateCheat(cheat.copy(enabled = enabled))
    }

    suspend fun setAllCheatsEnabled(romId: Long, enabled: Boolean) {
        dao.setAllCheatsEnabled(romId, enabled)
    }

    suspend fun setGroupCheatsEnabled(romId: Long, groupName: String, enabled: Boolean) {
        dao.setGroupCheatsEnabled(romId, groupName, enabled)
    }

    suspend fun deleteCheat(cheatId: Long) {
        dao.deleteCheatById(cheatId)
    }

    suspend fun importBiosFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot read BIOS file.")
            require(bytes.size == 0x4000) { "GBA BIOS must be exactly 16 KB (16,384 bytes)." }
            biosFile.writeBytes(bytes)
            biosFile.absolutePath
        }
    }

    suspend fun loadCustomBios(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext null
        val f = File(path)
        if (f.exists() && f.length() == 0x4000L) f.readBytes() else null
    }

    private fun extractGbaFromBytes(raw: ByteArray, fileName: String): Pair<ByteArray, String> {
        if (fileName.endsWith(".zip", ignoreCase = true) ||
            (raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte())
        ) {
            ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".gba", ignoreCase = true)) {
                        return zis.readBytes() to entry.name.substringAfterLast('/')
                    }
                    entry = zis.nextEntry
                }
            }
            error("ZIP archive does not contain a .gba ROM file.")
        }
        return raw to fileName
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }
}
