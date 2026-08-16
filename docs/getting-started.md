# Getting started

## Requirements

- JDK 21 to build Arianna;
- Git for repository status, baselines and revision comparisons;
- macOS or Linux for the supplied installer;
- optional `scip-java` and `scip` on `PATH` for semantic JVM indexing. See [SCIP setup](#scip-setup).

## Build from source

```bash
./gradlew installDist
build/install/learn/bin/learn --version
```

The installed distribution includes Arianna and its dependencies. The target machine still needs Git for Git-aware analysis.

To build a platform-specific package with an embedded Java runtime, use JDK 21:

```bash
./gradlew packageAutonomous
./gradlew packageAutonomousZip
```

To install or update a per-user `learn` command on macOS or Linux:

```bash
./scripts/install.sh
```

The installer uses `~/.local/share/arianna` and `~/.local/bin/learn`. Add `~/.local/bin` to `PATH` if necessary.

## First index

From a clean Git repository:

```bash
learn .
learn status .
```

The first index creates `.arianna/knowledge.db` and, if needed, `.arianna/ignore` from the repository `.gitignore`. The source repository is not modified by analysis.

Check optional tools before indexing:

```bash
learn preflight .
```

If SCIP is unavailable, Arianna uses its local structural JVM fallback. It is intentionally less precise; the result still identifies its origin and confidence.

## SCIP setup

SCIP consists of two commands in Arianna's integration:

- `scip-java` analyzes Java/Kotlin code and generates `index.scip`;
- `scip` reads that index and prints its structured JSON representation.

On macOS with Homebrew and Coursier, install the compatible `scip-java` launcher and the Go-based `scip` CLI:

```bash
brew install coursier/formulas/coursier go
mkdir -p "$HOME/.local/bin"

coursier bootstrap \
  --standalone \
  -o "$HOME/.local/bin/scip-java" \
  org.scip-code:scip-java:0.13.1 \
  --main org.scip_code.scip_java.ScipJava

git clone --depth=1 https://github.com/scip-code/scip.git /tmp/scip
(cd /tmp/scip && go build -o "$HOME/.local/bin/scip" ./cmd/scip)
export PATH="$HOME/.local/bin:$PATH"
```

Verify both commands:

```bash
scip-java --help
scip --version
learn preflight .
```

SCIP is optional. The normal `learn .` workflow uses Arianna's local structural/Spring indexer. To request semantic SCIP indexing, run:

```bash
learn index --scip --path .
```

With SCIP, symbol definitions, references and implementations are generally more precise. Without it, Arianna still indexes Java/Kotlin structure, documents and supported framework evidence, but dynamic calls and some cross-file relationships may be incomplete.

The `--scip` flag is best-effort. Arianna never asks the analyzed repository to upgrade its Java, Kotlin or Gradle toolchain. If `scip-java` cannot attach to the project's compiler, or the build fails because of a toolchain incompatibility, Arianna reports the reason on stderr and continues with the local structural/Spring indexer. The resulting evidence keeps its local analyzer origin and confidence. Use `learn preflight .` to check availability before indexing.

Arianna also disables Gradle's configuration cache only in the temporary child process used by `scip-java`; it does not edit the repository's `gradle.properties` or change the user's normal build behavior.

This distinction matters for Kotlin projects: `scip-java` runs compiler plugins as part of the regular build, so its Kotlin support is tied to the project's compiler version. Installing a newer `scip` CLI does not fix a `scip-java` compiler-plugin incompatibility; `scip` only reads and prints the generated `index.scip` file.

The command above is an example for a current `scip-java` release, not a universal compatibility guarantee. Keep the `scip-java` release aligned with the analyzed build when possible. For example, the tested releases `0.13.0` and `0.13.1` embed a Kotlin compiler/plugin line different from Kotlin `2.1.10`, while the older `0.12.3` has a different Gradle integration and also cannot be assumed to work with every Gradle version. Arianna treats these differences as a reason to use its local fallback, not as a reason to change the analyzed repository.

### Nested Gradle builds

Some repositories have a Git root that contains a separate Gradle build, for example:

```text
repository/
├── settings.gradle
└── backend/
    ├── settings.gradle
    └── build.gradle
```

Arianna detects the nested build root and runs SCIP there while keeping evidence paths relative to the Git root. This allows `learn index --scip --path .` to work for repositories such as this layout. If the repository has multiple unrelated nested Gradle builds, use the fallback or index each build separately until an explicit build-root option is available.

## Troubleshooting

If Gradle selects a JDK other than 21, set `JAVA_HOME` to a JDK 21 installation before building Arianna. If an index is missing, run `learn index .`. A dirty repository cannot overwrite its clean baseline; index it with `learn index --working-tree .` instead. If SCIP reports a compiler or build-tool incompatibility, use the local fallback or inspect the diagnostic with `learn preflight .`; do not change the analyzed project's toolchain just for Arianna. If `scip-java` reports that Gradle cannot find `clean`, check that the command is being run from the actual Gradle build root; Arianna detects a single nested Gradle build automatically.
