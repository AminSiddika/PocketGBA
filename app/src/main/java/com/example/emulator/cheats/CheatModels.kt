package com.example.emulator.cheats

enum class CheatFormat(val label: String) {
    AUTO("Auto-Detect"),
    CODEBREAKER("CodeBreaker (CBA)"),
    GAMESHARK_V1_V2("GameShark / AR v1-v2"),
    ACTION_REPLAY_V3("Action Replay v3 / MAX"),
    RAW("Raw Patch")
}

sealed class CheatOp {
    data class Write8(val addr: Int, val value: Int) : CheatOp()
    data class Write16(val addr: Int, val value: Int) : CheatOp()
    data class Write32(val addr: Int, val value: Int) : CheatOp()
    data class Add16(val addr: Int, val delta: Int) : CheatOp()
    data class RomPatch16(val addr: Int, val value: Int) : CheatOp()
    data class Slide16(val addr: Int, val value: Int, val count: Int, val addrStep: Int, val valStep: Int) : CheatOp()
    data class CondEqual16(val addr: Int, val value: Int, val skipNext: Int = 1) : CheatOp()
    data class CondNotEqual16(val addr: Int, val value: Int, val skipNext: Int = 1) : CheatOp()
    data class CondLess16(val addr: Int, val value: Int, val skipNext: Int = 1) : CheatOp()
    data class CondGreater16(val addr: Int, val value: Int, val skipNext: Int = 1) : CheatOp()
}

data class CompiledCheat(
    val id: Long,
    val name: String,
    val format: CheatFormat,
    val ops: List<CheatOp>
)

data class ParsedChtEntry(
    val name: String,
    val code: String,
    val format: CheatFormat,
    val enabled: Boolean,
    val sourceFormat: String
)
