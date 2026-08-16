package dev.arianna.core.source

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Shared path policy for repository scans. Ignore rules use the standard gitignore syntax. */
object RepositoryPathFilter {
    private val ignoredDirectories = setOf(".git", ".arianna", ".gradle", "build", "target", "out")
    private const val ignoreRelativePath = ".arianna/ignore"
    private val cache = ConcurrentHashMap<String, CachedIgnore>()

    fun ensureIgnoreFile(root: Path): Path {
        val directory = root.resolve(".arianna")
        val target = directory.resolve("ignore")
        if (!Files.exists(target)) {
            Files.createDirectories(directory)
            val source = root.resolve(".gitignore")
            Files.writeString(target, if (Files.isRegularFile(source)) Files.readString(source) else "")
        }
        cache.remove(root.toAbsolutePath().normalize().toString())
        return target
    }

    fun isIgnored(root: Path, path: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!normalizedPath.startsWith(normalizedRoot)) return true
        val relative = normalizedRoot.relativize(normalizedPath).toString().replace(path.fileSystem.separator, "/")
        if (relative.isEmpty()) return false
        if (relative == ignoreRelativePath) return true
        if (relative.split('/').any { it in ignoredDirectories || it.startsWith(".gradle-") }) return true
        return load(normalizedRoot).matches(relative)
    }

    private fun load(root: Path): IgnoreConfiguration {
        val path = root.resolve(ignoreRelativePath)
        val modified = if (Files.isRegularFile(path)) Files.getLastModifiedTime(path).toMillis() else -1L
        val size = if (modified >= 0) Files.size(path) else -1L
        val key = root.toString()
        val current = cache[key]
        if (current != null && current.modified == modified && current.size == size) return current.configuration
        val configuration = if (modified >= 0) IgnoreConfiguration.parse(Files.readAllLines(path)) else IgnoreConfiguration.parse(emptyList())
        cache[key] = CachedIgnore(modified, size, configuration)
        return configuration
    }

    private data class CachedIgnore(val modified: Long, val size: Long, val configuration: IgnoreConfiguration)
}

class IgnoreConfiguration private constructor(private val rules: List<IgnoreRule>) {
    fun matches(relativePath: String): Boolean {
        val normalized = relativePath.trim('/').replace('\\', '/')
        var ignored = false
        rules.forEach { rule -> if (rule.matches(normalized)) ignored = !rule.negated }
        return ignored
    }

    companion object {
        fun parse(lines: List<String>): IgnoreConfiguration = IgnoreConfiguration(
            lines.mapNotNull { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val negated = line.startsWith("!") && !line.startsWith("\\!")
                val pattern = line.removePrefix("!").removePrefix("\\").trim()
                if (pattern.isEmpty()) null else IgnoreRule(pattern, negated)
            }
        )
    }
}

internal data class IgnoreRule(val rawPattern: String, val negated: Boolean) {
    private val directoryOnly = rawPattern.endsWith('/')
    private val pattern = rawPattern.trim('/').replace('\\', '/')
    private val hasSlash = pattern.contains('/')
    private val regex = globRegex(pattern)

    fun matches(path: String): Boolean {
        val candidates = if (hasSlash) listOf(path) else path.split('/')
        return candidates.any { regex.matches(it) } || (directoryOnly && path.startsWith("$pattern/"))
    }

    private fun globRegex(glob: String): Regex {
        val result = buildString {
            append('^')
            var index = 0
            while (index < glob.length) {
                when {
                    glob.startsWith("**/", index) -> { append("(?:.*/)?"); index += 3 }
                    glob.startsWith("**", index) -> { append(".*"); index += 2 }
                    glob[index] == '*' -> { append("[^/]*"); index++ }
                    glob[index] == '?' -> { append("[^/]"); index++ }
                    else -> { append(Regex.escape(glob[index].toString())); index++ }
                }
            }
            append('$')
        }
        return Regex(result.toString())
    }
}
