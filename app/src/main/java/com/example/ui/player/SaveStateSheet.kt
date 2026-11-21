package com.example.ui.player

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.savestate.SaveStateSlotInfo
import com.example.ui.library.BadgeChip
import com.example.ui.library.formatDuration
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAction
import com.example.ui.theme.EmeraldLed
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveStateSheet(
    romTitle: String,
    slots: List<SaveStateSlotInfo>,
    onDismiss: () -> Unit,
    onSaveSlot: (Int) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    onExportSlot: (Int) -> Unit,
    onImportSlot: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteCandidate by remember { mutableStateOf<SaveStateSlotInfo?>(null) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SAVE STATES (.ss1 – .ss9)", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "$romTitle • Full Hardware Snapshots",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BadgeChip(
                    text = "${slots.count { it.exists }}/${slots.size} USED",
                    bg = SurfaceElevated,
                    fg = AmberBoost
                )
            }
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(slots, key = { it.slot }) { info ->
                    SaveSlotCard(
                        info = info,
                        onSave = { onSaveSlot(info.slot) },
                        onLoad = { onLoadSlot(info.slot) },
                        onExport = { onExportSlot(info.slot) },
                        onImport = { onImportSlot(info.slot) },
                        onDelete = { deleteCandidate = info }
                    )
                }
            }
        }
    }

    deleteCandidate?.let { slotInfo ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = SurfaceCard,
            title = { Text("Delete ${slotInfo.slotBadge}?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Permanently delete ${slotInfo.fileName}?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSlot(slotInfo.slot)
                    deleteCandidate = null
                }) {
                    Text("Delete", color = CrimsonAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SaveSlotCard(
    info: SaveStateSlotInfo,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    var expandedDetails by remember { mutableStateOf(false) }
    val thumbBitmap = remember(info.thumbnailPng) {
        info.thumbnailPng?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (info.exists) HandheldIndigo.copy(alpha = 0.55f) else BorderSubtle,
                RoundedCornerShape(16.dp)
            )
            .testTag("save_slot_card_${info.slot}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail Preview (3:2 GBA aspect ratio)
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap,
                            contentDescription = "${info.slotBadge} screenshot",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    // Slot tag overlay
                    Surface(
                        color = if (info.exists) AmberBoost else SurfaceElevated,
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = info.slotBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (info.exists) Color.Black else TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = info.fileName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (info.slot == 0) {
                            Spacer(Modifier.width(6.dp))
                            BadgeChip("AUTO", SurfaceElevated, TextSecondary)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    if (info.exists) {
                        Text(
                            text = "${dateFormat.format(Date(info.savedAtMillis))} • ${formatKb(info.fileSizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BadgeChip("Play ${formatDuration(info.playTimeSeconds)}", SurfaceElevated, LuminousViolet)
                            Icon(
                                imageVector = if (info.crcMatches) Icons.Default.Verified else Icons.Default.WarningAmber,
                                contentDescription = if (info.crcMatches) "CRC32 Verified" else "CRC32 Mismatch",
                                tint = if (info.crcMatches) EmeraldLed else AmberBoost,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = info.romCrc32Hex,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (info.crcMatches) EmeraldLed else AmberBoost
                            )
                        }
                    } else {
                        Text(
                            text = "Empty slot — tap Save to capture full state",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Primary action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("save_slot_${info.slot}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }

                if (info.exists) {
                    Button(
                        onClick = onLoad,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("load_slot_${info.slot}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldLed.copy(alpha = 0.22f), contentColor = EmeraldLed)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Load", style = MaterialTheme.typography.labelLarge)
                    }

                    IconButton(
                        onClick = onExport,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("export_slot_${info.slot}")
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Export ${info.fileName}", tint = TextSecondary)
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("delete_slot_${info.slot}")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${info.fileName}", tint = CrimsonAction)
                    }
                } else {
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("import_slot_${info.slot}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import .ss${info.slot}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Expandable Hardware Snapshot Details Inspector
            if (info.exists) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expandedDetails = !expandedDetails }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expandedDetails) "Hide Snapshot Hardware Details" else "Inspect Snapshot Hardware Details",
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminousViolet
                    )
                    Icon(
                        imageVector = if (expandedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = LuminousViolet,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = expandedDetails) {
                    Surface(
                        color = AnodizedSlate,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DetailRow("ROM Title / Code", "${info.romTitle} [${info.gameCode}]")
                            DetailRow("ROM CRC32", "${info.romCrc32Hex} (${if (info.crcMatches) "Match" else "Mismatch"})")
                            DetailRow("Program Counter", "${info.pcHex} (${if (info.isThumb) "THUMB 16-bit" else "ARM 32-bit"})")
                            DetailRow("CPU / Video Mode", "Mode ${info.cpuMode} • PPU Mode ${info.videoMode}")
                            DetailRow("CPU Cycles", "%,d cycles".format(info.totalCycles))
                            DetailRow("Backup RAM", info.saveTypeName)
                            DetailRow("Payload / File", "${formatKb(info.compressedStateBytes.toLong())} gzip / ${formatKb(info.fileSizeBytes)} total")
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onImport) {
                                    Text("Replace from .ss${info.slot} file", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatKb(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb >= 1.0) String.format(Locale.US, "%.1f KB", kb) else "$bytes B"
}
