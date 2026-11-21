package com.example.data

import android.content.Context
import com.example.data.db.RomEntity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ScrapedRomMetadata(
    val displayTitle: String,
    val publisher: String,
    val region: String,
    val releaseYear: String,
    val genre: String,
    val hardwareFeatures: String,
    val hasRumbleSupport: Boolean,
    val coverArtPath: String?,
    val scrapedFromRemote: Boolean
)

object GbaMetadataScraper {

    private const val NO_INTRO_DAT_URL =
        "https://raw.githubusercontent.com/libretro/libretro-database/master/dat/Nintendo%20-%20Game%20Boy%20Advance.dat"
    private const val LIBRETRO_BOXART_BASE =
        "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/"
    private const val LIBRETRO_TITLE_BASE =
        "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Titles/"

    private val makerMap = mapOf(
        "01" to "Nintendo",
        "08" to "Capcom",
        "13" to "Electronic Arts",
        "18" to "Hudson Soft",
        "20" to "Destination Software",
        "28" to "Kemco",
        "33" to "Ocean / Infogrames",
        "41" to "Ubisoft",
        "4F" to "Eidos Interactive",
        "51" to "Acclaim",
        "52" to "Activision",
        "5D" to "Midway",
        "5G" to "Majesco",
        "60" to "Titus",
        "64" to "LucasArts",
        "69" to "Electronic Arts",
        "6S" to "TDK Mediactive",
        "70" to "Infogrames / Atari",
        "78" to "THQ",
        "7D" to "Sierra / Vivendi",
        "8P" to "Sega",
        "99" to "Marvelous",
        "A4" to "Konami",
        "AF" to "Namco",
        "B2" to "Bandai",
        "BL" to "MTO",
        "C8" to "Koei",
        "DA" to "Tomy",
        "EB" to "Atlus",
        "EM" to "Konami",
        "FH" to "BAM! Entertainment",
        "G9" to "D3 Publisher",
        "GY" to "The Pokémon Company",
        "PA" to "PocketAdvance Homebrew"
    )

    private val rtcPrefixes = setOf("AXV", "AXP", "BPE", "U3I", "U32", "U33")
    private val rumblePrefixes = setOf("V49", "RZW", "KHP", "BPP", "A88", "AX4")
    private val gyroPrefixes = setOf("RZW", "KYG")
    private val solarPrefixes = setOf("U3I", "U32", "U33")

    fun extractLocalMetadata(rom: RomEntity, romBytes: ByteArray?): ScrapedRomMetadata {
        val codePrefix = rom.gameCode.take(3).uppercase()
        val regionChar = rom.gameCode.getOrNull(3)?.uppercaseChar() ?: 'E'
        val region = when (regionChar) {
            'E' -> "USA"
            'J' -> "Japan"
            'P', 'D', 'F', 'I', 'S', 'H', 'X', 'Y', 'Z' -> "Europe"
            'K' -> "Korea"
            'C' -> "China"
            else -> "World"
        }

        val publisher = makerMap[rom.makerCode.uppercase()]
            ?: if (rom.isBuiltInDemo) "PocketAdvance Homebrew" else "Maker ${rom.makerCode}"

        val asciiScan = if (romBytes != null && romBytes.size <= 32 * 1024 * 1024) {
            romBytes.toString(Charsets.ISO_8859_1)
        } else {
            ""
        }

        val hasRtc = codePrefix in rtcPrefixes || asciiScan.contains("SIIRTC_V")
        val hasRumble = codePrefix in rumblePrefixes || asciiScan.contains("GPIO_RUMBLE")
        val hasGyro = codePrefix in gyroPrefixes
        val hasSolar = codePrefix in solarPrefixes

        val features = ArrayList<String>()
        if (hasRumble) features.add("Rumble")
        if (hasRtc) features.add("RTC Clock")
        if (hasGyro) features.add("Gyro / Tilt")
        if (hasSolar) features.add("Solar Sensor")
        if (features.isEmpty()) features.add("Standard Cartridge")

        val cleanTitle = rom.displayTitle?.takeIf { it.isNotBlank() }
            ?: formatReadableTitle(rom.title)

        return ScrapedRomMetadata(
            displayTitle = cleanTitle,
            publisher = publisher,
            region = region,
            releaseYear = if (rom.isBuiltInDemo) "2026" else rom.releaseYear,
            genre = if (rom.isBuiltInDemo) "Homebrew Demo" else rom.genre,
            hardwareFeatures = features.joinToString(" • "),
            hasRumbleSupport = hasRumble,
            coverArtPath = rom.coverArtPath,
            scrapedFromRemote = false
        )
    }

    fun scrapeRomMetadata(
        context: Context,
        rom: RomEntity,
        romBytes: ByteArray?
    ): ScrapedRomMetadata {
        val local = extractLocalMetadata(rom, romBytes)
        if (rom.isBuiltInDemo) {
            return local
        }

        val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

        val datFile = ensureNoIntroDat(cacheDir)
        val datMatch = if (datFile != null) {
            findEntryInDat(datFile, rom.crc32Hex, rom.gameCode)
        } else null

        val canonicalName = datMatch?.name
        val displayTitle = if (!canonicalName.isNullOrBlank()) {
            canonicalName.substringBefore(" (").trim().ifBlank { local.displayTitle }
        } else {
            local.displayTitle
        }

        val scrapedRegion = if (!canonicalName.isNullOrBlank()) {
            Regex("""\(([^)]+)\)""").find(canonicalName)?.groupValues?.getOrNull(1) ?: local.region
        } else {
            local.region
        }

        val coverFile = File(coversDir, "${rom.crc32Hex}.png")
        var resolvedCoverPath = if (coverFile.exists() && coverFile.length() > 256) {
            coverFile.absolutePath
        } else null

        if (resolvedCoverPath == null && !canonicalName.isNullOrBlank()) {
            val safeThumbName = sanitizeLibretroThumbName(canonicalName)
            val encoded = URLEncoder.encode(safeThumbName, "UTF-8").replace("+", "%20")
            val boxartBytes = downloadBytes("$LIBRETRO_BOXART_BASE$encoded.png")
                ?: downloadBytes("$LIBRETRO_TITLE_BASE$encoded.png")
            if (boxartBytes != null && boxartBytes.size > 256) {
                coverFile.writeBytes(boxartBytes)
                resolvedCoverPath = coverFile.absolutePath
            }
        }

        return local.copy(
            displayTitle = displayTitle,
            region = scrapedRegion,
            releaseYear = datMatch?.year?.ifBlank { local.releaseYear } ?: local.releaseYear,
            genre = datMatch?.genre?.ifBlank { local.genre } ?: local.genre,
            coverArtPath = resolvedCoverPath,
            scrapedFromRemote = datMatch != null || resolvedCoverPath != null
        )
    }

    private data class DatEntryMatch(
        val name: String,
        val year: String = "",
        val genre: String = ""
    )

    private fun ensureNoIntroDat(cacheDir: File): File? {
        val datFile = File(cacheDir, "gba_nointro.dat")
        val maxAgeMs = 7L * 24L * 60L * 60L * 1000L
        if (datFile.exists() && datFile.length() > 4096 &&
            System.currentTimeMillis() - datFile.lastModified() < maxAgeMs
        ) {
            return datFile
        }
        val bytes = downloadBytes(NO_INTRO_DAT_URL, timeoutMs = 12000) ?: return datFile.takeIf { it.exists() }
        if (bytes.size > 4096) {
            datFile.writeBytes(bytes)
        }
        return datFile.takeIf { it.exists() }
    }

    private fun findEntryInDat(datFile: File, crc32Hex: String, gameCode: String): DatEntryMatch? {
        val targetCrc = crc32Hex.uppercase()
        val targetSerial = "AGB-${gameCode.uppercase()}"

        var currentName = ""
        var currentYear = ""
        var fallbackSerialMatch: DatEntryMatch? = null

        BufferedReader(InputStreamReader(datFile.inputStream(), Charsets.UTF_8)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("name \"")) {
                    currentName = trimmed.removePrefix("name \"").removeSuffix("\"").trim()
                } else if (trimmed.startsWith("releaseyear ")) {
                    currentYear = trimmed.removePrefix("releaseyear ").removeSurrounding("\"").trim()
                } else if (trimmed.startsWith("rom (") || trimmed.contains("crc ")) {
                    val crcMatch = Regex("""crc\s+([0-9A-Fa-f]{8})""").find(trimmed)
                    if (crcMatch != null && crcMatch.groupValues[1].equals(targetCrc, ignoreCase = true)) {
                        if (currentName.isNotBlank()) {
                            return DatEntryMatch(name = currentName, year = currentYear)
                        }
                    }
                    if (fallbackSerialMatch == null && currentName.isNotBlank() &&
                        trimmed.contains(targetSerial, ignoreCase = true)
                    ) {
                        fallbackSerialMatch = DatEntryMatch(name = currentName, year = currentYear)
                    }
                } else if (trimmed == ")") {
                    currentName = ""
                    currentYear = ""
                }
                line = reader.readLine()
            }
        }
        return fallbackSerialMatch
    }

    fun sanitizeLibretroThumbName(name: String): String =
        name.replace(Regex("""[&*/:<>?\\|"]"""), "_")

    private fun downloadBytes(urlString: String, timeoutMs: Int = 8000): ByteArray? {
        return runCatching {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PocketAdvance-Android/1.0")
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { it.readBytes() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun formatReadableTitle(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "GBA Cartridge"
        if (trimmed != trimmed.uppercase()) return trimmed
        return trimmed.lowercase().split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
