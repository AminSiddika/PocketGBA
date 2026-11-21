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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.GbaConstants
import com.example.emulator.shaders.ShaderEngine
import com.example.ui.theme.LuminousViolet

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
    half3 boosted = clamp(col.rgb * half(shade) * half3(1.03, 1.01, 0.99), 0.0, 1.0);
    return half4(boosted, col.a);
}
"""

@Composable
fun GbaLcdDisplay(
    frameBitmap: Bitmap?,
    profile: ShaderProfileEntity,
    modifier: Modifier = Modifier,
    splitRatio: Float? = null
) {
    var viewSize by remember { mutableStateOf(IntSize(240, 160)) }

    val colorFilter = remember(
        profile.baseMode,
        profile.saturation,
        profile.contrast,
        profile.brightness,
        profile.warmth
    ) {
        ColorFilter.colorMatrix(ShaderEngine.buildColorMatrix(profile))
    }

    val filterQuality = if (profile.smoothFilter || profile.baseMode == "SCALE2X") {
        FilterQuality.Low
    } else {
        FilterQuality.None
    }

    val runtimeEffect = remember(
        profile.baseMode,
        profile.gridIntensity,
        profile.agslSource,
        viewSize
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && viewSize.width > 0 && viewSize.height > 0) {
            when {
                profile.baseMode == "CUSTOM_AGSL" && !profile.agslSource.isNullOrBlank() -> runCatching {
                    val shader = RuntimeShader(profile.agslSource)
                    runCatching { shader.setFloatUniform("resolution", viewSize.width.toFloat(), viewSize.height.toFloat()) }
                    runCatching { shader.setFloatUniform("gridStrength", profile.gridIntensity.coerceIn(0f, 0.85f)) }
                    RenderEffect.createRuntimeShaderEffect(shader, "contents").asComposeRenderEffect()
                }.getOrNull()
                profile.baseMode == "LCD3X" && profile.gridIntensity > 0.02f -> runCatching {
                    val shader = RuntimeShader(LCD3X_AGSL)
                    shader.setFloatUniform("resolution", viewSize.width.toFloat(), viewSize.height.toFloat())
                    shader.setFloatUniform("gridStrength", profile.gridIntensity.coerceIn(0.05f, 0.85f))
                    RenderEffect.createRuntimeShaderEffect(shader, "contents").asComposeRenderEffect()
                }.getOrNull()
                profile.baseMode == "CRT_SCANLINES" && profile.gridIntensity > 0.02f -> runCatching {
                    val shader = RuntimeShader(CRT_AGSL)
                    shader.setFloatUniform("resolution", viewSize.width.toFloat(), viewSize.height.toFloat())
                    shader.setFloatUniform("gridStrength", (profile.gridIntensity + 0.12f).coerceIn(0.05f, 0.88f))
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

            // Raw layer rendered underneath when split comparison is active
            if (splitRatio != null) {
                Image(
                    bitmap = imageBmp,
                    contentDescription = "Raw Unshaded GBA Display",
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Shaded layer (full width normally, or clipped to [splitRatio..1f] during split comparison)
            val clipMod = if (splitRatio != null) {
                Modifier.drawWithContent {
                    val splitX = size.width * splitRatio.coerceIn(0f, 1f)
                    clipRect(left = splitX, top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            } else Modifier

            Box(modifier = Modifier.fillMaxSize().then(clipMod)) {
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

                val showCanvasFallback = runtimeEffect == null &&
                    profile.gridIntensity > 0.02f &&
                    (profile.baseMode == "LCD3X" || profile.baseMode == "CRT_SCANLINES" ||
                        profile.baseMode == "AGB_COLOR" || profile.baseMode == "AGS_101" ||
                        profile.baseMode == "CUSTOM_AGSL")

                if (showCanvasFallback) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cellH = size.height / GbaConstants.SCREEN_HEIGHT
                        val cellW = size.width / GbaConstants.SCREEN_WIDTH
                        val alpha = (profile.gridIntensity * 0.48f).coerceIn(0.04f, 0.48f)
                        val lineColor = Color.Black.copy(alpha = alpha)

                        if (cellH >= 2f) {
                            var y = 0f
                            while (y < size.height) {
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                y += cellH
                            }
                        }
                        if (profile.baseMode != "CRT_SCANLINES" && cellW >= 2f) {
                            var x = 0f
                            while (x < size.width) {
                                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                                x += cellW
                            }
                        }
                    }
                }
            }

            if (splitRatio != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val splitX = size.width * splitRatio.coerceIn(0f, 1f)
                    drawLine(
                        color = LuminousViolet,
                        start = Offset(splitX, 0f),
                        end = Offset(splitX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawCircle(
                        color = LuminousViolet,
                        radius = 6.dp.toPx(),
                        center = Offset(splitX, size.height / 2f)
                    )
                }
            }
        }
    }
}
