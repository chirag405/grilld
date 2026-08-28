# Phase 12 setup

No new required credentials. Follow the repository-root `SETUP.md` to run PostgreSQL, the AI service, Spring backend, and Next.js frontend as usual.

## Voice transcription provider (optional, not yet chosen)

`grilld.voice.provider` defaults to `none` - `UnconfiguredTranscriptionService` answers every `POST /api/v1/voice/transcribe` call with a clear 503 rather than pretending to transcribe anything. Recording, upload, and error handling on the frontend all work today; only the actual speech-to-text call is missing.

To wire a real provider once one is chosen:

1. Implement `com.grilld.backend.voice.TranscriptionService` against the provider's API (e.g. `ElevenLabsTranscriptionService`), annotated `@Component` + `@ConditionalOnProperty(prefix = "grilld.voice", name = "provider", havingValue = "<provider-name>")` - same pattern as `UnconfiguredTranscriptionService`.
2. Add whatever API key(s) the provider needs as new `@Value`-injected properties in `application.properties`, following the existing `${ENV_VAR:}` convention (see the Lemon Squeezy or AWS blocks for the pattern).
3. Set `VOICE_TRANSCRIPTION_PROVIDER` (backend env var) to the new provider name.

Nothing on the frontend (`VoiceRecorder.tsx`) needs to change - it already handles both the success and honest-failure responses.

## Multipart upload limit

`spring.servlet.multipart.max-file-size` / `max-request-size` are set to 10MB (a spoken answer is seconds long, not minutes). Raise these if a real provider needs longer clips.
