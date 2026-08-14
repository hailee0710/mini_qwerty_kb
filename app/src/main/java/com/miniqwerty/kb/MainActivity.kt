package com.miniqwerty.kb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup

/**
 * Settings screen for the keyboard: theme mode.
 * Also the launcher entry point for the app.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // ── Theme mode ────────────────────────────────────────────────────
        val themeGroup = findViewById<RadioGroup>(R.id.theme_group)
        when (prefs.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_SYSTEM)) {
            Prefs.THEME_LIGHT -> themeGroup.check(R.id.theme_light)
            Prefs.THEME_DARK  -> themeGroup.check(R.id.theme_dark)
            else              -> themeGroup.check(R.id.theme_system)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> Prefs.THEME_LIGHT
                R.id.theme_dark  -> Prefs.THEME_DARK
                else             -> Prefs.THEME_SYSTEM
            }
            prefs.edit().putInt(Prefs.KEY_THEME_MODE, mode).apply()
        }

        // ── Enable keyboard shortcut ──────────────────────────────────────
        findViewById<Button>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}
