package com.example.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun PortraitConsoleLayout(
    state: EmulatorUiState,
    frameBitmap: Bitmap?,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onQuickSaveSs1: () -> Unit,
    onReset: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenShaders: () -> Unit,
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
    onStepSpeed: (Int) -> Unit
) {
    val prefs = state.preferences
    val scale = prefs.controlScale
    val targetSpeed = state.rom?.preferredSpeedMultiplier ?: prefs.fastForwardSpeed
    val speedStr = if (state.isTurboActive) {
        if (targetSpeed % 1f == 0f) "${targetSpeed.toInt()}x" else "${targetSpeed}x"
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
                IconButton(onClick = onOpenShaders, modifier = Modifier.testTag("player_shaders_button")) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Shaders Manager", tint = LuminousViolet)
                }
                IconButton(onClick = onOpenCheats, modifier = Modifier.testTag("player_cheats_button")) {
                    Icon(Icons.Default.Code, contentDescription = "Cheats (.cht)")
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("player_settings_button")) {
                    Icon(Icons.Default.Tune, contentDescription = "Settings")
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
                    profile = state.activeShaderProfile,
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
                        text = "POCKET ADVANCE • ${state.activeShaderProfile.name.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        targetSpeed = targetSpeed,
                        mode = prefs.speedToggleMode,
                        opacity = prefs.controlOpacity,
                        hapticsEnabled = prefs.hapticsEnabled,
                        onToggle = onTurboToggle,
                        onHoldChange = onTurboHold,
                        onStepSpeed = onStepSpeed
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
fun LandscapeConsoleLayout(
    state: EmulatorUiState,
    frameBitmap: Bitmap?,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenShaders: () -> Unit,
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
    onStepSpeed: (Int) -> Unit
) {
    val prefs = state.preferences
    val scale = prefs.controlScale
    val targetSpeed = state.rom?.preferredSpeedMultiplier ?: prefs.fastForwardSpeed
    val speedStr = if (state.isTurboActive) {
        if (targetSpeed % 1f == 0f) "${targetSpeed.toInt()}x" else "${targetSpeed}x"
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
                profile = state.activeShaderProfile,
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
                    targetSpeed = targetSpeed,
                    mode = prefs.speedToggleMode,
                    opacity = prefs.controlOpacity,
                    hapticsEnabled = prefs.hapticsEnabled,
                    onToggle = onTurboToggle,
                    onHoldChange = onTurboHold,
                    onStepSpeed = onStepSpeed
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
                    IconButton(onClick = onOpenShaders, modifier = Modifier.testTag("player_shaders_button")) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Shaders Manager", tint = LuminousViolet)
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
