package com.example.emulator.shaders

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.ColorMatrix
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.GbaConstants
import com.example.emulator.GbaCore
import com.example.emulator.HomebrewRomBuilder
import com.example.emulator.SaveType
import org.json.JSONObject

data class AgslValidationResult(
    val isValid: Boolean,
    val message: String
)

object ShaderEngine {

    private var cachedSampleBitmap: Bitmap? = null

    fun builtInProfiles(): List<ShaderProfileEntity> = listOf(
        ShaderProfileEntity(
            name = "Sharp Pixel",
            baseMode = "SHARP",
            gridIntensity = 0.0f,
            saturation = 1.0f,
            contrast = 1.0f,
            brightness = 0.0f,
            warmth = 0.0f,
            smoothFilter = false,
            isBuiltIn = true,
            createdAt = 1L
        ),
        ShaderProfileEntity(
            name = "LCD3x Grid",
            baseMode = "LCD3X",
            gridIntensity = 0.35f,
            saturation = 1.05f,
            contrast = 1.02f,
            brightness = 0.0f,
            warmth = 0.0f,
            smoothFilter = false,
            isBuiltIn = true,
            createdAt = 2L
        ),
        ShaderProfileEntity(
            name = "AGB-001 Retro",
            baseMode = "AGB_COLOR",
            gridIntensity = 0.25f,
            saturation = 0.86f,
            contrast = 0.96f,
            brightness = 0.03f,
            warmth = 0.14f,
            smoothFilter = false,
            isBuiltIn = true,
            createdAt = 3L
        ),
        ShaderProfileEntity(
            name = "AGS-101 Backlit",
            baseMode = "AGS_101",
            gridIntensity = 0.18f,
            saturation = 1.18f,
            contrast = 1.06f,
            brightness = 0.02f,
            warmth = 0.0f,
            smoothFilter = false,
            isBuiltIn = true,
            createdAt = 4L
        ),
        ShaderProfileEntity(
            name = "CRT Scanlines",
            baseMode = "CRT_SCANLINES",
            gridIntensity = 0.48f,
            saturation = 1.08f,
            contrast = 1.04f,
            brightness = 0.01f,
            warmth = 0.06f,
            smoothFilter = true,
            isBuiltIn = true,
            createdAt = 5L
        ),
        ShaderProfileEntity(
            name = "Smooth Blend",
            baseMode = "SCALE2X",
            gridIntensity = 0.0f,
            saturation = 1.02f,
            contrast = 1.0f,
            brightness = 0.0f,
            warmth = 0.0f,
            smoothFilter = true,
            isBuiltIn = true,
            createdAt = 6L
        )
    )

    fun validateAgslSource(source: String): AgslValidationResult {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            return AgslValidationResult(false, "Shader source is empty.")
        }
        if (!trimmed.contains("main(")) {
            return AgslValidationResult(false, "AGSL shader must define half4 main(float2 fragCoord).")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return runCatching {
                RuntimeShader(trimmed)
                AgslValidationResult(true, "Compiled successfully on GPU RuntimeShader.")
            }.getOrElse { err ->
                AgslValidationResult(false, err.message ?: "AGSL compilation failed.")
            }
        }
        return AgslValidationResult(
            true,
            "Saved for Android 13+ GPU RuntimeShader (using color/grid fallback on this OS)."
        )
    }

    fun parseImportedShaderFile(fileName: String, content: String): Result<ShaderProfileEntity> = runCatching {
        val cleanName = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Custom Shader" }
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "Selected shader file is empty." }

        // 1. PocketAdvance JSON shader profile
        if (fileName.endsWith(".json", ignoreCase = true) || trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val agsl = obj.optString("agslSource", "").takeIf { it.isNotBlank() }
            if (agsl != null) {
                val res = validateAgslSource(agsl)
                require(res.isValid) { res.message }
            }
            return@runCatching ShaderProfileEntity(
                name = obj.optString("name", cleanName).ifBlank { cleanName },
                baseMode = obj.optString("baseMode", if (agsl != null) "CUSTOM_AGSL" else "LCD3X"),
                gridIntensity = obj.optDouble("gridIntensity", 0.35).toFloat().coerceIn(0f, 0.85f),
                saturation = obj.optDouble("saturation", 1.0).toFloat().coerceIn(0.4f, 1.6f),
                contrast = obj.optDouble("contrast", 1.0).toFloat().coerceIn(0.6f, 1.5f),
                brightness = obj.optDouble("brightness", 0.0).toFloat().coerceIn(-0.3f, 0.3f),
                warmth = obj.optDouble("warmth", 0.0).toFloat().coerceIn(-0.4f, 0.4f),
                smoothFilter = obj.optBoolean("smoothFilter", false),
                agslSource = agsl,
                sourceFileName = fileName,
                isBuiltIn = false
            )
        }

        // 2. Native AGSL shader (.agsl / main(float2 fragCoord))
        if (fileName.endsWith(".agsl", ignoreCase = true) ||
            (trimmed.contains("uniform shader") && trimmed.contains("main("))
        ) {
            val validation = validateAgslSource(trimmed)
            require(validation.isValid) { validation.message }
            return@runCatching ShaderProfileEntity(
                name = cleanName,
                baseMode = "CUSTOM_AGSL",
                gridIntensity = 0.35f,
                saturation = 1.0f,
                contrast = 1.0f,
                brightness = 0.0f,
                warmth = 0.0f,
                smoothFilter = false,
                agslSource = trimmed,
                sourceFileName = fileName,
                isBuiltIn = false
            )
        }

        // 3. RetroArch / GLSL / SLANG / INI shader preset parsing -> mapped to portable PocketAdvance profile
        val lower = trimmed.lowercase()
        val hasScanline = lower.contains("scanline") || lower.contains("crt")
        val hasLcd = lower.contains("lcd") || lower.contains("dot") || lower.contains("grid")
        val hasWarm = lower.contains("agb") || lower.contains("warm") || lower.contains("sepia")
        val hasSmooth = lower.contains("filter_linear") && lower.contains("true") || lower.contains("scale2x")

        val baseMode = when {
            hasScanline -> "CRT_SCANLINES"
            hasLcd -> "LCD3X"
            hasWarm -> "AGB_COLOR"
            hasSmooth -> "SCALE2X"
            else -> "AGS_101"
        }

        val satMatch = Regex("""saturation\s*[=:]\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val gridMatch = Regex("""(?:scanline|grid|mask)(?:_strength|_intensity)?\s*[=:]\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val brightMatch = Regex("""brightness\s*[=:]\s*(-?[0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val contrastMatch = Regex("""contrast\s*[=:]\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(trimmed)

        ShaderProfileEntity(
            name = cleanName,
            baseMode = baseMode,
            gridIntensity = gridMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 0.85f)
                ?: if (hasScanline || hasLcd) 0.42f else 0.15f,
            saturation = satMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0.4f, 1.6f)
                ?: if (hasWarm) 0.88f else 1.06f,
            contrast = contrastMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0.6f, 1.5f) ?: 1.02f,
            brightness = brightMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(-0.3f, 0.3f) ?: 0f,
            warmth = if (hasWarm) 0.16f else 0f,
            smoothFilter = hasSmooth,
            agslSource = null,
            sourceFileName = fileName,
            isBuiltIn = false
        )
    }

    fun exportProfileToJson(profile: ShaderProfileEntity): String {
        val obj = JSONObject()
        obj.put("name", profile.name)
        obj.put("baseMode", profile.baseMode)
        obj.put("gridIntensity", profile.gridIntensity)
        obj.put("saturation", profile.saturation)
        obj.put("contrast", profile.contrast)
        obj.put("brightness", profile.brightness)
        obj.put("warmth", profile.warmth)
        obj.put("smoothFilter", profile.smoothFilter)
        if (!profile.agslSource.isNullOrBlank()) {
            obj.put("agslSource", profile.agslSource)
        }
        return obj.toString(2)
    }

    fun buildColorMatrix(profile: ShaderProfileEntity): ColorMatrix {
        val sat = profile.saturation.coerceIn(0.3f, 1.8f)
        val con = profile.contrast.coerceIn(0.5f, 1.6f)
        val brightOffset = profile.brightness.coerceIn(-0.35f, 0.35f) * 255f
        val warmth = profile.warmth.coerceIn(-0.4f, 0.4f)

        val matrix = ColorMatrix()
        matrix.setToSaturation(sat)

        val t = (1f - con) * 128f + brightOffset
        val redScale = con * (1f + warmth * 0.25f)
        val greenScale = con * (1f + warmth * 0.05f)
        val blueScale = con * (1f - warmth * 0.28f)

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                redScale, 0f, 0f, 0f, t,
                0f, greenScale, 0f, 0f, t,
                0f, 0f, blueScale, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.timesAssign(toneMatrix)

        if (profile.baseMode == "AGB_COLOR") {
            val agbMatrix = ColorMatrix(
                floatArrayOf(
                    0.86f, 0.10f, 0.04f, 0f, 4f,
                    0.07f, 0.83f, 0.09f, 0f, 4f,
                    0.03f, 0.09f, 0.80f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.timesAssign(agbMatrix)
        }
        return matrix
    }

    fun getOrCreateSampleFrame(): Bitmap {
        cachedSampleBitmap?.let { if (!it.isRecycled) return it }
        val core = GbaCore()
        core.loadRom(HomebrewRomBuilder.buildPixelQuestRom(), SaveType.SRAM, null)
        repeat(4) { core.stepFrame(renderPixels = true, speedMultiplier = 1f) }
        val bmp = Bitmap.createBitmap(
            core.ppu.frameBuffer,
            GbaConstants.SCREEN_WIDTH,
            GbaConstants.SCREEN_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        cachedSampleBitmap = bmp
        return bmp
    }
}
