package com.miniqwerty.kb

/**
 * Shared preference keys and defaults used by both the IME service and the
 * settings screen.
 */
object Prefs {
    const val NAME = "miniqwerty_kb_prefs"

    // Theme mode
    const val KEY_THEME_MODE = "theme_mode"
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    // Keyboard row height, in dp
    const val KEY_ROW_HEIGHT_DP = "row_height_dp"
    const val ROW_HEIGHT_DEFAULT_DP = 46f
    const val ROW_HEIGHT_MIN_DP = 30f
    const val ROW_HEIGHT_MAX_DP = 75f
}
