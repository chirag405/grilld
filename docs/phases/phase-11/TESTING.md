# Phase 11 verification gate

## Automated checks

- [x] AI prompt and vagueness tests: `cd grilld-ai-service && uv run pytest tests/unit_tests/test_interrogator_prompt.py tests/unit_tests/test_vagueness.py` (9 passed).
- [x] Full backend suite: `cd grilld-backend && mvn test` (68 passed).
- [x] Changed frontend files pass ESLint.
- [x] Frontend production build: `cd grilld-frontend && npm run build`.
- [ ] Repo-wide frontend lint is clean. It currently reports two pre-existing `react-hooks/set-state-in-effect` errors in generated `src/components/ui/dropdown-menu.tsx`; Phase 11 files are clean.

## Manual end-to-end checklist

- [ ] Start an interview with a rough one-sentence idea and confirm questions are useful and non-repetitive.
- [ ] Choose one chip and confirm another chip cannot remain selected.
- [ ] Submit an answer and confirm every option locks and the action reads `Answer sent` while Grilld responds.
- [ ] Say “decide for me and finish it” and confirm the review appears without another question.
- [ ] Use `I have shared enough — finish the brief` and confirm the same transition.
- [ ] Edit a filled value in the right panel, refresh, and confirm the edit persists.
- [ ] On a narrow viewport, confirm the page cannot scroll below the input composer.
- [ ] Start generation and confirm the report advances from `0/10` as agents finish, including after temporarily interrupting the SSE connection.

The automated gate passes. The browser checklist is the release smoke test against deployed services.
