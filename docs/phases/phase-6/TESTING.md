# Phase 6 — Testing gate

## Automated checks

- [x] From `grilld-backend/`, run `./mvnw test` with JDK 26 and Docker available.
- [x] Result verified on 2026-08-16: **33 tests, 0 failures, 0 errors, 0 skipped**.
- [x] From `grilld-ai-service/`, run `uv run pytest tests/unit_tests` with `TAVILY_API_KEY` present; result verified on 2026-08-16: **20 passed**.
- [x] `HttpAiServiceClientTest` parses the captured real LangGraph SSE fixture without a live model call.
- [x] Generation tests cover immediate async response, progress persistence, partial failure, stale-run resume, real token accounting, and cost rejection.
- [x] Revision tests cover parent/unlock persistence and MINOR/MAJOR blast-radius classification.
- [x] `PackagerServiceTest` opens the real generated ZIP and compares entries with persisted documents.
- [x] `PackageControllerTest` proves authenticated status and download endpoints after a full mocked generation run.

## Manual checklist

- [ ] Start Postgres, the Python AI service, and Spring using `SETUP.md`.
- [ ] Complete and scale-calibrate a discovery session.
- [ ] Call `POST /api/v1/sessions/{sessionId}/generate`; confirm it returns an `IN_PROGRESS` run promptly.
- [ ] Connect to `GET /api/v1/sessions/{sessionId}/runs/{runId}/events`; confirm the Run Report changes as specialists finish.
- [ ] Poll `GET .../runs/{runId}/package` until `READY`.
- [ ] Download `GET .../runs/{runId}/package/download`; open the ZIP and confirm generated Markdown/Mermaid files are readable.
- [ ] Restart recovery against durable LangGraph hosting remains deferred; `langgraph dev` cannot prove durable remote run-status reconciliation.

## Gate result

The automated Phase 6 gate passes. The real-model/manual checklist remains a user-operated integration check because it consumes external model/search credits and requires local credentials.
