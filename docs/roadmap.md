# Roadmap and non-goals

This is the only public document for work that is not implemented. These items are directions, not commitments or available features.

## Planned extensions

- multi-repository graphs and cross-repository dependency closure;
- shared self-hosted deployment, PostgreSQL, background jobs and concurrent users;
- GitHub/GitLab synchronization, webhooks, CI and pull-request integrations;
- authentication, authorization, tenant isolation, auditing and secret redaction;
- OpenAPI/AsyncAPI, messaging contracts and richer system entities;
- TypeScript, Go, Rust and Python adapters;
- richer compiler/type-system and generated-code awareness;
- runtime evidence and reconciliation with static analysis;
- semantic, hybrid and embedding-based search;
- optional local or external LLM enrichment;
- richer shared architecture and change-impact visualizations;
- automatic source edits or autonomous refactoring.

## Design constraints

Future adapters should emit the canonical entity/relation model and preserve origin, confidence, evidence, revision and analyzer version. New transports should call the existing query engine. Server work must keep the local SQLite mode useful and must not make an LLM an internal dependency.

## Explicit non-goals for the current release

Arianna is not an IDE, autonomous coding agent, hosted multi-tenant service, Sourcegraph replacement or runtime observability system. It does not claim to prove every architecture decision or every dynamic relationship.
