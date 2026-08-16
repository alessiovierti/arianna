# Web Explorer

The Web Explorer is Arianna's local browser workspace for exploring and refreshing repository knowledge.

## Start it

```bash
learn serve .
```

It binds to `127.0.0.1:8080` by default. Use `--host` and `--port` to change the binding. The server can start before an index exists and offers baseline or working-tree indexing jobs with progress, status, cancellation and actionable errors.

The server never edits source files and provides no authentication or shared multi-user mode.

## Available views

- repository overview and index-quality signals;
- global knowledge search;
- entity details with evidence and relationships;
- bounded, depth-limited dependency neighborhoods;
- confidence/origin styling and clickable source evidence;
- documents in a repository-relative hierarchy with rendered Markdown;
- working-tree impact, refactoring and verification reports;
- light, dark and system themes, keyboard focus and reduced-motion support;
- portable read-only snapshots through `learn export` and `learn snapshot`.

Navigation is encoded in stable hash URLs, so browser history, bookmarks and copied links preserve the selected search, entity, source or change context. The UI uses local bundled assets and does not require network access for diagrams or Markdown rendering.

## HTTP contract

The JSON API currently exposes:

```text
GET /api/health
POST /api/index?mode=baseline|working-tree
GET /api/index/jobs/{jobId}
POST /api/index/jobs/{jobId}/cancel
GET /api/documents/index
GET /api/repository
GET /api/overview
GET /api/search?q=<term>&kind=<kind>&offset=<n>&limit=<n>
GET /api/entities?id=<entity-id>
GET /api/entities/relationships?entityId=<entity-id>
GET /api/entities/neighborhood?entityId=<entity-id>&depth=<n>&limit=<n>
GET /api/documents?path=<document-path>
GET /api/source?path=<source-path>&startLine=<n>&endLine=<n>
GET /api/impact?mode=working-tree
GET /api/impact?baseRevision=<base>&headRevision=<head>
GET /api/refactoring-plan?...same change context...
GET /api/verification?...same change context...
```

Responses retain entity/relation identity, evidence, revision, analyzer version, origin, confidence, severity and certainty. The web adapter delegates to the shared query and analysis engine.

## Offline snapshots

```bash
learn export . --output arianna-snapshot.zip
learn snapshot arianna-snapshot.zip
```

The export contains a read-only Explorer and the published `knowledge.db`. It can be opened from `index.html` without Arianna or an HTTP server. Search, relationships, neighborhoods and indexed documents remain available; source previews, indexing and change analysis require the original repository and are unavailable in snapshot mode.

## Limitations

The Web Explorer is intentionally local and dependency-light. It does not provide authentication, multi-repository views, live filesystem watching, code editing, semantic search or shared hosting. Compilation and repository tests remain the final verification authority.
