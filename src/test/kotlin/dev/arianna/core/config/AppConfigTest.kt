package dev.arianna.core.config

import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `uses repository local data directory by default`() {
        val root = createTempDirectory("arianna-config-")

        val config = AppConfig.forRepository(root)

        assertEquals(root.resolve(".arianna"), config.dataDirectory)
        assertEquals(root.resolve(".arianna/knowledge.db"), config.databaseFile)
        config.ensureDataDirectory()
        assertTrue(config.dataDirectory.exists())
    }
}
