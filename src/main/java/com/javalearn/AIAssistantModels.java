package com.javalearn;

import java.util.List;

/**
 * Data models for the AI Java Assistant.
 * Serialized to/from JSON between the browser and the Spring Boot backend.
 */
public final class AIAssistantModels {

    private AIAssistantModels() {}

    /**
     * One turn in the conversation history.
     */
    public record ChatMessage(
            String role, // "user" or "assistant"
            String content
    ) {}

    /**
     * Request payload sent by the browser.
     */
    public record AIRequest(
            String prompt,
            String action, // "ask", "explain", "fix", "generate"
            String code,   // Current editor code context
            String errors, // Optional compiler or runtime errors
            List<ChatMessage> history
    ) {}

    /**
     * Response payload sent back to the browser.
     */
    public record AIResponse(
            String status, // "ok", "error", "unavailable"
            String message,
            String reply,  // Markdown / explanatory text
            String code    // Extracted clean Java code for insert/replace (if any)
    ) {}
}

