package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Proves the real request/response wiring against Fish Audio's documented
 * contract (POST https://api.fish.audio/v1/asr, Bearer auth, multipart with
 * an "audio" field, {"text": "..."} response) without a live network call -
 * MockRestServiceServer intercepts the RestClient.Builder this service is
 * constructed with, so this asserts on the actual HTTP request produced,
 * not a hand-waved assumption about it.
 */
class FishAudioTranscriptionServiceTest {

    @Test
    void sendsABearerAuthenticatedMultipartRequestAndReturnsTheTranscript() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.fish.audio/v1/asr"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(request -> {
                    String contentType = request.getHeaders().getContentType().toString();
                    assertTrue(contentType.startsWith("multipart/form-data"),
                            "expected a multipart request, got Content-Type: " + contentType);
                })
                .andRespond(withSuccess("{\"text\": \"a tool for freelancers\", \"duration\": 2.5}", MediaType.APPLICATION_JSON));

        FishAudioTranscriptionService service = new FishAudioTranscriptionService(builder, "test-key");

        String transcript = service.transcribe(new byte[]{1, 2, 3}, "audio/webm");

        assertEquals("a tool for freelancers", transcript);
        server.verify();
    }

    @Test
    void blankApiKeyFailsHonestlyWithoutEverCallingFishAudio() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // No .expect(...) registered - if the service called out anyway, verify() below would catch it.

        FishAudioTranscriptionService service = new FishAudioTranscriptionService(builder, "");

        assertThrows(TranscriptionUnavailableException.class, () -> service.transcribe(new byte[]{1}, "audio/webm"));
        server.verify();
    }

    @Test
    void anUnauthorizedResponseFromFishAudioFailsHonestlyRatherThanCrashing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.fish.audio/v1/asr")).andRespond(withUnauthorizedRequest());

        FishAudioTranscriptionService service = new FishAudioTranscriptionService(builder, "bad-key");

        assertThrows(TranscriptionUnavailableException.class, () -> service.transcribe(new byte[]{1}, "audio/webm"));
    }

    @Test
    void aServerErrorFromFishAudioFailsHonestlyRatherThanCrashing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.fish.audio/v1/asr")).andRespond(withServerError());

        FishAudioTranscriptionService service = new FishAudioTranscriptionService(builder, "test-key");

        assertThrows(TranscriptionUnavailableException.class, () -> service.transcribe(new byte[]{1}, "audio/webm"));
    }
}
