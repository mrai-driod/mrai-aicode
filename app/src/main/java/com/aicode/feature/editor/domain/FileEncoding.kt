package com.aicode.feature.editor.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Character encoding detection for text files: consolidates the "bytes -> string" encoding
 * ambiguity into a single implementation, used uniformly by the editor when reading files
 * and by Git diff when reading workspace files.
 *
 * Detection rules (in priority order):
 * 1. With a UTF-8 / UTF-16 BOM -> decode according to the BOM (and strip the BOM);
 * 2. Otherwise, strict UTF-8 decoding ([CodingErrorAction.REPORT]) succeeds -> UTF-8;
 * 3. Still fails -> decode as GBK (GBK is a common legacy Chinese Windows encoding,
 *    covering the vast majority of mojibake cases).
 *
 * Write-side convention: remember the detected [FileEncoding.charset] when reading,
 * and write back using the same encoding when saving, to avoid silently converting
 * a GBK file to UTF-8 and corrupting the original file.
 */
enum class FileEncoding(val charset: Charset, val bom: ByteArray?) {
    UTF_8(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF_16_LE(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF_16_BE(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    GBK(Charset.forName("GBK"), null);

    /** Display name, for display in UI such as the status bar. */
    val displayName: String
        get() = when (this) {
            UTF_8 -> "UTF-8"
            UTF_16_LE -> "UTF-16 LE"
            UTF_16_BE -> "UTF-16 BE"
            GBK -> "GBK"
        }

    companion object {
        /**
         * Detects the byte stream encoding and decodes it to a string. Empty input returns an empty UTF-8 string.
         */
        fun decode(bytes: ByteArray): DecodedText {
            if (bytes.isEmpty()) return DecodedText("", UTF_8)
            val withBom = bytes.mapBom()?.let { (enc, rest) -> enc to rest }
            val (enc, body) = withBom ?: (detectBomless(bytes) to bytes)
            val text = String(body, enc.charset)
            return DecodedText(text, enc)
        }

        private fun ByteArray.mapBom(): Pair<FileEncoding, ByteArray>? {
            for (enc in entries) {
                val bom = enc.bom ?: continue
                if (size >= bom.size && bom.indices.all { this[it] == bom[it] }) {
                    return enc to copyOfRange(bom.size, size)
                }
            }
            return null
        }

        /** Without a BOM: strict UTF-8 success means UTF-8, otherwise GBK. */
        private fun detectBomless(bytes: ByteArray): FileEncoding {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes))
                UTF_8
            } catch (e: CharacterCodingException) {
                GBK
            }
        }
    }
}

/** Decoding result: string + detected encoding (used when writing back). */
data class DecodedText(val text: String, val encoding: FileEncoding)
