# Configuration and repository state

## `.arianna/`

The default local state directory contains:

- `knowledge.db` — the versioned SQLite index;
- `ignore` — repository-specific indexing exclusions.

`.arianna/` is ignored by the project Git configuration and should normally remain uncommitted. It can be deleted and recreated by indexing again.

## Ignore rules

On the first indexing operation Arianna copies the repository-root `.gitignore` to `.arianna/ignore` when that file does not exist. Subsequent scans use `.arianna/ignore` with standard Git-ignore syntax: comments, directory rules, basename patterns and negation.

The ignore file is a snapshot of indexing configuration. Edit it directly when generated files or large directories should be excluded. Excluded files are absent from the knowledge graph.

Legacy `arianna/.ignore`, `.arianna/ignore.yml` and `.arianna.yml` are not read.

## Git snapshots

`learn index .` publishes the clean baseline at `HEAD`. A dirty tree cannot overwrite that baseline. Use `learn index --working-tree .` to create an overlay. Change analysis rejects a stale overlay so reports cannot silently describe a different working tree.

## Network and privacy

The normal CLI, MCP and Web Explorer operate locally. Arianna does not send source content to a hosted service and does not require an account or an LLM. Optional SCIP executables are invoked locally. A user may choose to expose the Web Explorer on another host with `--host`, but authentication and shared multi-user access are not included.
