# MVP validation session template

Record one row for each execution of the same task and keep repository state and fixture stable.

| Field | Value |
|---|---|
| Date / operator | |
| Repository / commit | |
| Task ID (B01–B07) | |
| Condition (`DEVELOPER_DIRECT`, `AGENT_DIRECT`, `AGENT_ARIANNA`, `AGENT_ARIANNA_WITH_TESTS`) | |
| Duration (ms) | |
| Files explored | |
| Queries/commands | |
| Modification-test-repair cycles | |
| Report used before compilation? | |
| Evidence opened and verified? | |
| Unresolved cases declared? | |
| Task completed? | |
| Qualitative notes | |

Do not close the gate without a direct baseline for the same task, evidence verification and at least one measurable improvement. Machine-readable observations use fields `taskId`, `condition`, `durationMillis`, `exploredFiles`, `queryCount`, `correctionCycles`, `reportUsedBeforeCompile`, `evidenceVerified`, `unresolvedDeclared` and `completed`; evaluate them with:

```bash
learn validate --observations observations.json --json
```
