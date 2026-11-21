package com.example.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.shaders.ShaderEngine
import com.example.ui.library.BadgeChip
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAction
import com.example.ui.theme.EmeraldLed
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShaderManagerSheet(
    previewBitmap: Bitmap?,
    profiles: List<ShaderProfileEntity>,
    activeGlobalProfileId: Long,
    currentRom: RomEntity? = null,
    onDismiss: () -> Unit,
    onSelectGlobalProfile: (ShaderProfileEntity) -> Unit,
    onPinProfileToRom: ((Long?) -> Unit)? = null,
    onSaveProfile: (ShaderProfileEntity) -> Unit,
    onDeleteCustomProfile: (Long) -> Unit,
    onImportShaderFile: () -> Unit,
    onExportProfile: (ShaderProfileEntity) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val effectiveId = currentRom?.preferredShaderProfileId ?: activeGlobalProfileId
    val selectedFromDb = profiles.firstOrNull { it.id == effectiveId } ?: profiles.firstOrNull()

    var draftProfile by remember(selectedFromDb?.id) {
        mutableStateOf(selectedFromDb ?: ShaderEngine.builtInProfiles().first())
    }
    LaunchedEffect(selectedFromDb) {
        if (selectedFromDb != null && selectedFromDb.id == draftProfile.id) {
            draftProfile = selectedFromDb
        }
    }

    val liveBmp = remember(previewBitmap) { previewBitmap ?: ShaderEngine.getOrCreateSampleFrame() }
    var splitCompareEnabled by remember { mutableStateOf(true) }
    var splitRatio by remember { mutableFloatStateOf(0.42f) }
    var previewWidthPx by remember { mutableFloatStateOf(1f) }
    var showAgslEditor by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AnodizedSlate
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SHADERS MANAGER", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (currentRom != null) "${currentRom.title} • Live LCD Studio"
                        else "Global Display Profiles & AGSL Shaders",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilterChip(
                    selected = splitCompareEnabled,
                    onClick = { splitCompareEnabled = !splitCompareEnabled },
                    leadingIcon = {
                        Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Split", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HandheldIndigo,
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("shader_split_toggle")
                )
            }

            Spacer(Modifier.height(10.dp))

            // Sticky Live 240x160 GBA Preview with draggable Before/After Wipe
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1016)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    GbaLcdDisplay(
                        frameBitmap = liveBmp,
                        profile = draftProfile,
                        splitRatio = if (splitCompareEnabled) splitRatio else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(146.dp)
                            .aspectRatio(1.5f, matchHeightConstraintsFirst = true)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(8.dp))
                            .onSizeChanged { previewWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .pointerInput(splitCompareEnabled) {
                                if (splitCompareEnabled) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        splitRatio = (splitRatio + dragAmount / previewWidthPx).coerceIn(0.05f, 0.95f)
                                    }
                                }
                            }
                            .testTag("shader_live_preview")
                    )

                    if (splitCompareEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BadgeChip("RAW", Color.Black.copy(alpha = 0.72f), TextSecondary)
                            BadgeChip(draftProfile.name.uppercase(), Color.Black.copy(alpha = 0.72f), LuminousViolet)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // Action Strip: Import Shader File, Save Custom Profile, Export
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportShaderFile,
                            modifier = Modifier.weight(1f).testTag("import_shader_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import Shader", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { showSaveDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo),
                            modifier = Modifier.weight(1f).testTag("save_shader_profile_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Profile", style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(
                            onClick = { onExportProfile(draftProfile) },
                            modifier = Modifier.testTag("export_shader_button")
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Export shader profile", tint = TextSecondary)
                        }
                    }
                }

                // Optional Per-ROM Shader Override Strip
                if (currentRom != null && onPinProfileToRom != null) {
                    item {
                        val isPinned = currentRom.preferredShaderProfileId != null
                        Surface(
                            color = SurfaceCard,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (isPinned) AmberBoost else BorderSubtle, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isPinned) "Game-Specific Shader Active" else "Using Global Shader",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isPinned) AmberBoost else Color.White
                                    )
                                    Text(
                                        text = "Bind ${draftProfile.name} specifically to ${currentRom.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isPinned) {
                                    TextButton(onClick = { onPinProfileToRom(null) }) {
                                        Text("Use Global", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    TextButton(onClick = {
                                        onPinProfileToRom(draftProfile.id)
                                    }) {
                                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Pin to Game", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // Preset & Saved Profiles Selector
                item {
                    Text("PRESETS & SAVED PROFILES", style = MaterialTheme.typography.labelLarge, color = LuminousViolet)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profiles.forEach { profile ->
                            val selected = profile.id == draftProfile.id
                            Surface(
                                color = if (selected) HandheldIndigo.copy(alpha = 0.25f) else SurfaceCard,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (selected) LuminousViolet else BorderSubtle,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        draftProfile = profile
                                        onSelectGlobalProfile(profile)
                                    }
                                    .testTag("shader_profile_chip_${profile.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = LuminousViolet,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Color.White else TextSecondary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (!profile.isBuiltIn) {
                                        Spacer(Modifier.width(4.dp))
                                        BadgeChip("CUSTOM", SurfaceElevated, AmberBoost)
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete ${profile.name}",
                                            tint = CrimsonAction,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { onDeleteCustomProfile(profile.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Parameter Tuning Sliders
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("LIVE SHADER PARAMETERS", style = MaterialTheme.typography.labelLarge, color = LuminousViolet)
                                if (!draftProfile.isBuiltIn) {
                                    TextButton(onClick = { onSaveProfile(draftProfile) }) {
                                        Text("Update Profile", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            ShaderSlider("Grid / Scanline Intensity", "${(draftProfile.gridIntensity * 100).toInt()}%", draftProfile.gridIntensity, 0f..0.85f) {
                                draftProfile = draftProfile.copy(gridIntensity = it)
                            }
                            ShaderSlider("Color Saturation", String.format("%.2fx", draftProfile.saturation), draftProfile.saturation, 0.4f..1.6f) {
                                draftProfile = draftProfile.copy(saturation = it)
                            }
                            ShaderSlider("Contrast", String.format("%.2fx", draftProfile.contrast), draftProfile.contrast, 0.6f..1.5f) {
                                draftProfile = draftProfile.copy(contrast = it)
                            }
                            ShaderSlider("Brightness Offset", "${(draftProfile.brightness * 100).toInt()}%", draftProfile.brightness, -0.3f..0.3f) {
                                draftProfile = draftProfile.copy(brightness = it)
                            }
                            ShaderSlider("Warmth / Retro Tint", "${(draftProfile.warmth * 100).toInt()}%", draftProfile.warmth, -0.4f..0.4f) {
                                draftProfile = draftProfile.copy(warmth = it)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Bilinear Smoothing", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = draftProfile.smoothFilter,
                                    onCheckedChange = { draftProfile = draftProfile.copy(smoothFilter = it) }
                                )
                            }
                        }
                    }
                }

                // Custom AGSL Shader Code Editor & Live Compiler Check
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAgslEditor = !showAgslEditor },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Code, contentDescription = null, tint = AmberBoost, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Custom AGSL Shader Source (API 33+)", style = MaterialTheme.typography.labelLarge)
                                }
                                Text(if (showAgslEditor) "Hide" else "Edit", style = MaterialTheme.typography.labelSmall, color = LuminousViolet)
                            }

                            AnimatedVisibility(visible = showAgslEditor) {
                                var agslText by remember(draftProfile.id) {
                                    mutableStateOf(
                                        draftProfile.agslSource ?: """
                                            uniform shader contents;
                                            uniform float2 resolution;
                                            uniform float gridStrength;
                                            half4 main(float2 fragCoord) {
                                                half4 c = contents.eval(fragCoord);
                                                return c;
                                            }
                                        """.trimIndent()
                                    )
                                }
                                val validation = remember(agslText) { ShaderEngine.validateAgslSource(agslText) }

                                Column(modifier = Modifier.padding(top = 10.dp)) {
                                    OutlinedTextField(
                                        value = agslText,
                                        onValueChange = { agslText = it },
                                        minLines = 5,
                                        maxLines = 10,
                                        textStyle = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = validation.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (validation.isValid) EmeraldLed else CrimsonAction
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        enabled = validation.isValid,
                                        onClick = {
                                            draftProfile = draftProfile.copy(
                                                baseMode = "CUSTOM_AGSL",
                                                agslSource = agslText
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Preview AGSL Shader")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        var profileName by remember {
            mutableStateOf(
                if (draftProfile.isBuiltIn) "${draftProfile.name} Custom" else draftProfile.name
            )
        }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = SurfaceCard,
            title = { Text("Save Shader Profile", style = MaterialTheme.typography.titleLarge) },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        val toSave = draftProfile.copy(
                            id = if (draftProfile.isBuiltIn) 0L else draftProfile.id,
                            name = profileName.trim(),
                            isBuiltIn = false,
                            createdAt = System.currentTimeMillis()
                        )
                        onSaveProfile(toSave)
                        showSaveDialog = false
                    }
                ) { Text("Save & Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ShaderSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = LuminousViolet)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
