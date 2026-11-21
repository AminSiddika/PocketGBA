package com.example.ui.player

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.example.emulator.GbaConstants
import com.example.emulator.ShaderPreset

private const val LCD3X_AGSL = """
uniform shader contents;
uniform float2 resolution;
uniform float gridStrength;

half4 main(float2 fragCoord) {
    half4 col = contents.eval(fragCoord);
    float2 gbaPx = fragCoord / max(resolution, float2(1.0)) * float2(240.0, 160.0);
    float2 cell = fract(gbaPx);
    float maskX = smoothstep(0.0, 0.14, cell.x) * smoothstep(1.0, 0.84, cell.x);
    float maskY = smoothstep(0.0, 0.14, cell.y) * smoothstep(1.0, 0.84, cell.y);
    float grid = mix(1.0 - gridStrength, 1.0, maskX * maskY);
    // Subtle RGB subpixel tinting
    float sub = cell.x * 3.0;
    half3 tint = half3(
        mix(1.0, 1.08, step(sub, 1.0)),
        mix(1.0, 1.08, step(1.0, sub) * step(sub, 2.0)),
        mix(1.0, 1.08, step(2.0, sub))
    );
    return half4(col.rgb * half(grid) * mix(half3(1.0), tint, half(gridStrength)), col.a);
}
"""

private const val CRT_AGSL = """
uniform shader contents;
uniform float2 resolution;
uniform float gridStrength;

half4 main(float2 fragCoord) {
    half4 col = contents.eval(fragCoord);
    float2 gbaPx = fragCoord / max(resolution, float2(1.0)) * float2(240.0, 160.0);
    float scan = 0.5 + 0.5 * cos(gbaPx.y * 6.2831853);
    float shade = mix(1.0, 1.0 - gridStrength * 0.72, scan);
    // Slight warm phosphor boost
    half3 boosted = clamp(col.rgb * half(shade) * half3(1.03, 1.01, 0.99), 0.0, 1.0);
    return half4(boosted, col.a);
}
"""

@Composable
fun GbaLcdDisplay(
    frameBitmap: Bitmap?,
    shaderPreset: ShaderPreset,
    lcdGridIntensity: Float,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize(240, 160)) }

    val colorFilter = remember(shaderPreset) {
        when (shaderPreset) {
            ShaderPreset.AGB_COLOR -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.82f, 0.12f, 0.04f, 0f, 8f,
                        0.08f, 0.78f, 0.10f, 0f, 8f,
                        0.04f, 0.10f, 0.74f, 0f, 10f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            ShaderPreset.AGS_101 -> ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(1.16f) }
            )
            else -> null
        }
    }

    val filterQuality = if (shaderPreset == ShaderPreset.SCALE2X || shaderPreset == ShaderPreset.CRT_SCANLINES) {
        FilterQuality.Low
    } else {
        FilterQuality.None
    }

    val runtimeEffect = remember(shaderPreset, lcdGridIntensity, viewSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && viewSize.width > 0 && viewSize.height > 0) {
            when (shaderPreset) {
                ShaderPreset.LCD3X -> runCatching {
                    val shader = RuntimeShader(LCD3X_AGSL)
                    shader.setFloatUniform("resolution", viewSize.width.toFloat(), viewSize.height.toFloat())
                    shader.setFloatUniform("gridStrength", lcdGridIntensity.coerceIn(0.1f, 0.8f))
                    RenderEffect.createRuntimeShaderEffect(shader, "contents").asComposeRenderEffect()
                }.getOrNull()
                ShaderPreset.CRT_SCANLINES -> runCatching {
                    val shader = RuntimeShader(CRT_AGSL)
                    shader.setFloatUniform("resolution", viewSize.width.toFloat(), viewSize.height.toFloat())
                    shader.setFloatUniform("gridStrength", (lcdGridIntensity + 0.15f).coerceIn(0.15f, 0.85f))
                    RenderEffect.createRuntimeShaderEffect(shader, "contents").asComposeRenderEffect()
                }.getOrNull()
                else -> null
            }
        } else null
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { viewSize = it }
    ) {
        if (frameBitmap != null) {
            val imageBmp = remember(frameBitmap) { frameBitmap.asImageBitmap() }
            Image(
                bitmap = imageBmp,
                contentDescription = "Game Boy Advance Display",
                contentScale = ContentScale.FillBounds,
                filterQuality = filterQuality,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (runtimeEffect != null) {
                            renderEffect = runtimeEffect
                        }
                    }
            )
        }

        // Pre-API 33 fallback overlay for LCD3X and CRT_SCANLINES
        if (runtimeEffect == null && (shaderPreset == ShaderPreset.LCD3X || shaderPreset == ShaderPreset.CRT_SCANLINES)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellH = size.height / GbaConstants.SCREEN_HEIGHT
                val cellW = size.width / GbaConstants.SCREEN_WIDTH
                val alpha = (lcdGridIntensity * 0.45f).coerceIn(0.05f, 0.45f)
                val lineColor = Color.Black.copy(alpha = alpha)

                if (cellH >= 2f) {
                    var y = 0f
                    while (y < size.height) {
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += cellH
                    }
                }
                if (shaderPreset == ShaderPreset.LCD3X && cellW >= 2f) {
                    var x = 0f
                    while (x < size.width) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += cellW
                    }
                }
            }
        }
    }
}
