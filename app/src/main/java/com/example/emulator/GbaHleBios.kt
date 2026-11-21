package com.example.emulator

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GbaHleBios(private val mmu: GbaMmu) {

    fun handleSwi(comment: Int, cpu: Arm7TdmiCpu) {
        when (comment and 0xFF) {
            0x00 -> { // SoftReset
                cpu.resetToRomEntry()
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
            }
            0x02 -> { // Halt
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
                mmu.halted = true
                mmu.waitingForIntr = true
                mmu.intrWaitMask = waitMask
            }
            0x05 -> { // VBlankIntrWait
                val biosIf = mmu.read16(0x03007FF8)
                if ((biosIf and GbaConstants.IRQ_VBLANK) != 0) {
                    mmu.write16(0x03007FF8, biosIf and GbaConstants.IRQ_VBLANK.inv())
                    return
                }
                mmu.writeIo16Raw(0x208, 1)
                val ie = mmu.readIo16Raw(0x200)
                mmu.writeIo16Raw(0x200, ie or GbaConstants.IRQ_VBLANK)
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
            0x0E -> bgAffineSet(cpu.r[0], cpu.r[1], cpu.r[2])
            0x0F -> objAffineSet(cpu.r[0], cpu.r[1], cpu.r[2], cpu.r[3])
            0x11 -> lz77UnComp(cpu.r[0], cpu.r[1], stepBytes = 1)
            0x12 -> lz77UnComp(cpu.r[0], cpu.r[1], stepBytes = 2)
            0x14 -> rlUnComp(cpu.r[0], cpu.r[1], stepBytes = 1)
            0x15 -> rlUnComp(cpu.r[0], cpu.r[1], stepBytes = 2)
            0x19 -> { // SoundBias
                mmu.writeIo16Raw(0x088, if (cpu.r[0] != 0) 0x0200 else 0x0000)
            }
        }
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

    private fun lz77UnComp(srcAddr: Int, dstAddr: Int, stepBytes: Int) {
        val header = mmu.read32(srcAddr)
        var outSize = (header ushr 8) and 0xFFFFFF
        var src = srcAddr + 4
        var dst = dstAddr
        val buffer = ByteArray(outSize.coerceAtMost(0x40000))
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
        if (stepBytes == 2) {
            var i = 0
            while (i + 1 < buffer.size) {
                val hw = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
                mmu.write16(dst + i, hw)
                i += 2
            }
        } else {
            for (i in buffer.indices) {
                mmu.write8(dst + i, buffer[i].toInt() and 0xFF)
            }
        }
    }

    private fun rlUnComp(srcAddr: Int, dstAddr: Int, stepBytes: Int) {
        val header = mmu.read32(srcAddr)
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
