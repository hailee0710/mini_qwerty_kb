package com.miniqwerty.kb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast

/**
 * Settings screen for the keyboard: theme mode, learned-words backup/restore.
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

        // ── Learned words backup / restore ────────────────────────────────
        // Storage Access Framework: the user picks the file location, so no
        // storage permission is needed; the picker works with local storage
        // and cloud providers alike.
        findViewById<Button>(R.id.backup_button).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, BACKUP_FILE_NAME),
                REQUEST_CREATE_BACKUP
            )
        }
        findViewById<Button>(R.id.restore_button).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain"),
                REQUEST_OPEN_BACKUP
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_CREATE_BACKUP -> writeBackup(uri)
            REQUEST_OPEN_BACKUP   -> readBackup(uri)
        }
    }

    /**
     * Serialize the learned words to the chosen document. Prefers the live
     * engine map (same process — may hold words not yet persisted to prefs);
     * falls back to the stored string when the keyboard has never run.
     */
    private fun writeBackup(uri: android.net.Uri) {
        try {
            val engineWords = SuggestionEngine.userCountsSnapshot()
            val content = if (engineWords.isNotEmpty()) {
                SuggestionEngine.serializeUserCounts(engineWords)
            } else {
                SuggestionEngine.serializeUserCounts(
                    SuggestionEngine.parseUserCounts(prefs.getString(Prefs.KEY_USER_WORDS, null))
                )
            }
            val count = content.lineSequence().count { it.isNotBlank() && !it.startsWith('#') }
            if (count == 0) {
                Toast.makeText(this, R.string.backup_nothing, Toast.LENGTH_SHORT).show()
                return
            }
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, getString(R.string.backup_exported, count), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Parse the chosen document and replace the learned-word set. The running
     * IME picks the change up live through the engine (same process); a
     * not-yet-created IME reads the updated prefs at its onCreate.
     */
    private fun readBackup(uri: android.net.Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: return
            if (!raw.trimStart().startsWith(SuggestionEngine.BACKUP_HEADER_LINE)) {
                Toast.makeText(this, R.string.restore_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            val parsed = SuggestionEngine.parseUserCounts(raw)
            if (parsed.isEmpty()) {
                Toast.makeText(this, R.string.restore_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            // Replace semantics: the restored set becomes the learning state.
            prefs.edit().putString(Prefs.KEY_USER_WORDS, SuggestionEngine.serializeUserCounts(parsed)).apply()
            SuggestionEngine.setUserCounts(parsed)
            Toast.makeText(this, getString(R.string.restore_done, parsed.size), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.restore_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_CREATE_BACKUP = 1
        private const val REQUEST_OPEN_BACKUP = 2
        private const val BACKUP_FILE_NAME = "mini_qwerty_words_backup.txt"
    }
}
