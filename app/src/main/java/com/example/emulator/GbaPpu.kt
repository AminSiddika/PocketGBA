package com.example.emulator

class GbaPpu(private val mmu: GbaMmu) {
    val frameBuffer = IntArray(GbaConstants.PIXEL_COUNT)

    private val bgColors = IntArray(GbaConstants.SCREEN_WIDTH)
    private val bgPriorities = IntArray(GbaConstants.SCREEN_WIDTH)
    private val objColors = IntArray(GbaConstants.SCREEN_WIDTH)
    private val objPriorities = IntArray(GbaConstants.SCREEN_WIDTH)

    private var internalBg2X = 0
    private var internalBg2Y = 0
    private var internalBg3X = 0
    private var internalBg3Y = 0

    fun currentVideoMode(): Int = mmu.readIo16Raw(0x000) and 0x07

    fun beginFrame() {
        internalBg2X = mmu.bg2RefX
        internalBg2Y = mmu.bg2RefY
        internalBg3X = mmu.bg3RefX
        internalBg3Y = mmu.bg3RefY
    }

    fun renderScanline(y: Int) {
        val dispcnt = mmu.readIo16Raw(0x000)
        val rowOffset = y * GbaConstants.SCREEN_WIDTH

        // Forced blank
        if ((dispcnt and 0x0080) != 0) {
            for (x in 0 until GbaConstants.SCREEN_WIDTH) {
                frameBuffer[rowOffset + x] = 0xFFF8F8F8.toInt()
            }
            return
        }

        val backdrop = rgb555ToArgb(readPalette16(0))
        val mode = dispcnt and 0x07

        for (x in 0 until GbaConstants.SCREEN_WIDTH) {
            bgColors[x] = backdrop
            bgPriorities[x] = 4
            objColors[x] = 0
            objPriorities[x] = 4
        }

        when (mode) {
            0 -> {
                for (bg in 3 downTo 0) {
                    if ((dispcnt and (1 shl (8 + bg))) != 0) {
                        renderTextBgScanline(bg, y)
                    }
                }
            }
            1 -> {
                if ((dispcnt and (1 shl 10)) != 0) renderAffineBgScanline(2, y)
                if ((dispcnt and (1 shl 9)) != 0) renderTextBgScanline(1, y)
                if ((dispcnt and (1 shl 8)) != 0) renderTextBgScanline(0, y)
            }
            2 -> {
                if ((dispcnt and (1 shl 11)) != 0) renderAffineBgScanline(3, y)
                if ((dispcnt and (1 shl 10)) != 0) renderAffineBgScanline(2, y)
            }
            3 -> {
                if ((dispcnt and (1 shl 10)) != 0) {
                    val base = y * 480
                    for (x in 0 until GbaConstants.SCREEN_WIDTH) {
                        val o = base + (x shl 1)
                        val c15 = (mmu.vram[o].toInt() and 0xFF) or ((mmu.vram[o + 1].toInt() and 0xFF) shl 8)
                        bgColors[x] = rgb555ToArgb(c15)
                        bgPriorities[x] = 2
                    }
                }
            }
            4 -> {
                if ((dispcnt and (1 shl 10)) != 0) {
                    val page = if ((dispcnt and 0x10) != 0) 0xA000 else 0
                    val base = page + y * 240
                    for (x in 0 until GbaConstants.SCREEN_WIDTH) {
                        val idx = mmu.vram[base + x].toInt() and 0xFF
                        if (idx != 0) {
                            bgColors[x] = rgb555ToArgb(readPalette16(idx shl 1))
                            bgPriorities[x] = 2
                        }
                    }
                }
            }
            5 -> {
                if ((dispcnt and (1 shl 10)) != 0 && y < 128) {
                    val page = if ((dispcnt and 0x10) != 0) 0xA000 else 0
                    val base = page + y * 320
                    for (x in 0 until 160) {
                        val o = base + (x shl 1)
                        val c15 = (mmu.vram[o].toInt() and 0xFF) or ((mmu.vram[o + 1].toInt() and 0xFF) shl 8)
                        bgColors[x] = rgb555ToArgb(c15)
                        bgPriorities[x] = 2
                    }
                }
            }
        }

        if ((dispcnt and (1 shl 12)) != 0) {
            renderSpritesScanline(dispcnt, y)
        }

        // Composite BG & OBJ with priority ordering
        for (x in 0 until GbaConstants.SCREEN_WIDTH) {
            val objCol = objColors[x]
            val finalColor = if (objCol != 0 && objPriorities[x] <= bgPriorities[x]) objCol else bgColors[x]
            frameBuffer[rowOffset + x] = finalColor
        }

        // Advance affine reference points per scanline
        val pb2 = mmu.readIo16Raw(0x022).toShort().toInt()
        val pd2 = mmu.readIo16Raw(0x026).toShort().toInt()
        internalBg2X += pb2
        internalBg2Y += pd2
        val pb3 = mmu.readIo16Raw(0x032).toShort().toInt()
        val pd3 = mmu.readIo16Raw(0x036).toShort().toInt()
        internalBg3X += pb3
        internalBg3Y += pd3
    }

    private fun renderTextBgScanline(bg: Int, y: Int) {
        val bgCnt = mmu.readIo16Raw(0x008 + bg * 2)
        val priority = bgCnt and 3
        val charBase = ((bgCnt ushr 2) and 3) * 0x4000
        val color256 = (bgCnt and 0x80) != 0
        val screenBase = ((bgCnt ushr 8) and 0x1F) * 0x800
        val sizeMode = (bgCnt ushr 14) and 3
        val widthPx = if ((sizeMode and 1) != 0) 512 else 256
        val heightPx = if ((sizeMode and 2) != 0) 512 else 256

        val hofs = mmu.readIo16Raw(0x010 + bg * 4) and 0x1FF
        val vofs = mmu.readIo16Raw(0x012 + bg * 4) and 0x1FF
        val ty = (y + vofs) and (heightPx - 1)
        val tileY = ty ushr 3
        val fineY = ty and 7

        for (x in 0 until GbaConstants.SCREEN_WIDTH) {
            val tx = (x + hofs) and (widthPx - 1)
            val tileX = tx ushr 3
            val fineX = tx and 7

            var mapBlock = 0
            if (tileX >= 32) mapBlock += 1
            if (tileY >= 32) mapBlock += if (widthPx == 512) 2 else 1

            val entryAddr = screenBase + mapBlock * 0x800 + ((tileY and 31) * 32 + (tileX and 31)) * 2
            val entry = readVram16(entryAddr)
            val tileId = entry and 0x3FF
            val hFlip = (entry and 0x400) != 0
            val vFlip = (entry and 0x800) != 0
            val palBank = (entry ushr 12) and 0xF

            val px = if (hFlip) 7 - fineX else fineX
            val py = if (vFlip) 7 - fineY else fineY

            val colorIdx = if (color256) {
                val addr = charBase + tileId * 64 + py * 8 + px
                if (addr < mmu.vram.size) mmu.vram[addr].toInt() and 0xFF else 0
            } else {
                val addr = charBase + tileId * 32 + py * 4 + (px ushr 1)
                if (addr < mmu.vram.size) {
                    val b = mmu.vram[addr].toInt() and 0xFF
                    if ((px and 1) == 0) b and 0x0F else (b ushr 4) and 0x0F
                } else 0
            }

            if (colorIdx != 0 && priority <= bgPriorities[x]) {
                val palIdx = if (color256) colorIdx else (palBank shl 4) + colorIdx
                bgColors[x] = rgb555ToArgb(readPalette16(palIdx shl 1))
                bgPriorities[x] = priority
            }
        }
    }

    private fun renderAffineBgScanline(bg: Int, @Suppress("UNUSED_PARAMETER") y: Int) {
        val bgCnt = mmu.readIo16Raw(0x008 + bg * 2)
        val priority = bgCnt and 3
        val charBase = ((bgCnt ushr 2) and 3) * 0x4000
        val screenBase = ((bgCnt ushr 8) and 0x1F) * 0x800
        val wrap = (bgCnt and 0x2000) != 0
        val sizeTiles = 16 shl ((bgCnt ushr 14) and 3)
        val sizePx = sizeTiles * 8

        val pa = mmu.readIo16Raw(if (bg == 2) 0x020 else 0x030).toShort().toInt()
        val pc = mmu.readIo16Raw(if (bg == 2) 0x024 else 0x034).toShort().toInt()
        var refX = if (bg == 2) internalBg2X else internalBg3X
        var refY = if (bg == 2) internalBg2Y else internalBg3Y

        for (x in 0 until GbaConstants.SCREEN_WIDTH) {
            var px = refX shr 8
            var py = refY shr 8
            refX += pa
            refY += pc

            if (wrap) {
                px = ((px % sizePx) + sizePx) % sizePx
                py = ((py % sizePx) + sizePx) % sizePx
            } else if (px !in 0 until sizePx || py !in 0 until sizePx) {
                continue
            }

            val tileX = px ushr 3
            val tileY = py ushr 3
            val mapAddr = screenBase + tileY * sizeTiles + tileX
            if (mapAddr >= mmu.vram.size) continue
            val tileId = mmu.vram[mapAddr].toInt() and 0xFF
            val tileAddr = charBase + tileId * 64 + (py and 7) * 8 + (px and 7)
            if (tileAddr >= mmu.vram.size) continue
            val colorIdx = mmu.vram[tileAddr].toInt() and 0xFF
            if (colorIdx != 0 && priority <= bgPriorities[x]) {
                bgColors[x] = rgb555ToArgb(readPalette16(colorIdx shl 1))
                bgPriorities[x] = priority
            }
        }
    }

    private fun renderSpritesScanline(dispcnt: Int, y: Int) {
        val oneDim = (dispcnt and 0x40) != 0
        val mode = dispcnt and 0x07
        val objCharBase = 0x10000

        for (i in 0 until 128) {
            val o = i * 8
            val attr0 = readOam16(o)
            val objMode = (attr0 ushr 8) and 3
            if (objMode == 2) continue // Disabled

            val attr1 = readOam16(o + 2)
            val attr2 = readOam16(o + 4)

            val shape = (attr0 ushr 14) and 3
            val size = (attr1 ushr 14) and 3
            val (w, h) = spriteDimensions(shape, size)
            if (w == 0) continue

            val affine = objMode == 1 || objMode == 3
            val boxW = if (objMode == 3) w * 2 else w
            val boxH = if (objMode == 3) h * 2 else h

            var sy = attr0 and 0xFF
            if (sy >= 160) sy -= 256
            if (y < sy || y >= sy + boxH) continue

            var sx = attr1 and 0x1FF
            if (sx >= 240) sx -= 512

            val color256 = (attr0 and 0x2000) != 0
            val priority = (attr2 ushr 10) and 3
            val tileNum = attr2 and 0x3FF
            if (mode >= 3 && tileNum < 512) continue
            val palBank = (attr2 ushr 12) and 0xF

            val tilesPerRow = w ushr 3
            val hFlip = !affine && (attr1 and 0x1000) != 0
            val vFlip = !affine && (attr1 and 0x2000) != 0

            val affineIdx = if (affine) ((attr1 ushr 9) and 0x1F) else 0
            val pa = if (affine) readOam16(affineIdx * 32 + 0x06).toShort().toInt() else 256
            val pb = if (affine) readOam16(affineIdx * 32 + 0x0E).toShort().toInt() else 0
            val pc = if (affine) readOam16(affineIdx * 32 + 0x16).toShort().toInt() else 0
            val pd = if (affine) readOam16(affineIdx * 32 + 0x1E).toShort().toInt() else 256

            val halfW = boxW / 2
            val halfH = boxH / 2
            val localY = y - sy

            for (dx in 0 until boxW) {
                val screenX = sx + dx
                if (screenX !in 0 until GbaConstants.SCREEN_WIDTH) continue
                if (objColors[screenX] != 0 && priority >= objPriorities[screenX]) continue

                val tx: Int
                val ty: Int
                if (affine) {
                    val rx = dx - halfW
                    val ry = localY - halfH
                    tx = ((pa * rx + pb * ry) shr 8) + (w / 2)
                    ty = ((pc * rx + pd * ry) shr 8) + (h / 2)
                    if (tx !in 0 until w || ty !in 0 until h) continue
                } else {
                    tx = if (hFlip) w - 1 - dx else dx
                    ty = if (vFlip) h - 1 - localY else localY
                }

                val tileX = tx ushr 3
                val tileY = ty ushr 3
                val fineX = tx and 7
                val fineY = ty and 7

                val effectiveTile = if (oneDim) {
                    tileNum + (tileY * tilesPerRow + tileX) * (if (color256) 2 else 1)
                } else {
                    val baseRow = (tileNum and 0x3E0) + tileY * 32
                    val baseCol = (tileNum and 0x1F) + tileX * (if (color256) 2 else 1)
                    baseRow + (baseCol and 0x1F)
                }

                val addr = objCharBase + effectiveTile * 32 + if (color256) {
                    fineY * 8 + fineX
                } else {
                    fineY * 4 + (fineX ushr 1)
                }
                if (addr >= mmu.vram.size) continue

                val colorIdx = if (color256) {
                    mmu.vram[addr].toInt() and 0xFF
                } else {
                    val b = mmu.vram[addr].toInt() and 0xFF
                    if ((fineX and 1) == 0) b and 0x0F else (b ushr 4) and 0x0F
                }
                if (colorIdx != 0) {
                    val palOffset = 0x200 + ((if (color256) colorIdx else (palBank shl 4) + colorIdx) shl 1)
                    objColors[screenX] = rgb555ToArgb(readPalette16(palOffset))
                    objPriorities[screenX] = priority
                }
            }
        }
    }

    private fun spriteDimensions(shape: Int, size: Int): Pair<Int, Int> = when (shape) {
        0 -> when (size) { 0 -> 8 to 8; 1 -> 16 to 16; 2 -> 32 to 32; else -> 64 to 64 }
        1 -> when (size) { 0 -> 16 to 8; 1 -> 32 to 8; 2 -> 32 to 16; else -> 64 to 32 }
        2 -> when (size) { 0 -> 8 to 16; 1 -> 8 to 32; 2 -> 16 to 32; else -> 32 to 64 }
        else -> 0 to 0
    }

    private fun readPalette16(offset: Int): Int {
        val o = offset and 0x3FE
        return (mmu.palette[o].toInt() and 0xFF) or ((mmu.palette[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun readVram16(offset: Int): Int {
        val o = offset and 0x1FFFE
        val safe = if (o >= 0x18000) o - 0x8000 else o
        return (mmu.vram[safe].toInt() and 0xFF) or ((mmu.vram[safe + 1].toInt() and 0xFF) shl 8)
    }

    private fun readOam16(offset: Int): Int {
        val o = offset and 0x3FE
        return (mmu.oam[o].toInt() and 0xFF) or ((mmu.oam[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun rgb555ToArgb(c: Int): Int {
        val r = ((c and 0x1F) * 255) / 31
        val g = (((c ushr 5) and 0x1F) * 255) / 31
        val b = (((c ushr 10) and 0x1F) * 255) / 31
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
