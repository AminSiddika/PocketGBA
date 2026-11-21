package com.example.emulator

/**
 * Builds a genuine, valid GBA ROM ("PIXEL QUEST") containing real ARM7TDMI machine code.
 * Runs directly on the ARM7TDMI CPU, HLE BIOS (SWI 0x05 VBlankIntrWait), IRQ handler (0x03007FFC),
 * KEYINPUT (0x04000130), Mode 3 VRAM (0x06000000), and SRAM (0x0E000000).
 */
object HomebrewRomBuilder {

    private class ArmAsm {
        private val words = ArrayList<Int>()
        private val labels = HashMap<String, Int>()
        private val branchFixups = ArrayList<Triple<Int, String, Int>>() // wordIdx, label, condLinkBits
        private val literalFixups = ArrayList<Triple<Int, Int, Int>>() // wordIdx, rd, value

        val currentOffsetBytes: Int get() = words.size * 4

        fun label(name: String) {
            labels[name] = words.size
        }

        fun emit(word: Int) {
            words.add(word)
        }

        fun b(label: String, cond: Int = 0xE, link: Boolean = false) {
            val idx = words.size
            val linkBit = if (link) 1 shl 24 else 0
            words.add((cond shl 28) or (0x5 shl 25) or linkBit)
            branchFixups.add(Triple(idx, label, 0))
        }

        fun ldrLit(rd: Int, value: Int, cond: Int = 0xE) {
            val idx = words.size
            words.add((cond shl 28) or 0x059F0000 or (rd shl 12))
            literalFixups.add(Triple(idx, rd, value))
        }

        fun movImm(rd: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x03A00000 or (rd shl 12) or (imm8 and 0xFF))
        }

        fun movReg(rd: Int, rm: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x01A00000 or (rd shl 12) or (rm and 0xF))
        }

        fun addImm(rd: Int, rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x02800000 or (rn shl 16) or (rd shl 12) or (imm8 and 0xFF))
        }

        fun subImm(rd: Int, rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x02400000 or (rn shl 16) or (rd shl 12) or (imm8 and 0xFF))
        }

        fun addReg(rd: Int, rn: Int, rm: Int, shiftLsl: Int = 0, cond: Int = 0xE) {
            emit((cond shl 28) or 0x00800000 or (rn shl 16) or (rd shl 12) or ((shiftLsl and 0x1F) shl 7) or (rm and 0xF))
        }

        fun subReg(rd: Int, rn: Int, rm: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x00400000 or (rn shl 16) or (rd shl 12) or (rm and 0xF))
        }

        fun andImm(rd: Int, rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x02000000 or (rn shl 16) or (rd shl 12) or (imm8 and 0xFF))
        }

        fun orrImm(rd: Int, rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x03800000 or (rn shl 16) or (rd shl 12) or (imm8 and 0xFF))
        }

        fun eorImm(rd: Int, rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x02200000 or (rn shl 16) or (rd shl 12) or (imm8 and 0xFF))
        }

        fun cmpImm(rn: Int, imm8: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x03500000 or (rn shl 16) or (imm8 and 0xFF))
        }

        fun tstImm(rn: Int, imm8: Int, rotHalf: Int = 0, cond: Int = 0xE) {
            emit((cond shl 28) or 0x03100000 or (rn shl 16) or ((rotHalf and 0xF) shl 8) or (imm8 and 0xFF))
        }

        fun ldr(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFFF
            emit((cond shl 28) or 0x05100000 or u or (rn shl 16) or (rd shl 12) or off)
        }

        fun str(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFFF
            emit((cond shl 28) or 0x05000000 or u or (rn shl 16) or (rd shl 12) or off)
        }

        fun ldrh(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            require(kotlin.math.abs(offset) <= 0xFF) { "ldrh offset out of 8-bit range: $offset" }
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFF
            emit((cond shl 28) or 0x015000B0 or u or (rn shl 16) or (rd shl 12) or ((off and 0xF0) shl 4) or (off and 0xF))
        }

        fun strh(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            require(kotlin.math.abs(offset) <= 0xFF) { "strh offset out of 8-bit range: $offset" }
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFF
            emit((cond shl 28) or 0x014000B0 or u or (rn shl 16) or (rd shl 12) or ((off and 0xF0) shl 4) or (off and 0xF))
        }

        fun strhReg(rd: Int, rn: Int, rm: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x018000B0 or (rn shl 16) or (rd shl 12) or (rm and 0xF))
        }

        fun ldrb(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFFF
            emit((cond shl 28) or 0x05500000 or u or (rn shl 16) or (rd shl 12) or off)
        }

        fun strb(rd: Int, rn: Int, offset: Int = 0, cond: Int = 0xE) {
            val u = if (offset >= 0) (1 shl 23) else 0
            val off = kotlin.math.abs(offset) and 0xFFF
            emit((cond shl 28) or 0x05400000 or u or (rn shl 16) or (rd shl 12) or off)
        }

        fun bx(rm: Int, cond: Int = 0xE) {
            emit((cond shl 28) or 0x012FFF10 or (rm and 0xF))
        }

        fun swi(num: Int) {
            emit(0xEF000000.toInt() or (num and 0xFFFFFF))
        }

        fun finalize(): IntArray {
            // Append literal pool
            val poolMap = HashMap<Int, Int>()
            for ((instrIdx, _, value) in literalFixups) {
                val poolIdx = poolMap.getOrPut(value) {
                    val idx = words.size
                    words.add(value)
                    idx
                }
                val pcPlus8 = instrIdx + 2
                val byteOff = (poolIdx - pcPlus8) * 4
                words[instrIdx] = (words[instrIdx] and 0xFFFFF000.toInt()) or (byteOff and 0xFFF)
            }
            for ((instrIdx, labelName, _) in branchFixups) {
                val targetIdx = labels[labelName] ?: error("Missing label $labelName")
                val rel = targetIdx - (instrIdx + 2)
                words[instrIdx] = (words[instrIdx] and 0xFF000000.toInt()) or (rel and 0x00FFFFFF)
            }
            return words.toIntArray()
        }
    }

    fun buildPixelQuestRom(): ByteArray = buildActualCleanRom()

    private fun buildActualCleanRom(): ByteArray {
        val asm = ArmAsm()
        for (i in 0 until 48) asm.emit(0)

        asm.label("entry")
        asm.ldrLit(12, 0x03000100)
        asm.ldrLit(11, 0x04000000)

        while (asm.currentOffsetBytes < 0x100) asm.movReg(0, 0)
        asm.b("main_setup")

        // 0x08000104: IRQ handler
        asm.label("irq_handler_fixed")
        asm.ldrLit(0, 0x04000202)
        asm.movImm(1, 1)
        asm.strh(1, 0, 0)
        asm.ldrLit(0, 0x03007FF8)
        asm.ldrh(2, 0, 0)
        asm.orrImm(2, 2, 1)
        asm.strh(2, 0, 0)
        asm.bx(14)

        asm.label("main_setup")
        asm.ldrLit(0, 0x08000104)
        asm.ldrLit(1, 0x03007FFC)
        asm.str(0, 1, 0)

        asm.ldrLit(0, 0x0403)
        asm.strh(0, 11, 0)
        asm.movImm(0, 8)
        asm.strh(0, 11, 4)
        asm.movImm(0, 1)
        asm.ldrLit(2, 0x04000200)
        asm.strh(0, 2, 0)
        asm.strh(0, 2, 8)

        asm.movImm(0, 114); asm.str(0, 12, 0x00)
        asm.movImm(0, 74);  asm.str(0, 12, 0x04)
        asm.movImm(0, 180); asm.str(0, 12, 0x08)
        asm.movImm(0, 48);  asm.str(0, 12, 0x0C)
        asm.movImm(0, 0);   asm.str(0, 12, 0x10)
        asm.str(0, 12, 0x14)
        asm.str(0, 12, 0x18)
        asm.str(0, 12, 0x1C)

        asm.ldrLit(1, 0x0E000000)
        asm.ldrb(0, 1, 4)
        asm.cmpImm(0, 0x51)
        asm.ldrb(0, 1, 0, cond = 0x0)
        asm.str(0, 12, 0x10, cond = 0x0)

        asm.movImm(0, 0x80)
        asm.strh(0, 11, 0x84)

        asm.label("game_loop")
        asm.swi(0x05)

        asm.ldr(0, 12, 0x18)
        asm.addImm(0, 0, 1)
        asm.str(0, 12, 0x18)

        asm.ldrLit(4, 0x04000130)
        asm.ldrh(4, 4, 0)
        asm.movImm(5, 2)
        asm.tstImm(4, 1)
        asm.movImm(5, 4, cond = 0x0)

        // Right
        asm.tstImm(4, 0x10)
        asm.b("skip_right", cond = 0x1)
        asm.ldr(0, 12, 0x00)
        asm.addReg(0, 0, 5)
        asm.cmpImm(0, 224)
        asm.movImm(0, 224, cond = 0xC)
        asm.str(0, 12, 0x00)
        asm.label("skip_right")

        // Left
        asm.tstImm(4, 0x20)
        asm.b("skip_left", cond = 0x1)
        asm.ldr(0, 12, 0x00)
        asm.subReg(0, 0, 5)
        asm.cmpImm(0, 6)
        asm.movImm(0, 6, cond = 0xB)
        asm.str(0, 12, 0x00)
        asm.label("skip_left")

        // Up
        asm.tstImm(4, 0x40)
        asm.b("skip_up", cond = 0x1)
        asm.ldr(0, 12, 0x04)
        asm.subReg(0, 0, 5)
        asm.cmpImm(0, 18)
        asm.movImm(0, 18, cond = 0xB)
        asm.str(0, 12, 0x04)
        asm.label("skip_up")

        // Down
        asm.tstImm(4, 0x80)
        asm.b("skip_down", cond = 0x1)
        asm.ldr(0, 12, 0x04)
        asm.addReg(0, 0, 5)
        asm.cmpImm(0, 144)
        asm.movImm(0, 144, cond = 0xC)
        asm.str(0, 12, 0x04)
        asm.label("skip_down")

        // B button -> cycle theme
        asm.tstImm(4, 2)
        asm.b("b_not_pressed", cond = 0x1)
        asm.ldr(0, 12, 0x1C)
        asm.cmpImm(0, 0)
        asm.b("b_done", cond = 0x1)
        asm.movImm(0, 1)
        asm.str(0, 12, 0x1C)
        asm.ldr(1, 12, 0x14)
        asm.addImm(1, 1, 1)
        asm.andImm(1, 1, 3)
        asm.str(1, 12, 0x14)
        asm.b("b_done")
        asm.label("b_not_pressed")
        asm.movImm(0, 0)
        asm.str(0, 12, 0x1C)
        asm.label("b_done")

        // Orb collision
        asm.ldr(0, 12, 0x00)
        asm.ldr(1, 12, 0x08)
        asm.subReg(2, 0, 1)
        asm.cmpImm(2, 0)
        asm.emit(0xB2622000.toInt())
        asm.cmpImm(2, 12)
        asm.b("no_hit", cond = 0xA)

        asm.ldr(0, 12, 0x04)
        asm.ldr(1, 12, 0x0C)
        asm.subReg(2, 0, 1)
        asm.cmpImm(2, 0)
        asm.emit(0xB2622000.toInt())
        asm.cmpImm(2, 12)
        asm.b("no_hit", cond = 0xA)

        asm.ldr(0, 12, 0x10)
        asm.addImm(0, 0, 1)
        asm.str(0, 12, 0x10)
        asm.ldrLit(1, 0x0E000000)
        asm.strb(0, 1, 0)
        asm.movImm(2, 0x51)
        asm.strb(2, 1, 4)

        asm.ldr(3, 12, 0x18)
        asm.addReg(3, 3, 0, shiftLsl = 4)
        asm.addReg(3, 3, 3, shiftLsl = 2)
        asm.andImm(3, 3, 0x7F)
        asm.addImm(3, 3, 40)
        asm.str(3, 12, 0x08)

        asm.ldr(3, 12, 0x18)
        asm.addReg(3, 3, 0, shiftLsl = 3)
        asm.andImm(3, 3, 0x3F)
        asm.addImm(3, 3, 32)
        asm.str(3, 12, 0x0C)

        asm.ldrLit(0, 0xF780); asm.strh(0, 11, 0x62)
        asm.ldrLit(0, 0x86C0); asm.strh(0, 11, 0x64)
        asm.label("no_hit")

        // Render backdrop
        asm.ldr(0, 12, 0x14)
        asm.ldrLit(6, 0x2863)
        asm.cmpImm(0, 1); asm.ldrLit(6, 0x1542, cond = 0x0)
        asm.cmpImm(0, 2); asm.ldrLit(6, 0x10A6, cond = 0x0)
        asm.cmpImm(0, 3); asm.ldrLit(6, 0x20E5, cond = 0x0)

        asm.ldrLit(7, 0x06000000)
        asm.movImm(8, 0)
        asm.label("row_loop")
        asm.movImm(9, 0)
        asm.cmpImm(8, 15)
        asm.b("hud_row", cond = 0xB)

        asm.label("pf_col_loop")
        asm.movReg(10, 6)
        asm.tstImm(9, 15)
        asm.addImm(10, 10, 0x21, cond = 0x0)
        asm.tstImm(8, 15)
        asm.addImm(10, 10, 0x21, cond = 0x0)
        asm.strh(10, 7, 0)
        asm.addImm(7, 7, 2)
        asm.addImm(9, 9, 1)
        asm.cmpImm(9, 240)
        asm.b("pf_col_loop", cond = 0xB)
        asm.b("next_row")

        asm.label("hud_row")
        asm.ldrLit(10, 0x1084)
        asm.label("hud_col_loop")
        asm.strh(10, 7, 0)
        asm.addImm(7, 7, 2)
        asm.addImm(9, 9, 1)
        asm.cmpImm(9, 240)
        asm.b("hud_col_loop", cond = 0xB)

        asm.label("next_row")
        asm.addImm(8, 8, 1)
        asm.cmpImm(8, 160)
        asm.b("row_loop", cond = 0xB)

        // Score pips
        asm.ldr(5, 12, 0x10)
        asm.cmpImm(5, 28)
        asm.movImm(5, 28, cond = 0xC)
        asm.movImm(4, 0)
        asm.label("pip_loop")
        asm.emit(0xE1540005.toInt()) // CMP R4, R5
        asm.b("pip_done", cond = 0xA)
        asm.movImm(0, 8)
        asm.addReg(0, 0, 4, shiftLsl = 3)
        asm.movImm(1, 4)
        asm.movImm(2, 6)
        asm.ldrLit(3, 0x03FF)
        asm.b("draw_rect", link = true)
        asm.addImm(4, 4, 1)
        asm.b("pip_loop")
        asm.label("pip_done")

        // Orb
        asm.ldr(0, 12, 0x08)
        asm.ldr(1, 12, 0x0C)
        asm.movImm(2, 10)
        asm.ldrLit(3, 0x02BF)
        asm.b("draw_rect", link = true)

        // Player
        asm.ldr(0, 12, 0x00)
        asm.ldr(1, 12, 0x04)
        asm.movImm(2, 12)
        asm.ldrLit(3, 0x7FE0)
        asm.b("draw_rect", link = true)

        // Visor
        asm.ldr(0, 12, 0x00); asm.addImm(0, 0, 3)
        asm.ldr(1, 12, 0x04); asm.addImm(1, 1, 3)
        asm.movImm(2, 6)
        asm.ldrLit(3, 0x7FFF)
        asm.b("draw_rect", link = true)

        asm.b("game_loop")

        // draw_rect(R0=x, R1=y, R2=size, R3=color)
        asm.label("draw_rect")
        asm.ldrLit(7, 0x06000000)
        asm.movImm(8, 0) // dy
        asm.label("dr_y")
        asm.addReg(9, 1, 8)           // row = y + dy
        asm.emit(0xE1A0A209.toInt())  // MOV R10, R9, LSL #4 (row * 16)
        asm.emit(0xE06AA20A.toInt())  // RSB R10, R10, R10, LSL #4 (row * 240)
        asm.addReg(10, 10, 0)         // + x
        asm.addReg(10, 7, 10, shiftLsl = 1) // vram + idx * 2
        asm.movImm(9, 0) // dx
        asm.label("dr_x")
        asm.strh(3, 10, 0)
        asm.addImm(10, 10, 2)
        asm.addImm(9, 9, 1)
        asm.emit(0xE1590002.toInt())  // CMP R9, R2
        asm.b("dr_x", cond = 0xB)
        asm.addImm(8, 8, 1)
        asm.emit(0xE1580002.toInt())  // CMP R8, R2
        asm.b("dr_y", cond = 0xB)
        asm.bx(14)

        val words = asm.finalize()
        val bytes = ByteArray(maxOf(words.size * 4 + 32, 0x800))
        for (i in words.indices) {
            val w = words[i]
            bytes[i * 4] = (w and 0xFF).toByte()
            bytes[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
        }

        // Write GBA header at 0x00..0xBF
        // 0x00: B 0x080000C0
        val entryBranch = 0xEA00002E
        bytes[0] = (entryBranch and 0xFF).toByte()
        bytes[1] = ((entryBranch ushr 8) and 0xFF).toByte()
        bytes[2] = ((entryBranch ushr 16) and 0xFF).toByte()
        bytes[3] = ((entryBranch ushr 24) and 0xFF).toByte()

        // Title "PIXEL QUEST " at 0xA0..0xAB
        val title = "PIXEL QUEST ".toByteArray(Charsets.US_ASCII)
        System.arraycopy(title, 0, bytes, 0xA0, 12)
        // Game code "APQE" at 0xAC..0xAF
        val code = "APQE".toByteArray(Charsets.US_ASCII)
        System.arraycopy(code, 0, bytes, 0xAC, 4)
        // Maker "PA"
        bytes[0xB0] = 'P'.code.toByte()
        bytes[0xB1] = 'A'.code.toByte()
        bytes[0xB2] = 0x96.toByte()

        // Complement checksum at 0xBD
        var sum = 0
        for (i in 0xA0..0xBC) sum += bytes[i].toInt() and 0xFF
        bytes[0xBD] = ((-(sum + 0x19)) and 0xFF).toByte()

        // Embed SRAM_V110 marker string at end so save type auto-detects as SRAM
        val sramTag = "SRAM_V110\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(sramTag, 0, bytes, bytes.size - sramTag.size, sramTag.size)

        return bytes
    }
}
