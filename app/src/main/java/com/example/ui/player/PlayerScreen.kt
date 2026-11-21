package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.ShaderProfileEntity
import com.example.emulator.GbaConstants
import com.example.emulator.OrientationMode
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.CrimsonAction
import com.example.ui.theme.GraphiteBg
import com.example.ui.theme.HandheldIndigo

@Composable
fun PlayerScreen(
    romId: Long,
    initialLoadSlot: Int = -1,
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
    var showShadersSheet by remember { mutableStateOf(false) }
    var showCheatsSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var pendingTransferSlot by remember { mutableIntStateOf(1) }
    var pendingExportShader by remember { mutableStateOf<ShaderProfileEntity?>(null) }

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

    LaunchedEffect(romId, initialLoadSlot) {
        viewModel.loadRomSession(romId, initialLoadSlot)
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

    val importShaderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importShaderFile(uri)
    }

    val exportShaderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val profile = pendingExportShader
        if (uri != null && profile != null) {
            viewModel.exportShaderProfile(profile, uri)
        }
        pendingExportShader = null
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
                        onOpenShaders = {
                            viewModel.setPaused(true)
                            showShadersSheet = true
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
                        onStepSpeed = viewModel::stepFastForwardSpeed
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
                        onOpenShaders = {
                            viewModel.setPaused(true)
                            showShadersSheet = true
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
                        onStepSpeed = viewModel::stepFastForwardSpeed
                    )
                }
            }
        }

        // Transient Speed-Step HUD Banner when tapping or swiping/long-pressing SPD
        AnimatedVisibility(
            visible = state.speedStepBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 48.dp)
        ) {
            Surface(
                color = AmberBoost,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.testTag("speed_step_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = state.speedStepBanner.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
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

    if (showShadersSheet) {
        ShaderManagerSheet(
            previewBitmap = frameBitmap,
            profiles = state.shaderProfiles,
            activeGlobalProfileId = state.preferences.activeShaderProfileId,
            currentRom = state.rom,
            onDismiss = {
                showShadersSheet = false
                viewModel.setPaused(false)
            },
            onSelectGlobalProfile = viewModel::selectGlobalShaderProfile,
            onPinProfileToRom = viewModel::pinShaderToCurrentRom,
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
            onToggleGroup = viewModel::toggleCheatGroup,
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
            activeShaderProfile = state.activeShaderProfile,
            currentRom = state.rom,
            onDismiss = { showSettingsSheet = false },
            onOpenShadersManager = {
                showSettingsSheet = false
                viewModel.setPaused(true)
                showShadersSheet = true
            },
            onSelectSpeed = viewModel::setFastForwardSpeed,
            onSetRomSpeedOverride = viewModel::setRomSpeedOverride,
            onSelectSpeedMode = viewModel::setSpeedToggleMode,
            onMuteTurboAudioToggle = viewModel::setMuteTurboAudioAbove2x,
            onAutoSaveExitToggle = viewModel::setAutoSaveOnExit,
            onSelectOrientation = viewModel::setOrientationMode,
            onAudioToggle = viewModel::setAudioEnabled,
            onHapticsToggle = viewModel::setHapticsEnabled,
            onOpacityChange = viewModel::setControlOpacity,
            onScaleChange = viewModel::setControlScale,
            onImportBios = { importBiosLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            onClearBios = viewModel::clearCustomBios
        )
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
