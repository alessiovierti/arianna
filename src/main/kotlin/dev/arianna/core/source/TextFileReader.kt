package dev.arianna.core.source

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads repository text without making indexing fail on one malformed byte.
 * Replacing malformed input preserves the surrounding source and lets the
 * structural analyzers report what they can still recognize.
 */
object TextFileReader {
    fun readText(path: Path): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
    }

    fun readLines(path: Path): List<String> = readText(path).lineSequence().toList()
}
