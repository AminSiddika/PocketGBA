package com.example

import com.example.data.GbaMetadataScraper
import com.example.data.db.RomEntity
import com.example.emulator.GbaBiosManager
import com.example.emulator.GbaConstants
import com.example.emulator.GbaCore
import com.example.emulator.GbaRomHeaderParser
import com.example.emulator.HomebrewRomBuilder
import com.example.emulator.SaveType
import com.example.emulator.cheats.CheatDecoder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ChtFileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GbaCoreAndCheatsTest {

    @Test
    fun homebrewDemoBootsRendersMode3AndSnapshotsState() {
        val rom = HomebrewRomBuilder.buildPixelQuestRom()
        val meta = GbaRomHeaderParser.parse(rom, "pixel_quest.gba")
        assertTrue(meta.validHeader)
        assertEquals("PIXEL QUEST", meta.title)
        assertEquals("APQE", meta.gameCode)

        val core = GbaCore()
        core.loadRom(rom, SaveType.SRAM, null)
        // Step 10 frames with neutral input
        repeat(10) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        val tel = core.getTelemetry()
        assertEquals("Expected Mode 3, got ${tel.videoMode} at PC=${tel.pcHex} cycles=${tel.totalCycles}", 3, tel.videoMode)
        assertTrue(tel.totalCycles > 0L)

        val distinctColors = core.ppu.frameBuffer.toSet()
        assertTrue("Expected rendered Mode 3 pixels, got ${distinctColors.size}", distinctColors.size > 3)

        // Capture compressed full hardware state & mutate with input, then restore
        val snapshot = core.captureCompressedState()
        assertTrue(snapshot.isNotEmpty())
        val savedCycles = core.cpu.totalCycles

        core.setKeys(GbaConstants.KEY_RIGHT or GbaConstants.KEY_A)
        repeat(5) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        assertNotEquals(savedCycles, core.cpu.totalCycles)

        assertTrue(core.restoreCompressedState(snapshot))
        assertEquals(savedCycles, core.cpu.totalCycles)
    }

    @Test
    fun chtParserSupportsLibretroPizzaBoyMgbaAndCheatDecoderWritesWram() {
        val libretroCht = """
            cheats = 2
            cheat0_desc = "Max Coins"
            cheat0_code = "82001000 03E7"
            cheat0_enable = true
            cheat1_desc = "Infinite HP"
            cheat1_code = "32001010 0063"
            cheat1_enable = false
        """.trimIndent()

        val parsedLibretro = ChtFileParser.parse(libretroCht, defaultGroup = "FireRed Pack")
        assertEquals(2, parsedLibretro.size)
        assertEquals("Max Coins", parsedLibretro[0].name)
        assertEquals("FireRed Pack", parsedLibretro[0].groupName)
        assertTrue(parsedLibretro[0].enabled)
        assertEquals("Infinite HP", parsedLibretro[1].name)
        assertFalse(parsedLibretro[1].enabled)

        val mgbaCheats = """
            # Max Score
            82002000 270F
            # God Mode
            02002004 0001
        """.trimIndent()
        val parsedMgba = ChtFileParser.parse(mgbaCheats)
        assertEquals(2, parsedMgba.size)

        // Execute compiled cheat against GBA MMU EWRAM
        val core = GbaCore()
        core.loadRom(HomebrewRomBuilder.buildPixelQuestRom(), SaveType.SRAM, null)
        val compiled = listOf(
            CheatDecoder.compile(1L, "Max Coins", "82001000 03E7", CheatFormat.CODEBREAKER),
            CheatDecoder.compile(2L, "Raw 8bit", "32001010 0063", CheatFormat.RAW)
        )
        CheatDecoder.executeAll(compiled, core.mmu)
        assertEquals(0x03E7, core.mmu.read16(0x02001000) and 0xFFFF)
        assertEquals(0x63, core.mmu.read8(0x02001010) and 0xFF)
    }

    @Test
    fun cheatPreviewAndNormalizationDetectValidInvalidAndDuplicates() {
        val validPreview = CheatDecoder.inspectPreview("82025840 0044\n32025842 0001", CheatFormat.AUTO)
        assertTrue(validPreview.isValid)
        assertEquals(2, validPreview.lineCount)
        assertTrue(validPreview.summary.contains("0x02025840"))

        val invalidPreview = CheatDecoder.inspectPreview("ZZZZZZZZ 1234", CheatFormat.CODEBREAKER)
        assertFalse(invalidPreview.isValid)
        assertEquals(1, invalidPreview.invalidLines.size)

        assertEquals(
            CheatDecoder.normalizeCodeKey("82001000 03e7"),
            CheatDecoder.normalizeCodeKey("82001000:03E7\n")
        )
    }

    @Test
    fun gpioAndGameBoyPlayerRumbleTriggersAndRestoresSnapshot() {
        val core = GbaCore()
        core.loadRom(HomebrewRomBuilder.buildPixelQuestRom(), SaveType.SRAM, null)

        val rumbleTransitions = mutableListOf<Boolean>()
        core.mmu.onRumbleChanged = { rumbleTransitions.add(it) }

        // Enable Cartridge GPIO output direction & control, then activate rumble bit 3
        core.mmu.write16(0x080000C8, 0x0001)
        core.mmu.write16(0x080000C6, 0x0008)
        core.mmu.write16(0x080000C4, 0x0008)

        assertTrue(core.mmu.rumbleActive)
        assertTrue(core.getTelemetry().rumbleActive)
        assertEquals(1L, core.getTelemetry().rumbleEventsCount)
        assertEquals(listOf(true), rumbleTransitions)

        // Snapshot while active, turn off, then restore
        val snap = core.captureCompressedState()
        core.mmu.write16(0x080000C4, 0x0000)
        assertFalse(core.mmu.rumbleActive)

        assertTrue(core.restoreCompressedState(snap))
        assertTrue(core.mmu.rumbleActive)

        // Game Boy Player Serial rumble off/on packet (0x40000010 / 0x40000011)
        core.mmu.write32(0x04000120, 0x40000010)
        core.mmu.write16(0x04000128, 0x4080) // Start transfer + IRQ
        assertFalse(core.mmu.rumbleActive)
        assertTrue((core.mmu.readIo16Raw(0x202) and GbaConstants.IRQ_SERIAL) != 0)

        core.mmu.write32(0x04000120, 0x40000011)
        core.mmu.write16(0x04000128, 0x0080)
        assertTrue(core.mmu.rumbleActive)
    }

    @Test
    fun biosManagerValidatesVectorCrcAndZipExtraction() {
        val invalidSmall = ByteArray(1024)
        assertFalse(GbaBiosManager.isValidGbaBiosBytes(invalidSmall))
        assertNull(GbaBiosManager.inspectBytes(invalidSmall))

        val invalidOp = ByteArray(GbaBiosManager.BIOS_SIZE)
        assertFalse(GbaBiosManager.isValidGbaBiosBytes(invalidOp))

        val validBios = ByteArray(GbaBiosManager.BIOS_SIZE)
        // ARM unconditional branch 0xEA000018 (little endian)
        validBios[0] = 0x18
        validBios[1] = 0x00
        validBios[2] = 0x00
        validBios[3] = 0xEA.toByte()

        val status = GbaBiosManager.inspectBytes(validBios, "custom_bios.bin")
        assertNotNull(status)
        assertTrue(status!!.hasCustomBios)
        assertFalse(status.isOfficialNintendo)
        assertEquals(8, status.crc32Hex?.length)

        // Pack into a ZIP and verify extraction
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("readme.txt"))
                zos.write("not a bios".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("bios/gba_bios.bin"))
                zos.write(validBios)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val extracted = GbaBiosManager.extractBiosBytes(zipBytes, "bios_pack.zip")
        assertNotNull(extracted)
        assertEquals("gba_bios.bin", extracted!!.second)
        assertEquals(GbaBiosManager.BIOS_SIZE, extracted.first.size)
    }

    @Test
    fun metadataScraperExtractsPublisherRegionAndSensorsLocally() {
        val drillDozer = RomEntity(
            title = "DRILL DOZER",
            gameCode = "V49E",
            makerCode = "01",
            crc32Hex = "12345678",
            baseName = "drill_dozer",
            filePath = "/tmp/drill.gba",
            sizeBytes = 8 * 1024 * 1024L,
            saveType = "SRAM"
        )
        val drillMeta = GbaMetadataScraper.extractLocalMetadata(drillDozer, null)
        assertEquals("Drill Dozer", drillMeta.displayTitle)
        assertEquals("Nintendo", drillMeta.publisher)
        assertEquals("USA", drillMeta.region)
        assertTrue(drillMeta.hasRumbleSupport)
        assertTrue(drillMeta.hardwareFeatures.contains("Rumble"))

        val emerald = RomEntity(
            title = "POKEMON EMER",
            gameCode = "BPEP",
            makerCode = "01",
            crc32Hex = "87654321",
            baseName = "emerald",
            filePath = "/tmp/emerald.gba",
            sizeBytes = 16 * 1024 * 1024L,
            saveType = "FLASH128"
        )
        val emeraldMeta = GbaMetadataScraper.extractLocalMetadata(emerald, "SIIRTC_V001".toByteArray())
        assertEquals("Europe", emeraldMeta.region)
        assertTrue(emeraldMeta.hardwareFeatures.contains("RTC Clock"))
        assertFalse(emeraldMeta.hasRumbleSupport)
    }
}

