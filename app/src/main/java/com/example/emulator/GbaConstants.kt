package com.example.emulator

object GbaConstants {
    const val SCREEN_WIDTH = 240
    const val SCREEN_HEIGHT = 160
    const val PIXEL_COUNT = SCREEN_WIDTH * SCREEN_HEIGHT

    const val CYCLES_PER_SCANLINE = 1232
    const val VISIBLE_SCANLINES = 160
    const val TOTAL_SCANLINES = 228
    const val CYCLES_PER_FRAME = CYCLES_PER_SCANLINE * TOTAL_SCANLINES // 280,896 cycles @ 16.78 MHz

    // Keypad bits (active-low in KEYINPUT 0x04000130)
    const val KEY_A = 1 shl 0
    const val KEY_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
    const val KEY_R = 1 shl 8
    const val KEY_L = 1 shl 9
    const val KEY_ALL_RELEASED = 0x03FF

    // IRQ flags (IE/IF)
    const val IRQ_VBLANK = 1 shl 0
    const val IRQ_HBLANK = 1 shl 1
    const val IRQ_VCOUNT = 1 shl 2
    const val IRQ_TIMER0 = 1 shl 3
    const val IRQ_TIMER1 = 1 shl 4
    const val IRQ_TIMER2 = 1 shl 5
    const val IRQ_TIMER3 = 1 shl 6
    const val IRQ_SERIAL = 1 shl 7
    const val IRQ_DMA0 = 1 shl 8
    const val IRQ_DMA1 = 1 shl 9
    const val IRQ_DMA2 = 1 shl 10
    const val IRQ_DMA3 = 1 shl 11
    const val IRQ_KEYPAD = 1 shl 12
}

enum class SaveType(val displayName: String) {
    NONE("None"),
    SRAM("SRAM 32/64KB"),
    EEPROM("EEPROM 512B/8KB"),
    FLASH64("Flash 64KB"),
    FLASH128("Flash 128KB")
}

enum class ShaderPreset(val label: String, val description: String) {
    SHARP("Sharp Pixel", "Crisp nearest-neighbor GBA pixels"),
    LCD3X("LCD3x Grid", "Authentic GBA TFT subpixel matrix & grid"),
    AGB_COLOR("AGB-001 Retro", "Original warm desaturated handheld LCD"),
    AGS_101("AGS-101 Backlit", "High-contrast backlit portable screen"),
    CRT_SCANLINES("CRT Scanlines", "Horizontal scanlines & subtle phosphor glow"),
    SCALE2X("Smooth Blend", "Soft bilinear pixel smoothing")
}

enum class SpeedToggleMode(val label: String) {
    TOGGLE("Tap to Toggle"),
    HOLD("Hold to Boost")
}

enum class OrientationMode(val label: String) {
    AUTO("Auto Rotate"),
    PORTRAIT("Portrait Lock"),
    LANDSCAPE("Landscape Lock")
}

enum class BiosMode(val label: String, val description: String) {
    AUTO("Auto", "Use custom 16KB gba_bios.bin if found, else built-in HLE BIOS"),
    CUSTOM("Custom BIOS", "Require imported 16KB gba_bios.bin when available"),
    HLE("HLE BIOS", "Always use built-in zero-setup HLE BIOS")
}
