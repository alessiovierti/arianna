# Validation

Arianna uses deterministic fixtures and repeatable tasks to evaluate technical quality and usefulness.

## What is measured

- symbol, reference and implementation precision and recall;
- missing or false-positive relationships;
- indexing and impact-analysis latency;
- evidence completeness and confidence classification;
- direct repository exploration versus Arianna-assisted exploration.

The benchmark matrix and expected answers are in [benchmarks](benchmarks/README.md). Results are evidence about the fixtures and sessions used; they are not a general productivity claim.

## Current status

Automated indexing, query, change-analysis, MCP, Web Explorer and packaging tests are maintained in `src/test/kotlin`. The technical MVP is implemented. Human/IDE comparison sessions and some browser usability checks remain open and are recorded separately rather than presented as completed validation.
