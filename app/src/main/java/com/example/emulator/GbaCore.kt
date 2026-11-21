package com.example.emulator

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CoreTelemetry(
    val pcHex: String,
    val isThumb: Boolean,
    val cpuMode: String,
    val videoMode: Int,
    val totalCycles: Long,
    val saveType: SaveType,
    val rumbleActive: Boolean = false,
    val rumbleEventsCount: Long = 0L
)

class GbaCore {
    val mmu = GbaMmu()
    val bios = GbaHleBios(mmu)
    val cpu = Arm7TdmiCpu(mmu, bios)
    val ppu = GbaPpu(mmu)
    val apu = GbaApu(mmu)

    @Volatile
    var isLoaded = false
        private set

    var onVBlankCheatHook: ((GbaMmu) -> Unit)? = null

    fun loadRom(
        romBytes: ByteArray,
        saveType: SaveType,
        initialSram: ByteArray?,
        customBios: ByteArray? = null,
        skipBiosIntro: Boolean = true
    ) {
        mmu.reset(romBytes, saveType, customBios)
        if (initialSram != null) {
            val copyLen = minOf(initialSram.size, mmu.sram.size)
            System.arraycopy(initialSram, 0, mmu.sram, 0, copyLen)
            mmu.sramDirty = false
        } else {
            mmu.sram.fill(0xFF.toByte())
            mmu.sramDirty = false
        }
        apu.reset()
        cpu.resetToRomEntry(skipBiosIntro)
        isLoaded = true
    }

    fun setKeys(pressedMask: Int) {
        mmu.keyInput = GbaConstants.KEY_ALL_RELEASED and (pressedMask and 0x03FF).inv()
        mmu.checkKeypadIrq()
    }

    fun stepFrame(renderPixels: Boolean, speedMultiplier: Float) {
        if (!isLoaded) return
        ppu.beginFrame()
        apu.beginFrame(speedMultiplier, renderAudio = true)

        for (y in 0 until GbaConstants.TOTAL_SCANLINES) {
            // Update VCOUNT
            mmu.writeIo16Raw(0x006, y)

            // Update DISPSTAT flags (bit 0 = VBlank, bit 1 = HBlank=0, bit 2 = VCount match)
            var dispstat = mmu.readIo16Raw(0x004)
            val vcountTarget = (dispstat ushr 8) and 0xFF
            dispstat = if (y in 160..226) dispstat or 0x0001 else dispstat and 0xFFFE
            dispstat = dispstat and 0xFFFD // clear HBlank
            dispstat = if (y == vcountTarget) dispstat or 0x0004 else dispstat and 0xFFFB
            mmu.writeIo16Raw(0x004, dispstat)

            if (y == 160) {
                // Enter VBlank
                if ((dispstat and 0x0008) != 0) mmu.raiseIrq(GbaConstants.IRQ_VBLANK)
                mmu.triggerDmaTiming(1) // VBlank DMA
                onVBlankCheatHook?.invoke(mmu)
            }
            if (y == vcountTarget && (dispstat and 0x0020) != 0) {
                mmu.raiseIrq(GbaConstants.IRQ_VCOUNT)
            }

            // Run visible/draw portion (960 cycles)
            runCycles(960)

            // Render visible scanline
            if (renderPixels && y < GbaConstants.VISIBLE_SCANLINES) {
                ppu.renderScanline(y)
            }

            // Enter HBlank (272 cycles)
            dispstat = mmu.readIo16Raw(0x004) or 0x0002
            mmu.writeIo16Raw(0x004, dispstat)
            if ((dispstat and 0x0010) != 0) mmu.raiseIrq(GbaConstants.IRQ_HBLANK)
            if (y < GbaConstants.VISIBLE_SCANLINES) mmu.triggerDmaTiming(2) // HBlank DMA

            runCycles(272)
        }

        apu.flushFrame(speedMultiplier)
    }

    private fun runCycles(targetCycles: Int) {
        var elapsed = 0
        while (elapsed < targetCycles) {
            if (mmu.halted) {
                val rem = targetCycles - elapsed
                val chunk = if (rem > 512) 512 else rem
                mmu.stepTimers(chunk)
                apu.stepCycles(chunk)
                cpu.totalCycles += chunk
                elapsed += chunk
            } else {
                val c = cpu.step()
                mmu.stepTimers(c)
                apu.stepCycles(c)
                elapsed += c
            }
        }
    }

    fun getTelemetry(): CoreTelemetry = CoreTelemetry(
        pcHex = String.format("0x%08X", cpu.r[15]),
        isThumb = cpu.isThumb,
        cpuMode = cpu.currentModeName(),
        videoMode = ppu.currentVideoMode(),
        totalCycles = cpu.totalCycles,
        saveType = mmu.saveType,
        rumbleActive = mmu.rumbleActive,
        rumbleEventsCount = mmu.rumbleEventsCount
    )

    fun captureCompressedState(): ByteArray {
        val rawOut = ByteArrayOutputStream(512 * 1024)
        GZIPOutputStream(rawOut).use { gz ->
            DataOutputStream(gz).use { dos ->
                dos.writeInt(0x47424131) // "GBA1"
                cpu.snapshotTo(dos)
                mmu.snapshotTo(dos)
                apu.snapshotTo(dos)
                dos.writeInt(ppu.frameBuffer.size)
                for (px in ppu.frameBuffer) dos.writeInt(px)
            }
        }
        return rawOut.toByteArray()
    }

    fun restoreCompressedState(compressedPayload: ByteArray): Boolean {
        return try {
            GZIPInputStream(ByteArrayInputStream(compressedPayload)).use { gz ->
                DataInputStream(gz).use { dis ->
                    val magic = dis.readInt()
                    if (magic != 0x47424131) return false
                    cpu.restoreFrom(dis)
                    mmu.restoreFrom(dis)
                    apu.restoreFrom(dis)
                    val fbLen = dis.readInt()
                    if (fbLen == ppu.frameBuffer.size) {
                        for (i in 0 until fbLen) ppu.frameBuffer[i] = dis.readInt()
                    }
                    true
                }
            }
        } catch (_: Throwable) {
            false
        }
    }
}
