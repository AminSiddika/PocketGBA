package com.example.emulator

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

data class BiosStatusInfo(
    val bytes: ByteArray? = null,
    val resolvedMode: BiosMode = BiosMode.HLE,
    val sourceLabel: String = "Built-in HLE BIOS",
    val crc32Hex: String? = null,
    val isOfficialNintendo: Boolean = false,
    val storedFilePath: String? = null
) {
    val hasCustomBios: Boolean get() = bytes != null && bytes.size == GbaBiosManager.BIOS_SIZE
}

object GbaBiosManager {
    const val BIOS_SIZE = 0x4000 // 16,384 bytes
    const val OFFICIAL_NINTENDO_CRC32 = "81977335"

    private val candidateRelativeNames = listOf(
        "gba_bios.bin",
        "gba_bios.rom",
        "bios/gba_bios.bin",
        "bios/gba_bios.rom",
        "GBA_BIOS.BIN"
    )

    fun isValidGbaBiosBytes(bytes: ByteArray): Boolean {
        if (bytes.size != BIOS_SIZE) return false
        // Reset vector at 0x00000000 on ARM7TDMI GBA BIOS is an unconditional ARM branch (0xEAxxxxxx)
        // or valid exception table branch (0xE59FF... / 0xEA......)
        val op0 = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        val topByte = (op0 ushr 24) and 0xFF
        return topByte == 0xEA || topByte == 0xE5
    }

    fun inspectBytes(bytes: ByteArray, sourceName: String = "gba_bios.bin"): BiosStatusInfo? {
        if (!isValidGbaBiosBytes(bytes)) return null
        val crcHex = String.format("%08X", CRC32().apply { update(bytes) }.value)
        val isOfficial = crcHex.equals(OFFICIAL_NINTENDO_CRC32, ignoreCase = true)
        val label = if (isOfficial) {
            "Official GBA BIOS (CRC $crcHex)"
        } else {
            "$sourceName (CRC $crcHex)"
        }
        return BiosStatusInfo(
            bytes = bytes,
            resolvedMode = BiosMode.CUSTOM,
            sourceLabel = label,
            crc32Hex = crcHex,
            isOfficialNintendo = isOfficial
        )
    }

    fun autoResolveBios(
        context: Context,
        preferredMode: BiosMode,
        explicitPath: String?
    ): BiosStatusInfo {
        if (preferredMode == BiosMode.HLE) {
            return BiosStatusInfo(
                bytes = null,
                resolvedMode = BiosMode.HLE,
                sourceLabel = "Built-in HLE BIOS (Manual)"
            )
        }

        // 1. Check explicit path
        if (!explicitPath.isNullOrBlank()) {
            val f = File(explicitPath)
            if (f.exists() && f.length() == BIOS_SIZE.toLong()) {
                val info = inspectBytes(f.readBytes(), f.name)
                if (info != null) {
                    return info.copy(storedFilePath = f.absolutePath)
                }
            }
        }

        // 2. Auto-scan app internal & app-scoped external storage (zero broad permissions)
        val searchRoots = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(null)
        )

        val candidates = ArrayList<File>()
        for (root in searchRoots) {
            for (rel in candidateRelativeNames) {
                candidates.add(File(root, rel))
            }
            val biosSub = File(root, "bios")
            if (biosSub.isDirectory) {
                biosSub.listFiles()?.filter {
                    it.isFile && it.length() == BIOS_SIZE.toLong()
                }?.let { candidates.addAll(it) }
            }
            root.listFiles()?.filter {
                it.isFile && it.length() == BIOS_SIZE.toLong() &&
                    (it.name.contains("bios", ignoreCase = true) || it.name.endsWith(".bin", ignoreCase = true))
            }?.let { candidates.addAll(it) }
        }

        for (file in candidates) {
            if (!file.exists() || file.length() != BIOS_SIZE.toLong()) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val info = inspectBytes(bytes, file.name) ?: continue
            // Promote to canonical filesDir/gba_bios.bin for fast future loads
            val canonical = File(context.filesDir, "gba_bios.bin")
            if (file.absolutePath != canonical.absolutePath) {
                runCatching { canonical.writeBytes(bytes) }
            }
            return info.copy(storedFilePath = canonical.absolutePath)
        }

        return BiosStatusInfo(
            bytes = null,
            resolvedMode = BiosMode.HLE,
            sourceLabel = if (preferredMode == BiosMode.CUSTOM) {
                "No custom BIOS found — using HLE fallback"
            } else {
                "Auto: Built-in HLE BIOS"
            }
        )
    }

    suspend fun importBiosFromUri(context: Context, uri: Uri): Result<BiosStatusInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(context, uri) ?: "gba_bios.bin"
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read selected file.")

                val extracted = extractBiosBytes(raw, name)
                    ?: error("No valid 16KB GBA BIOS (16,384 bytes) found in $name.")

                val info = inspectBytes(extracted.first, extracted.second)
                    ?: error("File ${extracted.second} is 16KB but does not contain a valid ARM GBA BIOS reset vector.")

                val dest = File(context.filesDir, "gba_bios.bin")
                dest.writeBytes(extracted.first)
                info.copy(storedFilePath = dest.absolutePath)
            }
        }

    suspend fun scanFolderTreeForBios(context: Context, treeUri: Uri): Result<BiosStatusInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val found = scanDocumentDirectory(context, treeUri, rootDocId, depth = 0)
                    ?: error("No valid 16KB GBA BIOS (.bin/.rom/.zip) found in the selected folder.")

                val dest = File(context.filesDir, "gba_bios.bin")
                dest.writeBytes(found.bytes!!)
                found.copy(storedFilePath = dest.absolutePath)
            }
        }

    private fun scanDocumentDirectory(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        depth: Int
    ): BiosStatusInfo? {
        if (depth > 2) return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val subFolders = ArrayList<Pair<String, String>>()
        val fileCandidates = ArrayList<Triple<String, String, Long>>()

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subFolders.add(docId to name)
                } else if (
                    size == BIOS_SIZE.toLong() ||
                    name.contains("bios", ignoreCase = true) ||
                    name.endsWith(".zip", ignoreCase = true)
                ) {
                    fileCandidates.add(Triple(docId, name, size))
                }
            }
        }

        // Prioritize files explicitly named gba_bios*
        fileCandidates.sortByDescending { (_, name, size) ->
            var score = 0
            if (name.equals("gba_bios.bin", ignoreCase = true)) score += 100
            if (name.contains("gba", ignoreCase = true) && name.contains("bios", ignoreCase = true)) score += 50
            if (size == BIOS_SIZE.toLong()) score += 20
            score
        }

        for ((docId, name, size) in fileCandidates) {
            if (size > 8L * 1024L * 1024L) continue // skip huge non-BIOS files
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val raw = runCatching {
                context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
            }.getOrNull() ?: continue
            val extracted = extractBiosBytes(raw, name) ?: continue
            val info = inspectBytes(extracted.first, extracted.second)
            if (info != null) return info
        }

        for ((subId, subName) in subFolders.sortedByDescending { it.second.contains("bios", true) }) {
            val found = scanDocumentDirectory(context, treeUri, subId, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun extractBiosBytes(raw: ByteArray, fileName: String): Pair<ByteArray, String>? {
        val isZip = fileName.endsWith(".zip", ignoreCase = true) ||
            (raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte())
        if (isZip) {
            ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        (entry.size == BIOS_SIZE.toLong() || entry.size <= 0)
                    ) {
                        val entryBytes = zis.readBytes()
                        if (isValidGbaBiosBytes(entryBytes)) {
                            return entryBytes to entry.name.substringAfterLast('/')
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            return null
        }
        return if (isValidGbaBiosBytes(raw)) raw to fileName else null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }
}
