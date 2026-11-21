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
        assertEquals(GbaConstants.KEY_ALL_RELEASED, core.mmu.keyInput)
        // Step 10 frames with neutral input
        repeat(10) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        val tel = core.getTelemetry()
        assertEquals("Expected Mode 3, got ${tel.videoMode} at PC=${tel.pcHex} cycles=${tel.totalCycles}", 3, tel.videoMode)
        assertTrue(tel.totalCycles > 0L)
        assertEquals(GbaConstants.KEY_ALL_RELEASED, core.mmu.keyInput)

        val distinctColors = core.ppu.frameBuffer.toSet()
        assertTrue("Expected rendered Mode 3 pixels, got ${distinctColors.size}", distinctColors.size > 3)

        // Capture compressed full hardware state & mutate with input, then restore
        val snapshot = core.captureCompressedState()
        assertTrue(snapshot.isNotEmpty())
        val savedCycles = core.cpu.totalCycles

        core.setKeys(GbaConstants.KEY_RIGHT or GbaConstants.KEY_A)
        assertEquals(
            GbaConstants.KEY_ALL_RELEASED and (GbaConstants.KEY_RIGHT or GbaConstants.KEY_A).inv(),
            core.mmu.keyInput
        )
        repeat(5) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        assertNotEquals(savedCycles, core.cpu.totalCycles)

        assertTrue(core.restoreCompressedState(snapshot))
        assertEquals(savedCycles, core.cpu.totalCycles)
    }

    @Test
    fun commercialPatternArmAndThumbRomBootsRunsContinuousFramesAndHandlesInput() {
        val rom = buildCommercialPatternArmThumbRom()
        val meta = GbaRomHeaderParser.parse(rom, "commercial_pattern.gba")
        assertTrue(meta.validHeader)
        assertEquals(SaveType.EEPROM, meta.saveType)

        val core = GbaCore()
        core.loadRom(rom, meta.saveType, null)

        // 15 frames with neutral input (must NOT hit A+B+START+SELECT trap or SWI 0x00 reset)
        repeat(15) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }

        assertEquals(0xBAAE187F.toInt(), core.mmu.read32(0x02000000))
        assertEquals(15, core.mmu.read32(0x02000010))
        assertEquals(0, core.getTelemetry().videoMode)
        val distinctMode0Colors = core.ppu.frameBuffer.toSet()
        assertTrue("Expected Mode 0 tiles + sprites, got ${distinctMode0Colors.size}", distinctMode0Colors.size >= 3)
        assertFalse(
            "Screen should not be forced blank",
            distinctMode0Colors.size == 1 && distinctMode0Colors.first() == 0xFFF8F8F8.toInt()
        )

        // Press A button -> game records A input, increments frames smoothly without resetting
        core.setKeys(GbaConstants.KEY_A)
        repeat(3) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        assertEquals(18, core.mmu.read32(0x02000010))
        assertEquals(1, core.mmu.read32(0x02000014))

        // Release A -> stays alive and keeps rendering
        core.setKeys(0)
        repeat(2) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        assertEquals(20, core.mmu.read32(0x02000010))
        assertEquals(0, core.mmu.read32(0x02000014))
    }

    @Test
    fun ldmWithCaretRestoresSpsrThumbModeAndPreservesSysSp() {
        val rom = ByteArray(0x400)
        fun put32(addr: Int, v: Int) {
            rom[addr] = (v and 0xFF).toByte()
            rom[addr + 1] = ((v ushr 8) and 0xFF).toByte()
            rom[addr + 2] = ((v ushr 16) and 0xFF).toByte()
            rom[addr + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun put16(addr: Int, v: Int) {
            rom[addr] = (v and 0xFF).toByte()
            rom[addr + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        // Entry at 0x08000000: B 0x080000C0
        put32(0x00, 0xEA00002E.toInt())
        // 0x080000C0: Switch to Thumb at 0x08000101
        put32(0xC0, 0xE59F0004.toInt()) // LDR R0, [PC, #4] -> 0x080000CC
        put32(0xC4, 0xE12FFF10.toInt()) // BX R0
        put32(0xC8, 0xE1A00000.toInt()) // NOP
        put32(0xCC, 0x08000101)

        // Thumb code at 0x08000100: MOV R0, #0x42; B .
        put16(0x100, 0x2042) // MOV R0, #0x42
        put16(0x102, 0x2199) // MOV R1, #0x99
        put16(0x104, 0xE7FE) // B . (self)

        // Test IRQ stack return via LDMFD SP!, {R0, R12, PC}^ at 0x08000200
        put32(0x200, 0xE8FD9001.toInt()) // LDMFD SP!, {R0, R12, PC}^ (writeback + S=1 + PC)

        val core = GbaCore()
        core.loadRom(rom, SaveType.SRAM, null)

        // Execute B -> LDR R0 -> BX Thumb -> MOV R0, #0x42 -> MOV R1, #0x99
        repeat(5) { core.cpu.step() }
        assertTrue(core.cpu.isThumb)
        assertEquals(0x42, core.cpu.r[0])
        assertEquals(0x99, core.cpu.r[1])
        val sysSpBefore = core.cpu.r[13]

        // Simulate entering IRQ mode with SPSR having FLAG_T set, pushing {R0, R12, PC} onto IRQ stack
        val returnThumbPc = 0x08000102
        val savedSysCpsr = core.cpu.cpsr
        core.cpu.switchMode(com.example.emulator.Arm7TdmiCpu.MODE_IRQ)
        core.cpu.setSpsr(savedSysCpsr)
        core.cpu.cpsr = core.cpu.cpsr and com.example.emulator.Arm7TdmiCpu.FLAG_T.inv()
        val irqSp = core.cpu.r[13]
        core.mmu.write32(irqSp - 12, 0x11111111)
        core.mmu.write32(irqSp - 8, 0x22222222)
        core.mmu.write32(irqSp - 4, returnThumbPc)
        core.cpu.r[13] = irqSp - 12
        core.cpu.r[15] = 0x08000200

        // Execute LDMFD SP!, {R0, R12, PC}^
        core.cpu.step()
        assertTrue("CPSR T flag must be restored from SPSR", core.cpu.isThumb)
        assertEquals(returnThumbPc, core.cpu.r[15])
        assertEquals("SYS SP must remain intact after LDM^ mode restore", sysSpBefore, core.cpu.r[13])
        assertEquals(0x11111111, core.cpu.r[0])
        assertEquals(0x22222222, core.cpu.r[12])
    }

    @Test
    fun eepromDma3ReadWriteAndKeypadIrqWorkEndToEnd() {
        val core = GbaCore()
        core.loadRom(ByteArray(0x400), SaveType.EEPROM, null)

        // Idle EEPROM must return ready bit 1 (never 0!)
        assertEquals(1, core.mmu.read16(0x0D000000) and 1)

        // Build 73-bit 512B EEPROM write bitstream for block 3 in EWRAM (0x02001000)
        // Prefix: 1, 0 (write), 6-bit address (3 = 000011), 64-bit payload, trailing 0
        val payload = 0x1122334455667788L
        val writeBits = IntArray(73)
        writeBits[0] = 1
        writeBits[1] = 0
        for (i in 0 until 6) {
            writeBits[2 + i] = (3 ushr (5 - i)) and 1
        }
        for (b in 0 until 64) {
            writeBits[8 + b] = ((payload ushr (63 - b)) and 1L).toInt()
        }
        writeBits[72] = 0
        for (i in writeBits.indices) {
            core.mmu.write16(0x02001000 + i * 2, writeBits[i])
        }

        // Trigger DMA3 immediate 16-bit write (count=73) to 0x0D000000
        core.mmu.write32(0x040000D4, 0x02001000) // DMA3SAD
        core.mmu.write32(0x040000D8, 0x0D000000) // DMA3DAD
        core.mmu.write16(0x040000DC, 73)         // DMA3CNT_L
        core.mmu.write16(0x040000DE, 0x8000)     // Enable immediate 16-bit DMA3
        assertTrue(core.mmu.sramDirty)

        // Build 9-bit 512B EEPROM read request for block 3
        val readReq = intArrayOf(1, 1, 0, 0, 0, 0, 1, 1, 0)
        for (i in readReq.indices) {
            core.mmu.write16(0x02002000 + i * 2, readReq[i])
        }
        core.mmu.write32(0x040000D4, 0x02002000)
        core.mmu.write32(0x040000D8, 0x0D000000)
        core.mmu.write16(0x040000DC, 9)
        core.mmu.write16(0x040000DE, 0x8000)

        // Read 68 halfwords from 0x0D000000 into EWRAM 0x02003000 via DMA3
        core.mmu.write32(0x040000D4, 0x0D000000)
        core.mmu.write32(0x040000D8, 0x02003000)
        core.mmu.write16(0x040000DC, 68)
        core.mmu.write16(0x040000DE, 0x8000)

        var readBack = 0L
        for (b in 0 until 64) {
            val bit = core.mmu.read16(0x02003000 + (4 + b) * 2) and 1
            readBack = (readBack shl 1) or bit.toLong()
        }
        assertEquals(payload, readBack)
        assertEquals(1, core.mmu.read16(0x0D000000) and 1)

        // Verify KEYCNT keypad IRQ fires when configured key is pressed
        core.mmu.write16(0x04000132, 0x4000 or GbaConstants.KEY_A)
        core.setKeys(GbaConstants.KEY_A)
        assertTrue((core.mmu.readIo16Raw(0x202) and GbaConstants.IRQ_KEYPAD) != 0)
    }

    /**
     * Builds a real ARM+Thumb GBA test ROM modeling commercial GBA initialization & loop:
     * - Nintendo header + valid checksum + "EEPROM_V122" tag
     * - ARM startup setting SYS/IRQ SP, IRQ vector, Thumb BX trampoline
     * - Commercial-style ARM `SWI 0x05` (0xEF050000) VBlankIntrWait wrapper
     * - Thumb main loop calling SWI 0x0D (GetBiosChecksum), SWI 0x10 (BitUnPack),
     *   checking KEYINPUT (0x04000130) for A+B+START+SELECT SoftReset trap, and rendering Mode 0 BG0 + OAM.
     */
    private fun buildCommercialPatternArmThumbRom(): ByteArray {
        val rom = ByteArray(0x2000)
        fun put32(off: Int, w: Int) {
            rom[off] = (w and 0xFF).toByte()
            rom[off + 1] = ((w ushr 8) and 0xFF).toByte()
            rom[off + 2] = ((w ushr 16) and 0xFF).toByte()
            rom[off + 3] = ((w ushr 24) and 0xFF).toByte()
        }
        fun put16(off: Int, hw: Int) {
            rom[off] = (hw and 0xFF).toByte()
            rom[off + 1] = ((hw ushr 8) and 0xFF).toByte()
        }

        // 0x00: Entry branch to 0x080000C0
        put32(0x00, 0xEA00002E.toInt())
        val title = "COMM TEST   ".toByteArray(Charsets.US_ASCII)
        System.arraycopy(title, 0, rom, 0xA0, 12)
        System.arraycopy("ACTE".toByteArray(Charsets.US_ASCII), 0, rom, 0xAC, 4)
        rom[0xB0] = '0'.code.toByte()
        rom[0xB1] = '1'.code.toByte()
        rom[0xB2] = 0x96.toByte()
        var sum = 0
        for (i in 0xA0..0xBC) sum += rom[i].toInt() and 0xFF
        rom[0xBD] = ((-(sum + 0x19)) and 0xFF).toByte()

        // ARM startup at 0x0C0:
        // Install IRQ handler (0x08000140) at 0x03007FFC, then BX to Thumb main (0x08000201)
        put32(0xC0, 0xE59F0010.toInt()) // LDR R0, [PC, #16] -> 0x0D8 (0x08000140)
        put32(0xC4, 0xE59F1010.toInt()) // LDR R1, [PC, #16] -> 0x0DC (0x03007FFC)
        put32(0xC8, 0xE5810000.toInt()) // STR R0, [R1]
        put32(0xCC, 0xE59F000C.toInt()) // LDR R0, [PC, #12] -> 0x0E0 (0x08000201)
        put32(0xD0, 0xE12FFF10.toInt()) // BX R0
        put32(0xD4, 0xEAFFFFFE.toInt()) // B .
        put32(0xD8, 0x08000140)
        put32(0xDC, 0x03007FFC)
        put32(0xE0, 0x08000201)

        // ARM IRQ handler at 0x140: ack IF = 1, set 0x03007FF8 |= 1, BX LR
        put32(0x140, 0xE59F0018.toInt()) // LDR R0, =0x04000202
        put32(0x144, 0xE3A01001.toInt()) // MOV R1, #1
        put32(0x148, 0xE1C010B0.toInt()) // STRH R1, [R0]
        put32(0x14C, 0xE59F0010.toInt()) // LDR R0, =0x03007FF8
        put32(0x150, 0xE1D020B0.toInt()) // LDRH R2, [R0]
        put32(0x154, 0xE3822001.toInt()) // ORR R2, R2, #1
        put32(0x158, 0xE1C020B0.toInt()) // STRH R2, [R0]
        put32(0x15C, 0xE12FFF1E.toInt()) // BX LR
        put32(0x160, 0x04000202)
        put32(0x164, 0x03007FF8)

        // ARM VBlankIntrWait helper at 0x180 using commercial high-byte SWI encoding (0xEF050000)!
        put32(0x180, 0xEF050000.toInt()) // SWI 0x05 (commercial encoding!)
        put32(0x184, 0xE12FFF1E.toInt()) // BX LR

        // Static data at 0x400: 1bpp 8x8 tile (checkerboard) + BitUnPack struct at 0x410
        for (row in 0 until 8) {
            rom[0x400 + row] = if ((row and 1) == 0) 0xAA.toByte() else 0x55.toByte()
        }
        put16(0x410, 8)     // srcLen = 8 bytes
        rom[0x412] = 1      // srcBpp = 1
        rom[0x413] = 4      // dstBpp = 4
        put32(0x414, 1)     // dataOffset = +1 (0 stays 0, 1 becomes 2)

        // Embed EEPROM tag at end
        val tag = "EEPROM_V122\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(tag, 0, rom, rom.size - tag.size, tag.size)

        // Literal pool for Thumb main at 0x300..0x33F
        put32(0x300, 0x02000000) // [pool0] EWRAM vars base
        put32(0x304, 0x08000400) // [pool1] 1bpp tile src
        put32(0x308, 0x06000020) // [pool2] BG0 char tile #1 dest
        put32(0x30C, 0x08000410) // [pool3] BitUnPack info
        put32(0x310, 0x04000000) // [pool4] IO base
        put32(0x314, 0x05000000) // [pool5] Palette base
        put32(0x318, 0x06000800) // [pool6] BG0 map base (screenblock 1)
        put32(0x31C, 0x08000180) // [pool7] ARM VBlankIntrWait helper
        put32(0x320, 0x04000130) // [pool8] KEYINPUT
        put32(0x324, 0x07000000) // [pool9] OAM base
        put32(0x328, 0x06010020) // [pool10] OBJ tile #1 dest

        // Thumb main at 0x200 (all 16-bit Thumb opcodes!)
        var t = 0x200
        fun emitT(op: Int) { put16(t, op); t += 2 }
        fun ldrLitT(rd: Int, poolAddr: Int) {
            val pcAligned = ((t + 4) and 0xFFFFFFFC.toInt())
            val imm8 = ((poolAddr - pcAligned) ushr 2) and 0xFF
            emitT(0x4800 or (rd shl 8) or imm8)
        }

        // 1. SWI 0x0D (GetBiosChecksum) -> store to [0x02000000]
        emitT(0xDF0D)            // SWI 0x0D
        ldrLitT(1, 0x300)        // LDR R1, =0x02000000
        emitT(0x6008)            // STR R0, [R1, #0]
        emitT(0x2000)            // MOV R0, #0
        emitT(0x6108)            // STR R0, [R1, #0x10] (frameCounter = 0)

        // 2. BitUnPack tile #1 into BG VRAM and OBJ VRAM
        ldrLitT(0, 0x304)
        ldrLitT(1, 0x308)
        ldrLitT(2, 0x30C)
        emitT(0xDF10)            // SWI 0x10 BitUnPack
        ldrLitT(0, 0x304)
        ldrLitT(1, 0x328)
        ldrLitT(2, 0x30C)
        emitT(0xDF10)            // SWI 0x10 BitUnPack to OBJ

        // 3. Set palette backdrop + color 2 + OBJ color 2
        ldrLitT(4, 0x314)        // R4 = 0x05000000
        emitT(0x201F)            // MOV R0, #0x1F (red)
        emitT(0x8020)            // STRH R0, [R4, #0]
        emitT(0x20E0)            // MOV R0, #0xE0
        emitT(0x00C0)            // LSL R0, R0, #3 (0x0700 green-ish)
        emitT(0x80A0)            // STRH R0, [R4, #4] (color idx 2)
        emitT(0x2102)            // MOV R1, #2
        emitT(0x0209)            // LSL R1, R1, #8 (0x200 OBJ palette offset)
        emitT(0x1861)            // ADD R1, R4, R1
        emitT(0x20FF)            // MOV R0, #0xFF
        emitT(0x8088)            // STRH R0, [R1, #4]

        // 4. Fill top-left BG0 map tile with tile #1 and enable one 8x8 OAM sprite at (16, 16) tile #1
        ldrLitT(0, 0x318)        // 0x06000800
        emitT(0x2101)            // MOV R1, #1
        emitT(0x8001)            // STRH R1, [R0, #0]
        ldrLitT(0, 0x324)        // OAM
        emitT(0x2110)            // MOV R1, #16 (Y=16)
        emitT(0x8001)            // STRH R1, [R0, #0]
        emitT(0x8041)            // STRH R1, [R0, #2] (X=16)
        emitT(0x2101)            // MOV R1, #1 (tile #1)
        emitT(0x8081)            // STRH R1, [R0, #4]

        // 5. Configure Mode 0 (BG0 + OBJ 1D: DISPCNT = 0x1140), BG0CNT = 0x0100 (screenblock 1)
        ldrLitT(4, 0x310)        // IO base 0x04000000
        emitT(0x2011)            // MOV R0, #0x11
        emitT(0x0200)            // LSL R0, R0, #8 -> 0x1100
        emitT(0x3040)            // ADD R0, #0x40 -> 0x1140
        emitT(0x8020)            // STRH R0, [R4, #0] (DISPCNT)
        emitT(0x2008)            // MOV R0, #8
        emitT(0x80A0)            // STRH R0, [R4, #4] (DISPSTAT VBlank IRQ enable)
        emitT(0x2001)            // MOV R0, #1
        emitT(0x0200)            // LSL R0, R0, #8 -> 0x0100
        emitT(0x8120)            // STRH R0, [R4, #8] (BG0CNT)

        // Enable IE=1, IME=1
        emitT(0x2102)            // MOV R1, #2
        emitT(0x0209)            // LSL R1, R1, #8 -> 0x200
        emitT(0x1861)            // ADD R1, R4, R1 -> 0x04000200
        emitT(0x2001)            // MOV R0, #1
        emitT(0x8008)            // STRH R0, [R1, #0] (IE)
        emitT(0x8108)            // STRH R0, [R1, #8] (IME)

        // 6. Game Loop (at loopOffset):
        val loopOffset = t
        // Call ARM VBlankIntrWait helper (BX to 0x08000180 via BL trampoline below)
        // First compute LR = loopAfterWait | 1, BX R3
        ldrLitT(3, 0x31C)        // R3 = 0x08000180
        val callPatchAt = t
        emitT(0x4E00)            // placeholder ADD/MOV LR? Let's use MOV R0, PC + #off -> MOV LR, R0
        // Actually Thumb BL to a 4-byte Thumb veneer at end of loop is cleaner: BL veneer
        t = callPatchAt          // rewind and emit BL +0x38? Better: put veneer at fixed offset 0x2D0!
        val veneerOffset = 0x2D0
        val blDiff = veneerOffset - (t + 4)
        emitT(0xF000 or ((blDiff ushr 12) and 0x7FF))
        emitT(0xF800 or ((blDiff ushr 1) and 0x7FF))

        // Check KEYINPUT (0x04000130):
        ldrLitT(2, 0x320)        // R2 = 0x04000130
        emitT(0x8810)            // LDRH R0, [R2, #0]
        emitT(0x210F)            // MOV R1, #0x0F (A|B|SELECT|START)
        emitT(0x4008)            // AND R0, R1
        emitT(0x2800)            // CMP R0, #0 -> all 4 held?
        emitT(0xD103)            // BNE +3 instrs (skip soft reset)
        emitT(0x2080)            // MOV R0, #0x80
        emitT(0x8020)            // STRH R0, [R4, #0] (forced blank!)
        emitT(0xDF00)            // SWI 0x00 SoftReset!

        // Check if A is pressed (bit 0 == 0): store 1 or 0 to [0x02000014]
        emitT(0x8810)            // LDRH R0, [R2, #0]
        emitT(0x2101)            // MOV R1, #1
        emitT(0x4008)            // AND R0, R1
        emitT(0x4048)            // EOR R0, R1 (R0 = 1 if A pressed else 0)
        ldrLitT(3, 0x300)        // R3 = 0x02000000
        emitT(0x6158)            // STR R0, [R3, #0x14]

        // Increment frameCounter at [0x02000010]
        emitT(0x6918)            // LDR R0, [R3, #0x10]
        emitT(0x3001)            // ADD R0, #1
        emitT(0x6118)            // STR R0, [R3, #0x10]

        // Branch back to loopOffset
        val backOff = ((loopOffset - (t + 4)) shr 1) and 0x7FF
        emitT(0xE000 or backOff)

        // Thumb-to-ARM veneer at 0x2D0: BX R3 (R3 = 0x08000180, returns to LR in Thumb!)
        put16(veneerOffset, 0x4718) // BX R3

        return rom
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

