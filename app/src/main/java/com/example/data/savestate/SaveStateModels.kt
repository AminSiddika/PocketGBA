package com.example.data.savestate

import com.example.emulator.SaveType

data class SaveStateSlotInfo(
    val slot: Int,
    val fileName: String,
    val exists: Boolean,
    val fileSizeBytes: Long = 0L,
    val compressedStateBytes: Int = 0,
    val savedAtMillis: Long = 0L,
    val playTimeSeconds: Long = 0L,
    val romTitle: String = "",
    val gameCode: String = "",
    val romCrc32Hex: String = "",
    val crcMatches: Boolean = true,
    val pcHex: String = "",
    val isThumb: Boolean = false,
    val cpuMode: String = "",
    val videoMode: Int = 0,
    val totalCycles: Long = 0L,
    val saveTypeName: String = SaveType.SRAM.displayName,
    val thumbnailPng: ByteArray? = null
) {
    val slotBadge: String get() = "SS$slot"
}
