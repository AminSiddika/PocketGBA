package com.example

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
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
