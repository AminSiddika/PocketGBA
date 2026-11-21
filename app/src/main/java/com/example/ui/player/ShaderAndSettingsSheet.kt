package com.example.ui.player

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.OrientationMode
import com.example.emulator.SpeedToggleMode
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextSecondary
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShaderAndSettingsSheet(
    preferences: EmulatorPreferences,
    activeShaderProfile: ShaderProfileEntity,
    currentRom: RomEntity?,
    onDismiss: () -> Unit,
    onOpenShadersManager: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSetRomSpeedOverride: (Float?) -> Unit,
    onSelectSpeedMode: (SpeedToggleMode) -> Unit,
    onMuteTurboAudioToggle: (Boolean) -> Unit,
    onAutoSaveExitToggle: (Boolean) -> Unit,
    onSelectOrientation: (OrientationMode) -> Unit,
    onAudioToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onImportBios: () -> Unit,
    onClearBios: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val speedOptions = listOf(1.5f, 2f, 3f, 4f, 6f, 8f)
    val effectiveSpeed = currentRom?.preferredSpeedMultiplier ?: preferences.fastForwardSpeed

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
                Text("EMULATOR SETTINGS & SPEED (8x)", style = MaterialTheme.typography.titleLarge)
            }

            // Dedicated Shaders Manager Entry
            item {
                SectionCard(title = "Display Shaders Studio") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeShaderProfile.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (currentRom?.preferredShaderProfileId != null) {
                                    "Pinned to ${currentRom.title}"
                                } else {
                                    "Global active display shader"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onOpenShadersManager,
                        colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_shaders_manager_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Shaders Manager (Live Preview & Import)", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Fast-Forward Speed Section (1.5x to 8x)
            item {
                SectionCard(title = "Fast-Forward Speed & Pad Toggle") {
                    Text(
                        "Target Speed Multiplier (1.5x – 8x)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        speedOptions.forEach { spd ->
                            val selected = abs(effectiveSpeed - spd) < 0.2f
                            val lbl = if (spd % 1f == 0f) "${spd.toInt()}x" else "${spd}x"
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (currentRom?.preferredSpeedMultiplier != null) {
                                        onSetRomSpeedOverride(spd)
                                    } else {
                                        onSelectSpeed(spd)
                                    }
                                },
                                label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberBoost,
                                    selectedLabelColor = Color.Black
                                ),
                                modifier = Modifier.testTag("speed_chip_${lbl}")
                            )
                        }
                    }

                    if (currentRom != null) {
                        Spacer(Modifier.height(8.dp))
                        val isSpeedPinned = currentRom.preferredSpeedMultiplier != null
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = if (isSpeedPinned) AmberBoost else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isSpeedPinned) "Speed pinned to ${currentRom.title}"
                                    else "Pin speed to ${currentRom.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSpeedPinned) AmberBoost else TextSecondary
                                )
                            }
                            if (isSpeedPinned) {
                                TextButton(onClick = { onSetRomSpeedOverride(null) }) {
                                    Text("Use Global", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                TextButton(onClick = { onSetRomSpeedOverride(effectiveSpeed) }) {
                                    Text("Pin Speed", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Speed Button Behavior (Beside D-Pad)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Tip: Swipe any direction or long-press the SPD pad button to step 1.5x–8x on the fly.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
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

            // Audio, Auto-Save & Controls
            item {
                SectionCard(title = "Audio, Auto-Save & Touch Ergonomics") {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Soften / Mute Audio Above 2x", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Prevents high-pitched audio clipping during 3x–8x turbo",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = preferences.muteTurboAudioAbove2x,
                            onCheckedChange = onMuteTurboAudioToggle
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Save .ss0 on Exit / Pause", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Resume instantly from the Library shelf",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = preferences.autoSaveOnExit,
                            onCheckedChange = onAutoSaveExitToggle
                        )
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
