package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "roms")
data class RomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val crc32Hex: String,
    val baseName: String,
    val filePath: String,
    val sizeBytes: Long,
    val saveType: String,
    val isBuiltInDemo: Boolean = false,
    val totalPlaySeconds: Long = 0L,
    val totalSavesCreated: Int = 0,
    val lastSlotUsed: Int = 1,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val preferredShaderProfileId: Long? = null,
    val preferredSpeedMultiplier: Float? = null
)

@Entity(tableName = "cheats")
data class CheatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romId: Long,
    val name: String,
    val code: String,
    val formatName: String,
    val enabled: Boolean = true,
    val sourceFormat: String = "Manual",
    val groupName: String = "Manual",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shader_profiles")
data class ShaderProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseMode: String,
    val gridIntensity: Float = 0.35f,
    val saturation: Float = 1.0f,
    val contrast: Float = 1.0f,
    val brightness: Float = 0.0f,
    val warmth: Float = 0.0f,
    val smoothFilter: Boolean = false,
    val agslSource: String? = null,
    val sourceFileName: String? = null,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
