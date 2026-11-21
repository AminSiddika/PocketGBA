package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emulator.GbaConstants
import com.example.emulator.OrientationMode
import com.example.ui.library.BadgeChip
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun PlayerScreen(
    romId: Long,
    viewModel: EmulatorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val frameBitmap by viewModel.frameBitmap.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    var showSaveStatesSheet by remember { mutableStateOf(false) }
    var showCheatsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var pendingTransferSlot by remember { mutableIntStateOf(1) }

    // Input mask tracking across touch & physical keyboard/gamepad
    var dpadMask by remember { mutableIntStateOf(0) }
    var aPressed by remember { mutableStateOf(false) }
    var bPressed by remember { mutableStateOf(false) }
    var lPressed by remember { mutableStateOf(false) }
    var rPressed by remember { mutableStateOf(false) }
    var startPressed by remember { mutableStateOf(false) }
    var selectPressed by remember { mutableStateOf(false) }
    var hwKeyMask by remember { mutableIntStateOf(0) }

    val combinedMask = remember(
        dpadMask, aPressed, bPressed, lPressed, rPressed, startPressed, selectPressed, hwKeyMask
    ) {
        var m = dpadMask or hwKeyMask
        if (aPressed) m = m or GbaConstants.KEY_A
        if (bPressed) m = m or GbaConstants.KEY_B
        if (lPressed) m = m or GbaConstants.KEY_L
        if (rPressed) m = m or GbaConstants.KEY_R
        if (startPressed) m = m or GbaConstants.KEY_START
        if (selectPressed) m = m or GbaConstants.KEY_SELECT
        m
    }

    LaunchedEffect(combinedMask) {
        viewModel.updateKeyMask(combinedMask)
    }

    LaunchedEffect(romId) {
        viewModel.loadRomSession(romId)
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    LaunchedEffect(state.preferences.orientationMode) {
        activity?.requestedOrientation = when (state.preferences.orientationMode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.flushAndPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushAndPause()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        viewModel.flushAndPause()
        onBack()
    }

    // SAF Launchers (zero broad storage permissions)
    val exportSlotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.exportSlotToUri(pendingTransferSlot, uri)
    }

    val importSlotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importSlotFromUri(pendingTransferSlot, uri)
    }

    val importCheatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importCheatFileFromUri(uri)
    }

    val exportCheatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportCheatsToUri(uri)
    }

    val importBiosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importBios(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphiteBg)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val gbaBit = mapAndroidKeyCodeToGbaBit(ev.nativeKeyEvent.keyCode)
                if (gbaBit != 0) {
                    hwKeyMask = if (ev.type == KeyEventType.KeyDown) {
                        hwKeyMask or gbaBit
                    } else {
                        hwKeyMask and gbaBit.inv()
                    }
                    true
                } else if (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_SPACE &&
                    ev.type == KeyEventType.KeyDown
                ) {
                    viewModel.toggleTurbo()
                    true
                } else {
                    false
                }
            }
    ) {
        if (state.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = HandheldIndigo)
                Spacer(Modifier.height(12.dp))
                Text("Booting GBA Core...", style = MaterialTheme.typography.titleMedium)
            }
        } else if (state.errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.errorMessage ?: "Error", color = CrimsonAction, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text("Back to Library") }
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val isLandscape = maxWidth > maxHeight
                if (isLandscape) {
                    LandscapeConsoleLayout(
                        state = state,
                        frameBitmap = frameBitmap,
                        onBack = {
                            viewModel.flushAndPause()
                            onBack()
                        },
                        onTogglePause = viewModel::togglePause,
                        onOpenSaveStates = {
                            viewModel.setPaused(true)
                            showSaveStatesSheet = true
                        },
                        onOpenCheats = {
                            viewModel.setPaused(true)
                            showCheatsSheet = true
                        },
                        onOpenSettings = { showSettingsSheet = true },
                        onDirectionMask = { dpadMask = it },
                        onAChange = { aPressed = it },
                        onBChange = { bPressed = it },
                        onLChange = { lPressed = it },
                        onRChange = { rPressed = it },
                        onStartChange = { startPressed = it },
                        onSelectChange = { selectPressed = it },
                        onTurboToggle = viewModel::toggleTurbo,
                        onTurboHold = viewModel::setTurboHeld,
                        onCycleSpeed = viewModel::cycleFastForwardSpeed
                    )
                } else {
                    PortraitConsoleLayout(
                        state = state,
                        frameBitmap = frameBitmap,
                        onBack = {
                            viewModel.flushAndPause()
                            onBack()
                        },
                        onTogglePause = viewModel::togglePause,
                        onQuickSaveSs1 = { viewModel.saveToSlot(1) },
                        onReset = viewModel::resetConsole,
                        onOpenSaveStates = {
                            viewModel.setPaused(true)
                            showSaveStatesSheet = true
                        },
                        onOpenCheats = {
                            viewModel.setPaused(true)
                            showCheatsSheet = true
                        },
                        onOpenSettings = { showSettingsSheet = true },
                        onDirectionMask = { dpadMask = it },
                        onAChange = { aPressed = it },
                        onBChange = { bPressed = it },
                        onLChange = { lPressed = it },
                        onRChange = { rPressed = it },
                        onStartChange = { startPressed = it },
                        onSelectChange = { selectPressed = it },
                        onTurboToggle = viewModel::toggleTurbo,
                        onTurboHold = viewModel::setTurboHeld,
                        onCycleSpeed = viewModel::cycleFastForwardSpeed
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 8.dp)
        )
    }

    if (showSaveStatesSheet) {
        SaveStateSheet(
            romTitle = state.rom?.title ?: "GBA Cartridge",
            slots = state.slots,
            onDismiss = {
                showSaveStatesSheet = false
                viewModel.setPaused(false)
            },
            onSaveSlot = { slot -> viewModel.saveToSlot(slot) },
            onLoadSlot = { slot ->
                viewModel.loadFromSlot(slot)
                showSaveStatesSheet = false
            },
            onDeleteSlot = { slot -> viewModel.deleteSlot(slot) },
            onExportSlot = { slot ->
                pendingTransferSlot = slot
                val base = state.rom?.baseName ?: "save"
                exportSlotLauncher.launch("$base.ss$slot")
            },
            onImportSlot = { slot ->
                pendingTransferSlot = slot
                importSlotLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }
        )
    }

    if (showCheatsSheet) {
        CheatManagerSheet(
            romTitle = state.rom?.title ?: "GBA Cartridge",
            cheats = state.cheats,
            masterEnabled = state.preferences.masterCheatsEnabled,
            zipCandidates = state.zipCandidates,
            onDismiss = {
                showCheatsSheet = false
                viewModel.setPaused(false)
            },
            onToggleMaster = viewModel::setMasterCheatsEnabled,
            onToggleCheat = viewModel::toggleCheat,
            onSetAllEnabled = viewModel::setAllCheatsEnabled,
            onSaveCheat = viewModel::addOrEditCheat,
            onDeleteCheat = viewModel::deleteCheat,
            onImportCheatFile = {
                importCheatLauncher.launch(arrayOf("text/plain", "application/zip", "application/octet-stream", "*/*"))
            },
            onExportCheatFile = {
                val base = state.rom?.baseName ?: "cheats"
                exportCheatLauncher.launch("$base.cht")
            },
            onPickZipCandidate = viewModel::importZipCandidate,
            onDismissZipCandidates = viewModel::dismissZipCandidates
        )
    }

    if (showSettingsSheet) {
        ShaderAndSettingsSheet(
            preferences = state.preferences,
            onDismiss = { showSettingsSheet = false },
            onSelectShader = viewModel::setShader,
            onSelectSpeed = viewModel::setFastForwardSpeed,
            onSelectSpeedMode = viewModel::setSpeedToggleMode,
            onSelectOrientation = viewModel::setOrientationMode,
            onLcdGridChange = viewModel::setLcdGridIntensity,
            onAudioToggle = viewModel::setAudioEnabled,
            onHapticsToggle = viewModel::setHapticsEnabled,
            onOpacityChange = viewModel::setControlOpacity,
            onScaleChange = viewModel::setControlScale,
            onImportBios = { importBiosLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            onClearBios = viewModel::clearCustomBios
        )
    }
}

@Composable
private fun PortraitConsoleLayout(
    state: EmulatorUiState,
    frameBitmap: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onQuickSaveSs1: () -> Unit,
    onReset: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenSettings: () -> Unit,
    onDirectionMask: (Int) -> Unit,
    onAChange: (Boolean) -> Unit,
    onBChange: (Boolean) -> Unit,
    onLChange: (Boolean) -> Unit,
    onRChange: (Boolean) -> Unit,
    onStartChange: (Boolean) -> Unit,
    onSelectChange: (Boolean) -> Unit,
    onTurboToggle: () -> Unit,
    onTurboHold: (Boolean) -> Unit,
    onCycleSpeed: () -> Unit
) {
    val prefs = state.preferences
    val scale = prefs.controlScale
    val speedStr = if (state.isTurboActive) {
        val spd = prefs.fastForwardSpeed
        if (spd % 1f == 0f) "${spd.toInt()}x" else "${spd}x"
    } else "1x"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GraphiteBg, AnodizedSlate)))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top HUD Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("player_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library")
                }
                Column {
                    Text(
                        text = state.rom?.title ?: "GBA",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BadgeChip("${state.fps} FPS", SurfaceCard, EmeraldLed)
                        BadgeChip(
                            speedStr,
                            if (state.isTurboActive) AmberBoost else SurfaceCard,
                            if (state.isTurboActive) Color.Black else TextSecondary
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePause, modifier = Modifier.testTag("player_pause_button")) {
                    Icon(
                        imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.isPaused) "Resume" else "Pause"
                    )
                }
                IconButton(onClick = onQuickSaveSs1, modifier = Modifier.testTag("player_quicksave_ss1")) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Quick Save .ss1", tint = AmberBoost)
                }
                IconButton(onClick = onOpenSaveStates, modifier = Modifier.testTag("player_savestates_button")) {
                    Icon(Icons.Default.Bookmarks, contentDescription = "Save States (.ss1-.ss9)")
                }
                IconButton(onClick = onOpenCheats, modifier = Modifier.testTag("player_cheats_button")) {
                    Icon(Icons.Default.Code, contentDescription = "Cheats (.cht)")
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("player_settings_button")) {
                    Icon(Icons.Default.Tune, contentDescription = "Shaders & Settings")
                }
            }
        }

        // GBA Anodized Screen Bezel
        Surface(
            color = Color(0xFF0E1016),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, BorderSubtle, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GbaLcdDisplay(
                    frameBitmap = frameBitmap,
                    shaderPreset = prefs.shaderPreset,
                    lcdGridIntensity = prefs.lcdGridIntensity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("gba_lcd_display")
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (state.isPaused) AmberBoost else EmeraldLed)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.isPaused) "PAUSED" else "POWER",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = "POCKET ADVANCE • ${prefs.shaderPreset.label.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }

        // Lower Ergonomic Handheld Control Deck
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // L / R Shoulder Triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShoulderBumperButton(
                    label = "L",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_l",
                    onPressedChange = onLChange,
                    modifier = Modifier.width(104.dp)
                )
                IconButton(onClick = onReset, modifier = Modifier.testTag("player_reset_button")) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset Console", tint = TextSecondary)
                }
                ShoulderBumperButton(
                    label = "R",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_r",
                    onPressedChange = onRChange,
                    modifier = Modifier.width(104.dp)
                )
            }

            // Primary Control Deck: D-Pad + Pizza Boy-Style Speed Toggle beside it | B & A
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RetroDPad(
                        size = (140 * scale).dp,
                        opacity = prefs.controlOpacity,
                        hapticsEnabled = prefs.hapticsEnabled,
                        onDirectionMaskChanged = onDirectionMask
                    )
                    SpeedTogglePadButton(
                        isTurboActive = state.isTurboActive,
                        targetSpeed = prefs.fastForwardSpeed,
                        mode = prefs.speedToggleMode,
                        opacity = prefs.controlOpacity,
                        hapticsEnabled = prefs.hapticsEnabled,
                        onToggle = onTurboToggle,
                        onHoldChange = onTurboHold,
                        onCycleSpeed = onCycleSpeed
                    )
                }

                // Classic GBA angled B & A buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TactileActionButton(
                        label = "B",
                        accentColor = CrimsonAction,
                        size = (64 * scale).dp,
                        opacity = prefs.controlOpacity,
                        hapticsEnabled = prefs.hapticsEnabled,
                        testTag = "control_b",
                        onPressedChange = onBChange,
                        modifier = Modifier.offset(y = 14.dp)
                    )
                    TactileActionButton(
                        label = "A",
                        accentColor = HandheldIndigo,
                        size = (64 * scale).dp,
                        opacity = prefs.controlOpacity,
                        hapticsEnabled = prefs.hapticsEnabled,
                        testTag = "control_a",
                        onPressedChange = onAChange,
                        modifier = Modifier.offset(y = (-10).dp)
                    )
                }
            }

            // Bottom SELECT / START Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillControlButton(
                    label = "SELECT",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_select",
                    onPressedChange = onSelectChange
                )
                Spacer(Modifier.width(24.dp))
                PillControlButton(
                    label = "START",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_start",
                    onPressedChange = onStartChange
                )
            }
        }
    }
}

@Composable
private fun LandscapeConsoleLayout(
    state: EmulatorUiState,
    frameBitmap: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenSettings: () -> Unit,
    onDirectionMask: (Int) -> Unit,
    onAChange: (Boolean) -> Unit,
    onBChange: (Boolean) -> Unit,
    onLChange: (Boolean) -> Unit,
    onRChange: (Boolean) -> Unit,
    onStartChange: (Boolean) -> Unit,
    onSelectChange: (Boolean) -> Unit,
    onTurboToggle: () -> Unit,
    onTurboHold: (Boolean) -> Unit,
    onCycleSpeed: () -> Unit
) {
    val prefs = state.preferences
    val scale = prefs.controlScale
    val speedStr = if (state.isTurboActive) {
        val spd = prefs.fastForwardSpeed
        if (spd % 1f == 0f) "${spd.toInt()}x" else "${spd}x"
    } else "1x"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0F14))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Centered 3:2 GBA Display
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1.5f)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(10.dp))
                .border(1.5.dp, BorderSubtle, RoundedCornerShape(10.dp))
        ) {
            GbaLcdDisplay(
                frameBitmap = frameBitmap,
                shaderPreset = prefs.shaderPreset,
                lcdGridIntensity = prefs.lcdGridIntensity,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gba_lcd_display")
            )

            // Compact top telemetry overlay
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BadgeChip("${state.fps} FPS", Color.Black.copy(alpha = 0.65f), EmeraldLed)
                BadgeChip(
                    speedStr,
                    if (state.isTurboActive) AmberBoost else Color.Black.copy(alpha = 0.65f),
                    if (state.isTurboActive) Color.Black else TextSecondary
                )
            }
        }

        // Left Control Deck Overlay: L, D-Pad + Speed Toggle beside D-pad, SELECT
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                }
                ShoulderBumperButton(
                    label = "L",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_l",
                    onPressedChange = onLChange,
                    modifier = Modifier.width(86.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RetroDPad(
                    size = (132 * scale).dp,
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    onDirectionMaskChanged = onDirectionMask
                )
                SpeedTogglePadButton(
                    isTurboActive = state.isTurboActive,
                    targetSpeed = prefs.fastForwardSpeed,
                    mode = prefs.speedToggleMode,
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    onToggle = onTurboToggle,
                    onHoldChange = onTurboHold,
                    onCycleSpeed = onCycleSpeed
                )
            }

            PillControlButton(
                label = "SELECT",
                opacity = prefs.controlOpacity,
                hapticsEnabled = prefs.hapticsEnabled,
                testTag = "control_select",
                onPressedChange = onSelectChange
            )
        }

        // Right Control Deck Overlay: HUD Actions + R, B & A, START
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePause, modifier = Modifier.testTag("player_pause_button")) {
                        Icon(
                            if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause/Resume",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = onOpenSaveStates, modifier = Modifier.testTag("player_savestates_button")) {
                        Icon(Icons.Default.Bookmarks, contentDescription = "Save States", tint = TextSecondary)
                    }
                    IconButton(onClick = onOpenCheats, modifier = Modifier.testTag("player_cheats_button")) {
                        Icon(Icons.Default.Code, contentDescription = "Cheats", tint = TextSecondary)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("player_settings_button")) {
                        Icon(Icons.Default.Tune, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
                ShoulderBumperButton(
                    label = "R",
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_r",
                    onPressedChange = onRChange,
                    modifier = Modifier.width(86.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactileActionButton(
                    label = "B",
                    accentColor = CrimsonAction,
                    size = (62 * scale).dp,
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_b",
                    onPressedChange = onBChange,
                    modifier = Modifier.offset(y = 14.dp)
                )
                TactileActionButton(
                    label = "A",
                    accentColor = HandheldIndigo,
                    size = (62 * scale).dp,
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    testTag = "control_a",
                    onPressedChange = onAChange,
                    modifier = Modifier.offset(y = (-10).dp)
                )
            }

            PillControlButton(
                label = "START",
                opacity = prefs.controlOpacity,
                hapticsEnabled = prefs.hapticsEnabled,
                testTag = "control_start",
                onPressedChange = onStartChange
            )
        }
    }
}

private fun mapAndroidKeyCodeToGbaBit(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> GbaConstants.KEY_UP
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> GbaConstants.KEY_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> GbaConstants.KEY_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> GbaConstants.KEY_RIGHT
    KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_BUTTON_A -> GbaConstants.KEY_A
    KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_BUTTON_B -> GbaConstants.KEY_B
    KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_BUTTON_L1 -> GbaConstants.KEY_L
    KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_BUTTON_R1 -> GbaConstants.KEY_R
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START -> GbaConstants.KEY_START
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BUTTON_SELECT -> GbaConstants.KEY_SELECT
    else -> 0
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
