package com.javalearn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.AIAssistantModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Java Coding Assistant backend service.
 * Supports Google Gemini (free tier default), OpenAI, and Groq via server-side HTTP calls.
 * Never exposes API keys to client JavaScript.
 */
@Service
public class AIAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AIAssistantService.class);
    private static final int MAX_INPUT_CHARS = 32_000;

    @Value("${ai.assistant.enabled:true}")
    private boolean enabled;

    @Value("${ai.assistant.provider:gemini}")
    private String provider;

    @Value("${ai.assistant.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.assistant.gemini.model:gemini-flash-lite-latest}")
    private String geminiModel;

    @Value("${ai.assistant.openai.api-key:}")
    private String openAiApiKey;

    @Value("${ai.assistant.openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${ai.assistant.openai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    @Value("${ai.assistant.groq.api-key:}")
    private String groqApiKey;

    @Value("${ai.assistant.groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    @Value("${ai.assistant.timeout-seconds:25}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Checks if active provider credentials are configured.
     */
    public boolean isConfigured() {
        if (!enabled) return false;
        String active = provider != null ? provider.trim().toLowerCase(Locale.ROOT) : "gemini";
        return switch (active) {
            case "openai" -> openAiApiKey != null && !openAiApiKey.isBlank();
            case "groq" -> groqApiKey != null && !groqApiKey.isBlank();
            default -> geminiApiKey != null && !geminiApiKey.isBlank();
        };
    }

    public String getActiveProviderName() {
        return provider != null ? provider.trim().toLowerCase(Locale.ROOT) : "gemini";
    }

    /**
     * Handles student AI requests safely without executing code.
     */
    public AIResponse chat(AIRequest request) {
        if (!enabled) {
            return new AIResponse("unavailable", "AI Assistant is disabled on this server.", null, null);
        }

        String active = getActiveProviderName();
        if (!isConfigured()) {
            String keyName = switch (active) {
                case "openai" -> "OPENAI_API_KEY";
                case "groq" -> "GROQ_API_KEY";
                default -> "GEMINI_API_KEY";
            };
            return new AIResponse(
                    "unavailable",
                    "AI Assistant is not configured on this server. Set the " + keyName +
                    " environment variable or configure it in application.properties to enable AI features.",
                    null,
                    null
            );
        }

        if (request == null) {
            return new AIResponse("error", "Invalid request.", null, null);
        }

        // Validate prompt/action
        String prompt = request.prompt() != null ? request.prompt().trim() : "";
        String action = request.action() != null ? request.action().trim().toLowerCase(Locale.ROOT) : "ask";
        String code = request.code() != null ? request.code().trim() : "";
        String errors = request.errors() != null ? request.errors().trim() : "";

        if (prompt.isBlank() && code.isBlank() && errors.isBlank()) {
            return new AIResponse("error", "Please provide a question or code to analyze.", null, null);
        }

        // Prevent excessively large inputs
        if (prompt.length() > MAX_INPUT_CHARS || code.length() > MAX_INPUT_CHARS) {
            return new AIResponse("error", "Input text exceeds maximum size limit of " + MAX_INPUT_CHARS + " characters.", null, null);
        }

        try {
            String systemInstruction = buildSystemPrompt(action);
            String userMessage = buildUserPrompt(action, prompt, code, errors);

            String rawReply;
            if ("openai".equals(active) || "groq".equals(active)) {
                rawReply = callOpenAiCompatible(active, systemInstruction, userMessage, request.history());
            } else {
                rawReply = callGemini(systemInstruction, userMessage, request.history());
            }

            String extractedCode = extractJavaCode(rawReply);

            return new AIResponse("ok", "Success", rawReply, extractedCode);

        } catch (java.net.http.HttpConnectTimeoutException | java.util.concurrent.TimeoutException e) {
            log.warn("AI Assistant request timed out: {}", e.getMessage());
            return new AIResponse("error", "The AI request timed out after " + timeoutSeconds + " seconds. Please try again.", null, null);
        } catch (Exception e) {
            log.warn("AI Assistant provider call failed: {}", e.getMessage());
            String sanitizedErr = e.getMessage() != null ? e.getMessage().replaceAll("key=[^&\\s]+", "key=REDACTED") : "Unknown error";
            return new AIResponse("error", "Could not complete AI request: " + sanitizedErr, null, null);
        }
    }

    private String buildSystemPrompt(String action) {
        return """
                You are JavaLearn AI Assistant, an expert Java tutor and pair programmer designed for beginners and students.
                Your role is to explain Java concepts clearly, debug compiler/runtime errors, and generate clean, idiomatic Java 21 code.

                GUIDELINES:
                1. Tone: Friendly, encouraging, clear, and beginner-focused.
                2. Java Environment: JavaLearn runs standard Java programs with `public class Main { public static void main(String[] args) { ... } }`.
                3. When providing runnable code, ALWAYS format it with standard markdown code blocks (```java ... ```) and ensure it has a `public class Main` entrypoint so students can run it in the playground.
                4. For explanations: Break down the logic simply. Avoid unnecessary jargon.
                5. For error fixing: Point out the exact error, explain why Java rejects it, and show the corrected code.
                6. Do NOT invent libraries or features that require external build dependencies. Use the standard Java JDK library.
                """;
    }

    private String buildUserPrompt(String action, String prompt, String code, String errors) {
        StringBuilder sb = new StringBuilder();

        switch (action) {
            case "explain" -> {
                sb.append("Please explain this Java code step by step for a beginner student:\n\n");
                if (!code.isBlank()) {
                    sb.append("```java\n").append(code).append("\n```\n\n");
                }
                if (!prompt.isBlank()) {
                    sb.append("Student question: ").append(prompt);
                }
            }
            case "fix" -> {
                sb.append("Please help fix the errors in my Java code.\n\n");
                if (!code.isBlank()) {
                    sb.append("Current Code:\n```java\n").append(code).append("\n```\n\n");
                }
                if (!errors.isBlank()) {
                    sb.append("Compiler / Runtime Errors:\n```\n").append(errors).append("\n```\n\n");
                }
                if (!prompt.isBlank()) {
                    sb.append("Student Note: ").append(prompt);
                }
            }
            case "generate" -> {
                sb.append("Please write a complete Java program for the following task:\n");
                sb.append(prompt).append("\n\n");
                sb.append("Make sure it includes `public class Main` with a `main` method so I can run it directly.");
            }
            default -> { // "ask"
                if (!prompt.isBlank()) {
                    sb.append(prompt).append("\n\n");
                }
                if (!code.isBlank()) {
                    sb.append("Context Code:\n```java\n").append(code).append("\n```\n\n");
                }
                if (!errors.isBlank()) {
                    sb.append("Error Output:\n```\n").append(errors).append("\n```\n\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Google Gemini API implementation (v1beta REST API).
     */
    private String callGemini(String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
                          geminiModel + ":generateContent?key=" + geminiApiKey.trim();

        List<Map<String, Object>> contents = new ArrayList<>();

        // Add history if present
        if (history != null) {
            for (ChatMessage msg : history) {
                if (msg == null || msg.content() == null || msg.content().isBlank()) continue;
                String role = "assistant".equalsIgnoreCase(msg.role()) ? "model" : "user";
                contents.add(Map.of(
                        "role", role,
                        "parts", List.of(Map.of("text", msg.content()))
                ));
            }
        }

        // Add current user prompt
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        ));

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
        ));
        reqBody.put("contents", contents);
        reqBody.put("generationConfig", Map.of(
                "temperature", 0.3,
                "maxOutputTokens", 2048
        ));

        String jsonPayload = objectMapper.writeValueAsString(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            JsonNode errNode = parseJsonSafe(response.body());
            String errMsg = errNode != null && errNode.has("error") && errNode.get("error").has("message")
                    ? errNode.get("error").get("message").asText()
                    : "HTTP " + response.statusCode();
            throw new RuntimeException("Gemini API error (" + response.statusCode() + "): " + errMsg);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
            if (!textNode.isMissingNode()) {
                return textNode.asText();
            }
        }

        return "I received your request, but could not produce a response.";
    }

    /**
     * OpenAI or Groq compatible chat completions API.
     */
    private String callOpenAiCompatible(String activeProvider, String systemPrompt, String userMessage, List<ChatMessage> history) throws Exception {
        String baseUrl = "groq".equals(activeProvider) ? "https://api.groq.com/openai/v1" : openAiBaseUrl;
        String apiKey = "groq".equals(activeProvider) ? groqApiKey : openAiApiKey;
        String model = "groq".equals(activeProvider) ? groqModel : openAiModel;

        String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null) {
            for (ChatMessage msg : history) {
                if (msg == null || msg.content() == null || msg.content().isBlank()) continue;
                String role = "assistant".equalsIgnoreCase(msg.role()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", msg.content()));
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", model);
        reqBody.put("messages", messages);
        reqBody.put("temperature", 0.3);

        String jsonPayload = objectMapper.writeValueAsString(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            JsonNode errNode = parseJsonSafe(response.body());
            String errMsg = errNode != null && errNode.has("error") && errNode.get("error").has("message")
                    ? errNode.get("error").get("message").asText()
                    : "HTTP " + response.statusCode();
            throw new RuntimeException(activeProvider + " API error: " + errMsg);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode textNode = choices.get(0).path("message").path("content");
            if (!textNode.isMissingNode()) {
                return textNode.asText();
            }
        }

        return "I received your request, but could not produce a response.";
    }

    private JsonNode parseJsonSafe(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts Java code from markdown ```java ... ``` blocks.
     */
    public static String extractJavaCode(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("```(?:java)?\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}

