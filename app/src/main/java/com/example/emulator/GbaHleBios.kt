package com.example.emulator

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GbaHleBios(private val mmu: GbaMmu) {

    fun handleSwi(comment: Int, cpu: Arm7TdmiCpu) {
        when (comment and 0xFF) {
            0x00 -> { // SoftReset
                // Clear top 512 bytes of IWRAM (0x03007E00..0x03007FFF)
                for (i in 0x7E00 until 0x8000) {
                    mmu.iwram[i] = 0
                }
                val bootToEwram = mmu.read8(0x03007FFA) != 0
                cpu.softReset(bootToEwram)
            }
            0x01 -> { // RegisterRamReset
                val flags = cpu.r[0]
                if ((flags and 0x01) != 0) mmu.ewram.fill(0)
                if ((flags and 0x02) != 0) {
                    for (i in 0 until 0x7E00) mmu.iwram[i] = 0
                }
                if ((flags and 0x04) != 0) mmu.palette.fill(0)
                if ((flags and 0x08) != 0) mmu.vram.fill(0)
                if ((flags and 0x10) != 0) mmu.oam.fill(0)
                if ((flags and 0x20) != 0) {
                    for (i in 0x120 until 0x160) mmu.io[i] = 0
                }
                if ((flags and 0x40) != 0) {
                    for (i in 0x060 until 0x0AC) mmu.io[i] = 0
                    mmu.writeIo16Raw(0x088, 0x0200)
                }
                if ((flags and 0x80) != 0) {
                    for (i in 0x000 until 0x060) mmu.io[i] = 0
                    for (i in 0x0AC until 0x120) mmu.io[i] = 0
                    for (i in 0x160 until 0x20C) mmu.io[i] = 0
                    mmu.timerReload.fill(0)
                    mmu.timerControl.fill(0)
                    mmu.timerCounter.fill(0)
                    mmu.timerSubCycles.fill(0)
                    mmu.dmaControl.fill(0)
                    mmu.bg2RefX = 0
                    mmu.bg2RefY = 0
                    mmu.bg3RefX = 0
                    mmu.bg3RefY = 0
                }
                // Hardware sets DISPCNT forced blank on RegisterRamReset
                mmu.writeIo16Raw(0x000, 0x0080)
            }
            0x02, 0x03 -> { // Halt / Stop
                mmu.halted = true
                mmu.waitingForIntr = false
            }
            0x04 -> { // IntrWait
                val discardOld = cpu.r[0] != 0
                val waitMask = cpu.r[1] and 0xFFFF
                val biosIf = mmu.read16(0x03007FF8)
                if (discardOld) {
                    mmu.write16(0x03007FF8, biosIf and waitMask.inv())
                } else if ((biosIf and waitMask) != 0) {
                    mmu.write16(0x03007FF8, biosIf and waitMask.inv())
                    return
                }
                mmu.writeIo16Raw(0x208, 1) // Enable IME
                val ie = mmu.readIo16Raw(0x200)
                mmu.writeIo16Raw(0x200, ie or waitMask)
                // Ensure CPSR IRQ enable bit is clear so IRQ can fire while halted
                cpu.cpsr = cpu.cpsr and Arm7TdmiCpu.FLAG_I.inv()
                mmu.halted = true
                mmu.waitingForIntr = true
                mmu.intrWaitMask = waitMask
            }
            0x05 -> { // VBlankIntrWait (equivalent to IntrWait(1, IRQ_VBLANK))
                val biosIf = mmu.read16(0x03007FF8)
                mmu.write16(0x03007FF8, biosIf and GbaConstants.IRQ_VBLANK.inv())
                mmu.writeIo16Raw(0x208, 1)
                val ie = mmu.readIo16Raw(0x200)
                mmu.writeIo16Raw(0x200, ie or GbaConstants.IRQ_VBLANK)
                cpu.cpsr = cpu.cpsr and Arm7TdmiCpu.FLAG_I.inv()
                mmu.halted = true
                mmu.waitingForIntr = true
                mmu.intrWaitMask = GbaConstants.IRQ_VBLANK
            }
            0x06 -> { // Div (R0 / R1)
                val num = cpu.r[0]
                val den = cpu.r[1]
                if (den != 0) {
                    val q = num / den
                    val rem = num % den
                    cpu.r[0] = q
                    cpu.r[1] = rem
                    cpu.r[3] = kotlin.math.abs(q)
                }
            }
            0x07 -> { // DivArm (R1 / R0)
                val num = cpu.r[1]
                val den = cpu.r[0]
                if (den != 0) {
                    val q = num / den
                    val rem = num % den
                    cpu.r[0] = q
                    cpu.r[1] = rem
                    cpu.r[3] = kotlin.math.abs(q)
                }
            }
            0x08 -> { // Sqrt
                val v = cpu.r[0].toLong() and 0xFFFFFFFFL
                cpu.r[0] = sqrt(v.toDouble()).toInt()
            }
            0x09 -> { // ArcTan
                val theta = (cpu.r[0].toShort().toDouble()) / 16384.0
                val res = (atan(theta) / (Math.PI / 2.0) * 16384.0).toInt()
                cpu.r[0] = res
            }
            0x0A -> { // ArcTan2
                val x = cpu.r[0].toShort().toDouble()
                val y = cpu.r[1].toShort().toDouble()
                var angle = atan2(y, x) / (2.0 * Math.PI)
                if (angle < 0) angle += 1.0
                cpu.r[0] = (angle * 65536.0).toInt() and 0xFFFF
            }
            0x0B -> cpuSet(cpu.r[0], cpu.r[1], cpu.r[2], force32 = false)
            0x0C -> cpuSet(cpu.r[0], cpu.r[1], cpu.r[2], force32 = true)
            0x0D -> cpu.r[0] = 0xBAAE187F.toInt() // GetBiosChecksum (official AGB BIOS)
            0x0E -> bgAffineSet(cpu.r[0], cpu.r[1], cpu.r[2])
            0x0F -> objAffineSet(cpu.r[0], cpu.r[1], cpu.r[2], cpu.r[3])
            0x10 -> bitUnPack(cpu.r[0], cpu.r[1], cpu.r[2])
            0x11 -> lz77UnComp(cpu.r[0], cpu.r[1], stepBytes = 1)
            0x12 -> lz77UnComp(cpu.r[0], cpu.r[1], stepBytes = 2)
            0x13 -> huffUnComp(cpu.r[0], cpu.r[1])
            0x14 -> rlUnComp(cpu.r[0], cpu.r[1], stepBytes = 1)
            0x15 -> rlUnComp(cpu.r[0], cpu.r[1], stepBytes = 2)
            0x16 -> diffUnFilter8(cpu.r[0], cpu.r[1], stepBytes = 1)
            0x17 -> diffUnFilter8(cpu.r[0], cpu.r[1], stepBytes = 2)
            0x18 -> diffUnFilter16(cpu.r[0], cpu.r[1])
            0x19 -> { // SoundBias
                mmu.writeIo16Raw(0x088, if (cpu.r[0] != 0) 0x0200 else 0x0000)
            }
            0x1F -> { // MidiKey2Freq
                val wavePtr = cpu.r[0]
                val mk = cpu.r[1] and 0xFF
                val fp = cpu.r[2] and 0xFF
                val baseFreq = readUnaligned32(wavePtr + 4).toLong() and 0xFFFFFFFFL
                val semi = (180.0 - mk.toDouble()) - (fp.toDouble() / 256.0)
                val freq = baseFreq.toDouble() / 2.0.pow(semi / 12.0)
                cpu.r[0] = freq.toLong().toInt()
            }
        }
    }

    private fun readUnaligned32(addr: Int): Int {
        return (mmu.read8(addr) and 0xFF) or
            ((mmu.read8(addr + 1) and 0xFF) shl 8) or
            ((mmu.read8(addr + 2) and 0xFF) shl 16) or
            ((mmu.read8(addr + 3) and 0xFF) shl 24)
    }

    private fun cpuSet(srcAddr: Int, dstAddr: Int, ctrl: Int, force32: Boolean) {
        val count = ctrl and 0x1FFFFF
        val fixedSrc = (ctrl and (1 shl 24)) != 0
        val use32 = force32 || (ctrl and (1 shl 26)) != 0
        var src = srcAddr
        var dst = dstAddr
        if (use32) {
            val fill = mmu.read32(src)
            for (i in 0 until count) {
                val value = if (fixedSrc) fill else mmu.read32(src)
                mmu.write32(dst, value)
                if (!fixedSrc) src += 4
                dst += 4
            }
        } else {
            val fill = mmu.read16(src)
            for (i in 0 until count) {
                val value = if (fixedSrc) fill else mmu.read16(src)
                mmu.write16(dst, value)
                if (!fixedSrc) src += 2
                dst += 2
            }
        }
    }

    private fun bgAffineSet(srcAddr: Int, dstAddr: Int, count: Int) {
        var src = srcAddr
        var dst = dstAddr
        for (i in 0 until count) {
            val bgX = mmu.read32(src)
            val bgY = mmu.read32(src + 4)
            val dispX = mmu.read16(src + 8).toShort().toInt()
            val dispY = mmu.read16(src + 10).toShort().toInt()
            val sx = mmu.read16(src + 12).toShort().toDouble() / 256.0
            val sy = mmu.read16(src + 14).toShort().toDouble() / 256.0
            val angle = (mmu.read16(src + 16) and 0xFFFF) / 65536.0 * (2.0 * Math.PI)
            val cosA = cos(angle)
            val sinA = sin(angle)
            val pa = (cosA * sx * 256.0).toInt()
            val pb = (-sinA * sx * 256.0).toInt()
            val pc = (sinA * sy * 256.0).toInt()
            val pd = (cosA * sy * 256.0).toInt()
            val startX = bgX - (pa * dispX + pb * dispY)
            val startY = bgY - (pc * dispX + pd * dispY)

            mmu.write16(dst, pa)
            mmu.write16(dst + 2, pb)
            mmu.write16(dst + 4, pc)
            mmu.write16(dst + 6, pd)
            mmu.write32(dst + 8, startX)
            mmu.write32(dst + 12, startY)
            src += 20
            dst += 16
        }
    }

    private fun objAffineSet(srcAddr: Int, dstAddr: Int, count: Int, offset: Int) {
        var src = srcAddr
        var dst = dstAddr
        for (i in 0 until count) {
            val sx = mmu.read16(src).toShort().toDouble() / 256.0
            val sy = mmu.read16(src + 2).toShort().toDouble() / 256.0
            val angle = (mmu.read16(src + 4) and 0xFFFF) / 65536.0 * (2.0 * Math.PI)
            val cosA = cos(angle)
            val sinA = sin(angle)
            mmu.write16(dst, (cosA * sx * 256.0).toInt())
            mmu.write16(dst + offset, (-sinA * sx * 256.0).toInt())
            mmu.write16(dst + offset * 2, (sinA * sy * 256.0).toInt())
            mmu.write16(dst + offset * 3, (cosA * sy * 256.0).toInt())
            src += 8
            dst += offset * 4
        }
    }

    private fun bitUnPack(srcAddr: Int, dstAddr: Int, infoPtr: Int) {
        val srcLen = mmu.read16(infoPtr) and 0xFFFF
        val srcBpp = mmu.read8(infoPtr + 2).coerceIn(1, 8)
        val dstBpp = mmu.read8(infoPtr + 3).coerceIn(1, 32)
        val dataOffsetField = readUnaligned32(infoPtr + 4)
        val dataOffset = dataOffsetField and 0x7FFFFFFF
        val zeroDataFlag = (dataOffsetField and Int.MIN_VALUE) != 0

        val srcMask = (1 shl srcBpp) - 1
        var outWord = 0
        var outBits = 0
        var src = srcAddr
        var dst = dstAddr

        for (i in 0 until srcLen) {
            val byteVal = mmu.read8(src++)
            var bitPos = 0
            while (bitPos < 8) {
                var unit = (byteVal ushr bitPos) and srcMask
                if (unit != 0 || zeroDataFlag) {
                    unit += dataOffset
                }
                outWord = outWord or (unit shl outBits)
                outBits += dstBpp
                if (outBits >= 32) {
                    mmu.write32(dst, outWord)
                    dst += 4
                    outWord = 0
                    outBits = 0
                }
                bitPos += srcBpp
            }
        }
        if (outBits > 0) {
            mmu.write32(dst, outWord)
        }
    }

    private fun lz77UnComp(srcAddr: Int, dstAddr: Int, stepBytes: Int) {
        val header = readUnaligned32(srcAddr)
        val outSize = ((header ushr 8) and 0xFFFFFF).coerceAtMost(0x40000)
        var src = srcAddr + 4
        val buffer = ByteArray(outSize)
        var outIdx = 0
        while (outIdx < buffer.size) {
            val flags = mmu.read8(src++)
            for (bit in 7 downTo 0) {
                if (outIdx >= buffer.size) break
                if ((flags and (1 shl bit)) == 0) {
                    buffer[outIdx++] = mmu.read8(src++).toByte()
                } else {
                    val b1 = mmu.read8(src++)
                    val b2 = mmu.read8(src++)
                    val len = ((b1 ushr 4) and 0x0F) + 3
                    val disp = (((b1 and 0x0F) shl 8) or b2) + 1
                    for (k in 0 until len) {
                        if (outIdx >= buffer.size) break
                        val ref = (outIdx - disp).coerceAtLeast(0)
                        buffer[outIdx++] = buffer[ref]
                    }
                }
            }
        }
        writeDecompressedBuffer(dstAddr, buffer, stepBytes)
    }

    private fun huffUnComp(srcAddr: Int, dstAddr: Int) {
        val header = readUnaligned32(srcAddr)
        val bitWidth = (header and 0x0F).let { if (it == 8) 8 else 4 }
        val outSize = ((header ushr 8) and 0xFFFFFF).coerceAtMost(0x40000)
        val treeSizeBytes = ((mmu.read8(srcAddr + 4) and 0xFF) + 1) * 2
        val treeBase = srcAddr + 5
        var src = srcAddr + 4 + treeSizeBytes
        src = (src + 3) and 0xFFFFFFFC.toInt()

        var nodeOffset = 0
        var bitStream = 0
        var bitsLeft = 0
        var outWord = 0
        var outBits = 0
        var outBytes = 0
        var dst = dstAddr

        while (outBytes < outSize) {
            if (bitsLeft == 0) {
                bitStream = mmu.read32(src)
                src += 4
                bitsLeft = 32
            }
            val dir = (bitStream ushr 31) and 1
            bitStream = bitStream shl 1
            bitsLeft--

            val nodeVal = mmu.read8(treeBase + nodeOffset)
            val isLeaf = if (dir == 0) (nodeVal and 0x80) != 0 else (nodeVal and 0x40) != 0
            val nextNodeOffset = (nodeOffset and 0xFFFFFFFE.toInt()) + ((nodeVal and 0x3F) + 1) * 2 + dir
            if (isLeaf) {
                val data = mmu.read8(treeBase + nextNodeOffset)
                val mask = if (bitWidth == 8) 0xFF else 0x0F
                outWord = outWord or ((data and mask) shl outBits)
                outBits += bitWidth
                if (outBits >= 32) {
                    mmu.write32(dst, outWord)
                    dst += 4
                    outBytes += 4
                    outWord = 0
                    outBits = 0
                }
                nodeOffset = 0
            } else {
                nodeOffset = nextNodeOffset
            }
        }
        if (outBits > 0) {
            mmu.write32(dst, outWord)
        }
    }

    private fun rlUnComp(srcAddr: Int, dstAddr: Int, stepBytes: Int) {
        val header = readUnaligned32(srcAddr)
        val outSize = ((header ushr 8) and 0xFFFFFF).coerceAtMost(0x40000)
        var src = srcAddr + 4
        val buffer = ByteArray(outSize)
        var outIdx = 0
        while (outIdx < buffer.size) {
            val flag = mmu.read8(src++)
            if ((flag and 0x80) != 0) {
                val len = (flag and 0x7F) + 3
                val value = mmu.read8(src++).toByte()
                for (i in 0 until len) {
                    if (outIdx < buffer.size) buffer[outIdx++] = value
                }
            } else {
                val len = (flag and 0x7F) + 1
                for (i in 0 until len) {
                    if (outIdx < buffer.size) buffer[outIdx++] = mmu.read8(src++).toByte()
                }
            }
        }
        writeDecompressedBuffer(dstAddr, buffer, stepBytes)
    }

    private fun diffUnFilter8(srcAddr: Int, dstAddr: Int, stepBytes: Int) {
        val header = readUnaligned32(srcAddr)
        val outSize = ((header ushr 8) and 0xFFFFFF).coerceAtMost(0x40000)
        val buffer = ByteArray(outSize)
        var src = srcAddr + 4
        var acc = 0
        for (i in 0 until outSize) {
            acc = (acc + mmu.read8(src++)) and 0xFF
            buffer[i] = acc.toByte()
        }
        writeDecompressedBuffer(dstAddr, buffer, stepBytes)
    }

    private fun diffUnFilter16(srcAddr: Int, dstAddr: Int) {
        val header = readUnaligned32(srcAddr)
        val outSize = ((header ushr 8) and 0xFFFFFF).coerceAtMost(0x40000)
        var src = srcAddr + 4
        var dst = dstAddr
        var acc = 0
        var i = 0
        while (i + 1 < outSize) {
            val diff = (mmu.read8(src) and 0xFF) or ((mmu.read8(src + 1) and 0xFF) shl 8)
            src += 2
            acc = (acc + diff) and 0xFFFF
            mmu.write16(dst, acc)
            dst += 2
            i += 2
        }
    }

    private fun writeDecompressedBuffer(dstAddr: Int, buffer: ByteArray, stepBytes: Int) {
        if (stepBytes == 2) {
            var i = 0
            while (i + 1 < buffer.size) {
                val hw = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
                mmu.write16(dstAddr + i, hw)
                i += 2
            }
        } else {
            for (i in buffer.indices) {
                mmu.write8(dstAddr + i, buffer[i].toInt() and 0xFF)
            }
        }
    }
}
