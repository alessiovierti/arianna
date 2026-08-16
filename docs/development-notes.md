# Development notes

This is the public technical tracker. It records implementation status and decisions; it is not a user guide or a second roadmap.

## Current status

The local technical MVP is implemented: indexing, SQLite snapshots, queries, change impact, refactoring planning, verification, MCP, Web Explorer and portable snapshots are available and covered by automated tests. Human/IDE validation and selected browser workflows remain in progress.

## Open validation work

- complete repeated human/IDE sessions for the benchmark tasks;
- complete browser validation of entity, source, impact and offline snapshot workflows;
- decide whether the available evidence supports a productivity claim.

## Maintenance procedure

1. Read the relevant focused guide and source/test package.
2. Make a small change with focused regression coverage.
3. Run `./gradlew test`; run `./gradlew build` and `./gradlew installDist` for release-facing changes.
4. Update the focused public documentation and this status when behavior changes.
5. Put future capabilities only in [roadmap.md](roadmap.md).
