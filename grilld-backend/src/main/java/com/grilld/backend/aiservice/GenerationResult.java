package com.grilld.backend.aiservice;

import java.util.Map;

/**
 * The full blueprint package produced by one Orchestrator run
 * (docs/product-and-architecture.md §3.2's specialist roster). {@code files}
 * maps a virtual path (e.g. "/docs/TECH_STACK.md") to its full text content -
 * the Orchestrator's Deep Agents state, read directly off the LangGraph
 * server's final response, not parsed out of a chat message.
 */
public record GenerationResult(Map<String, String> files) {
}
