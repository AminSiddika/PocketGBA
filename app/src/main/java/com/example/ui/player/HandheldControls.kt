package com.example.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emulator.GbaConstants
import com.example.emulator.SpeedToggleMode
import com.example.ui.theme.AmberBoost
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonAction
import com.example.ui.theme.HandheldIndigo
import com.example.ui.theme.LuminousViolet
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Multi-touch 8-way Cross D-Pad tracking active pointer position.
 */
@Composable
fun RetroDPad(
    size: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    onDirectionMaskChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var activeDirs by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .size(size)
            .testTag("control_dpad")
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id

                    fun computeMask(pos: Offset): Int {
                        val cx = this.size.width / 2f
                        val cy = this.size.height / 2f
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val deadzone = this.size.width * 0.14f
                        var mask = 0
                        if (dx > deadzone) mask = mask or GbaConstants.KEY_RIGHT
                        if (dx < -deadzone) mask = mask or GbaConstants.KEY_LEFT
                        if (dy < -deadzone) mask = mask or GbaConstants.KEY_UP
                        if (dy > deadzone) mask = mask or GbaConstants.KEY_DOWN
                        return mask
                    }

                    val initialMask = computeMask(down.position)
                    if (initialMask != 0 && hapticsEnabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    activeDirs = initialMask
                    onDirectionMaskChanged(initialMask)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            activeDirs = 0
                            onDirectionMaskChanged(0)
                            break
                        }
                        val nextMask = computeMask(change.position)
                        if (nextMask != activeDirs) {
                            if (nextMask != 0 && hapticsEnabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            activeDirs = nextMask
                            onDirectionMaskChanged(nextMask)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val arm = w * 0.34f
            val padOffset = (w - arm) / 2f
            val baseColor = SurfaceElevated.copy(alpha = opacity)
            val strokeColor = BorderSubtle.copy(alpha = opacity)

            // Outer recessed well
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f * opacity),
                radius = w * 0.48f
            )

            // Horizontal bar
            drawRoundRect(
                color = baseColor,
                topLeft = Offset(w * 0.04f, padOffset),
                size = Size(w * 0.92f, arm),
                cornerRadius = CornerRadius(14.dp.toPx())
            )
            // Vertical bar
            drawRoundRect(
                color = baseColor,
                topLeft = Offset(padOffset, h * 0.04f),
                size = Size(arm, h * 0.92f),
                cornerRadius = CornerRadius(14.dp.toPx())
            )

            // Highlight active directional pads
            val activeTint = LuminousViolet.copy(alpha = 0.65f * opacity)
            if ((activeDirs and GbaConstants.KEY_UP) != 0) {
                drawRoundRect(activeTint, Offset(padOffset + 3f, h * 0.05f), Size(arm - 6f, arm * 0.95f), CornerRadius(10.dp.toPx()))
            }
            if ((activeDirs and GbaConstants.KEY_DOWN) != 0) {
                drawRoundRect(activeTint, Offset(padOffset + 3f, h * 0.95f - arm * 0.95f), Size(arm - 6f, arm * 0.95f), CornerRadius(10.dp.toPx()))
            }
            if ((activeDirs and GbaConstants.KEY_LEFT) != 0) {
                drawRoundRect(activeTint, Offset(w * 0.05f, padOffset + 3f), Size(arm * 0.95f, arm - 6f), CornerRadius(10.dp.toPx()))
            }
            if ((activeDirs and GbaConstants.KEY_RIGHT) != 0) {
                drawRoundRect(activeTint, Offset(w * 0.95f - arm * 0.95f, padOffset + 3f), Size(arm * 0.95f, arm - 6f), CornerRadius(10.dp.toPx()))
            }

            // Center pivot dimple
            drawCircle(
                color = strokeColor,
                radius = arm * 0.24f,
                center = Offset(w / 2f, h / 2f)
            )
        }
    }
}

/**
 * Tactile multi-touch circular action button (A / B).
 */
@Composable
fun TactileActionButton(
    label: String,
    accentColor: Color,
    size: Dp,
    opacity: Float,
    hapticsEnabled: Boolean,
    testTag: String,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (pressed) 0.90f else 1f, label = "btnScale")

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        (if (pressed) accentColor else SurfaceElevated).copy(alpha = opacity),
                        AnodizedSlate.copy(alpha = opacity)
                    )
                )
            )
            .border(
                width = 2.dp,
                color = (if (pressed) accentColor else BorderSubtle).copy(alpha = opacity),
                shape = CircleShape
            )
            .testTag(testTag)
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    pressed = true
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPressedChange(true)

                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == id }
                        if (ch == null || !ch.pressed) {
                            pressed = false
                            onPressedChange(false)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (pressed) Color.White else TextPrimary.copy(alpha = opacity)
        )
    }
}

/**
 * Pizza Boy-style ergonomic Speed Toggle button placed directly beside the control pad.
 * - Tap toggles 1x <-> target speed (or Hold-to-Boost when SpeedToggleMode.HOLD is selected).
 * - Swipe (up/right = faster, down/left = slower) or Long-Press cycles target speed (1.5x..8x).
 */
@Composable
fun SpeedTogglePadButton(
    isTurboActive: Boolean,
    targetSpeed: Float,
    mode: SpeedToggleMode,
    opacity: Float,
    hapticsEnabled: Boolean,
    onToggle: () -> Unit,
    onHoldChange: (Boolean) -> Unit,
    onStepSpeed: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val bgColor by animateColorAsState(
        targetValue = if (isTurboActive) AmberBoost.copy(alpha = 0.94f) else SurfaceCard.copy(alpha = opacity),
        label = "speedBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isTurboActive) AmberBoost else BorderSubtle.copy(alpha = opacity),
        label = "speedBorder"
    )
    val scale by animateFloatAsState(targetValue = if (isTurboActive) 1.05f else 1f, label = "speedScale")

    val speedLabel = if (targetSpeed % 1f == 0f) "${targetSpeed.toInt()}x" else "${targetSpeed}x"

    Box(
        modifier = modifier
            .scale(scale)
            .size(width = 62.dp, height = 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .testTag("pad_speed_toggle")
            .pointerInput(mode, hapticsEnabled) {
                val swipeThresholdPx = 22.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val startPos = down.position
                    var gestureHandled = false
                    var longPressJob: Job? = null

                    if (mode == SpeedToggleMode.HOLD) {
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onHoldChange(true)
                    } else {
                        longPressJob = scope.launch {
                            delay(420L)
                            gestureHandled = true
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStepSpeed(1)
                        }
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == pointerId }
                            if (ch == null || !ch.pressed) {
                                longPressJob?.cancel()
                                if (mode == SpeedToggleMode.HOLD) {
                                    onHoldChange(false)
                                } else if (!gestureHandled) {
                                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggle()
                                }
                                break
                            }

                            if (!gestureHandled) {
                                val dx = ch.position.x - startPos.x
                                val dy = ch.position.y - startPos.y
                                if (abs(dx) > swipeThresholdPx || abs(dy) > swipeThresholdPx) {
                                    longPressJob?.cancel()
                                    gestureHandled = true
                                    val delta = if (abs(dx) >= abs(dy)) {
                                        if (dx > 0f) 1 else -1
                                    } else {
                                        if (dy < 0f) 1 else -1
                                    }
                                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStepSpeed(delta)
                                    ch.consume()
                                }
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "Fast Forward Toggle",
                    tint = if (isTurboActive) Color.Black else AmberBoost,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(1.dp))
                Text(
                    text = if (isTurboActive) speedLabel else "SPD",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTurboActive) Color.Black else TextPrimary
                )
            }
            if (!isTurboActive) {
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun ShoulderBumperButton(
    label: String,
    opacity: Float,
    hapticsEnabled: Boolean,
    testTag: String,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) HandheldIndigo.copy(alpha = opacity)
                else SurfaceCard.copy(alpha = opacity)
            )
            .border(
                width = 1.5.dp,
                color = if (pressed) LuminousViolet else BorderSubtle.copy(alpha = opacity),
                shape = RoundedCornerShape(14.dp)
            )
            .testTag(testTag)
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPressedChange(true)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) {
                            pressed = false
                            onPressedChange(false)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (pressed) Color.White else TextSecondary
        )
    }
}

@Composable
fun PillControlButton(
    label: String,
    opacity: Float,
    hapticsEnabled: Boolean,
    testTag: String,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(width = 76.dp, height = 48.dp)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (pressed) HandheldIndigo.copy(alpha = opacity)
                else SurfaceElevated.copy(alpha = opacity)
            )
            .border(1.dp, BorderSubtle.copy(alpha = opacity), RoundedCornerShape(16.dp))
            .testTag(testTag)
            .pointerInput(hapticsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPressedChange(true)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) {
                            pressed = false
                            onPressedChange(false)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (pressed) Color.White else TextSecondary
        )
    }
}
