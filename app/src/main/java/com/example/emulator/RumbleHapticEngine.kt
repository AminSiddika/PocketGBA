package com.example.emulator

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class RumbleHapticEngine(context: Context) {

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile
    private var currentlyVibrating = false

    val hasVibrator: Boolean
        get() = vibrator?.hasVibrator() == true

    fun setRumble(active: Boolean, enabled: Boolean, intensity: Float) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (!active || !enabled) {
            if (currentlyVibrating) {
                currentlyVibrating = false
                runCatching { vib.cancel() }
            }
            return
        }

        if (currentlyVibrating) return
        currentlyVibrating = true
        val amp = (intensity.coerceIn(0.2f, 1.0f) * 255f).toInt().coerceIn(40, 255)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0L, 65L, 12L, 65L),
                    intArrayOf(0, amp, (amp * 0.7f).toInt().coerceAtLeast(30), amp),
                    1
                )
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0L, 70L, 15L), 1)
            }
        }
    }

    fun pulseTest(intensity: Float) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val amp = (intensity.coerceIn(0.2f, 1.0f) * 255f).toInt().coerceIn(50, 255)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0L, 90L, 45L, 130L),
                    intArrayOf(0, amp, 0, amp),
                    -1
                )
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(180L)
            }
        }
    }

    fun stop() {
        if (currentlyVibrating) {
            currentlyVibrating = false
            runCatching { vibrator?.cancel() }
        }
    }
}
