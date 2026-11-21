package com.example.emulator

object ThumbExecutor {

    fun execute(cpu: Arm7TdmiCpu, op: Int): Int {
        val top5 = (op ushr 11) and 0x1F
        val top3 = (op ushr 13) and 7
        when (top3) {
            0 -> {
                // Format 1 & 2
                val sub = (op ushr 11) and 3
                if (sub == 3) {
                    // Add/subtract
                    val imm = (op and (1 shl 10)) != 0
                    val isSub = (op and (1 shl 9)) != 0
                    val rnOrImm = (op ushr 6) and 7
                    val rs = (op ushr 3) and 7
                    val rd = op and 7
                    val operand = if (imm) rnOrImm else cpu.r[rnOrImm]
                    cpu.r[rd] = if (isSub) cpu.subWithFlags(cpu.r[rs], operand, true)
                    else cpu.addWithFlags(cpu.r[rs], operand, true)
                } else {
                    // Move shifted register
                    val imm5 = (op ushr 6) and 0x1F
                    val rs = (op ushr 3) and 7
                    val rd = op and 7
                    val v = cpu.r[rs]
                    var carry = (cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0
                    val res = when (sub) {
                        0 -> if (imm5 == 0) v else { carry = ((v ushr (32 - imm5)) and 1) != 0; v shl imm5 }
                        1 -> if (imm5 == 0) { carry = (v and Int.MIN_VALUE) != 0; 0 } else { carry = ((v ushr (imm5 - 1)) and 1) != 0; v ushr imm5 }
                        else -> if (imm5 == 0) { carry = (v and Int.MIN_VALUE) != 0; if (carry) -1 else 0 } else { carry = ((v ushr (imm5 - 1)) and 1) != 0; v shr imm5 }
                    }
                    cpu.r[rd] = res
                    cpu.setNZC(res, carry)
                }
                return 1
            }
            1 -> {
                // Format 3: Move/Compare/Add/Sub immediate
                val sub = (op ushr 11) and 3
                val rd = (op ushr 8) and 7
                val imm = op and 0xFF
                when (sub) {
                    0 -> { cpu.r[rd] = imm; cpu.setNZ(imm) }
                    1 -> cpu.subWithFlags(cpu.r[rd], imm, true)
                    2 -> cpu.r[rd] = cpu.addWithFlags(cpu.r[rd], imm, true)
                    3 -> cpu.r[rd] = cpu.subWithFlags(cpu.r[rd], imm, true)
                }
                return 1
            }
            2 -> {
                val sub = (op ushr 10) and 7
                when (sub) {
                    0 -> return execAlu(cpu, op)
                    1 -> return execHiRegBx(cpu, op)
                    2, 3 -> {
                        // PC-relative load
                        val rd = (op ushr 8) and 7
                        val imm = (op and 0xFF) shl 2
                        val addr = ((cpu.r[15] + 2) and 0xFFFFFFFC.toInt()) + imm
                        cpu.r[rd] = cpu.mmu.read32(addr)
                        return 3
                    }
                    else -> {
                        // Load/store register offset
                        val ro = (op ushr 6) and 7
                        val rb = (op ushr 3) and 7
                        val rd = op and 7
                        val addr = cpu.r[rb] + cpu.r[ro]
                        val type = (op ushr 9) and 7
                        when (type) {
                            0 -> cpu.mmu.write32(addr, cpu.r[rd])
                            1 -> cpu.mmu.write16(addr, cpu.r[rd])
                            2 -> cpu.mmu.write8(addr, cpu.r[rd])
                            3 -> cpu.r[rd] = cpu.mmu.read8(addr).toByte().toInt()
                            4 -> cpu.r[rd] = Integer.rotateRight(cpu.mmu.read32(addr), (addr and 3) * 8)
                            5 -> cpu.r[rd] = cpu.mmu.read16(addr)
                            6 -> cpu.r[rd] = cpu.mmu.read8(addr)
                            7 -> cpu.r[rd] = if ((addr and 1) == 0) cpu.mmu.read16(addr).toShort().toInt() else cpu.mmu.read8(addr).toByte().toInt()
                        }
                        return 2
                    }
                }
            }
            3 -> {
                // Load/store immediate offset
                val isByte = (op and (1 shl 12)) != 0
                val isLoad = (op and (1 shl 11)) != 0
                val imm5 = (op ushr 6) and 0x1F
                val rb = (op ushr 3) and 7
                val rd = op and 7
                val addr = cpu.r[rb] + if (isByte) imm5 else (imm5 shl 2)
                if (isLoad) {
                    cpu.r[rd] = if (isByte) cpu.mmu.read8(addr)
                    else Integer.rotateRight(cpu.mmu.read32(addr), (addr and 3) * 8)
                } else {
                    if (isByte) cpu.mmu.write8(addr, cpu.r[rd]) else cpu.mmu.write32(addr, cpu.r[rd])
                }
                return 2
            }
            4 -> {
                val isSpRel = (op and (1 shl 12)) != 0
                val isLoad = (op and (1 shl 11)) != 0
                if (isSpRel) {
                    val rd = (op ushr 8) and 7
                    val addr = cpu.r[13] + ((op and 0xFF) shl 2)
                    if (isLoad) cpu.r[rd] = Integer.rotateRight(cpu.mmu.read32(addr), (addr and 3) * 8)
                    else cpu.mmu.write32(addr, cpu.r[rd])
                } else {
                    val imm5 = ((op ushr 6) and 0x1F) shl 1
                    val rb = (op ushr 3) and 7
                    val rd = op and 7
                    val addr = cpu.r[rb] + imm5
                    if (isLoad) cpu.r[rd] = cpu.mmu.read16(addr) else cpu.mmu.write16(addr, cpu.r[rd])
                }
                return 2
            }
            5 -> {
                if ((op and (1 shl 12)) == 0) {
                    // Load address (ADD Rd, PC/SP, #imm)
                    val sp = (op and (1 shl 11)) != 0
                    val rd = (op ushr 8) and 7
                    val imm = (op and 0xFF) shl 2
                    cpu.r[rd] = if (sp) cpu.r[13] + imm else ((cpu.r[15] + 2) and 0xFFFFFFFC.toInt()) + imm
                    return 1
                } else {
                    val sub = (op ushr 8) and 0xF
                    if (sub == 0) {
                        val imm = (op and 0x7F) shl 2
                        cpu.r[13] += if ((op and 0x80) != 0) -imm else imm
                        return 1
                    }
                    // Push / Pop
                    val isPop = (op and (1 shl 11)) != 0
                    val pclr = (op and (1 shl 8)) != 0
                    val rlist = op and 0xFF
                    if (isPop) {
                        var sp = cpu.r[13]
                        for (i in 0..7) {
                            if ((rlist and (1 shl i)) != 0) {
                                cpu.r[i] = cpu.mmu.read32(sp)
                                sp += 4
                            }
                        }
                        if (pclr) {
                            cpu.r[15] = cpu.mmu.read32(sp) and 0xFFFFFFFE.toInt()
                            sp += 4
                        }
                        cpu.r[13] = sp
                    } else {
                        var sp = cpu.r[13]
                        val count = Integer.bitCount(rlist) + if (pclr) 1 else 0
                        sp -= count * 4
                        cpu.r[13] = sp
                        for (i in 0..7) {
                            if ((rlist and (1 shl i)) != 0) {
                                cpu.mmu.write32(sp, cpu.r[i])
                                sp += 4
                            }
                        }
                        if (pclr) cpu.mmu.write32(sp, cpu.r[14])
                    }
                    return 3
                }
            }
            6 -> {
                if ((op and (1 shl 12)) == 0) {
                    // Multiple load/store (LDMIA/STMIA)
                    val isLoad = (op and (1 shl 11)) != 0
                    val rb = (op ushr 8) and 7
                    val rlist = op and 0xFF
                    var addr = cpu.r[rb]
                    for (i in 0..7) {
                        if ((rlist and (1 shl i)) != 0) {
                            if (isLoad) cpu.r[i] = cpu.mmu.read32(addr) else cpu.mmu.write32(addr, cpu.r[i])
                            addr += 4
                        }
                    }
                    if (!isLoad || (rlist and (1 shl rb)) == 0) cpu.r[rb] = addr
                    return 3
                } else {
                    val cond = (op ushr 8) and 0xF
                    if (cond == 0xF) {
                        cpu.bios.handleSwi(op and 0xFF, cpu)
                        return 3
                    }
                    if (cpu.checkCondition(cond)) {
                        var off = op and 0xFF
                        if ((off and 0x80) != 0) off = off or 0xFFFFFF00.toInt()
                        cpu.r[15] = cpu.r[15] + 2 + (off shl 1)
                        return 3
                    }
                    return 1
                }
            }
            7 -> {
                when (top5) {
                    0x1C -> {
                        // Unconditional branch
                        var off = op and 0x7FF
                        if ((off and 0x400) != 0) off = off or 0xFFFFF800.toInt()
                        cpu.r[15] = cpu.r[15] + 2 + (off shl 1)
                        return 3
                    }
                    0x1E -> {
                        var off = op and 0x7FF
                        if ((off and 0x400) != 0) off = off or 0xFFFFF800.toInt()
                        cpu.r[14] = cpu.r[15] + 2 + (off shl 12)
                        return 1
                    }
                    0x1F -> {
                        val nextPc = cpu.r[15] or 1
                        cpu.r[15] = (cpu.r[14] + ((op and 0x7FF) shl 1)) and 0xFFFFFFFE.toInt()
                        cpu.r[14] = nextPc
                        return 3
                    }
                }
            }
        }
        return 1
    }

    private fun execAlu(cpu: Arm7TdmiCpu, op: Int): Int {
        val opcode = (op ushr 6) and 0xF
        val rs = (op ushr 3) and 7
        val rd = op and 7
        val a = cpu.r[rd]
        val b = cpu.r[rs]
        when (opcode) {
            0x0 -> { val r = a and b; cpu.r[rd] = r; cpu.setNZ(r) }
            0x1 -> { val r = a xor b; cpu.r[rd] = r; cpu.setNZ(r) }
            0x2 -> {
                val s = b and 0xFF
                var c = (cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0
                val r = if (s == 0) a else if (s < 32) { c = ((a ushr (32 - s)) and 1) != 0; a shl s } else if (s == 32) { c = (a and 1) != 0; 0 } else { c = false; 0 }
                cpu.r[rd] = r; cpu.setNZC(r, c)
            }
            0x3 -> {
                val s = b and 0xFF
                var c = (cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0
                val r = if (s == 0) a else if (s < 32) { c = ((a ushr (s - 1)) and 1) != 0; a ushr s } else if (s == 32) { c = (a and Int.MIN_VALUE) != 0; 0 } else { c = false; 0 }
                cpu.r[rd] = r; cpu.setNZC(r, c)
            }
            0x4 -> {
                val s = b and 0xFF
                var c = (cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0
                val r = if (s == 0) a else if (s < 32) { c = ((a ushr (s - 1)) and 1) != 0; a shr s } else { c = (a and Int.MIN_VALUE) != 0; if (c) -1 else 0 }
                cpu.r[rd] = r; cpu.setNZC(r, c)
            }
            0x5 -> {
                val carry = if ((cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0) 1L else 0L
                val sum = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) + carry
                val r = sum.toInt()
                cpu.r[rd] = r; cpu.setNZ(r)
                cpu.cpsr = if (sum > 0xFFFFFFFFL) cpu.cpsr or Arm7TdmiCpu.FLAG_C else cpu.cpsr and Arm7TdmiCpu.FLAG_C.inv()
                val ov = ((a xor r) and (b xor r)) < 0
                cpu.cpsr = if (ov) cpu.cpsr or Arm7TdmiCpu.FLAG_V else cpu.cpsr and Arm7TdmiCpu.FLAG_V.inv()
            }
            0x6 -> {
                val borrow = if ((cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0) 0L else 1L
                val diff = (a.toLong() and 0xFFFFFFFFL) - (b.toLong() and 0xFFFFFFFFL) - borrow
                val r = diff.toInt()
                cpu.r[rd] = r; cpu.setNZ(r)
                cpu.cpsr = if (diff >= 0L) cpu.cpsr or Arm7TdmiCpu.FLAG_C else cpu.cpsr and Arm7TdmiCpu.FLAG_C.inv()
                val ov = ((a xor b) and (a xor r)) < 0
                cpu.cpsr = if (ov) cpu.cpsr or Arm7TdmiCpu.FLAG_V else cpu.cpsr and Arm7TdmiCpu.FLAG_V.inv()
            }
            0x7 -> {
                val rot = b and 31
                val r = if (rot == 0) a else Integer.rotateRight(a, rot)
                cpu.r[rd] = r
                val c = if ((b and 0xFF) == 0) (cpu.cpsr and Arm7TdmiCpu.FLAG_C) != 0 else (r and Int.MIN_VALUE) != 0
                cpu.setNZC(r, c)
            }
            0x8 -> cpu.setNZ(a and b)
            0x9 -> cpu.r[rd] = cpu.subWithFlags(0, b, true)
            0xA -> cpu.subWithFlags(a, b, true)
            0xB -> cpu.addWithFlags(a, b, true)
            0xC -> { val r = a or b; cpu.r[rd] = r; cpu.setNZ(r) }
            0xD -> { val r = a * b; cpu.r[rd] = r; cpu.setNZ(r) }
            0xE -> { val r = a and b.inv(); cpu.r[rd] = r; cpu.setNZ(r) }
            0xF -> { val r = b.inv(); cpu.r[rd] = r; cpu.setNZ(r) }
        }
        return 1
    }

    private fun execHiRegBx(cpu: Arm7TdmiCpu, op: Int): Int {
        val opcode = (op ushr 8) and 3
        val h1 = (op ushr 7) and 1
        val h2 = (op ushr 6) and 1
        val rs = ((op ushr 3) and 7) or (h2 shl 3)
        val rd = (op and 7) or (h1 shl 3)
        val src = if (rs == 15) cpu.r[15] + 2 else cpu.r[rs]
        when (opcode) {
            0 -> {
                val dst = if (rd == 15) cpu.r[15] + 2 else cpu.r[rd]
                val res = dst + src
                if (rd == 15) cpu.r[15] = res and 0xFFFFFFFE.toInt() else cpu.r[rd] = res
            }
            1 -> {
                val dst = if (rd == 15) cpu.r[15] + 2 else cpu.r[rd]
                cpu.subWithFlags(dst, src, true)
            }
            2 -> {
                if (rd == 15) cpu.r[15] = src and 0xFFFFFFFE.toInt() else cpu.r[rd] = src
            }
            3 -> {
                if ((src and 1) != 0) {
                    cpu.cpsr = cpu.cpsr or Arm7TdmiCpu.FLAG_T
                    cpu.r[15] = src and 0xFFFFFFFE.toInt()
                } else {
                    cpu.cpsr = cpu.cpsr and Arm7TdmiCpu.FLAG_T.inv()
                    cpu.r[15] = src and 0xFFFFFFFC.toInt()
                }
                return 3
            }
        }
        return 1
    }
}
