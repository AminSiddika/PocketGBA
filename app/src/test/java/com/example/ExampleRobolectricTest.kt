package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.emulator.shaders.ShaderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PocketAdvance", appName)
    }

    @Test
    fun `shaderEngine parses json glslp and agsl files`() {
        val profile = ShaderEngine.builtInProfiles().first { it.baseMode == "LCD3X" }
        val exportedJson = ShaderEngine.exportProfileToJson(profile)
        val reimported = ShaderEngine.parseImportedShaderFile("my_lcd.json", exportedJson).getOrThrow()
        assertEquals("LCD3x Grid", reimported.name)
        assertEquals("LCD3X", reimported.baseMode)
        assertEquals(profile.gridIntensity, reimported.gridIntensity, 0.01f)

        val glslPreset = """
            shaders = "1"
            filter_linear0 = "true"
            scanline_intensity = 0.62
            saturation = 1.15
        """.trimIndent()
        val parsedGlsl = ShaderEngine.parseImportedShaderFile("crt_royale.glslp", glslPreset).getOrThrow()
        assertEquals("CRT_SCANLINES", parsedGlsl.baseMode)
        assertEquals(0.62f, parsedGlsl.gridIntensity, 0.01f)
        assertEquals(1.15f, parsedGlsl.saturation, 0.01f)
        assertTrue(parsedGlsl.smoothFilter)

        val agsl = """
            uniform shader contents;
            half4 main(float2 fragCoord) {
                return contents.eval(fragCoord);
            }
        """.trimIndent()
        val parsedAgsl = ShaderEngine.parseImportedShaderFile("vivid.agsl", agsl).getOrThrow()
        assertEquals("CUSTOM_AGSL", parsedAgsl.baseMode)
        assertTrue(parsedAgsl.agslSource?.contains("main(float2 fragCoord)") == true)
    }
}
