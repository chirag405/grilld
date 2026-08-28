# Phase 12 verification gate

## Automated checks

- [x] Full backend suite: `cd grilld-backend && mvn test` (73 passed - 68 pre-existing + 5 new: `GenerationServiceTest`'s two `listRuns` tests, `GeneratedDocumentControllerTest`, `TranscriptionControllerTest`'s two tests). Requires a running Docker daemon (Testcontainers boots real Postgres per test class) - this session's sandbox had Docker Desktop's daemon stopped at first; started it mid-session and re-ran the full suite clean.
- [x] Backend compiles clean (`mvn compile`) and `mvn test-compile` catches nothing.
- [x] Changed frontend files pass ESLint individually (`VoiceRecorder.tsx`, `AnswerForm.tsx`, `GenerationPanel.tsx`, `mermaid-diagram.tsx`, `markdown.tsx`, the proxy route, `types.ts`).
- [x] Frontend TypeScript: `npx tsc --noEmit` clean.
- [x] Frontend production build: `cd grilld-frontend && npm run build` - succeeds.
- [ ] Repo-wide frontend lint is clean. It currently reports 3 pre-existing `react-hooks/set-state-in-effect` errors in generated `dialog.tsx`/`dropdown-menu.tsx` (same class of issue phase-11 already flagged in `dropdown-menu.tsx`; `dialog.tsx` picked up a third since) - none in any file this phase touched.

## Manual end-to-end checklist

- [ ] On a question with `inputMode: "voice_primary"`, click the mic button, allow microphone access, speak, click stop - confirm it uploads and shows the honest "voice input isn't turned on yet" message (expected until a provider is configured), and that typing still works normally.
- [ ] Deny microphone permission and confirm a clear, non-crashing error message instead.
- [ ] View a Run Report (or the new document preview) whose markdown contains a ` ```mermaid ` fenced block and confirm it renders as an actual diagram, not a code block.
- [ ] Feed Mermaid source that doesn't parse and confirm it falls back to showing the raw text rather than a blank gap.
- [ ] Generate a blueprint, note the session, navigate away (or reopen from the session-history list) and back - confirm the Run Report and "Download blueprint" button reappear without re-generating.
- [ ] Click a completed document's name in the Run Report's step list and confirm its real content opens in a preview dialog.
- [ ] Force a generation run to fail (e.g. temporarily point `grilld.ai-service.base-url` at nothing) and confirm reopening that session shows the failure and a working "Try again" button that successfully starts a fresh run.
- [ ] Confirm a large/garbled multipart upload to `/api/v1/voice/transcribe` doesn't corrupt other proxy traffic (regression check for the `request.arrayBuffer()` fix).

The automated gate passes. The browser checklist needs a running frontend + backend + real microphone access, which this session's sandbox can't drive end-to-end - flagged here as the release smoke test, same as every prior phase's manual checklist.
