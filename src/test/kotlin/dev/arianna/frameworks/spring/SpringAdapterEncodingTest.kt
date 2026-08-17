package dev.arianna.frameworks.spring

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class SpringAdapterEncodingTest {
    @Test
    fun `continues indexing when a source file contains malformed utf8`() {
        val root = createTempDirectory("arianna-spring-encoding-")
        val source = root.resolve("src/main/java/PaymentService.java")
        Files.createDirectories(source.parent)
        Files.write(
            source,
            byteArrayOf(
                '@'.code.toByte(), 'S'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 'v'.code.toByte(),
                'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(), '\n'.code.toByte(),
                'c'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(),
                ' '.code.toByte(), 'P'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte(), 'm'.code.toByte(),
                'e'.code.toByte(), 'n'.code.toByte(), 't'.code.toByte(), 'S'.code.toByte(), 'e'.code.toByte(),
                'r'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(),
                ' '.code.toByte(), 0xC3.toByte(), '\n'.code.toByte(), '{'.code.toByte(), '}'.code.toByte()
            )
        )

        val analysis = SpringAdapter().analyze(root, "repo", "HEAD")

        assertTrue(analysis.entities.any { it.id.value == "class:PaymentService" })
    }
}
