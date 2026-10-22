package com.aicode.feature.editor.domain

import android.content.Context
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * One-time initialization of TextMate grammars and themes.
 *
 * The grammar bundle is nearly 1 MB in size, and [GrammarRegistry.loadGrammars] parses it
 * in full, so it must be called on an IO thread and must not be placed in
 * Application.onCreate or on the main thread, otherwise it slows down cold start / janks the first frame.
 */
object TextMateSetup {

    const val THEME_DARK = "dark_plus"
    const val THEME_LIGHT = "light_plus"

    @Volatile
    private var initialized = false

    /** Idempotent: called every time the editor page opens, but only actually loads the first time. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // TextMate analysis runs sequentially from the first line to the last line (each line's state depends on the previous line), so a full run on a large file takes several seconds.
            // With this enabled, after every 1000 lines analyzed the coloring obtained so far is pushed to the editor, so the first screen does not have to wait for the full analysis to finish.
            AsyncIncrementalAnalyzeManager.setUpdateStylesDuringAnalysis(true)
            FileProviderRegistry.getInstance()
                .addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            loadThemes()
            GrammarRegistry.getInstance().loadGrammars(ASSET_LANGUAGES)
            initialized = true
        }
    }

    private fun loadThemes() {
        val registry = ThemeRegistry.getInstance()
        listOf(THEME_DARK to true, THEME_LIGHT to false).forEach { (name, dark) ->
            val path = "textmate/$name.json"
            val source = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(path),
                path,
                null
            )
            registry.loadTheme(ThemeModel(source, name).apply { isDark = dark })
        }
    }

    /** Switches the current grammar theme; it must match the light/dark mode of the Compose theme, otherwise token colors will not match the background. */
    fun applyTheme(dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) THEME_DARK else THEME_LIGHT)
    }

    /**
     * Returns the TextMate scopeName, covering only the grammars actually bundled in
     * assets/textmate/languages.json. Returning null means no highlighting, displayed as plain text.
     *
     * Must be called after [ensureInitialized]: the extension mapping and languages.json are
     * two independent lists, and unpackaged grammars must be filtered out here --
     * TextMateLanguage.create throws IllegalArgumentException for unregistered scopes,
     * which would crash directly during editor construction.
     */
    fun scopeNameFor(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val scope = scopeByExtension(fileName.substringAfterLast('.', "").lowercase()) ?: return null
        return scope.takeIf { GrammarRegistry.getInstance().findGrammar(it) != null }
    }

    private fun scopeByExtension(ext: String): String? = when (ext) {
        "kt", "kts" -> "source.kotlin"
        "java" -> "source.java"
        "py", "pyw", "pyi" -> "source.python"
        "js", "mjs", "cjs", "jsx" -> "source.js"
        "ts", "mts", "cts" -> "source.ts"
        "tsx" -> "source.tsx"
        "json" -> "source.json"
        "jsonc", "json5" -> "source.json.comments"
        "yaml", "yml" -> "source.yaml"
        "sh", "bash", "zsh", "ksh", "bashrc", "zshrc", "profile" -> "source.shell"
        "go" -> "source.go"
        "rs" -> "source.rust"
        "c", "h" -> "source.c"
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> "source.cpp"
        "css" -> "source.css"
        "php" -> "source.php"
        "lua" -> "source.lua"
        "xml" -> "text.xml"
        "html", "htm" -> "text.html.basic"
        "md", "markdown" -> "text.html.markdown"
        else -> null
    }

    private const val ASSET_LANGUAGES = "textmate/languages.json"
}
