package com.example.ui.player

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.EmulatorPreferences
import com.example.emulator.OrientationMode
import com.example.emulator.ShaderPreset
import com.example.emulator.SpeedToggleMode
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShaderAndSettingsSheet(
    preferences: EmulatorPreferences,
    onDismiss: () -> Unit,
    onSelectShader: (ShaderPreset) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSpeedMode: (SpeedToggleMode) -> Unit,
    onSelectOrientation: (OrientationMode) -> Unit,
    onLcdGridChange: (Float) -> Unit,
    onAudioToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onImportBios: () -> Unit,
    onClearBios: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speedOptions = listOf(1.5f, 2f, 3f, 4f, 6f, 8f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AnodizedSlate
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("SHADERS, SPEED (UP TO 8x) & SETTINGS", style = MaterialTheme.typography.titleLarge)
            }

            // Shaders Section
            item {
                SectionCard(title = "Display Shader Preset") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShaderPreset.entries.forEach { preset ->
                            val selected = preset == preferences.shaderPreset
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (selected) LuminousViolet else BorderSubtle,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onSelectShader(preset) }
                                    .padding(10.dp)
                                    .testTag("shader_option_${preset.name}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        preset.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) LuminousViolet else Color.White
                                    )
                                    Text(preset.description, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                                if (selected) {
                                    Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = LuminousViolet)
                                }
                            }
                        }

                        if (preferences.shaderPreset == ShaderPreset.LCD3X ||
                            preferences.shaderPreset == ShaderPreset.CRT_SCANLINES
                        ) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Shader Grid / Scanline Intensity: ${(preferences.lcdGridIntensity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Slider(
                                value = preferences.lcdGridIntensity,
                                onValueChange = onLcdGridChange,
                                valueRange = 0.1f..0.8f
                            )
                        }
                    }
                }
            }

            // Fast-Forward Speed Section (up to 8x)
            item {
                SectionCard(title = "Fast-Forward Speed & Pad Toggle") {
                    Text(
                        "Target Speed Multiplier (up to 8x)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        speedOptions.forEach { spd ->
                            val selected = kotlin.math.abs(preferences.fastForwardSpeed - spd) < 0.2f
                            val lbl = if (spd % 1f == 0f) "${spd.toInt()}x" else "${spd}x"
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectSpeed(spd) },
                                label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberBoost,
                                    selectedLabelColor = Color.Black
                                ),
                                modifier = Modifier.testTag("speed_chip_${lbl}")
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Speed Button Behavior (Beside Control Pad)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeedToggleMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.speedToggleMode == mode,
                                onClick = { onSelectSpeedMode(mode) },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = HandheldIndigo,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Screen Orientation Mode
            item {
                SectionCard(title = "Screen Orientation") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrientationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.orientationMode == mode,
                                onClick = { onSelectOrientation(mode) },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = HandheldIndigo,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Audio & Controls
            item {
                SectionCard(title = "Audio & Touch Ergonomics") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Audio Output (PSG + DirectSound)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = preferences.audioEnabled, onCheckedChange = onAudioToggle)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Haptic Feedback on Buttons", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = preferences.hapticsEnabled, onCheckedChange = onHapticsToggle)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Control Opacity: ${(preferences.controlOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = preferences.controlOpacity,
                        onValueChange = onOpacityChange,
                        valueRange = 0.35f..1.0f
                    )
                    Text(
                        "Control Size Scale: ${(preferences.controlScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = preferences.controlScale,
                        onValueChange = onScaleChange,
                        valueRange = 0.8f..1.25f
                    )
                }
            }

            // Optional GBA BIOS
            item {
                SectionCard(title = "GBA BIOS") {
                    Text(
                        text = if (preferences.customBiosPath != null) "Custom 16KB BIOS Active"
                        else "Using Built-in HLE BIOS (zero setup required)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImportBios) {
                            Text("Import gba_bios.bin")
                        }
                        if (preferences.customBiosPath != null) {
                            TextButton(onClick = onClearBios) {
                                Text("Use HLE BIOS")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = LuminousViolet)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
