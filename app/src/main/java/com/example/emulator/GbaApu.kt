package com.example.emulator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream

class GbaApu(private val mmu: GbaMmu) {
    companion object {
        const val SAMPLE_RATE = 32768
        const val CYCLES_PER_SAMPLE = 512 // 16,777,216 Hz / 32,768 Hz = 512 cycles/sample
        const val SAMPLES_PER_FRAME = 548 // ~280,896 / 512
        private const val CYCLES_PER_FRAME_SEQ_STEP = 32768 // 512 Hz frame sequencer (16,777,216 / 512)

        private val DUTY_TABLE = arrayOf(
            intArrayOf(-1, -1, -1, -1, -1, -1, -1, 1), // 12.5%
            intArrayOf(1, -1, -1, -1, -1, -1, -1, 1),  // 25%
            intArrayOf(1, -1, -1, -1, -1, 1, 1, 1),    // 50%
            intArrayOf(-1, 1, 1, 1, 1, 1, 1, -1)       // 75%
        )

        private val NOISE_DIVISORS = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)
    }

    // DirectSound FIFOs A & B (32 bytes each)
    private val fifoA = ByteArray(32)
    private var fifoAHead = 0
    private var fifoATail = 0
    private var fifoACount = 0
    private var fifoASample = 0

    private val fifoB = ByteArray(32)
    private var fifoBHead = 0
    private var fifoBTail = 0
    private var fifoBCount = 0
    private var fifoBSample = 0

    // PSG Channel 1 (Sweep + Square)
    private var ch1Enabled = false
    private var ch1SweepTimer = 0
    private var ch1SweepShadow = 0
    private var ch1SweepEnabled = false
    private var ch1Length = 0
    private var ch1EnvVol = 0
    private var ch1EnvTimer = 0
    private var ch1FreqTimer = 0
    private var ch1DutyStep = 0

    // PSG Channel 2 (Square)
    private var ch2Enabled = false
    private var ch2Length = 0
    private var ch2EnvVol = 0
    private var ch2EnvTimer = 0
    private var ch2FreqTimer = 0
    private var ch2DutyStep = 0

    // PSG Channel 3 (Wave RAM: 2 banks of 16 bytes)
    private val waveRam = Array(2) { ByteArray(16) }
    private var ch3Enabled = false
    private var ch3Length = 0
    private var ch3FreqTimer = 0
    private var ch3SamplePos = 0

    // PSG Channel 4 (Noise LFSR)
    private var ch4Enabled = false
    private var ch4Length = 0
    private var ch4EnvVol = 0
    private var ch4EnvTimer = 0
    private var ch4FreqTimer = 0
    private var ch4Lfsr = 0x7FFF

    // Sequencer & Cycle Accumulators
    private var frameSeqCycles = 0
    private var frameSeqStep = 0
    private var sampleCycleAcc = 0
    private var turboStrideAcc = 0f

    // Staging PCM buffer (16-bit interleaved stereo; sized comfortably for frame jitter)
    private val pcmBuffer = ShortArray(2048)
    private var pcmWriteIndex = 0
    private var currentFrameSpeed = 1f
    private var shouldOutputAudioThisFrame = true

    // DC-blocking high-pass filter state (eliminates clicks/pops)
    private var prevInL = 0f
    private var prevOutL = 0f
    private var prevInR = 0f
    private var prevOutR = 0f

    private var audioTrack: AudioTrack? = null
    @Volatile var enabled: Boolean = true
    @Volatile var volume: Float = 0.8f
    @Volatile var diagnosticCaptureEnabled: Boolean = false
    private var lastMixedPcmSnapshot = ShortArray(0)

    fun readPcmSnapshot(): ShortArray =
        if (pcmWriteIndex > 0) pcmBuffer.copyOf(pcmWriteIndex) else lastMixedPcmSnapshot

    init {
        mmu.onFifoWrite = { ch, word -> pushFifoWord(ch, word) }
        mmu.onTimerOverflow = { timerIdx -> onTimerOverflow(timerIdx) }
        mmu.onSoundRegisterWrite = { offset, value -> onSoundRegisterWrite(offset, value) }
        mmu.onWaveRamWrite = { offset, byteVal -> writeWaveRamByte(offset, byteVal) }
        mmu.onWaveRamRead = { offset -> readWaveRamByte(offset) }
        mmu.onQuerySoundCntXBits = { queryChannelActiveBits() }
    }

    fun startAudioTrack() {
        if (audioTrack != null) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLES_PER_FRAME * 4 * 4)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build().apply { play() }
        } catch (_: Throwable) {
            audioTrack = null
        }
    }

    fun setPlaybackPaused(paused: Boolean) {
        val track = audioTrack ?: return
        try {
            if (paused) {
                track.pause()
                track.flush()
            } else {
                track.play()
            }
        } catch (_: Throwable) {
        }
    }

    fun flushTrack() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: Throwable) {
        }
        pcmWriteIndex = 0
        turboStrideAcc = 0f
    }

    fun release() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    fun reset() {
        fifoA.fill(0); fifoAHead = 0; fifoATail = 0; fifoACount = 0; fifoASample = 0
        fifoB.fill(0); fifoBHead = 0; fifoBTail = 0; fifoBCount = 0; fifoBSample = 0
        ch1Enabled = false; ch1SweepTimer = 0; ch1SweepShadow = 0; ch1SweepEnabled = false
        ch1Length = 0; ch1EnvVol = 0; ch1EnvTimer = 0; ch1FreqTimer = 0; ch1DutyStep = 0
        ch2Enabled = false; ch2Length = 0; ch2EnvVol = 0; ch2EnvTimer = 0; ch2FreqTimer = 0; ch2DutyStep = 0
        waveRam[0].fill(0); waveRam[1].fill(0)
        ch3Enabled = false; ch3Length = 0; ch3FreqTimer = 0; ch3SamplePos = 0
        ch4Enabled = false; ch4Length = 0; ch4EnvVol = 0; ch4EnvTimer = 0; ch4FreqTimer = 0; ch4Lfsr = 0x7FFF
        frameSeqCycles = 0; frameSeqStep = 0; sampleCycleAcc = 0; turboStrideAcc = 0f
        pcmWriteIndex = 0
        prevInL = 0f; prevOutL = 0f; prevInR = 0f; prevOutR = 0f
    }

    fun beginFrame(speedMultiplier: Float, renderAudio: Boolean = true) {
        currentFrameSpeed = speedMultiplier.coerceIn(1f, 8f)
        shouldOutputAudioThisFrame = renderAudio && enabled && currentFrameSpeed <= 2.5f && (audioTrack != null || diagnosticCaptureEnabled)
        pcmWriteIndex = 0
    }

    fun stepCycles(cycles: Int) {
        if (cycles <= 0) return

        // Always advance PSG frame sequencer & timers if audio is active
        val soundCntX = mmu.readIo16Raw(0x084)
        val masterOn = (soundCntX and 0x80) != 0

        if (!masterOn) {
            return
        }

        if (shouldOutputAudioThisFrame) {
            // Advance PSG frequency timers at cycle resolution & emit 32,768 Hz samples
            frameSeqCycles += cycles
            while (frameSeqCycles >= CYCLES_PER_FRAME_SEQ_STEP) {
                frameSeqCycles -= CYCLES_PER_FRAME_SEQ_STEP
                clockFrameSequencer()
            }
            stepPsgOscillators(cycles)

            sampleCycleAcc += cycles
            while (sampleCycleAcc >= CYCLES_PER_SAMPLE) {
                sampleCycleAcc -= CYCLES_PER_SAMPLE
                if (currentFrameSpeed > 1.05f) {
                    // Decimate smoothly at 1.25x..2.5x turbo so AudioTrack buffer stays in sync
                    turboStrideAcc += 1f
                    if (turboStrideAcc >= currentFrameSpeed) {
                        turboStrideAcc -= currentFrameSpeed
                        mixOneSample()
                    }
                } else {
                    mixOneSample()
                }
            }
        } else {
            // Fast path during skipped/turbo/muted frames: keep PSG length/envelope counters moving lightly
            frameSeqCycles += cycles
            while (frameSeqCycles >= CYCLES_PER_FRAME_SEQ_STEP) {
                frameSeqCycles -= CYCLES_PER_FRAME_SEQ_STEP
                clockFrameSequencer()
            }
        }
    }

    fun flushFrame(speedMultiplier: Float) {
        if (!enabled || speedMultiplier > 2.5f || !shouldOutputAudioThisFrame) return
        if (pcmWriteIndex <= 0) return
        if (diagnosticCaptureEnabled) {
            lastMixedPcmSnapshot = pcmBuffer.copyOf(pcmWriteIndex)
        }
        val track = audioTrack
        if (track != null) {
            try {
                track.write(pcmBuffer, 0, pcmWriteIndex, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Throwable) {
            }
        }
        pcmWriteIndex = 0
    }

    // Legacy compatibility helper for direct callers/tests
    fun renderFrame(speedMultiplier: Float) {
        flushFrame(speedMultiplier)
    }

    private fun clockFrameSequencer() {
        when (frameSeqStep and 7) {
            0, 2, 4, 6 -> clockLengths()
        }
        if ((frameSeqStep and 3) == 2) clockSweep()
        if ((frameSeqStep and 7) == 7) clockEnvelopes()
        frameSeqStep = (frameSeqStep + 1) and 7
    }

    private fun clockLengths() {
        if (ch1Enabled && (mmu.readIo16Raw(0x064) and 0x4000) != 0) {
            if (ch1Length > 0 && --ch1Length == 0) ch1Enabled = false
        }
        if (ch2Enabled && (mmu.readIo16Raw(0x06C) and 0x4000) != 0) {
            if (ch2Length > 0 && --ch2Length == 0) ch2Enabled = false
        }
        if (ch3Enabled && (mmu.readIo16Raw(0x074) and 0x4000) != 0) {
            if (ch3Length > 0 && --ch3Length == 0) ch3Enabled = false
        }
        if (ch4Enabled && (mmu.readIo16Raw(0x07C) and 0x4000) != 0) {
            if (ch4Length > 0 && --ch4Length == 0) ch4Enabled = false
        }
    }

    private fun clockSweep() {
        if (!ch1Enabled || !ch1SweepEnabled) return
        if (--ch1SweepTimer <= 0) {
            val sweepReg = mmu.readIo16Raw(0x060)
            val period = (sweepReg ushr 4) and 7
            ch1SweepTimer = if (period != 0) period else 8
            val shift = sweepReg and 7
            if (period != 0 && shift != 0) {
                val delta = ch1SweepShadow ushr shift
                val negate = (sweepReg and 8) != 0
                val nextFreq = if (negate) ch1SweepShadow - delta else ch1SweepShadow + delta
                if (nextFreq > 2047) {
                    ch1Enabled = false
                } else if (nextFreq >= 0) {
                    ch1SweepShadow = nextFreq
                    val reg64 = mmu.readIo16Raw(0x064)
                    mmu.writeIo16Raw(0x064, (reg64 and 0xF800) or (nextFreq and 0x7FF))
                }
            }
        }
    }

    private fun clockEnvelopes() {
        // Ch1
        if (ch1Enabled) {
            val envReg = mmu.readIo16Raw(0x062)
            val period = (envReg ushr 8) and 7
            if (period != 0 && --ch1EnvTimer <= 0) {
                ch1EnvTimer = period
                val up = (envReg and 0x0800) != 0
                if (up && ch1EnvVol < 15) ch1EnvVol++
                else if (!up && ch1EnvVol > 0) ch1EnvVol--
            }
        }
        // Ch2
        if (ch2Enabled) {
            val envReg = mmu.readIo16Raw(0x068)
            val period = (envReg ushr 8) and 7
            if (period != 0 && --ch2EnvTimer <= 0) {
                ch2EnvTimer = period
                val up = (envReg and 0x0800) != 0
                if (up && ch2EnvVol < 15) ch2EnvVol++
                else if (!up && ch2EnvVol > 0) ch2EnvVol--
            }
        }
        // Ch4
        if (ch4Enabled) {
            val envReg = mmu.readIo16Raw(0x078)
            val period = (envReg ushr 8) and 7
            if (period != 0 && --ch4EnvTimer <= 0) {
                ch4EnvTimer = period
                val up = (envReg and 0x0800) != 0
                if (up && ch4EnvVol < 15) ch4EnvVol++
                else if (!up && ch4EnvVol > 0) ch4EnvVol--
            }
        }
    }

    private fun stepPsgOscillators(cycles: Int) {
        if (ch1Enabled) {
            val period = ((2048 - (mmu.readIo16Raw(0x064) and 0x7FF)) * 16).coerceAtLeast(16)
            ch1FreqTimer -= cycles
            if (ch1FreqTimer <= 0) {
                val steps = (-ch1FreqTimer / period) + 1
                ch1FreqTimer += steps * period
                ch1DutyStep = (ch1DutyStep + steps) and 7
            }
        }
        if (ch2Enabled) {
            val period = ((2048 - (mmu.readIo16Raw(0x06C) and 0x7FF)) * 16).coerceAtLeast(16)
            ch2FreqTimer -= cycles
            if (ch2FreqTimer <= 0) {
                val steps = (-ch2FreqTimer / period) + 1
                ch2FreqTimer += steps * period
                ch2DutyStep = (ch2DutyStep + steps) and 7
            }
        }
        if (ch3Enabled) {
            val period = ((2048 - (mmu.readIo16Raw(0x074) and 0x7FF)) * 8).coerceAtLeast(8)
            ch3FreqTimer -= cycles
            if (ch3FreqTimer <= 0) {
                val steps = (-ch3FreqTimer / period) + 1
                ch3FreqTimer += steps * period
                val dim64 = (mmu.readIo16Raw(0x070) and 0x20) != 0
                val mod = if (dim64) 64 else 32
                ch3SamplePos = (ch3SamplePos + steps) % mod
            }
        }
        if (ch4Enabled) {
            val reg = mmu.readIo16Raw(0x07C)
            val divCode = reg and 7
            val shift = (reg ushr 4) and 0x0F
            val period = (NOISE_DIVISORS[divCode] shl shift).coerceAtLeast(16)
            ch4FreqTimer -= cycles
            while (ch4FreqTimer <= 0) {
                ch4FreqTimer += period
                val xorBit = (ch4Lfsr and 1) xor ((ch4Lfsr ushr 1) and 1)
                ch4Lfsr = (ch4Lfsr ushr 1) or (xorBit shl 14)
                if ((reg and 8) != 0) {
                    // 7-bit width mode
                    ch4Lfsr = (ch4Lfsr and (1 shl 6).inv()) or (xorBit shl 6)
                }
            }
        }
    }

    private fun mixOneSample() {
        if (pcmWriteIndex + 1 >= pcmBuffer.size) return

        val soundCntL = mmu.readIo16Raw(0x080)
        val soundCntH = mmu.readIo16Raw(0x082)

        // PSG Ch1..Ch4 samples (-15..+15 range)
        var s1 = 0
        if (ch1Enabled && ch1EnvVol > 0) {
            val duty = (mmu.readIo16Raw(0x062) ushr 6) and 3
            s1 = DUTY_TABLE[duty][ch1DutyStep] * ch1EnvVol
        }
        var s2 = 0
        if (ch2Enabled && ch2EnvVol > 0) {
            val duty = (mmu.readIo16Raw(0x068) ushr 6) and 3
            s2 = DUTY_TABLE[duty][ch2DutyStep] * ch2EnvVol
        }
        var s3 = 0
        val cnt3L = mmu.readIo16Raw(0x070)
        if (ch3Enabled && (cnt3L and 0x80) != 0) {
            val cnt3H = mmu.readIo16Raw(0x072)
            val volCode = (cnt3H ushr 13) and 3
            val force75 = (cnt3H and 0x8000) != 0
            if (volCode != 0 || force75) {
                val activeBank = (cnt3L ushr 6) and 1
                val dim64 = (cnt3L and 0x20) != 0
                val bankIdx = if (dim64) (ch3SamplePos ushr 5) xor activeBank else activeBank
                val sampleIdx = ch3SamplePos and 31
                val byteVal = waveRam[bankIdx][sampleIdx ushr 1].toInt() and 0xFF
                val nibble = if ((sampleIdx and 1) == 0) (byteVal ushr 4) and 0x0F else byteVal and 0x0F
                val centered = (nibble * 2) - 15 // -15..+15
                s3 = when {
                    force75 -> (centered * 3) / 4
                    volCode == 1 -> centered
                    volCode == 2 -> centered shr 1
                    volCode == 3 -> centered shr 2
                    else -> 0
                }
            }
        }
        var s4 = 0
        if (ch4Enabled && ch4EnvVol > 0) {
            val noiseSign = if ((ch4Lfsr and 1) == 0) 1 else -1
            s4 = noiseSign * ch4EnvVol
        }

        // PSG panning (SOUNDCNT_L bits 8..11 = R, bits 12..15 = L) and master PSG vol (bits 0..2 = R, 4..6 = L)
        var psgR = 0
        var psgL = 0
        if ((soundCntL and (1 shl 8)) != 0) psgR += s1
        if ((soundCntL and (1 shl 9)) != 0) psgR += s2
        if ((soundCntL and (1 shl 10)) != 0) psgR += s3
        if ((soundCntL and (1 shl 11)) != 0) psgR += s4
        if ((soundCntL and (1 shl 12)) != 0) psgL += s1
        if ((soundCntL and (1 shl 13)) != 0) psgL += s2
        if ((soundCntL and (1 shl 14)) != 0) psgL += s3
        if ((soundCntL and (1 shl 15)) != 0) psgL += s4

        val psgMasterR = (soundCntL and 7) + 1
        val psgMasterL = ((soundCntL ushr 4) and 7) + 1
        psgR = (psgR * psgMasterR) shr 2 // scale into ~[-120..+120]
        psgL = (psgL * psgMasterL) shr 2

        val psgMixShift = when (soundCntH and 3) {
            0 -> 2 // 25%
            1 -> 1 // 50%
            else -> 0 // 100%
        }
        psgR = psgR shr psgMixShift
        psgL = psgL shr psgMixShift

        // DirectSound A & B (signed 8-bit -128..127, 50% or 100% volume)
        val dsAScaled = if ((soundCntH and (1 shl 2)) != 0) fifoASample * 2 else fifoASample
        val dsBScaled = if ((soundCntH and (1 shl 3)) != 0) fifoBSample * 2 else fifoBSample

        var rawL = psgL
        var rawR = psgR
        if ((soundCntH and (1 shl 9)) != 0) rawL += dsAScaled
        if ((soundCntH and (1 shl 8)) != 0) rawR += dsAScaled
        if ((soundCntH and (1 shl 13)) != 0) rawL += dsBScaled
        if ((soundCntH and (1 shl 12)) != 0) rawR += dsBScaled

        // First-order DC-blocking high-pass filter + master volume
        val gain = volume.coerceIn(0f, 1f) * 54f
        val inL = rawL.toFloat()
        val inR = rawR.toFloat()
        val hpL = inL - prevInL + 0.996f * prevOutL
        val hpR = inR - prevInR + 0.996f * prevOutR
        prevInL = inL; prevOutL = hpL
        prevInR = inR; prevOutR = hpR

        val outL = (hpL * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val outR = (hpR * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

        pcmBuffer[pcmWriteIndex++] = outL.toShort()
        pcmBuffer[pcmWriteIndex++] = outR.toShort()
    }

    private fun onSoundRegisterWrite(offset: Int, value: Int) {
        when (offset) {
            0x064 -> { // SOUND1CNT_X
                if ((value and 0x8000) != 0) {
                    val envReg = mmu.readIo16Raw(0x062)
                    ch1Enabled = (envReg and 0xF800) != 0
                    ch1Length = 64 - (envReg and 0x3F)
                    ch1EnvVol = (envReg ushr 12) and 0x0F
                    ch1EnvTimer = ((envReg ushr 8) and 7).let { if (it == 0) 8 else it }
                    ch1SweepShadow = value and 0x7FF
                    val sweepReg = mmu.readIo16Raw(0x060)
                    val sPeriod = (sweepReg ushr 4) and 7
                    val sShift = sweepReg and 7
                    ch1SweepTimer = if (sPeriod != 0) sPeriod else 8
                    ch1SweepEnabled = (sPeriod != 0 || sShift != 0)
                    ch1FreqTimer = ((2048 - ch1SweepShadow) * 16).coerceAtLeast(16)
                    ch1DutyStep = 0
                }
            }
            0x06C -> { // SOUND2CNT_H
                if ((value and 0x8000) != 0) {
                    val envReg = mmu.readIo16Raw(0x068)
                    ch2Enabled = (envReg and 0xF800) != 0
                    ch2Length = 64 - (envReg and 0x3F)
                    ch2EnvVol = (envReg ushr 12) and 0x0F
                    ch2EnvTimer = ((envReg ushr 8) and 7).let { if (it == 0) 8 else it }
                    ch2FreqTimer = ((2048 - (value and 0x7FF)) * 16).coerceAtLeast(16)
                    ch2DutyStep = 0
                }
            }
            0x070 -> { // SOUND3CNT_L
                if ((value and 0x80) == 0) ch3Enabled = false
            }
            0x074 -> { // SOUND3CNT_X
                if ((value and 0x8000) != 0) {
                    val cnt3L = mmu.readIo16Raw(0x070)
                    ch3Enabled = (cnt3L and 0x80) != 0
                    ch3Length = 256 - (mmu.readIo16Raw(0x072) and 0xFF)
                    ch3FreqTimer = ((2048 - (value and 0x7FF)) * 8).coerceAtLeast(8)
                    ch3SamplePos = 0
                }
            }
            0x07C -> { // SOUND4CNT_H
                if ((value and 0x8000) != 0) {
                    val envReg = mmu.readIo16Raw(0x078)
                    ch4Enabled = (envReg and 0xF800) != 0
                    ch4Length = 64 - (envReg and 0x3F)
                    ch4EnvVol = (envReg ushr 12) and 0x0F
                    ch4EnvTimer = ((envReg ushr 8) and 7).let { if (it == 0) 8 else it }
                    ch4Lfsr = 0x7FFF
                    val divCode = value and 7
                    val shift = (value ushr 4) and 0x0F
                    ch4FreqTimer = (NOISE_DIVISORS[divCode] shl shift).coerceAtLeast(16)
                }
            }
            0x082 -> { // SOUNDCNT_H FIFO resets (bits 11 & 15)
                if ((value and (1 shl 11)) != 0) {
                    fifoA.fill(0); fifoAHead = 0; fifoATail = 0; fifoACount = 0; fifoASample = 0
                }
                if ((value and (1 shl 15)) != 0) {
                    fifoB.fill(0); fifoBHead = 0; fifoBTail = 0; fifoBCount = 0; fifoBSample = 0
                }
                mmu.writeIo16Raw(0x082, value and 0x770F)
            }
            0x084 -> { // SOUNDCNT_X
                if ((value and 0x80) == 0) {
                    ch1Enabled = false; ch2Enabled = false; ch3Enabled = false; ch4Enabled = false
                    for (reg in 0x060..0x082 step 2) mmu.writeIo16Raw(reg, 0)
                }
                mmu.writeIo16Raw(0x084, value and 0x80)
            }
        }
    }

    private fun writeWaveRamByte(offset: Int, byteVal: Int) {
        val activeBank = (mmu.readIo16Raw(0x070) ushr 6) and 1
        val cpuBank = activeBank xor 1
        val idx = (offset - 0x090) and 0x0F
        waveRam[cpuBank][idx] = byteVal.toByte()
    }

    private fun readWaveRamByte(offset: Int): Int {
        val activeBank = (mmu.readIo16Raw(0x070) ushr 6) and 1
        val cpuBank = activeBank xor 1
        val idx = (offset - 0x090) and 0x0F
        return waveRam[cpuBank][idx].toInt() and 0xFF
    }

    private fun queryChannelActiveBits(): Int {
        var bits = 0
        if (ch1Enabled) bits = bits or 0x01
        if (ch2Enabled) bits = bits or 0x02
        if (ch3Enabled) bits = bits or 0x04
        if (ch4Enabled) bits = bits or 0x08
        return bits
    }

    private fun pushFifoWord(channel: Int, word: Int) {
        for (b in 0 until 4) {
            val byteVal = ((word ushr (b * 8)) and 0xFF).toByte()
            if (channel == 0) {
                if (fifoACount >= 32) {
                    fifoAHead = (fifoAHead + 1) and 31
                    fifoACount--
                }
                fifoA[fifoATail] = byteVal
                fifoATail = (fifoATail + 1) and 31
                fifoACount++
            } else {
                if (fifoBCount >= 32) {
                    fifoBHead = (fifoBHead + 1) and 31
                    fifoBCount--
                }
                fifoB[fifoBTail] = byteVal
                fifoBTail = (fifoBTail + 1) and 31
                fifoBCount++
            }
        }
    }

    private fun onTimerOverflow(timerIdx: Int) {
        val soundCntH = mmu.readIo16Raw(0x082)
        val timerA = if ((soundCntH and (1 shl 10)) != 0) 1 else 0
        val timerB = if ((soundCntH and (1 shl 14)) != 0) 1 else 0

        if (timerIdx == timerA) {
            if (fifoACount > 0) {
                fifoASample = fifoA[fifoAHead].toInt()
                fifoAHead = (fifoAHead + 1) and 31
                fifoACount--
            } else {
                fifoASample = 0
            }
            if (fifoACount <= 16) mmu.triggerFifoDma(0)
        }
        if (timerIdx == timerB) {
            if (fifoBCount > 0) {
                fifoBSample = fifoB[fifoBHead].toInt()
                fifoBHead = (fifoBHead + 1) and 31
                fifoBCount--
            } else {
                fifoBSample = 0
            }
            if (fifoBCount <= 16) mmu.triggerFifoDma(1)
        }
    }

    fun snapshotTo(out: DataOutputStream) {
        out.write(fifoA)
        out.writeInt(fifoAHead)
        out.writeInt(fifoATail)
        out.writeInt(fifoACount)
        out.writeInt(fifoASample)
        out.write(fifoB)
        out.writeInt(fifoBHead)
        out.writeInt(fifoBTail)
        out.writeInt(fifoBCount)
        out.writeInt(fifoBSample)
        out.write(waveRam[0])
        out.write(waveRam[1])
    }

    fun restoreFrom(inp: DataInputStream) {
        inp.readFully(fifoA)
        fifoAHead = inp.readInt()
        fifoATail = inp.readInt()
        fifoACount = inp.readInt()
        fifoASample = inp.readInt()
        inp.readFully(fifoB)
        fifoBHead = inp.readInt()
        fifoBTail = inp.readInt()
        fifoBCount = inp.readInt()
        fifoBSample = inp.readInt()
        try {
            inp.readFully(waveRam[0])
            inp.readFully(waveRam[1])
        } catch (_: java.io.EOFException) {
            waveRam[0].fill(0)
            waveRam[1].fill(0)
        }
        ch1Enabled = false; ch2Enabled = false; ch3Enabled = false; ch4Enabled = false
        pcmWriteIndex = 0
        prevInL = 0f; prevOutL = 0f; prevInR = 0f; prevOutR = 0f
    }
}
