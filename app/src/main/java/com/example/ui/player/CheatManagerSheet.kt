package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.CheatEntity
import com.example.emulator.cheats.CheatDecoder
import com.example.emulator.cheats.CheatFormat
import com.example.emulator.cheats.ZipCheatCandidate
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatManagerSheet(
    romTitle: String,
    cheats: List<CheatEntity>,
    masterEnabled: Boolean,
    zipCandidates: List<ZipCheatCandidate>,
    onDismiss: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onToggleCheat: (CheatEntity) -> Unit,
    onToggleGroup: (groupName: String, enabled: Boolean) -> Unit,
    onSetAllEnabled: (Boolean) -> Unit,
    onSaveCheat: (id: Long, name: String, code: String, format: CheatFormat, groupName: String) -> Unit,
    onDeleteCheat: (Long) -> Unit,
    onImportCheatFile: () -> Unit,
    onExportCheatFile: () -> Unit,
    onPickZipCandidate: (ZipCheatCandidate) -> Unit,
    onDismissZipCandidates: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editingCheat by remember { mutableStateOf<CheatEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFormatFilter by remember { mutableStateOf<CheatFormat?>(null) }
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val filteredCheats = remember(cheats, searchQuery, selectedFormatFilter) {
        val q = searchQuery.trim().lowercase()
        cheats.filter { cheat ->
            val fmtMatches = selectedFormatFilter == null || cheat.formatName == selectedFormatFilter?.name
            val qMatches = q.isEmpty() ||
                cheat.name.lowercase().contains(q) ||
                cheat.code.lowercase().contains(q) ||
                cheat.groupName.lowercase().contains(q)
            fmtMatches && qMatches
        }
    }

    val groupedCheats = remember(filteredCheats) {
        filteredCheats.groupBy { it.groupName.ifBlank { "Manual" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

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
                    Text("CHEAT CODE MANAGER", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "$romTitle • .cht / .zip / CBA / GS / AR",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = onToggleMaster,
                    modifier = Modifier.testTag("master_cheats_switch")
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { editingCheat = null; showAddDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_cheat_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Code", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onImportCheatFile,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_cht_button")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import .cht/.zip", style = MaterialTheme.typography.labelLarge)
                }

                IconButton(
                    onClick = onExportCheatFile,
                    modifier = Modifier.testTag("export_cht_button")
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Export .cht", tint = TextSecondary)
                }
            }

            if (cheats.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search cheat name, hex code, or pack...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = SurfaceCard,
                        focusedBorderColor = LuminousViolet,
                        unfocusedBorderColor = BorderSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cheat_search_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedFormatFilter == null,
                        onClick = { selectedFormatFilter = null },
                        label = { Text("All (${cheats.size})", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HandheldIndigo,
                            selectedLabelColor = Color.White
                        )
                    )
                    CheatFormat.entries.filter { it != CheatFormat.AUTO }.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormatFilter == fmt,
                            onClick = {
                                selectedFormatFilter = if (selectedFormatFilter == fmt) null else fmt
                            },
                            label = { Text(fmt.label.substringBefore(" "), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HandheldIndigo,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${cheats.count { it.enabled }}/${cheats.size} active across ${groupedCheats.size} pack(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (masterEnabled) EmeraldLed else AmberBoost
                    )
                    Row {
                        TextButton(onClick = { onSetAllEnabled(true) }) { Text("Enable All") }
                        TextButton(onClick = { onSetAllEnabled(false) }) { Text("Disable All") }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (cheats.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("No Cheats Added", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap Add Code for CodeBreaker, GameShark v1/v2, Action Replay v3/MAX, or Raw patches—or import a .cht file or whole .zip cheat pack.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else if (filteredCheats.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = "No cheats match \"$searchQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    groupedCheats.forEach { (groupName, groupItems) ->
                        val isCollapsed = collapsedGroups[groupName] == true
                        val groupAllEnabled = groupItems.isNotEmpty() && groupItems.all { it.enabled }
                        val groupEnabledCount = groupItems.count { it.enabled }

                        item(key = "header_$groupName") {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                    .clickable { collapsedGroups[groupName] = !isCollapsed }
                                    .testTag("cheat_group_header_$groupName")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = AmberBoost,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = groupName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "$groupEnabledCount/${groupItems.size} enabled",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (groupEnabledCount > 0) EmeraldLed else TextSecondary
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = groupAllEnabled,
                                            onCheckedChange = { checked -> onToggleGroup(groupName, checked) },
                                            modifier = Modifier.testTag("cheat_group_switch_$groupName")
                                        )
                                        Icon(
                                            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                            contentDescription = if (isCollapsed) "Expand group" else "Collapse group",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (!isCollapsed) {
                            items(groupItems, key = { it.id }) { cheat ->
                                val fmt = runCatching { CheatFormat.valueOf(cheat.formatName) }.getOrDefault(CheatFormat.AUTO)
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 6.dp)
                                        .border(
                                            1.dp,
                                            if (cheat.enabled) HandheldIndigo.copy(alpha = 0.6f) else BorderSubtle,
                                            RoundedCornerShape(14.dp)
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cheat.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = cheat.code.lines().take(2).joinToString(" + "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LuminousViolet,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                BadgeChip(fmt.label, SurfaceElevated, TextSecondary)
                                                BadgeChip(cheat.sourceFormat, SurfaceElevated, TextMuted)
                                            }
                                        }
                                        IconButton(onClick = {
                                            editingCheat = cheat
                                            showAddDialog = true
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit cheat", tint = TextSecondary)
                                        }
                                        IconButton(onClick = { onDeleteCheat(cheat.id) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete cheat", tint = CrimsonAction)
                                        }
                                        Switch(
                                            checked = cheat.enabled,
                                            onCheckedChange = { onToggleCheat(cheat) },
                                            modifier = Modifier.testTag("cheat_switch_${cheat.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditCheatDialog(
            initial = editingCheat,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, code, format, groupName ->
                onSaveCheat(editingCheat?.id ?: 0L, name, code, format, groupName)
                showAddDialog = false
            }
        )
    }

    if (zipCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissZipCandidates,
            containerColor = SurfaceCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderZip, contentDescription = null, tint = AmberBoost)
                    Spacer(Modifier.width(8.dp))
                    Text("Select .cht from Pack", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(zipCandidates, key = { it.entryName }) { cand ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AnodizedSlate),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickZipCandidate(cand) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cand.entryName.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${cand.cheatCountEstimate} cheats",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                if (cand.matchScore > 0) {
                                    BadgeChip("MATCH", EmeraldLed.copy(alpha = 0.2f), EmeraldLed)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissZipCandidates) { Text("Close") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditCheatDialog(
    initial: CheatEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, CheatFormat, String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var groupName by remember { mutableStateOf(initial?.groupName ?: "Manual") }
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var format by remember {
        mutableStateOf(
            initial?.formatName?.let { runCatching { CheatFormat.valueOf(it) }.getOrNull() }
                ?: CheatFormat.AUTO
        )
    }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val preview = remember(code, format) { CheatDecoder.inspectPreview(code, format) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text(if (initial == null) "Add GBA Cheat" else "Edit GBA Cheat", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Cheat Name") },
                    placeholder = { Text("e.g. Infinite Master Balls") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group / Pack Folder") },
                    placeholder = { Text("e.g. Favorites, Items, Manual") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = format.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Code Format") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        CheatFormat.entries.forEach { fmt ->
                            DropdownMenuItem(
                                text = { Text(fmt.label) },
                                onClick = {
                                    format = fmt
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Hex Codes (Multi-line supported)") },
                    placeholder = { Text("82025840 0044\nor XXXXXXXX YYYYYYYY") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Hex Syntax & Decoded Memory Target Preview
                AnimatedVisibility(visible = code.isNotBlank()) {
                    Surface(
                        color = AnodizedSlate,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (preview.isValid) EmeraldLed.copy(alpha = 0.6f) else CrimsonAction.copy(alpha = 0.7f),
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (preview.isValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = if (preview.isValid) EmeraldLed else CrimsonAction,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = preview.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (preview.isValid) EmeraldLed else CrimsonAction
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, code, format, groupName.ifBlank { "Manual" }) },
                enabled = name.isNotBlank() && code.isNotBlank() && preview.isValid
            ) {
                Text("Save Cheat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
