# Limitations

Arianna is a static, evidence-first repository index. Its output is useful context, not a proof of runtime behavior.

## Current boundaries

- Java and Kotlin are the primary supported languages.
- The default deployment is one local repository with SQLite.
- SCIP improves symbol precision but is optional; the structural fallback is less complete.
- Reflection, generated code, dynamic dispatch, custom scanning, conditional runtime wiring and runtime-built routes may be missing or unresolved.
- Docker Compose describes declared topology; it does not prove that services are running or communicating.
- `verify-change` does not compile code, run tests or inspect runtime traffic.
- The Web Explorer has no authentication or shared multi-user mode.
- Search is deterministic token search; it is not semantic or embedding-based retrieval.

## Confidence and uncertainty

Static, framework, inferred and unresolved relationships remain distinguishable through origin and confidence. Dynamic behavior is not promoted to a confirmed edge merely because it is plausible. Consumers should inspect source evidence and run the repository's own compiler and tests.

## Release status

The technical MVP is implemented and tested. Human/IDE productivity validation and some browser validation remain tracked in [validation](validation.md) and [development notes](development-notes.md).
