package com.aicode.feature.terminal.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.terminal.domain.font.TerminalFontManager
import com.aicode.feature.terminal.domain.model.TerminalThemePreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalDataStore by preferencesDataStore(
    name = "terminal_settings_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Terminal configuration model. */
data class TerminalSettings(
    val themeId: String = TerminalThemePreset.TERMIUS_DARK.id,
    val fontSizeSp: Int = 12,
    val cursorStyle: Int = 0, // 0=Block, 1=Underline, 2=Bar
    /** Font identifier: empty = system monospace, TerminalFontManager.BUILTIN_PATH = built-in JetBrains Mono NL, otherwise the absolute path of an imported font. */
    val fontPath: String = TerminalFontManager.BUILTIN_PATH
) {
    val theme: TerminalThemePreset
        get() = TerminalThemePreset.findById(themeId)
}

/** Persistence repository for terminal personalization settings (DataStore). */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_ID_KEY = stringPreferencesKey("terminal_theme_id")
        val FONT_SIZE_SP_KEY = intPreferencesKey("terminal_font_size_sp")
        val CURSOR_STYLE_KEY = intPreferencesKey("terminal_cursor_style")
        val FONT_PATH_KEY = stringPreferencesKey("terminal_font_path")
    }

    val settingsFlow: Flow<TerminalSettings> = context.terminalDataStore.data.map { prefs ->
        TerminalSettings(
            themeId = prefs[THEME_ID_KEY] ?: TerminalThemePreset.TERMIUS_DARK.id,
            fontSizeSp = prefs[FONT_SIZE_SP_KEY]?.coerceIn(5, 22) ?: 12,
            cursorStyle = prefs[CURSOR_STYLE_KEY] ?: 0,
            // Users who have never set a font get the built-in font; those who explicitly chose "system monospace" (empty string) keep it as-is; if an imported font has been deleted, fall back to the built-in one
            fontPath = when (val saved = prefs[FONT_PATH_KEY]) {
                null -> TerminalFontManager.BUILTIN_PATH
                "", TerminalFontManager.BUILTIN_PATH -> saved
                else -> if (File(saved).isFile) saved else TerminalFontManager.BUILTIN_PATH
            }
        )
    }

    suspend fun setThemeId(themeId: String) {
        context.terminalDataStore.edit { it[THEME_ID_KEY] = themeId }
    }

    suspend fun setFontSizeSp(sizeSp: Int) {
        context.terminalDataStore.edit { it[FONT_SIZE_SP_KEY] = sizeSp.coerceIn(5, 22) }
    }

    suspend fun setCursorStyle(style: Int) {
        context.terminalDataStore.edit { it[CURSOR_STYLE_KEY] = style.coerceIn(0, 2) }
    }

    suspend fun setFontPath(path: String) {
        context.terminalDataStore.edit { it[FONT_PATH_KEY] = path }
    }
}
