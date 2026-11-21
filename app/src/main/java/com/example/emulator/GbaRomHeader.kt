package com.example.emulator

import java.util.zip.CRC32

data class GbaRomMetadata(
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val crc32Hex: String,
    val sizeBytes: Long,
    val saveType: SaveType,
    val validHeader: Boolean
)

object GbaRomHeaderParser {

    fun parse(romBytes: ByteArray, fallbackName: String): GbaRomMetadata {
        val crc = CRC32().apply { update(romBytes) }.value
        val crcHex = String.format("%08X", crc)

        if (romBytes.size < 0xC0) {
            return GbaRomMetadata(
                title = fallbackName.substringBeforeLast('.').ifBlank { "GBA ROM" },
                gameCode = "UNKN",
                makerCode = "00",
                crc32Hex = crcHex,
                sizeBytes = romBytes.size.toLong(),
                saveType = SaveType.SRAM,
                validHeader = false
            )
        }

        val rawTitle = ByteArray(12) { idx -> romBytes[0xA0 + idx] }
        val title = rawTitle
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.US_ASCII)
            .trim()
            .ifBlank { fallbackName.substringBeforeLast('.').ifBlank { "GBA ROM" } }

        val gameCode = ByteArray(4) { idx -> romBytes[0xAC + idx] }
            .map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '?' }
            .joinToString("")

        val makerCode = ByteArray(2) { idx -> romBytes[0xB0 + idx] }
            .map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '0' }
            .joinToString("")

        val fixed96 = (romBytes[0xB2].toInt() and 0xFF) == 0x96
        var sum = 0
        for (i in 0xA0..0xBC) {
            sum += romBytes[i].toInt() and 0xFF
        }
        val expectedCheck = (-(sum + 0x19)) and 0xFF
        val actualCheck = romBytes[0xBD].toInt() and 0xFF

        val saveType = detectSaveType(romBytes)

        return GbaRomMetadata(
            title = title,
            gameCode = gameCode,
            makerCode = makerCode,
            crc32Hex = crcHex,
            sizeBytes = romBytes.size.toLong(),
            saveType = saveType,
            validHeader = fixed96 && expectedCheck == actualCheck
        )
    }

    private fun detectSaveType(romBytes: ByteArray): SaveType {
        // Scan 4-byte aligned strings for Nintendo SDK save ID tags
        val markers = listOf(
            "FLASH1M_V" to SaveType.FLASH128,
            "FLASH512_V" to SaveType.FLASH64,
            "FLASH_V" to SaveType.FLASH64,
            "EEPROM_V" to SaveType.EEPROM,
            "SRAM_V" to SaveType.SRAM,
            "SRAM_F_V" to SaveType.SRAM
        )
        val ascii = romBytes.toString(Charsets.ISO_8859_1)
        for ((tag, type) in markers) {
            if (ascii.contains(tag)) return type
        }
        return SaveType.SRAM
    }
}
