package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmulatorDao {
    @Query("SELECT * FROM roms ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeAllRoms(): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE id = :id")
    suspend fun getRomById(id: Long): RomEntity?

    @Query("SELECT * FROM roms WHERE crc32Hex = :crc LIMIT 1")
    suspend fun getRomByCrc(crc: String): RomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRom(rom: RomEntity): Long

    @Update
    suspend fun updateRom(rom: RomEntity)

    @Query("DELETE FROM roms WHERE id = :id")
    suspend fun deleteRomById(id: Long)

    @Query("UPDATE roms SET lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun markPlayed(id: Long, timestamp: Long)

    @Query("UPDATE roms SET totalPlaySeconds = totalPlaySeconds + :deltaSeconds, lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun addPlayTime(id: Long, deltaSeconds: Long, timestamp: Long)

    @Query("UPDATE roms SET totalSavesCreated = totalSavesCreated + 1, lastSlotUsed = :slot WHERE id = :id")
    suspend fun recordSaveCreated(id: Long, slot: Int)

    // Cheats
    @Query("SELECT * FROM cheats WHERE romId = :romId ORDER BY createdAt ASC")
    fun observeCheatsForRom(romId: Long): Flow<List<CheatEntity>>

    @Query("SELECT COUNT(*) FROM cheats WHERE romId = :romId AND enabled = 1")
    fun observeEnabledCheatCount(romId: Long): Flow<Int>

    @Query("SELECT * FROM cheats WHERE romId = :romId ORDER BY createdAt ASC")
    suspend fun getCheatsForRom(romId: Long): List<CheatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheat(cheat: CheatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheats(cheats: List<CheatEntity>)

    @Update
    suspend fun updateCheat(cheat: CheatEntity)

    @Query("DELETE FROM cheats WHERE id = :cheatId")
    suspend fun deleteCheatById(cheatId: Long)

    @Query("DELETE FROM cheats WHERE romId = :romId")
    suspend fun deleteCheatsForRom(romId: Long)

    @Query("UPDATE cheats SET enabled = :enabled WHERE romId = :romId")
    suspend fun setAllCheatsEnabled(romId: Long, enabled: Boolean)
}
