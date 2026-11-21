package com.example.emulator.cheats

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class ZipCheatCandidate(
    val entryName: String,
    val matchScore: Int,
    val cheatCountEstimate: Int,
    val contentText: String
)

object ChtFileParser {

    fun parse(text: String, fallbackName: String = "Imported Cheat"): List<ParsedChtEntry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Libretro / RetroArch .cht format: cheats = N, cheat0_desc = "...", cheat0_code = "...", cheat0_enable = ...
        if (trimmed.contains("cheat0_code") || trimmed.contains("cheats =")) {
            return parseLibretroCht(trimmed)
        }

        // 2. Pizza Boy / VBA INI style: [Section] or Key=Value or Name\nCode
        if (trimmed.lines().any { it.trim().startsWith("[") && it.trim().endsWith("]") }) {
            return parseIniCht(trimmed)
        }

        // 3. mGBA / Plain-text cheat pack (# Title\nCODE or Title: CODE)
        return parsePlainTextCheats(trimmed, fallbackName)
    }

    private fun parseLibretroCht(text: String): List<ParsedChtEntry> {
        val descMap = HashMap<Int, String>()
        val codeMap = HashMap<Int, String>()
        val enableMap = HashMap<Int, Boolean>()
        val typeMap = HashMap<Int, CheatFormat>()

        val regex = Regex("""^cheat(\d+)_(\w+)\s*=\s*(.+)$""")
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            val match = regex.matchEntire(line) ?: continue
            val idx = match.groupValues[1].toIntOrNull() ?: continue
            val key = match.groupValues[2].lowercase()
            val rawVal = match.groupValues[3].trim().removeSurrounding("\"")

            when (key) {
                "desc" -> descMap[idx] = rawVal.ifBlank { "Cheat #${idx + 1}" }
                "code" -> codeMap[idx] = rawVal.replace("+", "\n")
                "enable" -> enableMap[idx] = rawVal.equals("true", ignoreCase = true) || rawVal == "1"
                "cheat_type", "type" -> typeMap[idx] = when {
                    rawVal.contains("cb", true) || rawVal.contains("codebreaker", true) -> CheatFormat.CODEBREAKER
                    rawVal.contains("ar", true) || rawVal.contains("v3", true) -> CheatFormat.ACTION_REPLAY_V3
                    rawVal.contains("gs", true) -> CheatFormat.GAMESHARK_V1_V2
                    else -> CheatFormat.AUTO
                }
            }
        }

        return codeMap.keys.sorted().mapNotNull { idx ->
            val code = codeMap[idx]?.trim().orEmpty()
            if (code.isBlank()) null
            else ParsedChtEntry(
                name = descMap[idx] ?: "Cheat #${idx + 1}",
                code = code,
                format = typeMap[idx] ?: CheatFormat.AUTO,
                enabled = enableMap[idx] ?: false,
                sourceFormat = "Libretro/PizzaBoy .cht"
            )
        }
    }

    private fun parseIniCht(text: String): List<ParsedChtEntry> {
        val result = ArrayList<ParsedChtEntry>()
        var currentSection = ""
        val currentCodes = ArrayList<String>()
        var currentFormat = CheatFormat.AUTO
        var currentEnabled = false

        fun flush() {
            if (currentCodes.isNotEmpty()) {
                result.add(
                    ParsedChtEntry(
                        name = currentSection.ifBlank { "Cheat ${result.size + 1}" },
                        code = currentCodes.joinToString("\n"),
                        format = currentFormat,
                        enabled = currentEnabled,
                        sourceFormat = "Pizza Boy / INI .cht"
                    )
                )
                currentCodes.clear()
                currentFormat = CheatFormat.AUTO
                currentEnabled = false
            }
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                currentSection = line.substring(1, line.length - 1).trim()
                continue
            }
            if (line.contains("=")) {
                val (k, v) = line.split("=", limit = 2).map { it.trim() }
                when {
                    k.equals("enabled", true) || k.equals("enable", true) -> {
                        currentEnabled = v == "1" || v.equals("true", true)
                    }
                    k.equals("format", true) || k.equals("type", true) -> {
                        currentFormat = when {
                            v.contains("cb", true) || v.contains("breaker", true) -> CheatFormat.CODEBREAKER
                            v.contains("ar", true) || v.contains("v3", true) -> CheatFormat.ACTION_REPLAY_V3
                            v.contains("gs", true) || v.contains("shark", true) -> CheatFormat.GAMESHARK_V1_V2
                            else -> CheatFormat.AUTO
                        }
                    }
                    k.equals("code", true) -> {
                        currentCodes.addAll(v.split("+", ";", ",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    else -> {
                        // Key=HexCode format (common in VBA/Pizza Boy text packs)
                        result.add(
                            ParsedChtEntry(
                                name = k,
                                code = v.replace("+", "\n").replace(";", "\n"),
                                format = CheatFormat.AUTO,
                                enabled = false,
                                sourceFormat = "Pizza Boy / VBA .cht"
                            )
                        )
                    }
                }
            } else {
                currentCodes.add(line)
            }
        }
        flush()
        return result
    }

    private fun parsePlainTextCheats(text: String, fallbackName: String): List<ParsedChtEntry> {
        val result = ArrayList<ParsedChtEntry>()
        var pendingTitle = fallbackName
        val pendingLines = ArrayList<String>()
        val hexPairRegex = Regex("""^[0-9A-Fa-f]{8}([\s:\-]+[0-9A-Fa-f]{4,8})?$""")

        fun flush() {
            if (pendingLines.isNotEmpty()) {
                result.add(
                    ParsedChtEntry(
                        name = pendingTitle.ifBlank { "Cheat ${result.size + 1}" },
                        code = pendingLines.joinToString("\n"),
                        format = CheatFormat.AUTO,
                        enabled = false,
                        sourceFormat = "mGBA / Plain .cht"
                    )
                )
                pendingLines.clear()
            }
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (line.startsWith("#") || line.startsWith("//")) {
                flush()
                pendingTitle = line.trimStart('#', '/', ' ').trim()
            } else if (hexPairRegex.matches(line)) {
                pendingLines.add(line)
            } else if (line.contains(":") && hexPairRegex.matches(line.substringAfter(":").trim())) {
                flush()
                val title = line.substringBefore(":").trim()
                val code = line.substringAfter(":").trim()
                result.add(
                    ParsedChtEntry(
                        name = title.ifBlank { fallbackName },
                        code = code,
                        format = CheatFormat.AUTO,
                        enabled = false,
                        sourceFormat = "Plain Cheat List"
                    )
                )
            } else {
                flush()
                pendingTitle = line
            }
        }
        flush()
        return result
    }

    fun scanZipCheatPack(
        zipBytes: ByteArray,
        romTitle: String,
        gameCode: String
    ): List<ZipCheatCandidate> {
        val candidates = ArrayList<ZipCheatCandidate>()
        val normTitle = romTitle.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val titleTokens = normTitle.split(" ").filter { it.length >= 3 }

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    (name.endsWith(".cht", true) || name.endsWith(".cheats", true) || name.endsWith(".txt", true))
                ) {
                    val content = zis.readBytes().toString(Charsets.UTF_8)
                    val lowerName = name.lowercase()
                    var score = 0
                    if (gameCode.isNotBlank() && lowerName.contains(gameCode.lowercase())) score += 50
                    if (normTitle.isNotBlank() && lowerName.contains(normTitle)) score += 40
                    for (token in titleTokens) {
                        if (lowerName.contains(token)) score += 10
                    }
                    val count = parse(content, name.substringAfterLast('/').substringBeforeLast('.')).size
                    if (count > 0) {
                        candidates.add(
                            ZipCheatCandidate(
                                entryName = name,
                                matchScore = score,
                                cheatCountEstimate = count,
                                contentText = content
                            )
                        )
                    }
                }
                entry = zis.nextEntry
            }
        }

        return candidates.sortedWith(
            compareByDescending<ZipCheatCandidate> { it.matchScore }.thenBy { it.entryName }
        )
    }

    fun exportToLibretroCht(entries: List<ParsedChtEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("cheats = ${entries.size}")
        sb.appendLine()
        entries.forEachIndexed { idx, entry ->
            val joinedCode = entry.code
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("+")
            sb.appendLine("cheat${idx}_desc = \"${entry.name.replace("\"", "'")}\"")
            sb.appendLine("cheat${idx}_code = \"$joinedCode\"")
            sb.appendLine("cheat${idx}_enable = ${entry.enabled}")
            sb.appendLine()
        }
        return sb.toString()
    }
}
