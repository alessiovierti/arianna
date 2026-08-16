# B07 qualitative observation — direct agent versus Arianna

This is the first reproducible agent observation for the M11 gate, not a human developer evaluation. The machine-readable record is [validation-b07-agent.json](validation-b07-agent.json).

## Setup and direct workflow

The task is to identify statically unresolved wiring in a copy of `mvp-fixture`. Direct exploration ran:

```bash
rg -n -i 'dynamicBeanName|dynamic' <fixture>/src <fixture>/README.md
```

It found `DynamicWiring.kt` and `PaymentServiceTest.kt`, requiring manual filtering.

## Arianna workflow

```bash
learn index <fixture> --json
learn search-knowledge dynamicBeanName --path <fixture> --json
learn search-knowledge dynamic --path <fixture> --json
```

Arianna returned `method:unknown.dynamicBeanName` with source evidence at `src/main/kotlin/fixture/DynamicWiring.kt:3` and explicit uncertainty. Only one relevant evidence file was explored.

The observation supports reduced relevant exploration for this task, but more sessions and a developer/IDE comparison are required before closing the MVP gate.
