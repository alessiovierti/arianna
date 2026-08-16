# Arianna contributor guide

This file contains repository instructions for agents and maintainers. User-facing and contributor-facing documentation belongs under `docs/`; this file is the only agent-specific instruction file at the repository root.

## Project map

- `src/main/kotlin/dev/arianna/cli/Main.kt` — CLI entry point and command wiring.
- `src/main/kotlin/dev/arianna/core/` — source, model, indexing, query and analysis engine.
- `src/main/kotlin/dev/arianna/frameworks/` — framework adapters.
- `src/main/kotlin/dev/arianna/storage/` — SQLite persistence and snapshots.
- `src/main/kotlin/dev/arianna/mcp/` — local MCP/JSON-RPC transport.
- `src/main/kotlin/dev/arianna/web/` — local Web Explorer and snapshot export.
- `src/test/kotlin/` — executable behavior and regression fixtures.
- `docs/` — public documentation, roadmap, validation material and development notes.

## Working rules

1. Keep `README.md` concise and oriented to a new user. Put detailed documentation in `docs/`.
2. Treat `docs/architecture.md` and the focused behavior guides as the source of truth for implemented behavior.
3. Keep `docs/roadmap.md` as the only source for unimplemented features and non-goals. Move an item there until it is real and tested.
4. Update the relevant public documentation whenever a CLI, API, data model, indexer, query, MCP or Web Explorer behavior changes.
5. Preserve origin, confidence, revision and evidence. Do not describe inferred or dynamic framework behavior as certain.
6. CLI, MCP and Web Explorer must call the shared query and analysis engine; do not duplicate business logic in a transport layer.
7. Add or update a focused test for behavior changes. Run `./gradlew test`; run `./gradlew build` for release-facing changes.
8. Treat `.arianna/ignore` as repository indexing configuration. Keep exclusions explicit and document changes that affect search or graph coverage.
9. Keep documentation in English and verify links and executable command examples after restructuring.

## Typical workflow

```bash
./gradlew test
./gradlew build
./gradlew installDist
build/install/learn/bin/learn --help
```

Do not commit `.arianna/`, build output, temporary fixtures or IDE metadata. See [docs/contributing.md](docs/contributing.md) for contribution guidance and [docs/development-notes.md](docs/development-notes.md) for the technical tracker.
