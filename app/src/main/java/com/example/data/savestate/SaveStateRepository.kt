package com.example.data.savestate

import android.content.Context
import android.graphics.Bitmap
import com.example.emulator.CoreTelemetry
import com.example.emulator.GbaConstants
import com.example.emulator.GbaCore
import com.example.emulator.GbaRomMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class SaveStateRepository(private val context: Context) {

    companion object {
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
        private const val FORMAT_VERSION = 1
    }

    private val savesDir: File by lazy {
        File(context.filesDir, "saves").apply { mkdirs() }
    }

    fun sanitizeRomBaseName(rawName: String): String {
        return rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "rom" }
    }

    fun getStateFile(romBaseName: String, slot: Int): File {
        val safeBase = sanitizeRomBaseName(romBaseName)
        return File(savesDir, "$safeBase.ss$slot")
    }

    fun getBatterySaveFile(romBaseName: String): File {
        val safeBase = sanitizeRomBaseName(romBaseName)
        return File(savesDir, "$safeBase.sav")
    }

    suspend fun loadBatterySram(romBaseName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = getBatterySaveFile(romBaseName)
        if (file.exists()) file.readBytes() else null
    }

    suspend fun saveBatterySram(romBaseName: String, sramBytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = getBatterySaveFile(romBaseName)
        FileOutputStream(file).use { it.write(sramBytes) }
    }

    suspend fun saveToSlot(
        romBaseName: String,
        slot: Int,
        meta: GbaRomMetadata,
        playTimeSeconds: Long,
        core: GbaCore
    ): Result<SaveStateSlotInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val telemetry: CoreTelemetry = core.getTelemetry()
            val payload = core.captureCompressedState()

            val bmp = Bitmap.createBitmap(
                core.ppu.frameBuffer,
                GbaConstants.SCREEN_WIDTH,
                GbaConstants.SCREEN_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            val pngStream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
            bmp.recycle()
            val pngBytes = pngStream.toByteArray()

            val now = System.currentTimeMillis()
            val file = getStateFile(romBaseName, slot)
            DataOutputStream(FileOutputStream(file).buffered()).use { dos ->
                dos.write(MAGIC)
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(slot)
                dos.writeUTF(meta.title)
                dos.writeUTF(meta.gameCode)
                dos.writeUTF(meta.crc32Hex)
                dos.writeLong(now)
                dos.writeLong(playTimeSeconds)
                dos.writeUTF(telemetry.pcHex)
                dos.writeBoolean(telemetry.isThumb)
                dos.writeUTF(telemetry.cpuMode)
                dos.writeInt(telemetry.videoMode)
                dos.writeLong(telemetry.totalCycles)
                dos.writeUTF(telemetry.saveType.displayName)
                dos.writeInt(pngBytes.size)
                dos.write(pngBytes)
                dos.writeInt(payload.size)
                dos.write(payload)
            }

            SaveStateSlotInfo(
                slot = slot,
                fileName = file.name,
                exists = true,
                fileSizeBytes = file.length(),
                compressedStateBytes = payload.size,
                savedAtMillis = now,
                playTimeSeconds = playTimeSeconds,
                romTitle = meta.title,
                gameCode = meta.gameCode,
                romCrc32Hex = meta.crc32Hex,
                crcMatches = true,
                pcHex = telemetry.pcHex,
                isThumb = telemetry.isThumb,
                cpuMode = telemetry.cpuMode,
                videoMode = telemetry.videoMode,
                totalCycles = telemetry.totalCycles,
                saveTypeName = telemetry.saveType.displayName,
                thumbnailPng = pngBytes
            )
        }
    }

    suspend fun loadFromSlot(
        romBaseName: String,
        slot: Int,
        expectedCrc32Hex: String,
        core: GbaCore
    ): Result<SaveStateSlotInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val file = getStateFile(romBaseName, slot)
            require(file.exists()) { "Slot SS$slot (${file.name}) is empty." }

            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                val magic = ByteArray(8)
                dis.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Invalid .ss$slot file header." }
                val version = dis.readInt()
                require(version == FORMAT_VERSION) { "Unsupported save state version $version." }
                val fileSlot = dis.readInt()
                val title = dis.readUTF()
                val gameCode = dis.readUTF()
                val crcHex = dis.readUTF()
                val savedAt = dis.readLong()
                val playTime = dis.readLong()
                val pcHex = dis.readUTF()
                val isThumb = dis.readBoolean()
                val cpuMode = dis.readUTF()
                val videoMode = dis.readInt()
                val cycles = dis.readLong()
                val saveTypeName = dis.readUTF()
                val pngLen = dis.readInt()
                val pngBytes = ByteArray(pngLen)
                dis.readFully(pngBytes)
                val payloadLen = dis.readInt()
                val payload = ByteArray(payloadLen)
                dis.readFully(payload)

                val ok = core.restoreCompressedState(payload)
                require(ok) { "Failed to restore hardware snapshot from ${file.name}." }

                SaveStateSlotInfo(
                    slot = fileSlot,
                    fileName = file.name,
                    exists = true,
                    fileSizeBytes = file.length(),
                    compressedStateBytes = payloadLen,
                    savedAtMillis = savedAt,
                    playTimeSeconds = playTime,
                    romTitle = title,
                    gameCode = gameCode,
                    romCrc32Hex = crcHex,
                    crcMatches = crcHex.equals(expectedCrc32Hex, ignoreCase = true),
                    pcHex = pcHex,
                    isThumb = isThumb,
                    cpuMode = cpuMode,
                    videoMode = videoMode,
                    totalCycles = cycles,
                    saveTypeName = saveTypeName,
                    thumbnailPng = pngBytes
                )
            }
        }
    }

    suspend fun inspectSlot(
        romBaseName: String,
        slot: Int,
        expectedCrc32Hex: String
    ): SaveStateSlotInfo = withContext(Dispatchers.IO) {
        val file = getStateFile(romBaseName, slot)
        if (!file.exists()) {
            return@withContext SaveStateSlotInfo(slot = slot, fileName = file.name, exists = false)
        }
        try {
            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                val magic = ByteArray(8)
                dis.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    return@withContext SaveStateSlotInfo(slot = slot, fileName = file.name, exists = false)
                }
                dis.readInt() // version
                dis.readInt() // slot
                val title = dis.readUTF()
                val gameCode = dis.readUTF()
                val crcHex = dis.readUTF()
                val savedAt = dis.readLong()
                val playTime = dis.readLong()
                val pcHex = dis.readUTF()
                val isThumb = dis.readBoolean()
                val cpuMode = dis.readUTF()
                val videoMode = dis.readInt()
                val cycles = dis.readLong()
                val saveTypeName = dis.readUTF()
                val pngLen = dis.readInt().coerceIn(0, 512 * 1024)
                val pngBytes = ByteArray(pngLen)
                dis.readFully(pngBytes)
                val payloadLen = dis.readInt()

                SaveStateSlotInfo(
                    slot = slot,
                    fileName = file.name,
                    exists = true,
                    fileSizeBytes = file.length(),
                    compressedStateBytes = payloadLen,
                    savedAtMillis = savedAt,
                    playTimeSeconds = playTime,
                    romTitle = title,
                    gameCode = gameCode,
                    romCrc32Hex = crcHex,
                    crcMatches = crcHex.equals(expectedCrc32Hex, ignoreCase = true),
                    pcHex = pcHex,
                    isThumb = isThumb,
                    cpuMode = cpuMode,
                    videoMode = videoMode,
                    totalCycles = cycles,
                    saveTypeName = saveTypeName,
                    thumbnailPng = pngBytes
                )
            }
        } catch (_: Throwable) {
            SaveStateSlotInfo(slot = slot, fileName = file.name, exists = false)
        }
    }

    suspend fun inspectAllSlots(romBaseName: String, expectedCrc32Hex: String): List<SaveStateSlotInfo> {
        val order = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
        return order.map { slot -> inspectSlot(romBaseName, slot, expectedCrc32Hex) }
    }

    suspend fun countExistingSlots(romBaseName: String): Int = withContext(Dispatchers.IO) {
        (0..9).count { getStateFile(romBaseName, it).exists() }
    }

    suspend fun deleteSlot(romBaseName: String, slot: Int): Boolean = withContext(Dispatchers.IO) {
        val file = getStateFile(romBaseName, slot)
        if (file.exists()) file.delete() else false
    }

    suspend fun readRawStateBytes(romBaseName: String, slot: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = getStateFile(romBaseName, slot)
        if (file.exists()) file.readBytes() else null
    }

    suspend fun importRawStateBytes(
        romBaseName: String,
        slot: Int,
        bytes: ByteArray,
        expectedCrc32Hex: String
    ): Result<SaveStateSlotInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.size > 32 && bytes.copyOfRange(0, 8).contentEquals(MAGIC)) {
                "Selected file is not a valid PocketAdvance .ss$slot save state."
            }
            val file = getStateFile(romBaseName, slot)
            file.writeBytes(bytes)
            inspectSlot(romBaseName, slot, expectedCrc32Hex)
        }
    }
}
