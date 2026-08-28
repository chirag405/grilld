package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Calls Fish Audio's hosted speech-to-text API - fetched live from
 * https://docs.fish.audio/api-reference/endpoint/openapi-v1/speech-to-text
 * (Research-First Rule: this is a BETA endpoint, so training-data familiarity
 * with "Fish Speech"/"Fish Audio" wouldn't be trustworthy here even if it
 * existed). Contract, exactly as documented: {@code POST https://api.fish.audio/v1/asr},
 * Bearer-authenticated, {@code multipart/form-data} with the clip as the
 * {@code audio} field (JSON/base64 is explicitly not supported), response is
 * JSON with a top-level {@code text} field (plus duration/segments/language,
 * which this single-answer use case doesn't need).
 *
 * Active whenever {@code grilld.voice.provider=fishaudio} - see
 * docs/phases/phase-12/SETUP.md for how to obtain the API key. Reads the
 * response as a raw {@code Map} rather than a typed record - the response
 * carries duration/segments/language too, none of which this single-answer
 * use case needs.
 *
 * Takes an injected {@code RestClient.Builder} (Spring Boot auto-configures
 * one) rather than calling the static {@code RestClient.create(url)}, purely
 * so {@link FishAudioTranscriptionServiceTest} can substitute a
 * {@code MockRestServiceServer}-bound builder and prove the real multipart
 * request/response wiring without a live network call.
 */
@Component
@ConditionalOnProperty(prefix = "grilld.voice", name = "provider", havingValue = "fishaudio")
public class FishAudioTranscriptionService implements TranscriptionService {

    private final RestClient restClient;
    private final String apiKey;

    public FishAudioTranscriptionService(RestClient.Builder restClientBuilder,
                                          @Value("${grilld.voice.fishaudio.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.baseUrl("https://api.fish.audio").build();
    }

    @Override
    public String transcribe(byte[] audioBytes, String contentType) {
        if (apiKey.isBlank()) {
            // Same "fail loudly only when actually used unconfigured" pattern as
            // LemonSqueezySignatureVerifier - booting the app shouldn't require this.
            throw new TranscriptionUnavailableException(
                    "Voice input is misconfigured on this deployment - no Fish Audio API key set.");
        }

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(parseOrOctetStream(contentType));
        HttpEntity<ByteArrayResource> audioPart =
                new HttpEntity<>(new NamedByteArrayResource(audioBytes, extensionFor(contentType)), partHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio", audioPart);
        // ignore_timestamps defaults to true server-side - exactly right for a single
        // spoken answer, where only the transcript text matters, not per-word timing.

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v1/asr")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Object text = response == null ? null : response.get("text");
            if (!(text instanceof String transcript) || transcript.isBlank()) {
                throw new TranscriptionUnavailableException("Fish Audio returned no transcript - try again.");
            }
            return transcript;
        } catch (RestClientException e) {
            throw new TranscriptionUnavailableException("Couldn't reach the transcription service - try again shortly.", e);
        }
    }

    private static MediaType parseOrOctetStream(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Best-effort only - the real format is carried by the part's Content-Type header
     * above; this just gives the multipart part a plausible filename, which some
     * server-side multipart parsers use as a secondary format hint. */
    private static String extensionFor(String contentType) {
        if (contentType == null) return "";
        if (contentType.contains("webm")) return ".webm";
        if (contentType.contains("ogg")) return ".ogg";
        if (contentType.contains("wav")) return ".wav";
        if (contentType.contains("mp4") || contentType.contains("m4a")) return ".m4a";
        if (contentType.contains("mpeg") || contentType.contains("mp3")) return ".mp3";
        return "";
    }

    /** Fish Audio's multipart parser needs a filename on the part; ByteArrayResource has none by default. */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String extension;

        NamedByteArrayResource(byte[] bytes, String extension) {
            super(bytes);
            this.extension = extension;
        }

        @Override
        public String getFilename() {
            return "answer" + extension;
        }
    }
}
