package com.javalearn;

import java.util.List;

/**
 * Data models for the Code Quality Coach.
 * Serialized to JSON and returned to the browser.
 */
public final class CoachModels {

    private CoachModels() {}

    /**
     * Request sent by the browser to the Code Quality Coach endpoint.
     */
    public record CoachRequest(
            String code,
            Models.RunResult lastRunResult // Optional: execution result for the exact same code
    ) {}

    /**
     * Complexity assessment (Time and Auxiliary Space).
     */
    public record ComplexityAssessment(
            String timeComplexity,        // e.g. "O(n)", "O(n²)", "O(log n)", "O(1)", "Undetermined"
            String timeExplanation,       // Beginner-friendly reasoning
            String timeCases,             // Best / Average / Worst case notes if meaningful, or null
            String spaceComplexity,       // e.g. "O(1) auxiliary", "O(n) auxiliary", "Undetermined"
            String spaceExplanation,      // Beginner-friendly reasoning (distinguishing input vs auxiliary)
            String assumptions,           // Assumptions made, or limitations when undetermined
            boolean confident             // Whether analyzer is confident or case is ambiguous
    ) {}

    /**
     * Code Issue (Errors, Potential Problems, Suggestions).
     */
    public record CodeIssue(
            String severity,              // "error", "warning", "info"
            String category,              // e.g., "Compiler Error", "Runtime Error", "Suspicious Logic", "Unused Variable", "Style"
            int lineNumber,               // 1-based, or -1 if unknown / general
            String message,               // Clear description of the issue
            String explanation            // Beginner-friendly context on why it matters
    ) {}

    /**
     * Practical Improvement Suggestion.
     */
    public record Suggestion(
            String title,
            String explanation,
            String snippet,               // Small illustrative snippet (optional, may be null)
            String performanceImpact      // Expected benefit or trade-off
    ) {}

    /**
     * Complete Code Quality Report.
     */
    public record QualityReport(
            String status,                // "ok", "error"
            String message,
            ComplexityAssessment complexity,
            List<CodeIssue> issues,
            List<Suggestion> suggestions
    ) {}
}

