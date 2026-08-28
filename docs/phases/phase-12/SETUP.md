# Phase 12 setup

No new required credentials. Follow the repository-root `SETUP.md` to run PostgreSQL, the AI service, Spring backend, and Next.js frontend as usual.

## Voice transcription provider: Fish Audio

`grilld.voice.provider` defaults to `none` (`UnconfiguredTranscriptionService`, an honest 503) unless set to `fishaudio`, which activates `FishAudioTranscriptionService` - calls Fish Audio's hosted ASR (`POST https://api.fish.audio/v1/asr`, per its live-fetched OpenAPI reference at `docs.fish.audio/api-reference/endpoint/openapi-v1/speech-to-text`).

**To turn it on:**

1. Create a Fish Audio account and generate an API key at **https://fish.audio/app/api-keys/**.
2. Set two backend env vars:
   - `VOICE_TRANSCRIPTION_PROVIDER=fishaudio`
   - `FISHAUDIO_API_KEY=<the key from step 1>`
3. Nothing else changes - restart the backend and `POST /api/v1/voice/transcribe` starts returning real transcripts. Nothing on the frontend (`VoiceRecorder.tsx`) needs to change either; it already handles both the success and honest-failure responses.

Pricing at the time this was wired: pay-as-you-go, ~$0.36/audio-hour (check Fish Audio's own pricing page for current rates - this is exactly the kind of number that goes stale). A blank `FISHAUDIO_API_KEY` with `grilld.voice.provider=fishaudio` still fails honestly (a clear "misconfigured" 503) rather than crashing the app at boot.

**To switch to a different provider later:** implement `com.grilld.backend.voice.TranscriptionService` against the new provider's API, `@Component` + `@ConditionalOnProperty(prefix = "grilld.voice", name = "provider", havingValue = "<provider-name>")` - same pattern `FishAudioTranscriptionService`/`UnconfiguredTranscriptionService` both already follow - then flip `VOICE_TRANSCRIPTION_PROVIDER`.

## Multipart upload limit

`spring.servlet.multipart.max-file-size` / `max-request-size` are set to 10MB (a spoken answer is seconds long, not minutes). Raise these if a real provider needs longer clips.
