# Architecture

Arianna is a Kotlin/JVM 21 application with a local SQLite store. The installed command is `learn`. The system is deliberately local and single-repository: it does not contain an internal LLM, upload repository content or modify source files.

## Indexing pipeline

1. The repository source detects the local directory and Git metadata.
2. File indexing creates repository, file and supported document entities.
3. JVM analysis uses SCIP when available and otherwise a conservative Java/Kotlin structural fallback.
4. Framework adapters identify common Spring wiring, configuration, endpoints and explicit Ktor routes.
5. Module and Docker Compose adapters add declared architecture evidence.
6. Document linking adds low-confidence relationships without presenting text matches as structural facts.
7. Relations are normalized and the complete snapshot is published transactionally.

Dynamic reflection, generated code, custom scanning and runtime-built routes remain uncertain unless an adapter can identify them explicitly.

## Knowledge model

The canonical model contains entities, typed relations and evidence. Evidence records repository, revision, file, optional line range, analyzer origin and analyzer version. Confidence distinguishes confirmed, inferred, low-confidence and unresolved knowledge.

SQLite stores versioned baseline and working-tree snapshots. A failed index does not replace the last valid snapshot. The database is `.arianna/knowledge.db` by default.

## Shared analysis engine

`KnowledgeQueryEngine` owns search, symbol, reference, implementation, relationship and document queries. Snapshot comparison, impact analysis, refactoring planning and change verification are shared by the CLI, MCP and Web Explorer. Transports do not duplicate business logic.

## Evidence contract

Arianna reports what its analyzers can support, not what the runtime might eventually do. Every important relationship should retain its origin, confidence, revision and source evidence. Reports complement the compiler, tests and runtime observation; they do not replace them.

See [limitations](limitations.md) for unresolved cases and [configuration](configuration.md) for repository traversal.
