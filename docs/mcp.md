# MCP server

Arianna exposes the shared local query and change-analysis engine through JSON-RPC over standard input/output.

```bash
learn mcp --path .
```

The server supports `initialize`, `ping`, `tools/list` and `tools/call`. It is local and read-only with respect to repository source files.

## Tools

| Tool | Purpose |
|---|---|
| `search_knowledge` | Search entities, files and documents. |
| `find_symbol` | Find JVM symbols with evidence. |
| `find_references` | Find references to a symbol. |
| `find_implementations` | Find implementations of a symbol. |
| `find_relationships` | Page direct relations for an entity. |
| `get_evidence` | Retrieve indexed entities and their evidence. |
| `analyze_change` | Report impact for a working tree or revision pair. |
| `plan_refactor` | Produce an ordered read-only change plan. |
| `verify_change` | Report residual graph risks after a change. |

Query tools support bounded pages with `limit`, `offset` or a cursor. Filters include repository, file, kind, revision and confidence where applicable. Change tools accept either a current baseline plus working-tree overlay or `baseRevision` and `headRevision`.

Responses are structured JSON. Errors identify invalid arguments, missing indexes, stale overlays, revision materialization failures and timeouts. Narrative explanation remains the responsibility of the consuming client or agent.
