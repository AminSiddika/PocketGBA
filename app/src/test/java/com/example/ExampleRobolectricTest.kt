package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.RomEntity
import com.example.emulator.BiosMode
import com.example.emulator.GbaBiosManager
import com.example.emulator.shaders.ShaderEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PocketAdvance", appName)
    }

    @Test
    fun `shaderEngine parses json glslp and agsl files`() {
        val profile = ShaderEngine.builtInProfiles().first { it.baseMode == "LCD3X" }
        val exportedJson = ShaderEngine.exportProfileToJson(profile)
        val reimported = ShaderEngine.parseImportedShaderFile("my_lcd.json", exportedJson).getOrThrow()
        assertEquals("LCD3x Grid", reimported.name)
        assertEquals("LCD3X", reimported.baseMode)
        assertEquals(profile.gridIntensity, reimported.gridIntensity, 0.01f)

        val glslPreset = """
            shaders = "1"
            filter_linear0 = "true"
            scanline_intensity = 0.62
            saturation = 1.15
        """.trimIndent()
        val parsedGlsl = ShaderEngine.parseImportedShaderFile("crt_royale.glslp", glslPreset).getOrThrow()
        assertEquals("CRT_SCANLINES", parsedGlsl.baseMode)
        assertEquals(0.62f, parsedGlsl.gridIntensity, 0.01f)
        assertEquals(1.15f, parsedGlsl.saturation, 0.01f)
        assertTrue(parsedGlsl.smoothFilter)

        val agsl = """
            uniform shader contents;
            half4 main(float2 fragCoord) {
                return contents.eval(fragCoord);
            }
        """.trimIndent()
        val parsedAgsl = ShaderEngine.parseImportedShaderFile("vivid.agsl", agsl).getOrThrow()
        assertEquals("CUSTOM_AGSL", parsedAgsl.baseMode)
        assertTrue(parsedAgsl.agslSource?.contains("main(float2 fragCoord)") == true)
    }

    @Test
    fun `biosManager autoResolves from app storage and respects HLE override`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val biosSubDir = File(context.filesDir, "bios").apply { mkdirs() }
        val stagedBios = File(biosSubDir, "gba_bios.bin")
        val canonicalBios = File(context.filesDir, "gba_bios.bin")
        canonicalBios.delete()

        val biosBytes = ByteArray(GbaBiosManager.BIOS_SIZE)
        biosBytes[0] = 0x18
        biosBytes[1] = 0x00
        biosBytes[2] = 0x00
        biosBytes[3] = 0xEA.toByte()
        stagedBios.writeBytes(biosBytes)

        val autoResolved = GbaBiosManager.autoResolveBios(context, BiosMode.AUTO, null)
        assertTrue(autoResolved.hasCustomBios)
        assertEquals(BiosMode.CUSTOM, autoResolved.resolvedMode)
        assertTrue(canonicalBios.exists())

        val forcedHle = GbaBiosManager.autoResolveBios(context, BiosMode.HLE, canonicalBios.absolutePath)
        assertFalse(forcedHle.hasCustomBios)
        assertEquals(BiosMode.HLE, forcedHle.resolvedMode)
        assertNull(forcedHle.bytes)
    }

    @Test
    fun `room database persists and updates scraped rom metadata`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.emulatorDao()
            val insertedId = dao.insertRom(
                RomEntity(
                    title = "DRILL DOZER",
                    gameCode = "V49E",
                    makerCode = "01",
                    crc32Hex = "AABBCCDD",
                    baseName = "drill_dozer",
                    filePath = "/tmp/drill_dozer.gba",
                    sizeBytes = 8_388_608L,
                    saveType = "SRAM"
                )
            )
            dao.updateRomMetadata(
                romId = insertedId,
                displayTitle = "Drill Dozer",
                publisher = "Nintendo",
                region = "USA",
                releaseYear = "2006",
                genre = "Action / Platformer",
                hardwareFeatures = "Rumble",
                hasRumbleSupport = true,
                coverArtPath = "/tmp/covers/AABBCCDD.png",
                scrapedAt = 1_700_000_000_000L
            )

            val loaded = dao.getRomById(insertedId)
            assertNotNull(loaded)
            assertEquals("Drill Dozer", loaded!!.displayTitle)
            assertEquals("Nintendo", loaded.publisher)
            assertEquals("USA", loaded.region)
            assertEquals("2006", loaded.releaseYear)
            assertTrue(loaded.hasRumbleSupport)
            assertEquals("/tmp/covers/AABBCCDD.png", loaded.coverArtPath)
        } finally {
            db.close()
        }
    }
}

