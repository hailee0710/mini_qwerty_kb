package com.miniqwerty.kb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/**
 * Settings screen for the keyboard: theme mode and keyboard height.
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

        // ── Keyboard height ───────────────────────────────────────────────
        val heightValue = findViewById<TextView>(R.id.height_value)
        val heightSeek = findViewById<SeekBar>(R.id.height_seek)
        heightSeek.max = (Prefs.ROW_HEIGHT_MAX_DP - Prefs.ROW_HEIGHT_MIN_DP).toInt()

        val rowHeight = prefs
            .getFloat(Prefs.KEY_ROW_HEIGHT_DP, Prefs.ROW_HEIGHT_DEFAULT_DP)
            .coerceIn(Prefs.ROW_HEIGHT_MIN_DP, Prefs.ROW_HEIGHT_MAX_DP)
        heightSeek.progress = (rowHeight - Prefs.ROW_HEIGHT_MIN_DP).toInt()
        updateHeightLabel(heightValue, rowHeight)

        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val dp = Prefs.ROW_HEIGHT_MIN_DP + progress
                updateHeightLabel(heightValue, dp)
                if (fromUser) {
                    prefs.edit().putFloat(Prefs.KEY_ROW_HEIGHT_DP, dp).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // ── Enable keyboard shortcut ──────────────────────────────────────
        findViewById<Button>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun updateHeightLabel(view: TextView, dp: Float) {
        view.text = getString(R.string.height_value, dp.toInt())
    }
}
