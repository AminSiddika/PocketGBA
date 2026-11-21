package com.example.emulator

import java.io.DataInputStream
import java.io.DataOutputStream

class Arm7TdmiCpu(
    val mmu: GbaMmu,
    val bios: GbaHleBios
) {
    companion object {
        const val MODE_USR = 0x10
        const val MODE_FIQ = 0x11
        const val MODE_IRQ = 0x12
        const val MODE_SVC = 0x13
        const val MODE_ABT = 0x17
        const val MODE_UND = 0x1B
        const val MODE_SYS = 0x1F

        const val FLAG_N = 1 shl 31
        const val FLAG_Z = 1 shl 30
        const val FLAG_C = 1 shl 29
        const val FLAG_V = 1 shl 28
        const val FLAG_I = 1 shl 7
        const val FLAG_F = 1 shl 6
        const val FLAG_T = 1 shl 5
    }

    val r = IntArray(16)
    var cpsr = MODE_SYS

    // Banked registers
    private val usrBank8_14 = IntArray(7) // R8..R14
    private val fiqBank8_14 = IntArray(7)
    private val irqBank13_14 = IntArray(2)
    private val svcBank13_14 = IntArray(2)
    private val abtBank13_14 = IntArray(2)
    private val undBank13_14 = IntArray(2)

    // SPSR per privileged mode: 0=FIQ, 1=IRQ, 2=SVC, 3=ABT, 4=UND
    private val spsr = IntArray(5)

    var totalCycles: Long = 0L
    private var shifterCarry = false

    fun resetToRomEntry(skipBiosIntro: Boolean = true) {
        r.fill(0)
        usrBank8_14.fill(0)
        fiqBank8_14.fill(0)
        irqBank13_14.fill(0)
        svcBank13_14.fill(0)
        abtBank13_14.fill(0)
        undBank13_14.fill(0)
        spsr.fill(0)

        // Post-BIOS initial stack pointers and SYS mode
        irqBank13_14[0] = 0x03007FA0
        svcBank13_14[0] = 0x03007FE0
        cpsr = MODE_SYS
        r[13] = 0x03007F00
        r[15] = if (mmu.hasExternalBios && !skipBiosIntro) 0x00000000 else 0x08000000
        totalCycles = 0L
    }

    val isThumb: Boolean get() = (cpsr and FLAG_T) != 0

    fun currentModeName(): String = when (cpsr and 0x1F) {
        MODE_USR -> "USR"
        MODE_FIQ -> "FIQ"
        MODE_IRQ -> "IRQ"
        MODE_SVC -> "SVC"
        MODE_ABT -> "ABT"
        MODE_UND -> "UND"
        MODE_SYS -> "SYS"
        else -> "UNK"
    }

    fun checkIrq() {
        if ((cpsr and FLAG_I) != 0) return
        if (!mmu.hasPendingIrq()) return

        val returnAddr = r[15] + 4
        val oldCpsr = cpsr
        switchMode(MODE_IRQ)
        spsr[1] = oldCpsr
        r[14] = returnAddr
        cpsr = (cpsr and FLAG_T.inv()) or FLAG_I
        r[15] = 0x00000018
        mmu.halted = false
    }

    fun step(): Int {
        if (mmu.halted) {
            return 16
        }
        checkIrq()
        val cycles = if ((cpsr and FLAG_T) != 0) {
            val op = mmu.read16(r[15])
            r[15] += 2
            ThumbExecutor.execute(this, op)
        } else {
            val op = mmu.read32(r[15])
            r[15] += 4
            executeArm(op)
        }
        totalCycles += cycles
        return cycles
    }

    fun switchMode(newModeRaw: Int) {
        val oldMode = cpsr and 0x1F
        val newMode = newModeRaw and 0x1F
        if (oldMode == newMode) {
            cpsr = (cpsr and 0x1F.inv()) or newMode
            return
        }
        // Save current banked registers
        if (oldMode == MODE_FIQ) {
            for (i in 0..6) fiqBank8_14[i] = r[8 + i]
        } else {
            for (i in 0..4) usrBank8_14[i] = r[8 + i]
            when (oldMode) {
                MODE_USR, MODE_SYS -> { usrBank8_14[5] = r[13]; usrBank8_14[6] = r[14] }
                MODE_IRQ -> { irqBank13_14[0] = r[13]; irqBank13_14[1] = r[14] }
                MODE_SVC -> { svcBank13_14[0] = r[13]; svcBank13_14[1] = r[14] }
                MODE_ABT -> { abtBank13_14[0] = r[13]; abtBank13_14[1] = r[14] }
                MODE_UND -> { undBank13_14[0] = r[13]; undBank13_14[1] = r[14] }
            }
        }
        // Restore new mode banked registers
        if (newMode == MODE_FIQ) {
            for (i in 0..6) r[8 + i] = fiqBank8_14[i]
        } else {
            for (i in 0..4) r[8 + i] = usrBank8_14[i]
            when (newMode) {
                MODE_USR, MODE_SYS -> { r[13] = usrBank8_14[5]; r[14] = usrBank8_14[6] }
                MODE_IRQ -> { r[13] = irqBank13_14[0]; r[14] = irqBank13_14[1] }
                MODE_SVC -> { r[13] = svcBank13_14[0]; r[14] = svcBank13_14[1] }
                MODE_ABT -> { r[13] = abtBank13_14[0]; r[14] = abtBank13_14[1] }
                MODE_UND -> { r[13] = undBank13_14[0]; r[14] = undBank13_14[1] }
            }
        }
        cpsr = (cpsr and 0x1F.inv()) or newMode
    }

    fun getSpsr(): Int = when (cpsr and 0x1F) {
        MODE_FIQ -> spsr[0]
        MODE_IRQ -> spsr[1]
        MODE_SVC -> spsr[2]
        MODE_ABT -> spsr[3]
        MODE_UND -> spsr[4]
        else -> cpsr
    }

    fun setSpsr(value: Int) {
        when (cpsr and 0x1F) {
            MODE_FIQ -> spsr[0] = value
            MODE_IRQ -> spsr[1] = value
            MODE_SVC -> spsr[2] = value
            MODE_ABT -> spsr[3] = value
            MODE_UND -> spsr[4] = value
        }
    }

    fun setCpsrWithMode(newCpsr: Int) {
        val oldMode = cpsr and 0x1F
        val newMode = newCpsr and 0x1F
        if (oldMode != newMode) switchMode(newMode)
        cpsr = newCpsr
    }

    fun checkCondition(cond: Int): Boolean {
        val n = (cpsr and FLAG_N) != 0
        val z = (cpsr and FLAG_Z) != 0
        val c = (cpsr and FLAG_C) != 0
        val v = (cpsr and FLAG_V) != 0
        return when (cond) {
            0x0 -> z
            0x1 -> !z
            0x2 -> c
            0x3 -> !c
            0x4 -> n
            0x5 -> !n
            0x6 -> v
            0x7 -> !v
            0x8 -> c && !z
            0x9 -> !c || z
            0xA -> n == v
            0xB -> n != v
            0xC -> !z && (n == v)
            0xD -> z || (n != v)
            0xE -> true
            else -> false
        }
    }

    fun setNZ(res: Int) {
        cpsr = cpsr and (FLAG_N or FLAG_Z).inv()
        if (res < 0) cpsr = cpsr or FLAG_N
        if (res == 0) cpsr = cpsr or FLAG_Z
    }

    fun setNZC(res: Int, carry: Boolean) {
        setNZ(res)
        cpsr = if (carry) cpsr or FLAG_C else cpsr and FLAG_C.inv()
    }

    fun addWithFlags(a: Int, b: Int, updateFlags: Boolean): Int {
        val res = a + b
        if (updateFlags) {
            setNZ(res)
            val ua = a.toLong() and 0xFFFFFFFFL
            val ub = b.toLong() and 0xFFFFFFFFL
            val carry = (ua + ub) > 0xFFFFFFFFL
            val overflow = ((a xor res) and (b xor res)) < 0
            cpsr = if (carry) cpsr or FLAG_C else cpsr and FLAG_C.inv()
            cpsr = if (overflow) cpsr or FLAG_V else cpsr and FLAG_V.inv()
        }
        return res
    }

    fun subWithFlags(a: Int, b: Int, updateFlags: Boolean): Int {
        val res = a - b
        if (updateFlags) {
            setNZ(res)
            val ua = a.toLong() and 0xFFFFFFFFL
            val ub = b.toLong() and 0xFFFFFFFFL
            val carry = ua >= ub
            val overflow = ((a xor b) and (a xor res)) < 0
            cpsr = if (carry) cpsr or FLAG_C else cpsr and FLAG_C.inv()
            cpsr = if (overflow) cpsr or FLAG_V else cpsr and FLAG_V.inv()
        }
        return res
    }

    private fun executeArm(op: Int): Int {
        val cond = (op ushr 28) and 0x0F
        if (cond != 0x0E && !checkCondition(cond)) return 1

        val bits27_25 = (op ushr 25) and 7
        when (bits27_25) {
            0 -> {
                // Branch and Exchange (BX)
                if ((op and 0x0FFFFFF0) == 0x012FFF10) {
                    val rm = op and 0xF
                    val target = if (rm == 15) r[15] + 4 else r[rm]
                    if ((target and 1) != 0) {
                        cpsr = cpsr or FLAG_T
                        r[15] = target and 0xFFFFFFFE.toInt()
                    } else {
                        cpsr = cpsr and FLAG_T.inv()
                        r[15] = target and 0xFFFFFFFC.toInt()
                    }
                    return 3
                }
                // Multiply / Multiply Long
                if ((op and 0x0FC000F0) == 0x00000090) return execArmMultiply(op)
                if ((op and 0x0F8000F0) == 0x00800090) return execArmMultiplyLong(op)
                // Swap (SWP/SWPB)
                if ((op and 0x0FB00FF0) == 0x01000090) return execArmSwap(op)
                // Halfword & Signed Transfer
                if ((op and 0x0E000090) == 0x00000090 && ((op ushr 4) and 0xF) != 9) {
                    return execArmHalfwordTransfer(op)
                }
                // PSR Transfer (MRS / MSR)
                if ((op and 0x0FBF0FFF) == 0x010F0000 || (op and 0x0FB0FFF0) == 0x0120F000) {
                    return execArmPsr(op)
                }
                return execArmDataProc(op)
            }
            1 -> {
                if ((op and 0x0FB0F000) == 0x0320F000) return execArmPsr(op)
                return execArmDataProc(op)
            }
            2, 3 -> return execArmSingleTransfer(op)
            4 -> return execArmBlockTransfer(op)
            5 -> {
                var offset = op and 0x00FFFFFF
                if ((offset and 0x00800000) != 0) offset = offset or 0xFF000000.toInt()
                if ((op and (1 shl 24)) != 0) r[14] = r[15]
                r[15] = (r[15] + 4 + (offset shl 2)) and 0xFFFFFFFC.toInt()
                return 3
            }
            7 -> {
                if ((op and 0x0F000000) == 0x0F000000) {
                    bios.handleSwi(op and 0x00FFFFFF, this)
                    return 3
                }
            }
        }
        return 1
    }

    private fun execArmDataProc(op: Int): Int {
        val imm = (op and (1 shl 25)) != 0
        val opcode = (op ushr 21) and 0x0F
        val setFlags = (op and (1 shl 20)) != 0
        val rnIdx = (op ushr 16) and 0x0F
        val rdIdx = (op ushr 12) and 0x0F

        val op2 = if (imm) {
            val imm8 = op and 0xFF
            val rot = ((op ushr 8) and 0x0F) * 2
            shifterCarry = (cpsr and FLAG_C) != 0
            if (rot == 0) imm8 else {
                val res = Integer.rotateRight(imm8, rot)
                shifterCarry = (res and Int.MIN_VALUE) != 0
                res
            }
        } else {
            val rmIdx = op and 0x0F
            var rmVal = if (rmIdx == 15) r[15] + 4 else r[rmIdx]
            val shiftType = (op ushr 5) and 3
            val byReg = (op and (1 shl 4)) != 0
            val amount = if (byReg) {
                val rs = (op ushr 8) and 0x0F
                if (rmIdx == 15) rmVal += 4
                r[rs] and 0xFF
            } else {
                (op ushr 7) and 0x1F
            }
            barrelShift(rmVal, shiftType, amount, !byReg)
        }

        val rn = if (rnIdx == 15) {
            if (!imm && (op and (1 shl 4)) != 0) r[15] + 8 else r[15] + 4
        } else r[rnIdx]

        var writeRd = true
        var result = 0
        when (opcode) {
            0x0 -> { result = rn and op2; if (setFlags) setNZC(result, shifterCarry) }
            0x1 -> { result = rn xor op2; if (setFlags) setNZC(result, shifterCarry) }
            0x2 -> result = subWithFlags(rn, op2, setFlags)
            0x3 -> result = subWithFlags(op2, rn, setFlags)
            0x4 -> result = addWithFlags(rn, op2, setFlags)
            0x5 -> {
                val carry = if ((cpsr and FLAG_C) != 0) 1L else 0L
                val sum = (rn.toLong() and 0xFFFFFFFFL) + (op2.toLong() and 0xFFFFFFFFL) + carry
                result = sum.toInt()
                if (setFlags) {
                    setNZ(result)
                    cpsr = if (sum > 0xFFFFFFFFL) cpsr or FLAG_C else cpsr and FLAG_C.inv()
                    val ov = ((rn xor result) and (op2 xor result)) < 0
                    cpsr = if (ov) cpsr or FLAG_V else cpsr and FLAG_V.inv()
                }
            }
            0x6 -> {
                val borrow = if ((cpsr and FLAG_C) != 0) 0L else 1L
                val diff = (rn.toLong() and 0xFFFFFFFFL) - (op2.toLong() and 0xFFFFFFFFL) - borrow
                result = diff.toInt()
                if (setFlags) {
                    setNZ(result)
                    cpsr = if (diff >= 0L) cpsr or FLAG_C else cpsr and FLAG_C.inv()
                    val ov = ((rn xor op2) and (rn xor result)) < 0
                    cpsr = if (ov) cpsr or FLAG_V else cpsr and FLAG_V.inv()
                }
            }
            0x7 -> {
                val borrow = if ((cpsr and FLAG_C) != 0) 0L else 1L
                val diff = (op2.toLong() and 0xFFFFFFFFL) - (rn.toLong() and 0xFFFFFFFFL) - borrow
                result = diff.toInt()
                if (setFlags) {
                    setNZ(result)
                    cpsr = if (diff >= 0L) cpsr or FLAG_C else cpsr and FLAG_C.inv()
                    val ov = ((op2 xor rn) and (op2 xor result)) < 0
                    cpsr = if (ov) cpsr or FLAG_V else cpsr and FLAG_V.inv()
                }
            }
            0x8 -> { writeRd = false; setNZC(rn and op2, shifterCarry) }
            0x9 -> { writeRd = false; setNZC(rn xor op2, shifterCarry) }
            0xA -> { writeRd = false; subWithFlags(rn, op2, true) }
            0xB -> { writeRd = false; addWithFlags(rn, op2, true) }
            0xC -> { result = rn or op2; if (setFlags) setNZC(result, shifterCarry) }
            0xD -> { result = op2; if (setFlags) setNZC(result, shifterCarry) }
            0xE -> { result = rn and op2.inv(); if (setFlags) setNZC(result, shifterCarry) }
            0xF -> { result = op2.inv(); if (setFlags) setNZC(result, shifterCarry) }
        }

        if (writeRd) {
            if (rdIdx == 15) {
                if (setFlags) {
                    setCpsrWithMode(getSpsr())
                }
                r[15] = if ((cpsr and FLAG_T) != 0) (result and 0xFFFFFFFE.toInt()) else (result and 0xFFFFFFFC.toInt())
                return 3
            } else {
                r[rdIdx] = result
            }
        }
        return 1
    }

    private fun barrelShift(value: Int, type: Int, amount: Int, immShift: Boolean): Int {
        shifterCarry = (cpsr and FLAG_C) != 0
        if (amount == 0) {
            if (!immShift) return value
            return when (type) {
                0 -> value
                1 -> { shifterCarry = (value and Int.MIN_VALUE) != 0; 0 }
                2 -> { shifterCarry = (value and Int.MIN_VALUE) != 0; if (shifterCarry) -1 else 0 }
                else -> {
                    val oldC = if (shifterCarry) Int.MIN_VALUE else 0
                    shifterCarry = (value and 1) != 0
                    (value ushr 1) or oldC
                }
            }
        }
        return when (type) {
            0 -> if (amount < 32) {
                shifterCarry = ((value ushr (32 - amount)) and 1) != 0
                value shl amount
            } else if (amount == 32) {
                shifterCarry = (value and 1) != 0; 0
            } else {
                shifterCarry = false; 0
            }
            1 -> if (amount < 32) {
                shifterCarry = ((value ushr (amount - 1)) and 1) != 0
                value ushr amount
            } else if (amount == 32) {
                shifterCarry = (value and Int.MIN_VALUE) != 0; 0
            } else {
                shifterCarry = false; 0
            }
            2 -> if (amount < 32) {
                shifterCarry = ((value ushr (amount - 1)) and 1) != 0
                value shr amount
            } else {
                shifterCarry = (value and Int.MIN_VALUE) != 0
                if (shifterCarry) -1 else 0
            }
            else -> {
                val rot = amount and 31
                val res = if (rot == 0) value else Integer.rotateRight(value, rot)
                shifterCarry = (res and Int.MIN_VALUE) != 0
                res
            }
        }
    }

    private fun execArmMultiply(op: Int): Int {
        val acc = (op and (1 shl 21)) != 0
        val setFlags = (op and (1 shl 20)) != 0
        val rd = (op ushr 16) and 0xF
        val rn = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        var res = r[rm] * r[rs]
        if (acc) res += r[rn]
        r[rd] = res
        if (setFlags) setNZ(res)
        return 2
    }

    private fun execArmMultiplyLong(op: Int): Int {
        val signed = (op and (1 shl 22)) != 0
        val acc = (op and (1 shl 21)) != 0
        val setFlags = (op and (1 shl 20)) != 0
        val rdHi = (op ushr 16) and 0xF
        val rdLo = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        var prod = if (signed) r[rm].toLong() * r[rs].toLong()
        else (r[rm].toLong() and 0xFFFFFFFFL) * (r[rs].toLong() and 0xFFFFFFFFL)
        if (acc) {
            val existing = ((r[rdHi].toLong() and 0xFFFFFFFFL) shl 32) or (r[rdLo].toLong() and 0xFFFFFFFFL)
            prod += existing
        }
        r[rdLo] = prod.toInt()
        r[rdHi] = (prod ushr 32).toInt()
        if (setFlags) {
            cpsr = cpsr and (FLAG_N or FLAG_Z).inv()
            if (prod < 0L) cpsr = cpsr or FLAG_N
            if (prod == 0L) cpsr = cpsr or FLAG_Z
        }
        return 3
    }

    private fun execArmSwap(op: Int): Int {
        val byteOp = (op and (1 shl 22)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val rm = op and 0xF
        val addr = r[rn]
        if (byteOp) {
            val old = mmu.read8(addr)
            mmu.write8(addr, r[rm])
            r[rd] = old
        } else {
            val old = Integer.rotateRight(mmu.read32(addr), (addr and 3) * 8)
            mmu.write32(addr, r[rm])
            r[rd] = old
        }
        return 3
    }

    private fun execArmPsr(op: Int): Int {
        val isMsr = (op and (1 shl 21)) != 0
        val useSpsr = (op and (1 shl 22)) != 0
        if (!isMsr) {
            val rd = (op ushr 12) and 0xF
            r[rd] = if (useSpsr) getSpsr() else cpsr
        } else {
            val value = if ((op and (1 shl 25)) != 0) {
                val imm8 = op and 0xFF
                val rot = ((op ushr 8) and 0xF) * 2
                Integer.rotateRight(imm8, rot)
            } else {
                r[op and 0xF]
            }
            var mask = 0
            if ((op and (1 shl 19)) != 0) mask = mask or 0xFF000000.toInt()
            if ((op and (1 shl 16)) != 0 && (cpsr and 0x1F) != MODE_USR) mask = mask or 0x000000FF
            if (useSpsr) {
                setSpsr((getSpsr() and mask.inv()) or (value and mask))
            } else {
                setCpsrWithMode((cpsr and mask.inv()) or (value and mask))
            }
        }
        return 1
    }

    private fun execArmSingleTransfer(op: Int): Int {
        val regOffset = (op and (1 shl 25)) != 0
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val byteOp = (op and (1 shl 22)) != 0
        val writeback = (op and (1 shl 21)) != 0
        val load = (op and (1 shl 20)) != 0
        val rnIdx = (op ushr 16) and 0xF
        val rdIdx = (op ushr 12) and 0xF

        val offset = if (!regOffset) {
            op and 0xFFF
        } else {
            val rm = op and 0xF
            val shiftType = (op ushr 5) and 3
            val amount = (op ushr 7) and 0x1F
            barrelShift(r[rm], shiftType, amount, true)
        }
        val base = if (rnIdx == 15) r[15] + 4 else r[rnIdx]
        val delta = if (up) offset else -offset
        val addr = if (pre) base + delta else base

        if (load) {
            val value = if (byteOp) mmu.read8(addr)
            else Integer.rotateRight(mmu.read32(addr), (addr and 3) * 8)
            if (rdIdx == 15) {
                r[15] = value and 0xFFFFFFFC.toInt()
            } else {
                r[rdIdx] = value
            }
        } else {
            val valToStore = if (rdIdx == 15) r[15] + 8 else r[rdIdx]
            if (byteOp) mmu.write8(addr, valToStore)
            else mmu.write32(addr, valToStore)
        }

        if ((!pre || writeback) && rnIdx != 15 && (!load || rdIdx != rnIdx)) {
            r[rnIdx] = base + delta
        }
        return if (load) 3 else 2
    }

    private fun execArmHalfwordTransfer(op: Int): Int {
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val imm = (op and (1 shl 22)) != 0
        val writeback = (op and (1 shl 21)) != 0
        val load = (op and (1 shl 20)) != 0
        val rnIdx = (op ushr 16) and 0xF
        val rdIdx = (op ushr 12) and 0xF
        val sh = (op ushr 5) and 3

        val offset = if (imm) (((op ushr 4) and 0xF0) or (op and 0xF)) else r[op and 0xF]
        val base = if (rnIdx == 15) r[15] + 4 else r[rnIdx]
        val delta = if (up) offset else -offset
        val addr = if (pre) base + delta else base

        if (load) {
            val value = when (sh) {
                1 -> mmu.read16(addr)
                2 -> mmu.read8(addr).toByte().toInt()
                else -> if ((addr and 1) == 0) mmu.read16(addr).toShort().toInt() else mmu.read8(addr).toByte().toInt()
            }
            if (rdIdx == 15) r[15] = value and 0xFFFFFFFC.toInt() else r[rdIdx] = value
        } else {
            val valToStore = if (rdIdx == 15) r[15] + 8 else r[rdIdx]
            mmu.write16(addr, valToStore)
        }
        if ((!pre || writeback) && rnIdx != 15 && (!load || rdIdx != rnIdx)) {
            r[rnIdx] = base + delta
        }
        return if (load) 3 else 2
    }

    private fun execArmBlockTransfer(op: Int): Int {
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val sBit = (op and (1 shl 22)) != 0
        val writeback = (op and (1 shl 21)) != 0
        val load = (op and (1 shl 20)) != 0
        val rnIdx = (op ushr 16) and 0xF
        val rlist = op and 0xFFFF

        val count = Integer.bitCount(rlist)
        val base = r[rnIdx]
        var addr = if (up) {
            if (pre) base + 4 else base
        } else {
            if (pre) base - count * 4 else base - count * 4 + 4
        }
        val finalBase = if (up) base + count * 4 else base - count * 4

        val savedMode = cpsr and 0x1F
        val userBank = sBit && (!load || (rlist and 0x8000) == 0)
        if (userBank) switchMode(MODE_USR)

        for (i in 0..15) {
            if ((rlist and (1 shl i)) != 0) {
                if (load) {
                    val v = mmu.read32(addr)
                    if (i == 15) {
                        if (sBit) setCpsrWithMode(getSpsr())
                        r[15] = v and 0xFFFFFFFC.toInt()
                    } else {
                        r[i] = v
                    }
                } else {
                    val v = if (i == 15) r[15] + 8 else r[i]
                    mmu.write32(addr, v)
                }
                addr += 4
            }
        }
        if (userBank) switchMode(savedMode)
        if (writeback && (!load || (rlist and (1 shl rnIdx)) == 0)) {
            r[rnIdx] = finalBase
        }
        return count + 2
    }

    fun snapshotTo(out: DataOutputStream) {
        for (v in r) out.writeInt(v)
        out.writeInt(cpsr)
        for (v in usrBank8_14) out.writeInt(v)
        for (v in fiqBank8_14) out.writeInt(v)
        for (v in irqBank13_14) out.writeInt(v)
        for (v in svcBank13_14) out.writeInt(v)
        for (v in abtBank13_14) out.writeInt(v)
        for (v in undBank13_14) out.writeInt(v)
        for (v in spsr) out.writeInt(v)
        out.writeLong(totalCycles)
    }

    fun restoreFrom(inp: DataInputStream) {
        for (i in 0..15) r[i] = inp.readInt()
        cpsr = inp.readInt()
        for (i in 0..6) usrBank8_14[i] = inp.readInt()
        for (i in 0..6) fiqBank8_14[i] = inp.readInt()
        for (i in 0..1) irqBank13_14[i] = inp.readInt()
        for (i in 0..1) svcBank13_14[i] = inp.readInt()
        for (i in 0..1) abtBank13_14[i] = inp.readInt()
        for (i in 0..1) undBank13_14[i] = inp.readInt()
        for (i in 0..4) spsr[i] = inp.readInt()
        totalCycles = inp.readLong()
    }
}
