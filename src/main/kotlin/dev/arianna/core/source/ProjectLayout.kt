package dev.arianna.core.source

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

enum class BuildSystem {
    MAVEN,
    GRADLE
}

data class ProjectLayout(
    val root: Path,
    val buildSystems: Set<BuildSystem>
) {
    val isJvmProject: Boolean
        get() = buildSystems.isNotEmpty()

    companion object {
        fun detect(root: Path): ProjectLayout {
            val systems = buildSet {
                if (Files.exists(root.resolve("pom.xml"))) add(BuildSystem.MAVEN)
                if (sequenceOf(
                        "build.gradle",
                        "build.gradle.kts",
                        "settings.gradle",
                        "settings.gradle.kts"
                    ).any { Files.exists(root.resolve(it)) }
                ) add(BuildSystem.GRADLE)
            }
            return ProjectLayout(root, systems)
        }

        /**
         * Finds the Gradle build root used for semantic indexing.
         *
         * Some repositories have a Git root with a small wrapper settings file
         * and a complete Gradle build nested below it (for example, `backend/`).
         * scip-java must run from the complete build root because it invokes the
         * build tool there.
         */
        fun scipBuildRoot(root: Path): Path {
            val normalizedRoot = root.toAbsolutePath().normalize()
            if (Files.exists(normalizedRoot.resolve("pom.xml")) || hasGradleBuildFile(normalizedRoot)) {
                return normalizedRoot
            }

            val nestedBuildRoots = runCatching {
                Files.walk(normalizedRoot, 3).use { paths ->
                    paths
                        .filter { it != normalizedRoot && it.isDirectory() }
                        .filter { hasGradleBuildFile(it) && hasGradleSettingsFile(it) }
                        .sorted()
                        .toList()
                }
            }.getOrDefault(emptyList())

            return nestedBuildRoots.firstOrNull() ?: normalizedRoot
        }

        private fun hasGradleBuildFile(root: Path): Boolean =
            Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))

        private fun hasGradleSettingsFile(root: Path): Boolean =
            Files.exists(root.resolve("settings.gradle")) || Files.exists(root.resolve("settings.gradle.kts"))
    }
}
