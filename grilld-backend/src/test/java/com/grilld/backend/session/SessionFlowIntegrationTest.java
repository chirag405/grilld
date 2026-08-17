package com.grilld.backend.session;

import com.grilld.backend.auth.TokenService;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Phase 2 gate end to end: create a session, exchange turns with
 * the (stub) AI service, and confirm everything lands in Postgres correctly -
 * without needing a real Google login, since it exercises UserService and
 * TokenService directly (the same beans OAuth2LoginSuccessHandler calls after
 * a real login - see docs/phases/phase-1/README.md).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SessionFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserService userService;

    @Autowired
    TokenService tokenService;

    @Autowired
    SlotRepository slotRepository;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Test
    void fullInterviewFlowPersistsCorrectly() throws Exception {
        User user = userService.findOrCreateFromGoogle("google-test-sub", "test@example.com");
        String token = tokenService.issueFor(user);

        // Turn 1: start a session, expect the restatement-style opening question (StubAiServiceClient)
        String startResponse = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"rawIdea\":\"a tool for freelancers to track unpaid invoices\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID sessionId = UUID.fromString(JsonPath.read(startResponse, "$.sessionId"));
        String openingQuestion = JsonPath.read(startResponse, "$.question");
        assertTrue(openingQuestion.contains("freelancers to track unpaid invoices"));
        assertEquals("text", (String) JsonPath.read(startResponse, "$.inputMode"),
                "the frontend's chip/free-text hybrid input needs inputMode on the wire");
        assertTrue(((String) JsonPath.read(startResponse, "$.whyAsking")).length() > 0,
                "the frontend's 'why am I being asked this' one-liner needs whyAsking on the wire");

        // Seed slots should exist before any answer is given
        List<Slot> seedSlots = slotRepository.findBySessionId(sessionId);
        assertEquals(8, seedSlots.size());
        assertTrue(seedSlots.stream().allMatch(s -> s.getOrigin() == Slot.Origin.SEED));

        // Turn 2: answer the opening question, expect a follow-up (fact extraction + new slot)
        String turn2Response = answer(sessionId, token, "Yes, that's exactly it.");
        String followUp = JsonPath.read(turn2Response, "$.question");
        assertTrue(followUp.contains("day one"));

        Slot problemStatement = slotRepository.findBySessionIdAndSlotKey(sessionId, "problem_statement").orElseThrow();
        assertEquals(Slot.Status.FILLED, problemStatement.getStatus());
        assertEquals("Yes, that's exactly it.", problemStatement.getValue());

        assertTrue(slotRepository.findBySessionIdAndSlotKey(sessionId, "monetization_intent").isPresent(),
                "StubAiServiceClient's follow-up turn should have spawned monetization_intent as a new (DERIVED) slot");

        assertTrue(briefRepository.findBySessionId(sessionId).orElseThrow().getBriefJson()
                .contains("problem_statement"), "extracted fact should have been merged into brief_json");

        // Turns 3 and 4: answer twice more - StubAiServiceClient concludes once 3 turns have been exchanged
        answer(sessionId, token, "About 50 people to start.");
        String finalResponse = answer(sessionId, token, "Whatever you think is best.");
        Boolean concluded = JsonPath.read(finalResponse, "$.concluded");
        assertTrue(concluded, "interview should conclude after the stub's configured turn count");
    }

    @Test
    void detailEndpointReturnsTheLiveBriefAndSlots() throws Exception {
        User user = userService.findOrCreateFromGoogle("session-detail-google-sub", "session-detail@example.com");
        String token = tokenService.issueFor(user);

        String startResponse = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"rawIdea\":\"a marketplace for local tutors\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID sessionId = UUID.fromString(JsonPath.read(startResponse, "$.sessionId"));
        answer(sessionId, token, "Yes, that's exactly it.");

        String detailResponse = mockMvc.perform(get("/api/v1/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("ACTIVE", (String) JsonPath.read(detailResponse, "$.status"));
        assertEquals("a marketplace for local tutors", (String) JsonPath.read(detailResponse, "$.rawIdea"));
        assertTrue(((String) JsonPath.read(detailResponse, "$.briefJson")).contains("problem_statement"),
                "the live brief panel needs the up-to-date brief_json, not just a status flag");
        List<Object> slots = JsonPath.read(detailResponse, "$.slots");
        assertEquals(9, slots.size(), "8 seed slots + monetization_intent spawned by the first answer");

        User intruder = userService.findOrCreateFromGoogle("session-detail-intruder-sub", "session-detail-intruder@example.com");
        String intruderToken = tokenService.issueFor(intruder);
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void answerEndpointRejectsAnotherUsersToken() throws Exception {
        User owner = userService.findOrCreateFromGoogle("session-owner-google-sub", "session-owner@example.com");
        String ownerToken = tokenService.issueFor(owner);
        String startResponse = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("{\"rawIdea\":\"a tool for freelancers to track unpaid invoices\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID sessionId = UUID.fromString(JsonPath.read(startResponse, "$.sessionId"));

        User intruder = userService.findOrCreateFromGoogle("session-intruder-google-sub", "session-intruder@example.com");
        String intruderToken = tokenService.issueFor(intruder);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/answer")
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType("application/json")
                        .content("{\"answerText\":\"not yours to answer\"}"))
                .andExpect(status().isForbidden());
    }

    private String answer(UUID sessionId, String token, String answerText) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/answer")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"answerText\":\"" + answerText + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
