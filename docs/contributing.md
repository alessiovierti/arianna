# Contributing

Contributions are welcome through focused changes that preserve Arianna's evidence and local-first behavior.

## Development environment

Use JDK 21 and the Gradle wrapper:

```bash
./gradlew test
./gradlew build
./gradlew installDist
```

Before submitting a behavior change, add or update a focused test under `src/test/kotlin/`. For CLI, MCP, Web Explorer or packaging changes, run the relevant smoke workflow in addition to the full test suite.

## Documentation rules

- Keep the root README short and user-oriented.
- Put all other documentation in `docs/`.
- Update the focused public guide when behavior changes.
- Put unimplemented ideas only in [roadmap.md](roadmap.md).
- Keep examples executable against the current `learn --help` output.
- Preserve evidence, origin, confidence and explicit limitations.

## Change hygiene

Do not commit `.arianna/`, build output, generated packages, temporary fixtures or IDE metadata. Keep CLI, MCP and Web Explorer behavior routed through the shared engine. A pull request should explain the user-visible behavior, tests run and documentation updated.
