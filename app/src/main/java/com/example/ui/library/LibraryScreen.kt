package com.example.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.db.RomEntity
import com.example.data.db.ShaderProfileEntity
import com.example.ui.player.CheatManagerSheet
import com.example.ui.player.ShaderManagerSheet
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAction
import com.example.ui.theme.EmeraldLed
import com.example.ui.theme.GraphiteBg
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenPlayer: (romId: Long, loadSlot: Int) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var romPendingDelete by remember { mutableStateOf<RomEntity?>(null) }
    var showShadersSheet by remember { mutableStateOf(false) }
    var pendingExportShader by remember { mutableStateOf<ShaderProfileEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshSlotBadges()
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatus()
        }
    }

    val romPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importRom(uri) { romId ->
                onOpenPlayer(romId, -1)
            }
        }
    }

    val importCheatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importCheatFile(uri)
    }

    val exportCheatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportCheats(uri)
    }

    val importShaderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importShaderFile(uri)
    }

    val exportShaderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val profile = pendingExportShader
        if (uri != null && profile != null) {
            viewModel.exportShaderProfile(profile, uri)
        }
        pendingExportShader = null
    }

    Scaffold(
        containerColor = GraphiteBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GraphiteBg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(EmeraldLed)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name).uppercase(),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(R.string.library_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showShadersSheet = true },
                        modifier = Modifier.testTag("toolbar_shaders_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Open Shaders Manager",
                            tint = LuminousViolet
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.launchBuiltInDemo { id -> onOpenPlayer(id, -1) } },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("toolbar_demo_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = "Launch homebrew demo",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Demo", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                containerColor = HandheldIndigo,
                contentColor = Color.White,
                modifier = Modifier.testTag("import_rom_fab"),
                icon = { Icon(Icons.Default.Add, contentDescription = "Import ROM") },
                text = { Text("Import ROM", style = MaterialTheme.typography.labelLarge) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(GraphiteBg, AnodizedSlate.copy(alpha = 0.7f), GraphiteBg)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (state.roms.isNotEmpty() || state.searchQuery.isNotBlank()) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 12.dp)
                            .testTag("rom_search_input"),
                        placeholder = { Text("Search ROM title, Game Code, or CRC32...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard,
                            focusedBorderColor = LuminousViolet,
                            unfocusedBorderColor = BorderSubtle
                        )
                    )
                }

                AnimatedVisibility(visible = state.isImporting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading GBA cartridge...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (state.roms.isEmpty() && state.searchQuery.isBlank()) {
                    EmptyShelfView(
                        onImportClick = {
                            romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                        },
                        onDemoClick = { viewModel.launchBuiltInDemo { id -> onOpenPlayer(id, -1) } }
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = if (maxWidth >= 680.dp) 2 else 1
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                        ) {
                            items(state.roms, key = { it.rom.id }) { card ->
                                RomCartridgeCard(
                                    card = card,
                                    onPlay = { onOpenPlayer(card.rom.id, -1) },
                                    onResumeAuto = {
                                        val resumeSlot = when {
                                            card.hasSs0 -> 0
                                            card.hasSs1 -> 1
                                            else -> 2
                                        }
                                        onOpenPlayer(card.rom.id, resumeSlot)
                                    },
                                    onOpenCheats = { viewModel.openCheatManager(card.rom) },
                                    onDelete = { romPendingDelete = card.rom }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showShadersSheet) {
        ShaderManagerSheet(
            previewBitmap = null,
            profiles = state.shaderProfiles,
            activeGlobalProfileId = state.activeShaderProfileId,
            currentRom = null,
            onDismiss = { showShadersSheet = false },
            onSelectGlobalProfile = viewModel::selectGlobalShaderProfile,
            onSaveProfile = viewModel::saveShaderProfile,
            onDeleteCustomProfile = viewModel::deleteCustomShaderProfile,
            onImportShaderFile = {
                importShaderLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream", "*/*"))
            },
            onExportProfile = { profile ->
                pendingExportShader = profile
                exportShaderLauncher.launch("${profile.name.replace(" ", "_").lowercase()}.json")
            }
        )
    }

    state.managingCheatsForRom?.let { targetRom ->
        CheatManagerSheet(
            romTitle = targetRom.title,
            cheats = state.managedRomCheats,
            masterEnabled = state.masterCheatsEnabled,
            zipCandidates = state.zipCandidates,
            onDismiss = viewModel::closeCheatManager,
            onToggleMaster = viewModel::setMasterCheatsEnabled,
            onToggleCheat = viewModel::toggleCheat,
            onToggleGroup = viewModel::toggleCheatGroup,
            onSetAllEnabled = viewModel::setAllCheatsEnabled,
            onSaveCheat = viewModel::saveCheat,
            onDeleteCheat = viewModel::deleteCheat,
            onImportCheatFile = {
                importCheatLauncher.launch(arrayOf("text/plain", "application/zip", "application/octet-stream", "*/*"))
            },
            onExportCheatFile = {
                exportCheatLauncher.launch("${targetRom.baseName}.cht")
            },
            onPickZipCandidate = viewModel::importZipCandidate,
            onDismissZipCandidates = viewModel::dismissZipCandidates
        )
    }

    romPendingDelete?.let { rom ->
        AlertDialog(
            onDismissRequest = { romPendingDelete = null },
            containerColor = SurfaceCard,
            title = { Text("Remove Cartridge?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Remove ${rom.title} from your shelf? Your .ss1/.ss2 save states and .sav battery file remain safely stored.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRom(rom)
                        romPendingDelete = null
                    }
                ) {
                    Text("Remove", color = CrimsonAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { romPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyShelfView(
    onImportClick: () -> Unit,
    onDemoClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_handheld_empty_1795238465049),
                    contentDescription = "Retro handheld console illustration",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.empty_library_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.empty_library_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onImportClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("empty_import_rom_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_rom), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDemoClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("empty_launch_demo_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.launch_demo), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun RomCartridgeCard(
    card: RomLibraryCard,
    onPlay: () -> Unit,
    onResumeAuto: () -> Unit,
    onOpenCheats: () -> Unit,
    onDelete: () -> Unit
) {
    val rom = card.rom
    val hasResumeSlot = card.hasSs0 || card.hasSs1 || card.hasSs2

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay)
            .testTag("rom_card_${rom.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(HandheldIndigo, SurfaceElevated))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Gamepad,
                        contentDescription = "GBA Cartridge",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rom.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (rom.isBuiltInDemo) {
                            Spacer(Modifier.width(6.dp))
                            BadgeChip("DEMO", EmeraldLed.copy(alpha = 0.18f), EmeraldLed)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BadgeChip(rom.gameCode, SurfaceElevated, LuminousViolet)
                        BadgeChip(rom.saveType, SurfaceElevated, TextSecondary)
                        BadgeChip(rom.crc32Hex, SurfaceElevated, TextSecondary)
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_rom_${rom.id}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove ROM",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Play stats + .ss0 / .ss1 / .ss2 slot status bar
            Surface(
                color = AnodizedSlate,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = TextSecondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatDuration(rom.totalPlaySeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = AmberBoost
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${card.saveSlotCount} slots",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (card.hasSs0) BadgeChip("SS0", EmeraldLed.copy(alpha = 0.2f), EmeraldLed)
                        if (card.hasSs1) BadgeChip("SS1", AmberBoost.copy(alpha = 0.2f), AmberBoost)
                        if (card.hasSs2) BadgeChip("SS2", LuminousViolet.copy(alpha = 0.2f), LuminousViolet)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Shelf quick actions: Manage Cheats (.cht) & Resume State
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenCheats,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("card_cheats_button_${rom.id}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (card.totalCheats > 0) "Cheats (${card.enabledCheats}/${card.totalCheats})" else "Cheats",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (hasResumeSlot) {
                    Button(
                        onClick = onResumeAuto,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("card_resume_button_${rom.id}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldLed.copy(alpha = 0.20f),
                            contentColor = EmeraldLed
                        )
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (card.hasSs0) "Resume SS0" else "Resume SS1",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HandheldIndigo)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeChip(text: String, bg: Color, fg: Color) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val mins = seconds / 60
    val hrs = mins / 60
    return if (hrs > 0) "${hrs}h ${mins % 60}m" else "${mins}m"
}
