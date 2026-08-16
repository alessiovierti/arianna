package dev.arianna.core.config

import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

data class AppConfig(
    val repositoryRoot: Path,
    val dataDirectory: Path,
    val outputJson: Boolean = false
) {
    val databaseFile: Path
        get() = dataDirectory.resolve("knowledge.db")

    fun ensureDataDirectory(): AppConfig {
        dataDirectory.createDirectories()
        return this
    }

    companion object {
        fun forRepository(repositoryRoot: Path, outputJson: Boolean = false): AppConfig {
            val root = repositoryRoot.absolute().normalize()
            return AppConfig(root, root.resolve(".arianna"), outputJson)
        }
    }
}
