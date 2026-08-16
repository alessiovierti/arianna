# B06 qualitative observation — signature change

This observation covers a real baseline Git commit to working-tree overlay flow. It is an agent observation, not a human developer evaluation.

## Setup

The temporary Git fixture changes `PaymentService.process(String)` to `process(String, String)` and updates the caller. The machine-readable record is [validation-b06-agent.json](validation-b06-agent.json).

## Results

Direct exploration used `git diff` and `rg`. Arianna used:

```bash
learn index <repository> --json
learn index --working-tree <repository> --json
learn impact --working-tree --path <repository> --json
```

The report found both changed methods, a `direct_callers` finding for `calls`, source line evidence, distinct baseline/working-tree revisions and medium confidence for the JVM fallback edge. Structural `defines` relations were correctly excluded from impact noise.

Both workflows explored two files. This is evidence of structured, verifiable output, not evidence of reduced time; first indexing also adds cost.
