package com.grilld.backend.aiservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grilld.backend.memory.WorkingContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Calls the real Interrogator - grilld-ai-service's "interrogator" LangGraph
 * (docs/decisions-and-technical-architecture.md §11.3), a plain StateGraph,
 * not the Orchestrator used for delegation testing in Phase 3. Activate with
 * SPRING_PROFILES_ACTIVE=python-ai-service; StubAiServiceClient
 * (@Profile("!python-ai-service")) stays the default everywhere else.
 *
 * The request/response shape here mirrors interrogation-engine.md §3's
 * contract exactly - WorkingContext's fields become the graph's input state,
 * and the graph's final "turn_result" field (built by generate_turn in
 * grilld_ai_service/interrogator/graph.py) is read directly as
 * InterrogatorTurnResult, not parsed out of a chat message the way Phase 3's
 * placeholder did.
 *
 * Grilld session id doubles as the LangGraph thread id (1:1, no separate
 * mapping table) - unchanged from Phase 3.
 */
@Component
@Profile("python-ai-service")
public class HttpAiServiceClient implements AiServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpAiServiceClient(@Value("${grilld.ai-service.base-url:http://localhost:2024}") String baseUrl,
                                ObjectMapper objectMapper) {
        this.restClient = RestClient.create(baseUrl);
        this.objectMapper = objectMapper;
    }

    @Override
    public InterrogatorTurnResult nextTurn(WorkingContext context) {
        ensureThreadExists(context.sessionId().toString());

        Map<String, Object> input = Map.of(
                "session_id", context.sessionId().toString(),
                "raw_idea", context.rawIdea(),
                "compacted_brief_summary", context.compactedBriefSummary() == null ? "" : context.compactedBriefSummary(),
                "recent_turns", context.recentTurns().stream().map(this::toPythonTurn).toList(),
                "open_slots_ranked", context.openSlotsRanked().stream().map(this::toPythonSlot).toList(),
                "answered_topics", context.answeredTopics(),
                "open_gaps", context.openGaps()
        );

        Map<String, Object> requestBody = Map.of("assistant_id", "interrogator", "input", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/threads/{id}/runs/wait", context.sessionId())
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseTurnResult(finalState);
    }

    private Map<String, Object> toPythonTurn(WorkingContext.RecentTurn turn) {
        return Map.of(
                "turn_number", turn.turnNumber(),
                "question_text", turn.questionText() == null ? "" : turn.questionText(),
                "answer_text", turn.answerText() == null ? "" : turn.answerText()
        );
    }

    private Map<String, Object> toPythonSlot(WorkingContext.RankedSlot slot) {
        return Map.of(
                "slot_key", slot.slotKey(),
                "description", slot.description(),
                "importance", slot.importance(),
                "priority", slot.priority()
        );
    }

    @Override
    public RubricResult evaluateRubric(RubricContext context) {
        Map<String, Object> input = Map.of(
                "session_id", context.sessionId().toString(),
                "brief_json", context.briefJson() == null ? "{}" : context.briefJson(),
                "slots", context.slots().stream().map(this::toPythonSlotSnapshot).toList()
        );

        Map<String, Object> requestBody = Map.of("assistant_id", "rubric", "input", input);

        // Stateless run (no thread) - the Rubric Agent has nothing to persist or
        // resume between calls, unlike the Interrogator's per-session thread.
        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/runs/wait")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseRubricResult(finalState);
    }

    private Map<String, Object> toPythonSlotSnapshot(RubricContext.SlotSnapshot slot) {
        return Map.of(
                "slot_key", slot.slotKey(),
                "status", slot.status(),
                "value", slot.value() == null ? "" : slot.value(),
                "importance", slot.importance()
        );
    }

    @SuppressWarnings("unchecked")
    private RubricResult parseRubricResult(Map<String, Object> finalState) {
        Map<String, Object> rubricResult = (Map<String, Object>) finalState.get("rubric_result");
        if (rubricResult == null) {
            throw new IllegalStateException("Rubric graph returned no rubric_result: " + finalState);
        }

        List<RubricResult.DimensionResult> dimensions = ((List<Map<String, Object>>) rubricResult.get("dimensions"))
                .stream()
                .map(d -> new RubricResult.DimensionResult(
                        (String) d.get("dimension"), (String) d.get("score"), (String) d.get("reasoning")))
                .toList();

        String verdict = (String) rubricResult.get("verdict");
        List<String> openGaps = (List<String>) rubricResult.get("open_gaps");

        return new RubricResult(dimensions, verdict, openGaps == null ? List.of() : openGaps);
    }

    @Override
    public ScaleCalibrationResult calibrateScale(String briefJson) {
        Map<String, Object> input = Map.of("brief_json", briefJson == null ? "{}" : briefJson);
        Map<String, Object> requestBody = Map.of("assistant_id", "scale_calibrator", "input", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = restClient.post()
                .uri("/runs/wait")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseScaleCalibrationResult(finalState);
    }

    @SuppressWarnings("unchecked")
    private ScaleCalibrationResult parseScaleCalibrationResult(Map<String, Object> finalState) {
        Map<String, Object> calibrationResult = (Map<String, Object>) finalState.get("calibration_result");
        if (calibrationResult == null) {
            throw new IllegalStateException("Scale Calibrator returned no calibration_result: " + finalState);
        }
        return new ScaleCalibrationResult(
                (String) calibrationResult.get("tier"),
                (String) calibrationResult.get("reasoning"),
                (List<String>) calibrationResult.get("signals"));
    }

    @Override
    public GenerationResult generateBlueprint(UUID runId, String briefJson, String scaleTier,
                                               List<String> unresolvedSlotDescriptions,
                                               Consumer<GenerationProgressEvent> onProgress) {
        ensureThreadExists(runId.toString());

        StringBuilder messageContent = new StringBuilder()
                .append("Here is the project brief (JSON):\n").append(briefJson == null ? "{}" : briefJson)
                .append("\n\nThe assigned scale tier is ").append(scaleTier).append(".");

        if (unresolvedSlotDescriptions != null && !unresolvedSlotDescriptions.isEmpty()) {
            messageContent.append("\n\nThe interview ended before these were resolved - write every one of them into "
                    + "/docs/ASSUMPTIONS.md prominently, alongside PROJECT_BRIEF.md, before delegating to any "
                    + "specialist:\n");
            unresolvedSlotDescriptions.forEach(desc -> messageContent.append("- ").append(desc).append("\n"));
        }
        messageContent.append("\nBegin.");

        Map<String, Object> input = Map.of(
                "messages", List.of(Map.of("role", "user", "content", messageContent.toString()))
        );
        // stream_mode=updates + stream_subgraphs=true: docs/decisions-and-technical-
        // architecture.md §11.3's run/stream API, verified live against a real run
        // this phase - the Orchestrator's own top-level "model"/"tools" updates carry
        // every task() delegation's start (tool_calls[].name=="task") and completion
        // (a "tools" message with the matching tool_call_id, whose content is that
        // specialist's own narration - §10.2), with the *namespaced* subgraph events
        // (event: updates|tools:<id>, one per specialist's own internal steps)
        // deliberately ignored - a specialist's own file writes surface in the
        // top-level "files" map the moment it finishes, which is all Spring needs.
        Map<String, Object> requestBody = Map.of(
                "assistant_id", "orchestrator",
                "input", input,
                "stream_mode", "updates",
                "stream_subgraphs", true
        );

        return restClient.post()
                .uri("/threads/{id}/runs/stream", runId)
                .body(requestBody)
                .exchange((request, response) -> consumeGenerationStream(response.getBody(), onProgress));
    }

    // Package-private (not private) so HttpAiServiceClientTest can feed it a
    // captured real SSE transcript directly - the parsing logic is the risky,
    // worth-testing part; the HTTP request-building around it is simple Map
    // construction.
    GenerationResult consumeGenerationStream(InputStream body, Consumer<GenerationProgressEvent> onProgress) {
        Map<String, String> pendingTaskCalls = new HashMap<>(); // tool_call_id -> subagent_type
        Map<String, String> files = new LinkedHashMap<>();
        // [inputTokens, outputTokens] for whichever specialist is currently delegated
        // to - reset the moment a new "task" tool call starts, read and attached to
        // its GenerationProgressEvent when the matching completion arrives. A single
        // shared accumulator is only correct because specialists run strictly
        // sequentially (product-and-architecture.md §3.1/§3.3, Phase 5's own build
        // order) - it would misattribute tokens across two genuinely concurrent
        // delegations, which this codebase doesn't currently produce.
        int[] pendingTokens = new int[2];

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventType = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventType != null && !data.isEmpty()) {
                        handleSseFrame(eventType, data.toString(), pendingTaskCalls, files, pendingTokens, onProgress);
                    }
                    eventType = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // heartbeat/comment line - not a real event, ignore
                } else if (line.startsWith("event:")) {
                    eventType = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading the generation run's stream", e);
        }

        return new GenerationResult(files);
    }

    @SuppressWarnings("unchecked")
    private void handleSseFrame(String eventType, String data, Map<String, String> pendingTaskCalls,
                                 Map<String, String> files, int[] pendingTokens, Consumer<GenerationProgressEvent> onProgress) {
        if ("error".equals(eventType)) {
            Map<String, Object> errorPayload = readJson(data);
            throw new IllegalStateException("Generation run failed: " + errorPayload.get("message"));
        }
        if (eventType.startsWith("updates|")) {
            // The namespaced per-specialist subgraph events (stream_subgraphs=true) -
            // ignored for delegation tracking (that's the top-level "updates" event's
            // job) but the only place a specialist's own real token usage is visible;
            // the top-level "tools" completion message carries the narration but no
            // usage_metadata (it's a ToolMessage, not an AIMessage - see LEARNING.md's
            // Phase 6 task 5 note).
            accumulateSubgraphTokens(data, pendingTokens);
            return;
        }
        if (!"updates".equals(eventType)) {
            return; // ignores "metadata"
        }

        Map<String, Object> update = readJson(data);

        Map<String, Object> modelUpdate = (Map<String, Object>) update.get("model");
        if (modelUpdate != null) {
            for (Map<String, Object> message : messagesOf(modelUpdate)) {
                for (Map<String, Object> toolCall : toolCallsOf(message)) {
                    if (!"task".equals(toolCall.get("name"))) {
                        continue; // the Orchestrator's own direct file writes, not a delegation
                    }
                    Map<String, Object> args = (Map<String, Object>) toolCall.get("args");
                    String subagentType = (String) args.get("subagent_type");
                    String toolCallId = (String) toolCall.get("id");
                    pendingTaskCalls.put(toolCallId, subagentType);
                    pendingTokens[0] = 0;
                    pendingTokens[1] = 0;
                    onProgress.accept(new GenerationProgressEvent(
                            subagentType, GenerationProgressEvent.Status.STARTED, null, List.of(), null, null));
                }
            }
        }

        Map<String, Object> toolsUpdate = (Map<String, Object>) update.get("tools");
        if (toolsUpdate != null) {
            List<String> newPaths = mergeFiles(files, (Map<String, Object>) toolsUpdate.get("files"));

            for (Map<String, Object> message : messagesOf(toolsUpdate)) {
                if (!"task".equals(message.get("name"))) {
                    continue;
                }
                String toolCallId = (String) message.get("tool_call_id");
                String subagentType = pendingTaskCalls.remove(toolCallId);
                if (subagentType == null) {
                    continue; // a task completion with no matching start - shouldn't happen, don't crash on it
                }
                String narration = (String) message.get("content");
                onProgress.accept(new GenerationProgressEvent(
                        subagentType, GenerationProgressEvent.Status.COMPLETED, narration, newPaths,
                        pendingTokens[0], pendingTokens[1]));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void accumulateSubgraphTokens(String data, int[] pendingTokens) {
        Map<String, Object> update = readJson(data);
        Map<String, Object> modelUpdate = (Map<String, Object>) update.get("model");
        if (modelUpdate == null) {
            return; // a non-model subgraph update (e.g. middleware bookkeeping) - nothing to sum
        }
        for (Map<String, Object> message : messagesOf(modelUpdate)) {
            Map<String, Object> usage = (Map<String, Object>) message.get("usage_metadata");
            if (usage == null) {
                continue;
            }
            Number inputTokens = (Number) usage.get("input_tokens");
            Number outputTokens = (Number) usage.get("output_tokens");
            if (inputTokens != null) {
                pendingTokens[0] += inputTokens.intValue();
            }
            if (outputTokens != null) {
                pendingTokens[1] += outputTokens.intValue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messagesOf(Map<String, Object> nodeUpdate) {
        Object messages = nodeUpdate.get("messages");
        return messages == null ? List.of() : (List<Map<String, Object>>) messages;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolCallsOf(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls == null ? List.of() : (List<Map<String, Object>>) toolCalls;
    }

    /** Merges a "files" update into the running cumulative map; returns paths that are new as of this update. */
    @SuppressWarnings("unchecked")
    private List<String> mergeFiles(Map<String, String> files, Map<String, Object> filesRaw) {
        if (filesRaw == null) {
            return List.of();
        }
        List<String> newPaths = new ArrayList<>();
        filesRaw.forEach((path, fileData) -> {
            if (!files.containsKey(path)) {
                newPaths.add(path);
            }
            Map<String, Object> data = (Map<String, Object>) fileData;
            List<String> contentLines = (List<String>) data.get("content");
            files.put(path, contentLines == null ? "" : String.join("\n", contentLines));
        });
        return newPaths;
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Malformed JSON in generation run's SSE stream: " + json, e);
        }
    }

    private void ensureThreadExists(String threadId) {
        try {
            restClient.post()
                    .uri("/threads")
                    .body(Map.of("thread_id", threadId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict alreadyExists) {
            // Expected on every turn after the first - thread creation isn't idempotent
            // server-side, so a 409 here just means "already set up," not a real error.
        }
    }

    @SuppressWarnings("unchecked")
    private InterrogatorTurnResult parseTurnResult(Map<String, Object> finalState) {
        Map<String, Object> turnResult = (Map<String, Object>) finalState.get("turn_result");
        if (turnResult == null) {
            throw new IllegalStateException("Interrogator graph returned no turn_result: " + finalState);
        }

        List<InterrogatorTurnResult.ExtractedFact> extractedFacts = ((List<Map<String, Object>>) turnResult.get("extracted_facts"))
                .stream()
                .map(f -> new InterrogatorTurnResult.ExtractedFact(
                        (String) f.get("slot_key"), (String) f.get("value"), ((Number) f.get("confidence")).doubleValue()))
                .toList();

        List<InterrogatorTurnResult.NewSlot> newSlots = ((List<Map<String, Object>>) turnResult.get("new_slots"))
                .stream()
                .map(s -> new InterrogatorTurnResult.NewSlot(
                        (String) s.get("key"), (String) s.get("description"), (String) s.get("origin"),
                        ((Number) s.get("importance")).intValue(), (String) s.get("parent_slot_key")))
                .toList();

        List<InterrogatorTurnResult.WaivedSlot> waivedSlots = ((List<Map<String, Object>>) turnResult.get("waived_slots"))
                .stream()
                .map(w -> new InterrogatorTurnResult.WaivedSlot((String) w.get("key"), (String) w.get("reason")))
                .toList();

        boolean readyToConclude = Boolean.TRUE.equals(turnResult.get("ready_to_conclude"));

        InterrogatorTurnResult.ReasoningTrace reasoningTrace = new InterrogatorTurnResult.ReasoningTrace(
                (String) turnResult.get("reasoning_summary"),
                (List<String>) turnResult.getOrDefault("reasoning_decisions", List.of()),
                (List<String>) turnResult.getOrDefault("reasoning_assumptions", List.of()));

        InterrogatorTurnResult.NextQuestion nextQuestion = null;
        Map<String, Object> questionMap = (Map<String, Object>) turnResult.get("next_question");
        if (questionMap != null) {
            List<String> chipOptions = (List<String>) questionMap.get("chip_options");
            nextQuestion = new InterrogatorTurnResult.NextQuestion(
                    (String) questionMap.get("text"),
                    (List<String>) questionMap.get("targets_slots"),
                    (String) questionMap.get("technique"),
                    (String) questionMap.get("input_mode"),
                    (String) questionMap.get("why_asking"),
                    chipOptions == null ? List.of() : chipOptions);
        }

        return new InterrogatorTurnResult(extractedFacts, newSlots, waivedSlots,
                (String) turnResult.get("intent"), (String) turnResult.get("assistant_message"),
                reasoningTrace, nextQuestion, readyToConclude);
    }
}
