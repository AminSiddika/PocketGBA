package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.db.CheatEntity
import com.example.data.db.EmulatorDao
import com.example.data.db.RomEntity
import com.example.emulator.GbaRomHeaderParser
import com.example.emulator.HomebrewRomBuilder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ParsedChtEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

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

    fun observeCheatsForRom(romId: Long): Flow<List<CheatEntity>> = dao.observeCheatsForRom(romId)

    suspend fun getRomById(id: Long): RomEntity? = dao.getRomById(id)

    suspend fun getCheatsForRom(romId: Long): List<CheatEntity> = dao.getCheatsForRom(romId)

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

        // Pre-seed a helpful sample CodeBreaker cheat (disabled by default) so users can test cheats immediately
        dao.insertCheat(
            CheatEntity(
                romId = id,
                name = "Score Boost (10 Pips) [CodeBreaker]",
                code = "83000110 000A",
                formatName = CheatFormat.CODEBREAKER.name,
                enabled = false,
                sourceFormat = "Demo Sample"
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

    suspend fun addOrUpdateCheat(cheat: CheatEntity): Long = dao.insertCheat(cheat)

    suspend fun importParsedCheats(romId: Long, entries: List<ParsedChtEntry>) {
        if (entries.isEmpty()) return
        val entities = entries.map {
            CheatEntity(
                romId = romId,
                name = it.name,
                code = it.code,
                formatName = it.format.name,
                enabled = it.enabled,
                sourceFormat = it.sourceFormat
            )
        }
        dao.insertCheats(entities)
    }

    suspend fun setCheatEnabled(cheat: CheatEntity, enabled: Boolean) {
        dao.updateCheat(cheat.copy(enabled = enabled))
    }

    suspend fun setAllCheatsEnabled(romId: Long, enabled: Boolean) {
        dao.setAllCheatsEnabled(romId, enabled)
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
