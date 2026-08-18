# Phase 11: Interview experience repair

This phase turns the discovery screen from an open-ended interrogation into a bounded product-copilot flow and makes its visible state match the backend.

## Flow

1. The Python Interrogator asks only questions that materially change the blueprint, recommends defaults when the user is unsure, and concludes when the user delegates or asks it to stop.
2. Spring persists extracted facts and now also applies waived-slot state. Explicit finish language bypasses another model call and marks the session ready.
3. The interview UI provides an always-visible finish action, locks submitted controls, treats chip questions as single-choice, and keeps the composer inside the viewport.
4. Values in the right-hand live brief can be edited. The `PUT /api/v1/sessions/{sessionId}/slots/{slotKey}` endpoint updates both the slot row and `project_briefs.brief_json`.
5. Generation report payloads include completed and total agent counts. The UI consumes SSE and uses report polling as a disconnect fallback.

## Key files

- `grilld-ai-service/src/grilld_ai_service/interrogator/graph.py`: copilot behavior and conclusion rules.
- `grilld-backend/.../session/SessionService.java`: finish detection, waive persistence, and slot editing.
- `grilld-backend/.../generation/RunReportUpdate.java`: numeric progress contract.
- `grilld-frontend/src/app/interview/page.tsx`: viewport layout, finish action, and edit API wiring.
- `grilld-frontend/src/components/AnswerForm.tsx`: single-choice and submitted state.
- `grilld-frontend/src/components/GenerationPanel.tsx`: numeric progress plus polling fallback.

No schema migration was required.
