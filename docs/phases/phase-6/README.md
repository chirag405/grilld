# Phase 6 — Durable, observable generation

Phase 6 turns generation from one opaque blocking call into a background run that can be observed, resumed, cost-limited, revised, and downloaded.

## Runtime flow

```text
POST /sessions/{id}/generate
  -> create generation_runs row and return its id
  -> virtual thread streams LangGraph updates
  -> STARTED/COMPLETED events update agent_executions
  -> deterministic RunReportService rewrites run_report_md
  -> RunReportBroadcaster publishes the current report over SSE
  -> token totals feed CostCircuitBreakerService
  -> final files are persisted as generated_documents
  -> PackagerService creates package + manifest rows and a ZIP
  -> client polls /package and downloads /package/download
```

`GenerationResumeSweep` periodically finds old `IN_PROGRESS` rows and re-dispatches them. This is intentionally Spring-side only while local `langgraph dev` keeps its run-status bookkeeping in memory; durable Python-status reconciliation waits for production-shaped LangGraph hosting.

## Main additions

| Area | Key files | Responsibility |
|---|---|---|
| Async execution | `AsyncConfig`, `GenerationService` | Return a run id immediately and execute the long AI call on a virtual thread. |
| Stream parsing | `HttpAiServiceClient`, `GenerationProgressEvent` | Parse real LangGraph SSE, correlate delegated agents, files, narration, and token usage. |
| Run Report | `RunReportService`, `RunReportController`, `RunReportBroadcaster` | Assemble free deterministic markdown and expose poll + live SSE APIs. |
| Durability | `GenerationResumeSweep`, `GenerationRun.updatedAt` | Re-trigger stale in-progress runs after a lost Java worker. |
| Cost guard | `CostCircuitBreakerService` | Reject new runs when the rolling real-token budget is exceeded. |
| Revisions | `RevisionClassifier`, slot parent/unlock wiring | Classify corrections by seed impact and transitive filled-slot blast radius. |
| Packaging | `GeneratedDocument`, `PackagerService`, `PackageStorage`, `PackageController` | Persist generated contents, assemble manifests/ZIPs, and serve downloads. |

Database migrations `V4`–`V6` add failure reasons, run activity timestamps, and durable generated document contents. Package metadata continues to use the `packages` and `package_documents` tables created in the initial schema.

## Deliberate boundaries

- Progress is pulled from one LangGraph SSE response, not posted back as independent Python webhooks.
- Resume reconciliation cannot trust `langgraph dev` after a restart, so it currently uses Spring timestamps only.
- Package storage is local filesystem behind `PackageStorage`; S3/R2 is not configured yet.
- Mermaid `.mmd` sources are packaged as-is; server-rendered SVG/PNG awaits the hosting-side Mermaid CLI decision.
- The Revision Classifier labels and persists major/minor impact, but confirmation UI and selective regeneration are later revision-flow work.

