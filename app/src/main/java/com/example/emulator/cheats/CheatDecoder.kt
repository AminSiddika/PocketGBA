package com.example.emulator.cheats

import com.example.emulator.GbaMmu

object CheatDecoder {

    private val cbSeeds = intArrayOf(
        0x09F4FBBD, 0x9681884A.toInt(), 0x352027E9, 0xF3DEE5A7.toInt()
    )

    fun compile(id: Long, name: String, rawCode: String, format: CheatFormat): CompiledCheat {
        val lines = rawCode
            .replace("+", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        val detectedFormat = if (format == CheatFormat.AUTO) detectFormat(lines) else format
        val ops = ArrayList<CheatOp>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val parsedPair = parseHexPair(line)
            if (parsedPair == null) {
                i++
                continue
            }
            val (part1, part2, is8Plus4) = parsedPair
            when (detectedFormat) {
                CheatFormat.CODEBREAKER -> {
                    val (addr, value) = decodeCodeBreakerPair(part1, part2)
                    val type = (addr ushr 28) and 0xF
                    val realAddr = normalizeGbaAddr(addr and 0x0FFFFFFF)
                    when (type) {
                        0x3 -> ops.add(CheatOp.Write8(realAddr, value and 0xFF))
                        0x8 -> ops.add(CheatOp.Write16(realAddr, value and 0xFFFF))
                        0x6 -> ops.add(CheatOp.Write16(realAddr, value and 0xFFFF))
                        0x2 -> ops.add(CheatOp.Write16(realAddr, value and 0xFFFF)) // OR helper
                        0x4 -> {
                            // Slide code: next line holds count / step
                            val next = if (i + 1 < lines.size) parseHexPair(lines[i + 1]) else null
                            if (next != null) {
                                val valStep = (next.first ushr 16) and 0xFFFF
                                val count = next.first and 0xFFFF
                                val addrStep = next.second and 0xFFFF
                                ops.add(CheatOp.Slide16(realAddr, value and 0xFFFF, count, addrStep, valStep))
                                i++
                            }
                        }
                        0x7 -> ops.add(CheatOp.CondEqual16(realAddr, value and 0xFFFF))
                        0xD -> ops.add(CheatOp.CondEqual16(realAddr, value and 0xFFFF))
                        0xA -> ops.add(CheatOp.RomPatch16(0x08000000 or (addr and 0x01FFFFFF), value and 0xFFFF))
                        else -> ops.add(CheatOp.Write16(realAddr, value and 0xFFFF))
                    }
                }
                CheatFormat.GAMESHARK_V1_V2 -> {
                    val (opAddr, val32) = decryptGsV1(part1, part2)
                    val type = (opAddr ushr 28) and 0xF
                    val realAddr = normalizeGbaAddr(opAddr and 0x0FFFFFFF)
                    when (type) {
                        0x0 -> ops.add(CheatOp.Write8(realAddr, val32 and 0xFF))
                        0x1 -> ops.add(CheatOp.Write16(realAddr, val32 and 0xFFFF))
                        0x2 -> ops.add(CheatOp.Write32(realAddr, val32))
                        0x3 -> ops.add(CheatOp.Write32(realAddr, val32))
                        0x6 -> ops.add(CheatOp.RomPatch16(0x08000000 or (opAddr and 0x01FFFFFF), val32 and 0xFFFF))
                        0xD -> ops.add(CheatOp.CondEqual16(realAddr, val32 and 0xFFFF))
                        0xE -> ops.add(CheatOp.CondNotEqual16(realAddr, val32 and 0xFFFF))
                        0xF -> ops.add(CheatOp.RomPatch16(0x08000000 or (opAddr and 0x01FFFFFF), val32 and 0xFFFF))
                        else -> ops.add(CheatOp.Write16(realAddr, val32 and 0xFFFF))
                    }
                }
                CheatFormat.ACTION_REPLAY_V3 -> {
                    val (opAddr, val32) = decryptArV3(part1, part2)
                    val type = (opAddr ushr 24) and 0xFF
                    val realAddr = normalizeGbaAddr(opAddr and 0x00FFFFFF or 0x02000000)
                    when {
                        (type and 0x03) == 0 -> ops.add(CheatOp.Write8(realAddr, val32 and 0xFF))
                        (type and 0x03) == 1 -> ops.add(CheatOp.Write16(realAddr, val32 and 0xFFFF))
                        (type and 0x03) == 2 -> ops.add(CheatOp.Write32(realAddr, val32))
                        (opAddr ushr 28) == 0xD -> ops.add(CheatOp.CondEqual16(normalizeGbaAddr(opAddr and 0x0FFFFFFF), val32 and 0xFFFF))
                        else -> ops.add(CheatOp.Write32(normalizeGbaAddr(opAddr and 0x0FFFFFFF), val32))
                    }
                }
                CheatFormat.RAW, CheatFormat.AUTO -> {
                    val addr = normalizeGbaAddr(part1 and 0x0FFFFFFF)
                    if (is8Plus4) {
                        val top = (part1 ushr 28) and 0xF
                        when (top) {
                            0x3 -> ops.add(CheatOp.Write8(addr, part2 and 0xFF))
                            0xD -> ops.add(CheatOp.CondEqual16(addr, part2 and 0xFFFF))
                            else -> ops.add(CheatOp.Write16(addr, part2 and 0xFFFF))
                        }
                    } else {
                        ops.add(CheatOp.Write32(addr, part2))
                    }
                }
            }
            i++
        }
        return CompiledCheat(id = id, name = name, format = detectedFormat, ops = ops)
    }

    fun executeAll(cheats: List<CompiledCheat>, mmu: GbaMmu) {
        for (cheat in cheats) {
            val ops = cheat.ops
            var idx = 0
            while (idx < ops.size) {
                when (val op = ops[idx]) {
                    is CheatOp.Write8 -> mmu.write8(op.addr, op.value)
                    is CheatOp.Write16 -> mmu.write16(op.addr, op.value)
                    is CheatOp.Write32 -> mmu.write32(op.addr, op.value)
                    is CheatOp.Add16 -> mmu.write16(op.addr, mmu.read16(op.addr) + op.delta)
                    is CheatOp.RomPatch16 -> mmu.patchRom16(op.addr, op.value)
                    is CheatOp.Slide16 -> {
                        var a = op.addr
                        var v = op.value
                        for (c in 0 until op.count.coerceAtMost(256)) {
                            mmu.write16(a, v)
                            a += op.addrStep
                            v = (v + op.valStep) and 0xFFFF
                        }
                    }
                    is CheatOp.CondEqual16 -> {
                        if ((mmu.read16(op.addr) and 0xFFFF) != op.value) idx += op.skipNext
                    }
                    is CheatOp.CondNotEqual16 -> {
                        if ((mmu.read16(op.addr) and 0xFFFF) == op.value) idx += op.skipNext
                    }
                    is CheatOp.CondLess16 -> {
                        if ((mmu.read16(op.addr) and 0xFFFF) >= op.value) idx += op.skipNext
                    }
                    is CheatOp.CondGreater16 -> {
                        if ((mmu.read16(op.addr) and 0xFFFF) <= op.value) idx += op.skipNext
                    }
                }
                idx++
            }
        }
    }

    private fun detectFormat(lines: List<String>): CheatFormat {
        for (line in lines) {
            val pair = parseHexPair(line) ?: continue
            if (pair.third) return CheatFormat.CODEBREAKER
            val highNibble = (pair.first ushr 24) and 0xFF
            if (highNibble in listOf(0x02, 0x03, 0x82, 0x83, 0x32, 0x33)) return CheatFormat.RAW
            return CheatFormat.GAMESHARK_V1_V2
        }
        return CheatFormat.CODEBREAKER
    }

    private fun parseHexPair(line: String): Triple<Int, Int, Boolean>? {
        val cleaned = line.replace(":", " ").replace("-", " ").trim()
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val p1 = parts[0].toLongOrNull(16)?.toInt() ?: return null
            val p2 = parts[1].toLongOrNull(16)?.toInt() ?: return null
            val is8Plus4 = parts[0].length == 8 && parts[1].length <= 4
            return Triple(p1, p2, is8Plus4)
        }
        if (parts.size == 1) {
            val hex = parts[0]
            if (hex.length == 12) {
                val p1 = hex.substring(0, 8).toLongOrNull(16)?.toInt() ?: return null
                val p2 = hex.substring(8, 12).toLongOrNull(16)?.toInt() ?: return null
                return Triple(p1, p2, true)
            }
            if (hex.length == 16) {
                val p1 = hex.substring(0, 8).toLongOrNull(16)?.toInt() ?: return null
                val p2 = hex.substring(8, 16).toLongOrNull(16)?.toInt() ?: return null
                return Triple(p1, p2, false)
            }
        }
        return null
    }

    private fun normalizeGbaAddr(addr: Int): Int {
        val region = (addr ushr 24) and 0xFF
        return if (region == 0x00) 0x02000000 or (addr and 0x3FFFF) else addr
    }

    private fun decodeCodeBreakerPair(addr: Int, value: Int): Pair<Int, Int> {
        // Decrypted CB codes already have valid GBA region nibbles (0x02/0x03/0x08/0x32/0x33/0x82/0x83/0xD0)
        val topByte = (addr ushr 24) and 0xFF
        val lowNib = topByte and 0x0F
        if (lowNib == 2 || lowNib == 3 || lowNib == 8 || (topByte ushr 4) in listOf(3, 8, 0xD, 7, 4, 6, 0xA)) {
            return addr to value
        }
        // Standard TEA-style CB seed decode fallback for encrypted 8+4 codes
        var v0 = addr
        var v1 = value
        var sum = 0xC6EF3720.toInt()
        val delta = 0x9E3779B9.toInt()
        for (round in 0 until 32) {
            v1 -= ((v0 shl 4) + cbSeeds[2]) xor (v0 + sum) xor ((v0 ushr 5) + cbSeeds[3])
            v0 -= ((v1 shl 4) + cbSeeds[0]) xor (v1 + sum) xor ((v1 ushr 5) + cbSeeds[1])
            sum -= delta
        }
        return v0 to (v1 and 0xFFFF)
    }

    private fun decryptGsV1(op1: Int, op2: Int): Pair<Int, Int> {
        val region = (op1 ushr 24) and 0x0F
        if (region in listOf(2, 3, 8)) return op1 to op2
        var v0 = op1
        var v1 = op2
        val seeds = intArrayOf(0x09F4FBBD, 0x9681884A.toInt(), 0x352027E9, 0xF3DEE5A7.toInt())
        var sum = 0xC6EF3720.toInt()
        val delta = 0x9E3779B9.toInt()
        for (r in 0 until 32) {
            v1 -= ((v0 shl 4) + seeds[2]) xor (v0 + sum) xor ((v0 ushr 5) + seeds[3])
            v0 -= ((v1 shl 4) + seeds[0]) xor (v1 + sum) xor ((v1 ushr 5) + seeds[1])
            sum -= delta
        }
        return v0 to v1
    }

    private fun decryptArV3(op1: Int, op2: Int): Pair<Int, Int> {
        val region = (op1 ushr 24) and 0x0F
        if (region in listOf(2, 3)) return op1 to op2
        var v0 = op1
        var v1 = op2
        val seeds = intArrayOf(0x7AA9648F, 0x7FAE6994, 0xC0EFAAD5.toInt(), 0x42712C57)
        var sum = 0xC6EF3720.toInt()
        val delta = 0x9E3779B9.toInt()
        for (r in 0 until 32) {
            v1 -= ((v0 shl 4) + seeds[2]) xor (v0 + sum) xor ((v0 ushr 5) + seeds[3])
            v0 -= ((v1 shl 4) + seeds[0]) xor (v1 + sum) xor ((v1 ushr 5) + seeds[1])
            sum -= delta
        }
        return v0 to v1
    }
}
