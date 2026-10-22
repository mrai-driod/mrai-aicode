package com.aicode.core.db

/**
 * Splits an SQL script into statements, recognising comments and literals so semicolons inside them do not participate in splitting.
 *
 * The old implementation used `split(";")` directly, so a semicolon inside a string literal (such as `';base64,'`) would be cut in half,
 * failing the whole migration. The state machine covers: `--` line comments, `/* */` block comments, single-quote/double-quote/backtick
 * literals (including doubled escapes `''`), and `[...]` identifiers. Block comments do not support nesting, per SQLite semantics.
 */
object SqlScriptSplitter {

    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var currentHasContent = false
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            when {
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    current.append("--")
                    i += 2
                    while (i < n && script[i] != '\n') {
                        current.append(script[i])
                        i++
                    }
                }

                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    current.append("/*")
                    i += 2
                    while (i < n && !(script[i] == '*' && i + 1 < n && script[i + 1] == '/')) {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append("*/")
                        i += 2
                    }
                }

                c == '\'' || c == '"' || c == '`' -> {
                    current.append(c)
                    i++
                    while (i < n) {
                        if (script[i] == c) {
                            // A doubled character is an escape (SQL '', ``, ""), so stay inside the literal
                            current.append(c)
                            i++
                            if (i < n && script[i] == c) {
                                current.append(c)
                                i++
                            } else {
                                break
                            }
                        } else {
                            current.append(script[i])
                            i++
                        }
                    }
                    currentHasContent = true
                }

                c == '[' -> {
                    current.append(c)
                    i++
                    while (i < n && script[i] != ']') {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append(']')
                        i++
                    }
                    currentHasContent = true
                }

                c == ';' -> {
                    if (currentHasContent) {
                        val stmt = current.toString().trim()
                        if (stmt.isNotEmpty()) statements.add(stmt)
                    }
                    current.setLength(0)
                    currentHasContent = false
                    i++
                }

                else -> {
                    current.append(c)
                    if (!c.isWhitespace()) currentHasContent = true
                    i++
                }
            }
        }
        val last = current.toString().trim()
        if (currentHasContent && last.isNotEmpty()) statements.add(last)
        return statements
    }
}