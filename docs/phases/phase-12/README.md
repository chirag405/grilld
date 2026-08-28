# Phase 12: voice input plumbing, rendered diagrams, and run history

Three independent gaps identified by a full-project audit, built together as one feature branch (`feature/voice-diagrams-run-history`): a voice-input mode that was a label with nothing behind it, Mermaid diagrams that only ever existed as raw text, and a session-history feature (Phase 11) that couldn't show a session's finished blueprint once you reopened it.

## 1. Voice input plumbing

Recording, upload, and the backend seam are fully built; no speech-to-text provider is chosen yet (the user will pick one - ElevenLabs Scribe, Fish Speech, or similar - later).

- `AnswerForm`'s `voice_primary` mode now renders a mic button (`VoiceRecorder.tsx`) next to the textarea. It records with `MediaRecorder`, uploads the clip to the backend, and drops the transcribed text into the textarea for the user to review/edit - it never auto-submits.
- `POST /api/v1/voice/transcribe` (`TranscriptionController`) accepts the multipart upload and calls `TranscriptionService` - one interface, swappable implementation, the same seam pattern as `AiServiceClient` and `PackageStorage`.
- `UnconfiguredTranscriptionService` is the only implementation today (active whenever `grilld.voice.provider=none`, the default). It throws `TranscriptionUnavailableException` (503) with an honest message - matches this project's "never fake a result" rule. Wiring a real provider later is: implement `TranscriptionService`, flip `grilld.voice.provider`, done - nothing above this layer changes.
- The upload endpoint sits in the same rate-limit tier as answering a question (`RateLimitConfig`), since it's the same per-turn action.

**A necessary fix along the way:** the frontend's `/api/proxy` route forwarded every request body through `request.text()`, which silently corrupts binary data. A multipart audio upload would have been mangled by the proxy before ever reaching Spring. Switched to `request.arrayBuffer()`, which round-trips both JSON and binary bodies correctly.

## 2. Rendered Mermaid diagrams

The Diagram Agent has only ever produced raw `.mmd` source (a known Phase 5 limitation); nothing in the app rendered it as an actual diagram.

- `MermaidDiagram.tsx` renders Mermaid source into an SVG client-side (`mermaid` npm package, `securityLevel: "strict"` so the SVG is safe to inject).
- Wired into `components/ui/markdown.tsx`'s code-block renderer: any fenced ` ```mermaid ` block, anywhere `<Markdown>` is used (chat messages, the Run Report preview, the new document preview below), now renders as a diagram instead of a code block.
- This is the client-side live-preview half only, by design (see the "Deliberate boundaries" section below) - a rendered SVG/PNG baked into the downloaded package is a separate, heavier server-side step (needs a headless-Chromium render step in a Docker image) that wasn't taken here.

## 3. Run history actually restores a finished blueprint

Phase 11 added session history/resume, but it only rehydrated the interview transcript - reopening a session whose blueprint had already generated showed an empty `GenerationPanel` with no way to see or re-download it, because no endpoint existed to ask "does this session already have a run?"

- New endpoint `GET /api/v1/sessions/{sessionId}/runs` (`GenerationController.listRuns` / `GenerationService.listRuns`) - every past generation attempt for a session, most recent first, same ownership check as `generate()`.
- `GenerationPanel` now fetches this (plus the session's persisted scale tier) on mount and restores `tier`/`run` state before the user does anything - the existing report-polling/SSE effect then picks up naturally, whether the restored run is `IN_PROGRESS` (resumes watching it), `COMPLETED` (shows the report + download button), or `FAILED` (shows the failure and a "Try again" button - previously a failed run had no visible way to retry once its refund had already landed).
- Along the way, added a small but real feature this made obvious was missing: a way to actually read a generated document's content before downloading the whole zip. `GET /api/v1/sessions/{sessionId}/runs/{runId}/documents?path=...` (`GeneratedDocumentController`) returns one document's persisted content; clicking a completed document's name in the Run Report's step list now opens it in a preview dialog, rendered through the same `Markdown`/`MermaidDiagram` pipeline as everything else.

## Key files

- `grilld-backend/.../voice/{TranscriptionService,UnconfiguredTranscriptionService,TranscriptionController}.java`
- `grilld-backend/.../common/exception/TranscriptionUnavailableException.java`
- `grilld-backend/.../generation/{GenerationService,GenerationController}.java` - `listRuns`/`GenerationRunSummary`
- `grilld-backend/.../generation/GeneratedDocumentController.java`
- `grilld-frontend/src/components/VoiceRecorder.tsx`
- `grilld-frontend/src/components/ui/mermaid-diagram.tsx`
- `grilld-frontend/src/components/GenerationPanel.tsx` - restore-on-mount effect, retry button, document preview dialog
- `grilld-frontend/src/app/api/proxy/[...path]/route.ts` - binary-safe body forwarding

## Deliberate boundaries

- No speech-to-text provider is configured. `grilld.voice.provider` stays `none` until the user picks one; see SETUP.md for what wiring a real provider will need.
- Diagram rendering is client-side preview only, per explicit scope decision - no server-side render step, no image files added to the downloaded package.
- The document-preview dialog is a single-file viewer, not a full doc browser (file tree, multi-pane navigation) - deliberately the smallest thing that makes the new Mermaid renderer actually reachable in the running app, not a scope expansion beyond what was asked.
- This session's sandbox couldn't run the Testcontainers-backed integration suite (`GenerationServiceTest`, `RunReportControllerTest`, `RateLimitMvcIntegrationTest`) - no Docker daemon was reachable. See TESTING.md for exactly what was and wasn't verified.
