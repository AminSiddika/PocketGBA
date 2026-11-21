package com.example.emulator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.DataOutputStream

class GbaApu(private val mmu: GbaMmu) {
    companion object {
        const val SAMPLE_RATE = 32768
        const val SAMPLES_PER_FRAME = 548 // ~32768 / 59.7275 Hz
    }

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

    private var psgPhase1 = 0f
    private var psgPhase2 = 0f
    private val pcmBuffer = ShortArray(SAMPLES_PER_FRAME * 2)

    private var audioTrack: AudioTrack? = null
    @Volatile var enabled: Boolean = true
    @Volatile var volume: Float = 0.8f

    init {
        mmu.onFifoWrite = { ch, word -> pushFifoWord(ch, word) }
        mmu.onTimerOverflow = { timerIdx -> onTimerOverflow(timerIdx) }
    }

    fun startAudioTrack() {
        if (audioTrack != null) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLES_PER_FRAME * 4 * 4)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                .build()
            audioTrack?.play()
        } catch (_: Throwable) {
            audioTrack = null
        }
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
        psgPhase1 = 0f
        psgPhase2 = 0f
    }

    private fun pushFifoWord(channel: Int, word: Int) {
        for (b in 0 until 4) {
            val byteVal = ((word ushr (b * 8)) and 0xFF).toByte()
            if (channel == 0) {
                if (fifoACount < 32) {
                    fifoA[fifoATail] = byteVal
                    fifoATail = (fifoATail + 1) and 31
                    fifoACount++
                }
            } else {
                if (fifoBCount < 32) {
                    fifoB[fifoBTail] = byteVal
                    fifoBTail = (fifoBTail + 1) and 31
                    fifoBCount++
                }
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
            }
            if (fifoACount <= 16) mmu.triggerFifoDma(0)
        }
        if (timerIdx == timerB) {
            if (fifoBCount > 0) {
                fifoBSample = fifoB[fifoBHead].toInt()
                fifoBHead = (fifoBHead + 1) and 31
                fifoBCount--
            }
            if (fifoBCount <= 16) mmu.triggerFifoDma(1)
        }
    }

    fun renderFrame(speedMultiplier: Float) {
        if (!enabled || speedMultiplier > 2.5f) return
        val track = audioTrack ?: return

        val soundCntX = mmu.readIo16Raw(0x084)
        if ((soundCntX and 0x80) == 0) return

        val soundCntH = mmu.readIo16Raw(0x082)
        val volScale = (volume * 180f).toInt().coerceIn(0, 255)

        // Simple PSG Ch1 & Ch2 square tone check + DirectSound A/B mixing
        val ch1FreqReg = mmu.readIo16Raw(0x064) and 0x7FF
        val ch1Vol = (mmu.readIo16Raw(0x062) ushr 12) and 0x0F
        val ch2FreqReg = mmu.readIo16Raw(0x06C) and 0x7FF
        val ch2Vol = (mmu.readIo16Raw(0x068) ushr 12) and 0x0F

        val f1 = if (ch1Vol > 0 && ch1FreqReg < 2048) 131072f / (2048f - ch1FreqReg) else 0f
        val f2 = if (ch2Vol > 0 && ch2FreqReg < 2048) 131072f / (2048f - ch2FreqReg) else 0f
        val inc1 = f1 / SAMPLE_RATE
        val inc2 = f2 / SAMPLE_RATE

        val dsaL = (soundCntH and (1 shl 9)) != 0
        val dsaR = (soundCntH and (1 shl 8)) != 0
        val dsbL = (soundCntH and (1 shl 13)) != 0
        val dsbR = (soundCntH and (1 shl 12)) != 0

        for (i in 0 until SAMPLES_PER_FRAME) {
            var psg = 0
            if (f1 > 0f) {
                psgPhase1 = (psgPhase1 + inc1) % 1f
                psg += if (psgPhase1 < 0.5f) ch1Vol * 2 else -ch1Vol * 2
            }
            if (f2 > 0f) {
                psgPhase2 = (psgPhase2 + inc2) % 1f
                psg += if (psgPhase2 < 0.5f) ch2Vol * 2 else -ch2Vol * 2
            }
            val left = (psg + (if (dsaL) fifoASample else 0) + (if (dsbL) fifoBSample else 0)) * volScale
            val right = (psg + (if (dsaR) fifoASample else 0) + (if (dsbR) fifoBSample else 0)) * volScale
            pcmBuffer[i * 2] = left.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcmBuffer[i * 2 + 1] = right.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        try {
            track.write(pcmBuffer, 0, pcmBuffer.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Throwable) {
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
    }
}
