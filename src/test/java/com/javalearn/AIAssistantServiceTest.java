package com.javalearn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AIAssistantServiceTest {

    @Test
    @DisplayName("Should return unavailable response when API key is not configured")
    void testUnconfiguredService() {
        AIAssistantService service = new AIAssistantService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "");

        assertFalse(service.isConfigured());

        AIAssistantModels.AIRequest request = new AIAssistantModels.AIRequest(
                "Explain this code",
                "explain",
                "public class Hello { public static void main(String[] args) {} }",
                null,
                List.of()
        );

        AIAssistantModels.AIResponse response = service.chat(request);
        assertNotNull(response);
        assertEquals("unavailable", response.status());
        assertTrue(response.message().contains("not configured"));
    }

    @Test
    @DisplayName("Should reject requests that exceed maximum prompt size")
    void testOversizedRequest() {
        AIAssistantService service = new AIAssistantService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "dummy-key");

        assertTrue(service.isConfigured());

        String hugeCode = "A".repeat(35000);
        AIAssistantModels.AIRequest request = new AIAssistantModels.AIRequest(
                "Test",
                "custom",
                hugeCode,
                null,
                List.of()
        );

        AIAssistantModels.AIResponse response = service.chat(request);
        assertEquals("error", response.status());
        assertTrue(response.message().contains("exceeds maximum size limit"));
    }

    @Test
    @DisplayName("Should return error when prompt, code, and errors are all blank")
    void testBlankPrompt() {
        AIAssistantService service = new AIAssistantService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "dummy-key");

        AIAssistantModels.AIRequest request = new AIAssistantModels.AIRequest(
                "   ",
                "custom",
                "",
                null,
                List.of()
        );

        AIAssistantModels.AIResponse response = service.chat(request);
        assertEquals("error", response.status());
        assertTrue(response.message().contains("Please provide a question or code"));
    }

    @Test
    @DisplayName("Should accurately extract Java code blocks from markdown responses")
    void testExtractJavaCode() {
        String markdown = "Here is the fixed code:\n\n```java\npublic class Solution {\n    public static void main(String[] args) {\n        System.out.println(\"Hello\");\n    }\n}\n```\n\nHope this helps!";
        String extracted = AIAssistantService.extractJavaCode(markdown);

        assertNotNull(extracted);
        assertTrue(extracted.contains("public class Solution"));
        assertTrue(extracted.contains("System.out.println(\"Hello\");"));
        assertFalse(extracted.contains("```java"));
        assertFalse(extracted.contains("Here is the fixed code"));
    }

    @Test
    @DisplayName("Should return null when no code blocks exist")
    void testExtractJavaCodeNoBlocks() {
        String textOnly = "This code has no syntax errors, but the logic in loop can be optimized.";
        String extracted = AIAssistantService.extractJavaCode(textOnly);

        assertNull(extracted);
    }
}
