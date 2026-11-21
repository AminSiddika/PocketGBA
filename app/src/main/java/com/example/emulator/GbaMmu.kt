package com.example.emulator

import java.io.DataInputStream
import java.io.DataOutputStream

class GbaMmu {
    val bios = ByteArray(0x4000)      // 16 KB
    val ewram = ByteArray(0x40000)    // 256 KB
    val iwram = ByteArray(0x8000)     // 32 KB
    val io = ByteArray(0x400)         // 1 KB I/O registers
    val palette = ByteArray(0x400)    // 1 KB Palette RAM
    val vram = ByteArray(0x18000)     // 96 KB VRAM
    val oam = ByteArray(0x400)        // 1 KB OAM
    var rom: ByteArray = ByteArray(0)
    var saveType: SaveType = SaveType.SRAM
    val sram = ByteArray(0x20000)     // Up to 128 KB (SRAM / Flash)
    var sramDirty = false

    // Flash state machine
    private var flashBank = 0
    private var flashState = 0
    private var flashIdMode = false

    // EEPROM state machine (512B / 8KB at 0x0Dxxxxxx)
    private var eepromMode = 0 // 0=IDLE (returns 1 ready bit), 1=READ_DATA
    private var eepromAddrBits = 14 // Auto-detected: 6 (512B) or 14 (8KB)
    private var eepromReadAddr = 0
    private var eepromReadBitPos = 0
    private val eepromBitBuf = IntArray(96)
    private var eepromBitLen = 0
    private var activeEepromDmaCount = 0

    // Keypad state (active low, bit 0..9)
    @Volatile
    var keyInput: Int = GbaConstants.KEY_ALL_RELEASED

    // Halt state (set by SWI Halt / VBlankIntrWait / HALTCNT)
    var halted = false
    var intrWaitMask = 0
    var waitingForIntr = false

    // Timers (4 channels)
    val timerReload = IntArray(4)
    val timerControl = IntArray(4)
    val timerCounter = IntArray(4)
    val timerSubCycles = IntArray(4)

    // DMA (4 channels)
    val dmaSad = IntArray(4)
    val dmaDad = IntArray(4)
    val dmaCount = IntArray(4)
    val dmaControl = IntArray(4)
    val dmaInternalSad = IntArray(4)
    val dmaInternalDad = IntArray(4)
    val dmaInternalCount = IntArray(4)

    // Audio FIFO & Rumble callbacks
    var onFifoWrite: ((channel: Int, word: Int) -> Unit)? = null
    var onTimerOverflow: ((timerIndex: Int) -> Unit)? = null
    var onRumbleChanged: ((active: Boolean) -> Unit)? = null
    var hasExternalBios = false

    // Cartridge GPIO (0x080000C4..C8) & Rumble motor state
    var gpioData = 0
        private set
    var gpioDirection = 0
        private set
    var gpioControl = 0
        private set
    @Volatile
    var rumbleActive = false
        private set
    var rumbleEventsCount = 0L
        private set

    // Internal affine BG reference latches (BG2X/Y, BG3X/Y)
    var bg2RefX = 0
    var bg2RefY = 0
    var bg3RefX = 0
    var bg3RefY = 0

    init {
        installHleBiosStubs()
    }

    fun reset(romBytes: ByteArray, detectedSaveType: SaveType, customBios: ByteArray? = null) {
        ewram.fill(0)
        iwram.fill(0)
        io.fill(0)
        palette.fill(0)
        vram.fill(0)
        oam.fill(0)
        rom = romBytes
        saveType = detectedSaveType
        flashBank = 0
        flashState = 0
        flashIdMode = false
        eepromMode = 0
        eepromAddrBits = 14
        eepromReadAddr = 0
        eepromReadBitPos = 0
        eepromBitLen = 0
        activeEepromDmaCount = 0
        halted = false
        intrWaitMask = 0
        waitingForIntr = false
        timerReload.fill(0)
        timerControl.fill(0)
        timerCounter.fill(0)
        timerSubCycles.fill(0)
        dmaSad.fill(0)
        dmaDad.fill(0)
        dmaCount.fill(0)
        dmaControl.fill(0)
        dmaInternalSad.fill(0)
        dmaInternalDad.fill(0)
        dmaInternalCount.fill(0)
        bg2RefX = 0
        bg2RefY = 0
        bg3RefX = 0
        bg3RefY = 0
        gpioData = 0
        gpioDirection = 0
        gpioControl = 0
        updateRumbleState(false)
        rumbleEventsCount = 0L

        if (customBios != null && customBios.size >= 0x4000) {
            System.arraycopy(customBios, 0, bios, 0, 0x4000)
            hasExternalBios = true
        } else {
            bios.fill(0)
            installHleBiosStubs()
            hasExternalBios = false
        }

        // Default SOUNDBIAS = 0x0200, KEYINPUT = 0x03FF
        writeIo16Raw(0x088, 0x0200)
        writeIo16Raw(0x130, GbaConstants.KEY_ALL_RELEASED)
    }

    private fun installHleBiosStubs() {
        // 0x00000018: IRQ exception vector -> B 0x00000128
        writeBios32(0x0018, 0xEA000042.toInt())
        // Official GBA BIOS IRQ wrapper at 0x00000128
        writeBios32(0x0128, 0xE92D500F.toInt()) // STMFD SP!, {R0-R3, R12, LR}
        writeBios32(0x012C, 0xE3A00301.toInt()) // MOV R0, #0x04000000
        writeBios32(0x0130, 0xE28FE000.toInt()) // ADD LR, PC, #0
        writeBios32(0x0134, 0xE510F004.toInt()) // LDR PC, [R0, #-4] (0x03007FFC)
        writeBios32(0x0138, 0xE8BD500F.toInt()) // LDMFD SP!, {R0-R3, R12, LR}
        writeBios32(0x013C, 0xE25EF004.toInt()) // SUBS PC, LR, #4
    }

    private fun writeBios32(offset: Int, value: Int) {
        bios[offset] = (value and 0xFF).toByte()
        bios[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bios[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bios[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun read8(address: Int): Int {
        val region = (address ushr 24) and 0xFF
        return when (region) {
            0x00 -> {
                val off = address and 0x3FFF
                if (address < 0x4000) bios[off].toInt() and 0xFF else 0
            }
            0x02 -> ewram[address and 0x3FFFF].toInt() and 0xFF
            0x03 -> iwram[address and 0x7FFF].toInt() and 0xFF
            0x04 -> readIo8(address and 0x3FF)
            0x05 -> palette[address and 0x3FF].toInt() and 0xFF
            0x06 -> {
                var off = address and 0x1FFFF
                if (off >= 0x18000) off -= 0x8000
                vram[off].toInt() and 0xFF
            }
            0x07 -> oam[address and 0x3FF].toInt() and 0xFF
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                val off = address and 0x01FFFFFF
                if (isEepromAccess(region, off)) {
                    if ((address and 1) == 0) readEeprom16() and 0xFF else 0
                } else if ((gpioControl and 1) != 0 && off in 0xC4..0xC9) {
                    readGpioHalfword(off and 0xFFFFFFFE.toInt()) ushr ((address and 1) * 8) and 0xFF
                } else if (off < rom.size) rom[off].toInt() and 0xFF else ((off ushr 1) and 0xFF)
            }
            0x0E, 0x0F -> readSram8(address and 0xFFFF)
            else -> 0
        }
    }

    fun read16(address: Int): Int {
        val aligned = address and 0xFFFFFFFE.toInt()
        val region = (aligned ushr 24) and 0xFF
        return when (region) {
            0x00 -> if (aligned < 0x4000) {
                val o = aligned and 0x3FFF
                (bios[o].toInt() and 0xFF) or ((bios[o + 1].toInt() and 0xFF) shl 8)
            } else 0
            0x02 -> {
                val o = aligned and 0x3FFFF
                (ewram[o].toInt() and 0xFF) or ((ewram[o + 1].toInt() and 0xFF) shl 8)
            }
            0x03 -> {
                val o = aligned and 0x7FFF
                (iwram[o].toInt() and 0xFF) or ((iwram[o + 1].toInt() and 0xFF) shl 8)
            }
            0x04 -> readIo16(aligned and 0x3FF)
            0x05 -> {
                val o = aligned and 0x3FF
                (palette[o].toInt() and 0xFF) or ((palette[o + 1].toInt() and 0xFF) shl 8)
            }
            0x06 -> {
                var o = aligned and 0x1FFFF
                if (o >= 0x18000) o -= 0x8000
                (vram[o].toInt() and 0xFF) or ((vram[o + 1].toInt() and 0xFF) shl 8)
            }
            0x07 -> {
                val o = aligned and 0x3FF
                (oam[o].toInt() and 0xFF) or ((oam[o + 1].toInt() and 0xFF) shl 8)
            }
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                val o = aligned and 0x01FFFFFF
                if (isEepromAccess(region, o)) {
                    readEeprom16()
                } else if ((gpioControl and 1) != 0 && (o == 0xC4 || o == 0xC6 || o == 0xC8)) {
                    readGpioHalfword(o)
                } else if (o + 1 < rom.size) {
                    (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
                } else {
                    (o ushr 1) and 0xFFFF
                }
            }
            0x0E, 0x0F -> {
                val v = readSram8(aligned and 0xFFFF)
                v or (v shl 8)
            }
            else -> 0
        }
    }

    fun read32(address: Int): Int {
        val aligned = address and 0xFFFFFFFC.toInt()
        val region = (aligned ushr 24) and 0xFF
        return when (region) {
            0x00 -> if (aligned < 0x4000) {
                val o = aligned and 0x3FFF
                (bios[o].toInt() and 0xFF) or
                    ((bios[o + 1].toInt() and 0xFF) shl 8) or
                    ((bios[o + 2].toInt() and 0xFF) shl 16) or
                    ((bios[o + 3].toInt() and 0xFF) shl 24)
            } else 0
            0x02 -> {
                val o = aligned and 0x3FFFF
                (ewram[o].toInt() and 0xFF) or
                    ((ewram[o + 1].toInt() and 0xFF) shl 8) or
                    ((ewram[o + 2].toInt() and 0xFF) shl 16) or
                    ((ewram[o + 3].toInt() and 0xFF) shl 24)
            }
            0x03 -> {
                val o = aligned and 0x7FFF
                (iwram[o].toInt() and 0xFF) or
                    ((iwram[o + 1].toInt() and 0xFF) shl 8) or
                    ((iwram[o + 2].toInt() and 0xFF) shl 16) or
                    ((iwram[o + 3].toInt() and 0xFF) shl 24)
            }
            0x04 -> {
                val o = aligned and 0x3FF
                readIo16(o) or (readIo16(o + 2) shl 16)
            }
            0x05 -> {
                val o = aligned and 0x3FF
                (palette[o].toInt() and 0xFF) or
                    ((palette[o + 1].toInt() and 0xFF) shl 8) or
                    ((palette[o + 2].toInt() and 0xFF) shl 16) or
                    ((palette[o + 3].toInt() and 0xFF) shl 24)
            }
            0x06 -> {
                var o = aligned and 0x1FFFF
                if (o >= 0x18000) o -= 0x8000
                (vram[o].toInt() and 0xFF) or
                    ((vram[o + 1].toInt() and 0xFF) shl 8) or
                    ((vram[o + 2].toInt() and 0xFF) shl 16) or
                    ((vram[o + 3].toInt() and 0xFF) shl 24)
            }
            0x07 -> {
                val o = aligned and 0x3FF
                (oam[o].toInt() and 0xFF) or
                    ((oam[o + 1].toInt() and 0xFF) shl 8) or
                    ((oam[o + 2].toInt() and 0xFF) shl 16) or
                    ((oam[o + 3].toInt() and 0xFF) shl 24)
            }
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                val o = aligned and 0x01FFFFFF
                if (isEepromAccess(region, o)) {
                    readEeprom16()
                } else if (o + 3 < rom.size) {
                    (rom[o].toInt() and 0xFF) or
                        ((rom[o + 1].toInt() and 0xFF) shl 8) or
                        ((rom[o + 2].toInt() and 0xFF) shl 16) or
                        ((rom[o + 3].toInt() and 0xFF) shl 24)
                } else if (o < rom.size) {
                    var res = 0
                    for (b in 0..3) {
                        val byteVal = if (o + b < rom.size) (rom[o + b].toInt() and 0xFF) else 0
                        res = res or (byteVal shl (b * 8))
                    }
                    res
                } else {
                    ((o ushr 1) and 0xFFFF) or ((((o + 2) ushr 1) and 0xFFFF) shl 16)
                }
            }
            0x0E, 0x0F -> {
                val v = readSram8(aligned and 0xFFFF)
                v * 0x01010101
            }
            else -> 0
        }
    }

    fun write8(address: Int, value: Int) {
        val v = (value and 0xFF).toByte()
        val region = (address ushr 24) and 0xFF
        when (region) {
            0x02 -> ewram[address and 0x3FFFF] = v
            0x03 -> iwram[address and 0x7FFF] = v
            0x04 -> writeIo8(address and 0x3FF, value and 0xFF)
            0x05 -> {
                // 8-bit writes to Palette RAM write the byte to both halves of the halfword
                val o = address and 0x3FE
                palette[o] = v
                palette[o + 1] = v
            }
            0x06 -> {
                var o = address and 0x1FFFE
                if (o >= 0x18000) o -= 0x8000
                // 8-bit writes to OBJ VRAM are ignored; BG VRAM writes both bytes
                val dispcnt = readIo16Raw(0x00) and 0x07
                val objLimit = if (dispcnt >= 3) 0x14000 else 0x10000
                if (o < objLimit) {
                    vram[o] = v
                    vram[o + 1] = v
                }
            }
            0x08, 0x09 -> {
                val o = address and 0x01FFFFFF
                if (o == 0xC4 || o == 0xC6 || o == 0xC8) {
                    writeGpioHalfword(o, value and 0xFF)
                }
            }
            0x0E, 0x0F -> writeSram8(address and 0xFFFF, value and 0xFF)
        }
    }

    fun write16(address: Int, value: Int) {
        val aligned = address and 0xFFFFFFFE.toInt()
        val b0 = (value and 0xFF).toByte()
        val b1 = ((value ushr 8) and 0xFF).toByte()
        val region = (aligned ushr 24) and 0xFF
        when (region) {
            0x02 -> {
                val o = aligned and 0x3FFFF
                ewram[o] = b0
                ewram[o + 1] = b1
            }
            0x03 -> {
                val o = aligned and 0x7FFF
                iwram[o] = b0
                iwram[o + 1] = b1
            }
            0x04 -> writeIo16(aligned and 0x3FF, value and 0xFFFF)
            0x05 -> {
                val o = aligned and 0x3FF
                palette[o] = b0
                palette[o + 1] = b1
            }
            0x06 -> {
                var o = aligned and 0x1FFFF
                if (o >= 0x18000) o -= 0x8000
                vram[o] = b0
                vram[o + 1] = b1
            }
            0x07 -> {
                val o = aligned and 0x3FF
                oam[o] = b0
                oam[o + 1] = b1
            }
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> {
                val o = aligned and 0x01FFFFFF
                if (isEepromAccess(region, o)) {
                    writeEeprom16(value)
                } else if ((region == 0x08 || region == 0x09) && (o == 0xC4 || o == 0xC6 || o == 0xC8)) {
                    writeGpioHalfword(o, value and 0xFFFF)
                }
            }
            0x0E, 0x0F -> writeSram8(aligned and 0xFFFF, value and 0xFF)
        }
    }

    fun write32(address: Int, value: Int) {
        val aligned = address and 0xFFFFFFFC.toInt()
        val b0 = (value and 0xFF).toByte()
        val b1 = ((value ushr 8) and 0xFF).toByte()
        val b2 = ((value ushr 16) and 0xFF).toByte()
        val b3 = ((value ushr 24) and 0xFF).toByte()
        val region = (aligned ushr 24) and 0xFF
        when (region) {
            0x02 -> {
                val o = aligned and 0x3FFFF
                ewram[o] = b0; ewram[o + 1] = b1; ewram[o + 2] = b2; ewram[o + 3] = b3
            }
            0x03 -> {
                val o = aligned and 0x7FFF
                iwram[o] = b0; iwram[o + 1] = b1; iwram[o + 2] = b2; iwram[o + 3] = b3
            }
            0x04 -> {
                val o = aligned and 0x3FF
                if (o == 0x0A0) {
                    onFifoWrite?.invoke(0, value)
                } else if (o == 0x0A4) {
                    onFifoWrite?.invoke(1, value)
                } else {
                    writeIo16(o, value and 0xFFFF)
                    writeIo16(o + 2, (value ushr 16) and 0xFFFF)
                }
            }
            0x05 -> {
                val o = aligned and 0x3FF
                palette[o] = b0; palette[o + 1] = b1; palette[o + 2] = b2; palette[o + 3] = b3
            }
            0x06 -> {
                var o = aligned and 0x1FFFF
                if (o >= 0x18000) o -= 0x8000
                vram[o] = b0; vram[o + 1] = b1; vram[o + 2] = b2; vram[o + 3] = b3
            }
            0x07 -> {
                val o = aligned and 0x3FF
                oam[o] = b0; oam[o + 1] = b1; oam[o + 2] = b2; oam[o + 3] = b3
            }
            0x08, 0x09 -> {
                val o = aligned and 0x01FFFFFF
                if (o == 0xC4 || o == 0xC6 || o == 0xC8) {
                    writeGpioHalfword(o, value and 0xFFFF)
                }
                if (o + 2 == 0xC6 || o + 2 == 0xC8) {
                    writeGpioHalfword(o + 2, (value ushr 16) and 0xFFFF)
                }
            }
            0x0E, 0x0F -> writeSram8(aligned and 0xFFFF, value and 0xFF)
        }
    }

    // Direct ROM patch helper used by Cheat Engine (e.g., GameShark/CodeBreaker ROM patch codes)
    fun patchRom16(address: Int, value: Int) {
        val off = address and 0x01FFFFFE
        if (off + 1 < rom.size) {
            rom[off] = (value and 0xFF).toByte()
            rom[off + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }

    fun readIo8(offset: Int): Int {
        if (offset == 0x130) return keyInput and 0xFF
        if (offset == 0x131) return (keyInput ushr 8) and 0x03
        return io[offset and 0x3FF].toInt() and 0xFF
    }

    fun readIo16(offset: Int): Int {
        val o = offset and 0x3FE
        if (o == 0x130) return keyInput and 0x03FF
        if (o in 0x100..0x10E && (o and 3) == 0) {
            val idx = (o - 0x100) ushr 2
            return timerCounter[idx] and 0xFFFF
        }
        return (io[o].toInt() and 0xFF) or ((io[o + 1].toInt() and 0xFF) shl 8)
    }

    fun readIo16Raw(offset: Int): Int {
        val o = offset and 0x3FE
        return (io[o].toInt() and 0xFF) or ((io[o + 1].toInt() and 0xFF) shl 8)
    }

    fun writeIo16Raw(offset: Int, value: Int) {
        val o = offset and 0x3FE
        io[o] = (value and 0xFF).toByte()
        io[o + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIo8(offset: Int, value: Int) {
        val o = offset and 0x3FF
        when (o) {
            0x004 -> {
                val ro = io[0x004].toInt() and 0x07
                io[0x004] = ((value and 0xF8) or ro).toByte()
                return
            }
            0x006, 0x007 -> return
            0x100, 0x101, 0x104, 0x105, 0x108, 0x109, 0x10C, 0x10D -> {
                val base = o and 0x3FE
                val idx = (base - 0x100) ushr 2
                io[o] = value.toByte()
                timerReload[idx] = readIo16Raw(base)
                return
            }
            0x102, 0x103, 0x106, 0x107, 0x10A, 0x10B, 0x10E, 0x10F -> {
                val base = o and 0x3FE
                val idx = (base - 0x102) ushr 2
                val prevEnabled = (timerControl[idx] and 0x80) != 0
                io[o] = value.toByte()
                val newCtrl = readIo16Raw(base)
                val nowEnabled = (newCtrl and 0x80) != 0
                timerControl[idx] = newCtrl
                if (!prevEnabled && nowEnabled) {
                    timerCounter[idx] = timerReload[idx]
                    timerSubCycles[idx] = 0
                }
                return
            }
            0x132, 0x133 -> {
                io[o] = value.toByte()
                checkKeypadIrq()
                return
            }
            0x202, 0x203 -> {
                // IF is write-1-to-clear
                val cur = io[o].toInt() and 0xFF
                io[o] = (cur and value.inv()).toByte()
                return
            }
            0x301 -> {
                // HALTCNT: bit 7 == 0 -> Halt
                if ((value and 0x80) == 0) {
                    halted = true
                }
                return
            }
        }
        io[o] = value.toByte()
        val base = o and 0x3FE
        handleIoHalfwordSideEffects(base, readIo16Raw(base))
    }

    private fun writeIo16(offset: Int, value: Int) {
        val o = offset and 0x3FE
        when (o) {
            0x004 -> {
                // DISPSTAT: bits 0..2 are read-only hardware flags
                val ro = readIo16Raw(0x004) and 0x0007
                writeIo16Raw(0x004, (value and 0xFFF8) or ro)
                return
            }
            0x006 -> return // VCOUNT is read-only
            0x132 -> {
                writeIo16Raw(0x132, value and 0xC3FF)
                checkKeypadIrq()
                return
            }
            0x202 -> {
                // IF write-1-to-clear
                val cur = readIo16Raw(0x202)
                writeIo16Raw(0x202, cur and value.inv())
                return
            }
            0x300 -> {
                io[0x300] = (value and 0xFF).toByte()
                if ((value and 0x8000) == 0) halted = true
                return
            }
            // Timers
            0x100, 0x104, 0x108, 0x10C -> {
                val idx = (o - 0x100) ushr 2
                timerReload[idx] = value and 0xFFFF
                writeIo16Raw(o, value)
                return
            }
            0x102, 0x106, 0x10A, 0x10E -> {
                val idx = (o - 0x102) ushr 2
                val prevEnabled = (timerControl[idx] and 0x80) != 0
                val nowEnabled = (value and 0x80) != 0
                timerControl[idx] = value and 0xFFFF
                if (!prevEnabled && nowEnabled) {
                    timerCounter[idx] = timerReload[idx]
                    timerSubCycles[idx] = 0
                }
                writeIo16Raw(o, value)
                return
            }
        }

        writeIo16Raw(o, value)
        handleIoHalfwordSideEffects(o, value)
    }

    private fun handleIoHalfwordSideEffects(o: Int, value: Int) {
        when (o) {
            // BG2/BG3 reference points
            0x028, 0x02A -> bg2RefX = signExtend28(readIo32Raw(0x028))
            0x02C, 0x02E -> bg2RefY = signExtend28(readIo32Raw(0x02C))
            0x038, 0x03A -> bg3RefX = signExtend28(readIo32Raw(0x038))
            0x03C, 0x03E -> bg3RefY = signExtend28(readIo32Raw(0x03C))

            // Serial SIOCNT (0x128) — Game Boy Player / Link Rumble handshake handling
            0x128 -> {
                if ((value and 0x0080) != 0) {
                    val data32 = readIo32Raw(0x120)
                    // Game Boy Player rumble control packets (0x40000010 / 0x0022xxxx / 0x80000001)
                    if ((data32 and 0xFFFF0000.toInt()) == 0x40000000) {
                        updateRumbleState((data32 and 0x01) != 0)
                    } else if ((data32 ushr 16) == 0x0022) {
                        updateRumbleState((data32 and 0x01) != 0)
                    }
                    // Complete transfer immediately & raise IRQ_SERIAL if bit 14 set
                    writeIo16Raw(0x128, value and 0xFF7F)
                    if ((value and 0x4000) != 0) {
                        raiseIrq(GbaConstants.IRQ_SERIAL)
                    }
                }
            }

            // DMA channels 0..3
            in 0x0B0..0x0DF -> {
                val ch = (o - 0x0B0) / 12
                val reg = (o - 0x0B0) % 12
                when (reg) {
                    0, 2 -> dmaSad[ch] = readIo32Raw(0x0B0 + ch * 12)
                    4, 6 -> dmaDad[ch] = readIo32Raw(0x0B4 + ch * 12)
                    8 -> dmaCount[ch] = value and 0xFFFF
                    10 -> {
                        val wasEnabled = (dmaControl[ch] and 0x8000) != 0
                        dmaControl[ch] = value and 0xFFFF
                        val nowEnabled = (value and 0x8000) != 0
                        if (!wasEnabled && nowEnabled) {
                            dmaInternalSad[ch] = dmaSad[ch]
                            dmaInternalDad[ch] = dmaDad[ch]
                            dmaInternalCount[ch] = if (dmaCount[ch] == 0) {
                                if (ch == 3) 0x10000 else 0x4000
                            } else dmaCount[ch]
                            if (((value ushr 12) and 3) == 0) {
                                runDma(ch)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readGpioHalfword(offset: Int): Int = when (offset) {
        0xC4 -> gpioData and 0x0F
        0xC6 -> gpioDirection and 0x0F
        0xC8 -> gpioControl and 0x01
        else -> 0
    }

    private fun writeGpioHalfword(offset: Int, value: Int) {
        when (offset) {
            0xC4 -> {
                gpioData = value and 0x0F
                val outputMask = if (gpioDirection == 0) 0x08 else gpioDirection
                if ((outputMask and 0x08) != 0) {
                    updateRumbleState((gpioData and 0x08) != 0)
                }
            }
            0xC6 -> gpioDirection = value and 0x0F
            0xC8 -> gpioControl = value and 0x01
        }
    }

    private fun updateRumbleState(active: Boolean) {
        if (rumbleActive != active) {
            rumbleActive = active
            if (active) rumbleEventsCount++
            onRumbleChanged?.invoke(active)
        }
    }

    private fun readIo32Raw(offset: Int): Int {
        return readIo16Raw(offset) or (readIo16Raw(offset + 2) shl 16)
    }

    private fun signExtend28(v: Int): Int {
        val masked = v and 0x0FFFFFFF
        return if ((masked and 0x08000000) != 0) masked or 0xF0000000.toInt() else masked
    }

    fun triggerDmaTiming(timing: Int) {
        for (ch in 0..3) {
            val ctrl = dmaControl[ch]
            if ((ctrl and 0x8000) != 0 && ((ctrl ushr 12) and 3) == timing) {
                runDma(ch)
            }
        }
    }

    fun triggerFifoDma(fifoChannel: Int) {
        val targetDad = if (fifoChannel == 0) 0x040000A0 else 0x040000A4
        for (ch in 1..2) {
            val ctrl = dmaControl[ch]
            if ((ctrl and 0x8000) != 0 && ((ctrl ushr 12) and 3) == 3 && dmaDad[ch] == targetDad) {
                var src = dmaInternalSad[ch]
                for (i in 0 until 4) {
                    write32(targetDad, read32(src))
                    src += 4
                }
                dmaInternalSad[ch] = src
            }
        }
    }

    private fun runDma(ch: Int) {
        val ctrl = dmaControl[ch]
        val word32 = (ctrl and 0x0400) != 0
        val step = if (word32) 4 else 2
        val count = dmaInternalCount[ch]
        val srcMode = (ctrl ushr 7) and 3
        val dstMode = (ctrl ushr 5) and 3
        val srcStep = when (srcMode) { 0 -> step; 1 -> -step; else -> 0 }
        val dstStep = when (dstMode) { 0, 3 -> step; 1 -> -step; else -> 0 }

        var src = dmaInternalSad[ch]
        var dst = dmaInternalDad[ch]
        if (ch == 3 && ((dst ushr 24) and 0xFF) == 0x0D) {
            activeEepromDmaCount = count
        }

        for (i in 0 until count) {
            if (word32) {
                write32(dst, read32(src))
            } else {
                write16(dst, read16(src))
            }
            src += srcStep
            dst += dstStep
        }
        activeEepromDmaCount = 0

        dmaInternalSad[ch] = src
        dmaInternalDad[ch] = if (dstMode == 3) dmaDad[ch] else dst

        val repeat = (ctrl and 0x0200) != 0 && ((ctrl ushr 12) and 3) != 0
        if (repeat) {
            dmaInternalCount[ch] = if (dmaCount[ch] == 0) {
                if (ch == 3) 0x10000 else 0x4000
            } else dmaCount[ch]
        } else {
            dmaControl[ch] = ctrl and 0x7FFF
            writeIo16Raw(0x0BA + ch * 12, dmaControl[ch])
        }

        if ((ctrl and 0x4000) != 0) {
            raiseIrq(GbaConstants.IRQ_DMA0 shl ch)
        }
    }

    fun stepTimers(cycles: Int) {
        var prevOverflow = false
        for (i in 0..3) {
            val ctrl = timerControl[i]
            if ((ctrl and 0x80) == 0) {
                prevOverflow = false
                continue
            }
            val cascade = i > 0 && (ctrl and 0x04) != 0
            var increments = 0
            if (cascade) {
                if (prevOverflow) increments = 1
            } else {
                val prescaler = when (ctrl and 3) {
                    0 -> 1
                    1 -> 64
                    2 -> 256
                    else -> 1024
                }
                timerSubCycles[i] += cycles
                while (timerSubCycles[i] >= prescaler) {
                    timerSubCycles[i] -= prescaler
                    increments++
                }
            }
            prevOverflow = false
            if (increments > 0) {
                var cnt = timerCounter[i] + increments
                while (cnt >= 0x10000) {
                    cnt -= (0x10000 - timerReload[i])
                    if (cnt < timerReload[i]) cnt = timerReload[i]
                    prevOverflow = true
                    if ((ctrl and 0x40) != 0) {
                        raiseIrq(GbaConstants.IRQ_TIMER0 shl i)
                    }
                    if (i <= 1) onTimerOverflow?.invoke(i)
                }
                timerCounter[i] = cnt and 0xFFFF
            }
        }
    }

    fun raiseIrq(irqBit: Int) {
        val currentIf = readIo16Raw(0x202)
        writeIo16Raw(0x202, currentIf or irqBit)
        val ie = readIo16Raw(0x200)
        if ((ie and irqBit) != 0) {
            if (halted && (!waitingForIntr || (intrWaitMask and irqBit) != 0)) {
                halted = false
                waitingForIntr = false
            }
        }
    }

    fun hasPendingIrq(): Boolean {
        val ime = readIo16Raw(0x208) and 1
        if (ime == 0) return false
        val ie = readIo16Raw(0x200)
        val irqFlags = readIo16Raw(0x202)
        return (ie and irqFlags) != 0
    }

    fun checkKeypadIrq() {
        val keycnt = readIo16Raw(0x132)
        if ((keycnt and 0x4000) == 0) return
        val selected = keycnt and 0x03FF
        if (selected == 0) return
        val pressed = keyInput.inv() and 0x03FF
        val triggered = if ((keycnt and 0x8000) != 0) {
            (pressed and selected) == selected
        } else {
            (pressed and selected) != 0
        }
        if (triggered) {
            raiseIrq(GbaConstants.IRQ_KEYPAD)
        }
    }

    private fun isEepromAccess(region: Int, romOffset: Int): Boolean {
        if (region != 0x0D) return false
        return rom.size <= 0x01000000 || romOffset >= 0x01FFFF00
    }

    private fun readEeprom16(): Int {
        if (eepromMode == 0) return 1 // Ready bit
        val pos = eepromReadBitPos++
        if (pos < 4) return 0 // 4 dummy zero bits
        val dataBitIdx = pos - 4
        if (dataBitIdx >= 64) {
            eepromMode = 0
            eepromReadBitPos = 0
            return 1
        }
        val byteIdx = dataBitIdx ushr 3
        val bitInByte = 7 - (dataBitIdx and 7)
        val sramOffset = ((eepromReadAddr and 0x3FF) * 8 + byteIdx) and 0x1FFF
        val bit = (sram[sramOffset].toInt() ushr bitInByte) and 1
        if (dataBitIdx == 63) {
            eepromMode = 0
            eepromReadBitPos = 0
        }
        return bit
    }

    private fun writeEeprom16(value: Int) {
        if (eepromBitLen == 0) {
            when (activeEepromDmaCount) {
                9, 73 -> eepromAddrBits = 6
                17, 81 -> eepromAddrBits = 14
            }
        }
        if (eepromBitLen < eepromBitBuf.size) {
            eepromBitBuf[eepromBitLen++] = value and 1
        }
        if (eepromBitLen < 2) return
        val cmd = (eepromBitBuf[0] shl 1) or eepromBitBuf[1]
        when (cmd) {
            0b11 -> { // Read request: 2 cmd bits + addrBits + 1 trailing bit
                val totalLen = 2 + eepromAddrBits + 1
                if (eepromBitLen >= totalLen) {
                    var addr = 0
                    for (i in 0 until eepromAddrBits) {
                        addr = (addr shl 1) or eepromBitBuf[2 + i]
                    }
                    eepromReadAddr = addr
                    eepromReadBitPos = 0
                    eepromMode = 1
                    eepromBitLen = 0
                }
            }
            0b10 -> { // Write request: 2 cmd bits + addrBits + 64 data bits + 1 trailing bit
                val totalLen = 2 + eepromAddrBits + 64 + 1
                if (eepromBitLen >= totalLen) {
                    var addr = 0
                    for (i in 0 until eepromAddrBits) {
                        addr = (addr shl 1) or eepromBitBuf[2 + i]
                    }
                    val baseOffset = (addr and 0x3FF) * 8
                    val dataStart = 2 + eepromAddrBits
                    for (byteIdx in 0 until 8) {
                        var b = 0
                        for (bit in 0 until 8) {
                            b = (b shl 1) or eepromBitBuf[dataStart + byteIdx * 8 + bit]
                        }
                        sram[(baseOffset + byteIdx) and 0x1FFF] = b.toByte()
                    }
                    sramDirty = true
                    eepromMode = 0
                    eepromBitLen = 0
                }
            }
            else -> {
                // Invalid command prefix, reset bit stream
                eepromBitLen = 0
                eepromMode = 0
            }
        }
    }

    private fun readSram8(offset: Int): Int {
        if (saveType == SaveType.FLASH64 || saveType == SaveType.FLASH128) {
            if (flashIdMode) {
                return when (offset) {
                    0 -> if (saveType == SaveType.FLASH128) 0x62 else 0xBF
                    1 -> if (saveType == SaveType.FLASH128) 0x13 else 0xD4
                    else -> 0xFF
                }
            }
            return sram[(flashBank shl 16) + offset].toInt() and 0xFF
        }
        return sram[offset and 0xFFFF].toInt() and 0xFF
    }

    private fun writeSram8(offset: Int, value: Int) {
        if (saveType == SaveType.FLASH64 || saveType == SaveType.FLASH128) {
            when {
                offset == 0x5555 && value == 0xAA && flashState == 0 -> flashState = 1
                offset == 0x2AAA && value == 0x55 && flashState == 1 -> flashState = 2
                offset == 0x5555 && flashState == 2 -> {
                    when (value) {
                        0x90 -> { flashIdMode = true; flashState = 0 }
                        0xF0 -> { flashIdMode = false; flashState = 0 }
                        0x80 -> flashState = 3
                        0xA0 -> flashState = 4
                        0xB0 -> flashState = 5
                        else -> flashState = 0
                    }
                }
                flashState == 3 -> {
                    if (offset == 0x5555 && value == 0xAA) flashState = 6 else flashState = 0
                }
                flashState == 6 -> {
                    if (offset == 0x2AAA && value == 0x55) flashState = 7 else flashState = 0
                }
                flashState == 7 -> {
                    if (value == 0x10) {
                        sram.fill(0xFF.toByte())
                        sramDirty = true
                    } else if (value == 0x30) {
                        val sectorBase = (flashBank shl 16) + (offset and 0xF000)
                        for (i in 0 until 0x1000) sram[sectorBase + i] = 0xFF.toByte()
                        sramDirty = true
                    }
                    flashState = 0
                }
                flashState == 4 -> {
                    sram[(flashBank shl 16) + offset] = value.toByte()
                    sramDirty = true
                    flashState = 0
                }
                flashState == 5 && offset == 0 -> {
                    flashBank = value and 1
                    flashState = 0
                }
                else -> flashState = 0
            }
            return
        }
        sram[offset and 0xFFFF] = value.toByte()
        sramDirty = true
    }

    fun snapshotTo(out: DataOutputStream) {
        out.write(ewram)
        out.write(iwram)
        out.write(io)
        out.write(palette)
        out.write(vram)
        out.write(oam)
        out.write(sram)
        out.writeInt(flashBank)
        out.writeInt(flashState)
        out.writeBoolean(flashIdMode)
        out.writeBoolean(halted)
        out.writeInt(intrWaitMask)
        out.writeBoolean(waitingForIntr)
        out.writeInt(bg2RefX)
        out.writeInt(bg2RefY)
        out.writeInt(bg3RefX)
        out.writeInt(bg3RefY)
        for (i in 0..3) {
            out.writeInt(timerReload[i])
            out.writeInt(timerControl[i])
            out.writeInt(timerCounter[i])
            out.writeInt(timerSubCycles[i])
            out.writeInt(dmaSad[i])
            out.writeInt(dmaDad[i])
            out.writeInt(dmaCount[i])
            out.writeInt(dmaControl[i])
            out.writeInt(dmaInternalSad[i])
            out.writeInt(dmaInternalDad[i])
            out.writeInt(dmaInternalCount[i])
        }
        out.writeInt(gpioData)
        out.writeInt(gpioDirection)
        out.writeInt(gpioControl)
    }

    fun restoreFrom(inp: DataInputStream) {
        inp.readFully(ewram)
        inp.readFully(iwram)
        inp.readFully(io)
        inp.readFully(palette)
        inp.readFully(vram)
        inp.readFully(oam)
        inp.readFully(sram)
        flashBank = inp.readInt()
        flashState = inp.readInt()
        flashIdMode = inp.readBoolean()
        halted = inp.readBoolean()
        intrWaitMask = inp.readInt()
        waitingForIntr = inp.readBoolean()
        bg2RefX = inp.readInt()
        bg2RefY = inp.readInt()
        bg3RefX = inp.readInt()
        bg3RefY = inp.readInt()
        for (i in 0..3) {
            timerReload[i] = inp.readInt()
            timerControl[i] = inp.readInt()
            timerCounter[i] = inp.readInt()
            timerSubCycles[i] = inp.readInt()
            dmaSad[i] = inp.readInt()
            dmaDad[i] = inp.readInt()
            dmaCount[i] = inp.readInt()
            dmaControl[i] = inp.readInt()
            dmaInternalSad[i] = inp.readInt()
            dmaInternalDad[i] = inp.readInt()
            dmaInternalCount[i] = inp.readInt()
        }
        try {
            gpioData = inp.readInt()
            gpioDirection = inp.readInt()
            gpioControl = inp.readInt()
            updateRumbleState((gpioData and 0x08) != 0 && ((gpioDirection and 0x08) != 0 || gpioDirection == 0))
        } catch (_: java.io.EOFException) {
            gpioData = 0
            gpioDirection = 0
            gpioControl = 0
            updateRumbleState(false)
        }
        sramDirty = true
    }
}
