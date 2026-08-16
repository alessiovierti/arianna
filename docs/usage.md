# Usage

## Repository status and indexing

```bash
learn status .
learn index .
learn index --working-tree .
learn diff --working-tree .
```

The clean baseline represents `HEAD`. A working-tree overlay is separate and requires an existing baseline. Use `--json` for scripts and integrations.

## Search and evidence

```bash
learn search "PaymentService" .
learn search-knowledge "payment" .
learn get-document README.md .
learn find-symbol PaymentService.process .
learn references PaymentService.process .
learn implementations PaymentService .
learn relations class:PaymentService .
```

Results are paged and include repository, revision, file and line evidence where available. Search and relationship commands support filters such as `--revision`, `--confidence`, `--limit` and `--offset`.

## Change analysis

For uncommitted work:

```bash
learn impact --working-tree .
learn plan-refactor --working-tree .
learn verify-change --working-tree .
```

For two revisions:

```bash
learn impact --base <base> --head <head> --path .
learn plan-refactor --base <base> --head <head> --path .
learn verify-change --base <base> --head <head> --path .
```

Impact analysis reports represented callers, implementations, tests, framework wiring, endpoints, documents and unresolved edges. The refactoring plan is read-only. Verification detects residual graph risks; it does not compile or run tests.

## Snapshots

Export a published baseline:

```bash
learn export . --output arianna-snapshot.zip
learn snapshot arianna-snapshot.zip
```

The archive can also be opened offline through its `index.html`. Snapshot views are read-only and cannot access source files or perform change analysis.
