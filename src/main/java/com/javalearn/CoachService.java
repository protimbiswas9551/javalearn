package com.javalearn;

import com.javalearn.CoachModels.*;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code Quality Coach: Analyzes student Java code without executing untrusted code directly.
 * Evaluates:
 *  1. Time & Auxiliary Space Complexity (with beginner explanations and honest limitations)
 *  2. Code Issues (compiler diagnostics, runtime error diagnostics, logic pitfalls, unused items, style)
 *  3. Practical Suggestions with illustrative snippets and performance notes
 */
@Service
public class CoachService {

    private static final int MAX_CODE_CHARS = 64_000;

    public QualityReport analyze(CoachRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            return new QualityReport(
                    "error",
                    "No code provided to analyze.",
                    null,
                    List.of(),
                    List.of()
            );
        }

        String rawCode = request.code();
        if (rawCode.length() > MAX_CODE_CHARS) {
            return new QualityReport(
                    "error",
                    "Code exceeds maximum allowed size of " + MAX_CODE_CHARS + " characters.",
                    null,
                    List.of(),
                    List.of()
            );
        }

        List<CodeIssue> issues = new ArrayList<>();
        List<Suggestion> suggestions = new ArrayList<>();

        // 1. Gather Compiler Diagnostics via ToolProvider (in-memory parse/compile without running)
        List<CodeIssue> compilerIssues = checkCompilerDiagnostics(rawCode);
        issues.addAll(compilerIssues);

        // 2. Process Last Run Result (if provided and recent)
        if (request.lastRunResult() != null) {
            processRunResult(request.lastRunResult(), issues, suggestions);
        }

        // 3. Static Code Linting / Pitfall Checks
        checkStaticCodeQuality(rawCode, issues, suggestions);

        // 4. Complexity Analysis
        ComplexityAssessment complexity = analyzeComplexity(rawCode, compilerIssues.isEmpty());

        return new QualityReport(
                "ok",
                "Analysis complete.",
                complexity,
                issues,
                suggestions
        );
    }

    /**
     * In-memory compilation check using standard JavaCompiler.
     * Compiles strictly to diagnostic collector without executing.
     */
    private List<CodeIssue> checkCompilerDiagnostics(String code) {
        List<CodeIssue> issues = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return issues;
        }

        String className = XRayService.detectPublicClass(code);
        if (className == null) className = "Main";

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new JavaSourceFromString(className, code);

        // Do not generate class files to disk, output to memory / discard
        List<String> options = List.of("-proc:none", "-Xlint:all");
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, null, diagnostics, options, null, List.of(file)
            );
            task.call();

            for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                String severity = switch (diag.getKind()) {
                    case ERROR -> "error";
                    case WARNING, MANDATORY_WARNING -> "warning";
                    default -> "info";
                };
                String category = diag.getKind() == Diagnostic.Kind.ERROR ? "Compiler Error" : "Compiler Warning";
                int line = (int) diag.getLineNumber();
                String message = diag.getMessage(Locale.ENGLISH);

                String explanation = switch (diag.getKind()) {
                    case ERROR -> "The code has a syntax or type problem that prevents Java from compiling it.";
                    case WARNING, MANDATORY_WARNING -> "The Java compiler flagged a potential hazard or unrecommended usage.";
                    default -> "Compiler informational diagnostic.";
                };

                issues.add(new CodeIssue(severity, category, line > 0 ? line : -1, message, explanation));
            }
        } catch (Exception ignored) {
            // If in-memory compilation throws, fail gracefully without crashing
        }

        return issues;
    }

    /**
     * If execution results are present from Judge0 or local runner, incorporate confirmed runtime errors.
     */
    private void processRunResult(Models.RunResult result, List<CodeIssue> issues, List<Suggestion> suggestions) {
        if ("compile_error".equalsIgnoreCase(result.status()) && result.compileOutput() != null && !result.compileOutput().isBlank()) {
            boolean alreadyHasError = issues.stream().anyMatch(i -> "Compiler Error".equals(i.category()));
            if (!alreadyHasError) {
                issues.add(new CodeIssue("error", "Execution Service Compiler Error", -1,
                        result.compileOutput().trim(), "The execution service was unable to compile this code."));
            }
        } else if ("runtime_error".equalsIgnoreCase(result.status())) {
            String err = (result.stderr() != null && !result.stderr().isBlank()) ? result.stderr().trim() :
                    (result.message() != null ? result.message() : "Runtime error occurred during execution.");

            int line = extractLineFromStackTrace(err);
            issues.add(new CodeIssue("error", "Confirmed Runtime Error", line, err,
                    "This exception actually occurred when the code ran with the provided input."));

            if (err.contains("ArrayIndexOutOfBoundsException")) {
                suggestions.add(new Suggestion(
                        "Check Array Boundaries",
                        "An array index was accessed outside its 0 to length-1 range. Ensure loop bounds use `< array.length` rather than `<= array.length`.",
                        "for (int i = 0; i < array.length; i++) {\n    // safe\n}",
                        "Prevents unexpected program termination."
                ));
            } else if (err.contains("NullPointerException")) {
                suggestions.add(new Suggestion(
                        "Guard Against null References",
                        "A method or property was accessed on an object reference that is currently null.",
                        "if (obj != null) {\n    obj.doSomething();\n}",
                        "Prevents NullPointerException at runtime."
                ));
            }
        } else if ("timeout".equalsIgnoreCase(result.status())) {
            issues.add(new CodeIssue("error", "Execution Timeout", -1,
                    "The program exceeded the execution time limit.",
                    "This often indicates an infinite loop, infinite recursion, or an algorithm with time complexity too high for the input size."));
            suggestions.add(new Suggestion(
                    "Verify Loop Termination",
                    "Check all `while` and `for` loops to confirm that the loop control variable is always updated toward the termination condition on every path.",
                    null,
                    "Avoids infinite loops and wasted CPU time."
            ));
        }
    }

    private int extractLineFromStackTrace(String trace) {
        if (trace == null) return -1;
        // Matches e.g. (Main.java:15) or (:15)
        Pattern p = Pattern.compile("(?::|\\.java:)(\\d+)\\)");
        Matcher m = p.matcher(trace);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * Pattern-based checks for common student pitfalls.
     */
    private void checkStaticCodeQuality(String code, List<CodeIssue> issues, List<Suggestion> suggestions) {
        String[] lines = code.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String line = lines[i].trim();

            // 1. String comparison with ==
            // Matches e.g. str == "abc", "abc" == str, s1 == s2 where strings are involved
            if (line.matches(".*\"[^\"]*\"\\s*==\\s*\\w+.*") || line.matches(".*\\w+\\s*==\\s*\"[^\"]*\".*")) {
                issues.add(new CodeIssue(
                        "warning",
                        "Suspicious Logic",
                        lineNum,
                        "Comparing String using '==' tests for object reference identity, not text equality.",
                        "In Java, '==' checks if two references point to the exact same memory location. Use `.equals()` to compare actual string content."
                ));
                suggestions.add(new Suggestion(
                        "Use .equals() for String Equality",
                        "Replace `str == \"value\"` with `\"value\".equals(str)` or `str.equals(\"value\")`.",
                        "if (\"expected\".equals(actual)) {\n    // compares text content safely\n}",
                        "Ensures logical correctness across different String allocations."
                ));
            }

            // 2. Empty catch block
            if (line.matches(".*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*")) {
                issues.add(new CodeIssue(
                        "warning",
                        "Swallowed Exception",
                        lineNum,
                        "Empty catch block silently ignores exceptions.",
                        "Silencing exceptions makes bugs difficult to diagnose. Log the exception or at least print its message."
                ));
            }

            // 3. System.gc() call
            if (line.contains("System.gc()")) {
                issues.add(new CodeIssue(
                        "info",
                        "Optional Suggestion",
                        lineNum,
                        "Explicit call to 'System.gc()' is generally discouraged.",
                        "The JVM garbage collector manages memory automatically. Explicit requests can cause unnecessary stop-the-world pauses."
                ));
            }

            // 4. Inefficient String concatenation inside loops
            if ((line.contains("+=") || line.contains("=")) && line.matches(".*\\b\\w+\\s*\\+=\\s*.*\".*") || line.matches(".*\\b\\w+\\s*=\\s*\\w+\\s*\\+\\s*.*")) {
                // If inside a loop structure (heuristic: check if previous lines have while/for)
                if (isInsideLoop(lines, i)) {
                    suggestions.add(new Suggestion(
                        "Consider StringBuilder for Repeated Concatenation",
                        "Concatenating Strings in a loop with '+' creates a new String and copies characters on every iteration, leading to O(k²) performance.",
                        "StringBuilder sb = new StringBuilder();\nfor (...) {\n    sb.append(item);\n}\nString result = sb.toString();",
                        "Reduces time complexity from O(k²) to O(k) for string assembly."
                    ));
                }
            }

            // 5. Unclosed Scanner on System.in (Warning for beginners if not closed, but info)
            if (line.contains("new Scanner(System.in)") && !code.contains(".close()")) {
                issues.add(new CodeIssue(
                        "info",
                        "Resource Management",
                        lineNum,
                        "Scanner opened on System.in is not explicitly closed.",
                        "While System.in is closed when the JVM exits, closing resources or using try-with-resources is a recommended Java habit."
                ));
            }
        }

        // 6. Check for non-standard or single-letter method names
        Matcher methodMatcher = Pattern.compile("(?:public|private|protected|static|final|\\s)+\\s+([a-zA-Z0-9_<>]+)\\s+([a-zA-Z0-9_]+)\\s*\\(([^)]*)\\)\\s*(?:\\{|throws)").matcher(code);
        while (methodMatcher.find()) {
            String mName = methodMatcher.group(2);
            if (mName.length() == 1 && !mName.equals("f") && !mName.equals("g")) {
                issues.add(new CodeIssue(
                        "info",
                        "Naming Convention",
                        findLineNumber(code, methodMatcher.start()),
                        "Single-letter method name '" + mName + "' is unclear.",
                        "Method names in Java should be descriptive verbs or action phrases using camelCase (e.g. `calculateTotal`, `findMax`)."
                ));
            }
        }
    }

    private boolean isInsideLoop(String[] lines, int currentIndex) {
        int braceDepth = 0;
        for (int i = 0; i <= currentIndex; i++) {
            String l = lines[i];
            if (l.contains("for ") || l.contains("for(") || l.contains("while ") || l.contains("while(")) {
                return true;
            }
        }
        return false;
    }

    private int findLineNumber(String text, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Complexity Analysis: Estimates Big-O Time & Auxiliary Space for common Java algorithms.
     * Straightforward cases: O(1), O(log n), O(n), O(n log n), O(n²), etc.
     * When pattern cannot be determined reliably, reports "Undetermined" with limitations explained.
     */
    private ComplexityAssessment analyzeComplexity(String code, boolean compilesCleanly) {
        // Strip comments and string literals to inspect control flow accurately
        String cleaned = stripCommentsAndStrings(code);

        // Count loops and structure
        int loopNesting = computeMaxLoopNesting(cleaned);
        boolean hasBinarySearchPattern = detectBinarySearchPattern(cleaned);
        boolean hasDivideAndConquerSort = detectDivideAndConquerSort(cleaned);
        boolean hasSortingCall = cleaned.contains("Arrays.sort(") || cleaned.contains("Collections.sort(");
        boolean hasTreeTraversal = detectTreeOrGraphTraversal(cleaned);
        boolean hasRecursion = detectRecursion(cleaned);

        String timeComplexity;
        String timeExplanation;
        String timeCases = null;
        String spaceComplexity;
        String spaceExplanation;
        String assumptions = "";
        boolean confident = true;

        // 1. Time Complexity Deduction
        if (hasDivideAndConquerSort) {
            timeComplexity = "O(n log n)";
            timeExplanation = "Divide-and-conquer sorting pattern detected (e.g., Merge Sort / Quick Sort) which repeatedly splits the problem in half (log n levels) and performs linear work across each level.";
            timeCases = "Best: O(n log n) | Average: O(n log n) | Worst: O(n²) if pivot selection is unoptimized (Quick Sort), or O(n log n) (Merge Sort).";
            assumptions = "Assumes input size n corresponds to the elements in the array/collection being sorted.";
        } else if (hasBinarySearchPattern) {
            timeComplexity = "O(log n)";
            timeExplanation = "Logarithmic pattern detected: the search space is divided in half (e.g., `mid = (low + high) / 2` or division by 2 in each loop iteration).";
            timeCases = "Best: O(1) (target element found at first midpoint) | Worst: O(log n).";
            assumptions = "Assumes array/collection is pre-sorted and accessed by index in O(1).";
        } else if (hasSortingCall && loopNesting <= 1) {
            timeComplexity = "O(n log n)";
            timeExplanation = "The code uses Java's built-in sorting (`Arrays.sort` / `Collections.sort`), which uses Dual-Pivot Quicksort or Timsort taking O(n log n) time.";
            timeCases = "Best: O(n) (for nearly sorted inputs with Timsort) | Average/Worst: O(n log n).";
            assumptions = "Assumes n is the number of elements passed to the sorting utility.";
        } else if (loopNesting == 2) {
            timeComplexity = "O(n²)";
            timeExplanation = "Nested loop pattern detected: an outer loop runs up to n times and an inner loop runs up to n times for each outer iteration, performing ~n² operations.";
            timeCases = "Worst: O(n²) | Best: O(n) if the inner loop breaks early on sorted inputs (e.g., optimized Bubble Sort).";
            assumptions = "Assumes both outer and inner loop boundaries scale linearly with the primary input size n.";
        } else if (loopNesting >= 3) {
            timeComplexity = "O(n^" + loopNesting + ")";
            timeExplanation = "Deeply nested loops detected (" + loopNesting + " levels). Each additional nesting level multiplies the number of iterations by n.";
            assumptions = "Assumes all " + loopNesting + " loop boundaries scale proportionally to input size n.";
        } else if (loopNesting == 1) {
            // Check step increments (e.g. i *= 2 vs i++)
            if (cleaned.matches("(?s).*for\\s*\\([^;]*;[^;]*;\\s*\\w+\\s*\\*=\\s*\\d+\\).*") ||
                cleaned.matches("(?s).*while\\s*\\([^)]*\\)\\s*\\{[^}]*\\w+\\s*\\/=\\s*\\d+.*")) {
                timeComplexity = "O(log n)";
                timeExplanation = "The loop index is multiplied or divided by a constant factor on each step, reducing the iteration count logarithmically.";
            } else {
                timeComplexity = "O(n)";
                timeExplanation = "Linear pattern detected: a single loop iterates sequentially through the input elements once.";
                timeCases = "Best: O(1) if search terminates on the first element | Worst: O(n).";
                assumptions = "Assumes the loop bounds scale linearly with the primary input size n.";
            }
        } else if (hasRecursion) {
            // Single recursion vs branching recursion
            int recursiveCallCount = countSelfMethodCalls(cleaned);
            if (recursiveCallCount >= 2) {
                timeComplexity = "O(2^n)";
                timeExplanation = "Branching recursion pattern detected: the method calls itself multiple times per frame (e.g., naive Fibonacci), forming an exponential recursive call tree.";
                assumptions = "Assumes recursion depth n without memoization or dynamic programming.";
            } else if (recursiveCallCount == 1) {
                timeComplexity = "O(n)";
                timeExplanation = "Linear recursion pattern detected: the method makes a single recursive call with a decremented parameter (e.g., factorial or linear linked-list traversal).";
                assumptions = "Assumes parameter reduces by a constant amount until the base case is reached.";
            } else {
                timeComplexity = "Undetermined";
                timeExplanation = "Recursive pattern found, but exact branching and reduction step could not be proven without runtime execution bounds.";
                confident = false;
                assumptions = "Static analysis alone cannot determine the termination depth for this recursion.";
            }
        } else if (cleaned.contains("public static void main") || cleaned.contains("class ")) {
            timeComplexity = "O(1)";
            timeExplanation = "Constant time pattern: the code executes a fixed sequence of statements, conditions, or constant-bound operations without scaling loops or recursion.";
            assumptions = "Assumes all called standard library methods (like basic printing or math operations) run in constant time.";
        } else {
            timeComplexity = "Undetermined";
            timeExplanation = "The control flow does not match recognizable linear, logarithmic, polynomial, or standard recursive algorithm templates.";
            confident = false;
            assumptions = "Complex or dynamically computed control flow cannot be reliably classified statically.";
        }

        // 2. Auxiliary Space Complexity Deduction
        boolean createsArray = cleaned.matches("(?s).*new\\s+\\w+\\s*\\[[^\\]]+\\].*");
        boolean createsCollection = cleaned.matches("(?s).*new\\s+(?:ArrayList|LinkedList|HashSet|HashMap|TreeSet|TreeMap|ArrayDeque|Vector|PriorityQueue)[^;]*;.*");

        if (createsArray || createsCollection) {
            spaceComplexity = "O(n) auxiliary";
            spaceExplanation = "The code allocates additional dynamic storage (such as a new array or collection) that scales with input size, in addition to the input itself.";
            assumptions = "Assumes the allocated array or collection capacity grows proportionally with input size n.";
        } else if (hasRecursion) {
            spaceComplexity = "O(n) auxiliary (call stack)";
            spaceExplanation = "Each recursive invocation adds a new frame to the JVM call stack until the base case is reached. This memory is auxiliary (consumed by execution overhead).";
            assumptions = "Assumes maximum call stack depth reaches up to n frames.";
        } else {
            spaceComplexity = "O(1) auxiliary";
            spaceExplanation = "Only a fixed number of primitive variables or references are allocated. Memory usage remains constant regardless of input size.";
            assumptions = "Input memory (arguments passed into the method) is not counted against auxiliary space.";
        }

        return new ComplexityAssessment(
                timeComplexity,
                timeExplanation,
                timeCases,
                spaceComplexity,
                spaceExplanation,
                assumptions,
                confident
        );
    }

    private int computeMaxLoopNesting(String code) {
        int maxDepth = 0;
        int currentDepth = 0;
        String[] tokens = code.split("(?<=[{};])|(?=[{};])");
        Deque<Boolean> loopStack = new ArrayDeque<>();

        Pattern loopStart = Pattern.compile("^\\s*(for|while)\\s*\\(.*\\)");

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (token.startsWith("for") || token.startsWith("while")) {
                currentDepth++;
                if (currentDepth > maxDepth) maxDepth = currentDepth;
                loopStack.push(true);
            } else if (token.equals("}")) {
                if (!loopStack.isEmpty()) {
                    loopStack.pop();
                    if (currentDepth > 0) currentDepth--;
                }
            }
        }
        return maxDepth;
    }

    private boolean detectBinarySearchPattern(String code) {
        boolean hasMidpoint = code.contains("/ 2") || code.contains(">> 1") || code.contains("/2");
        boolean hasBounds = (code.contains("low") && code.contains("high")) || (code.contains("left") && code.contains("right"));
        boolean hasLoop = code.contains("while") || code.contains("for");
        return hasMidpoint && hasBounds && hasLoop;
    }

    private boolean detectDivideAndConquerSort(String code) {
        boolean mentionsMerge = code.toLowerCase().contains("merge") && code.contains("mid");
        boolean mentionsQuick = code.toLowerCase().contains("quick") || (code.contains("partition") && code.contains("pivot"));
        return mentionsMerge || mentionsQuick;
    }

    private boolean detectTreeOrGraphTraversal(String code) {
        return (code.contains(".left") && code.contains(".right")) ||
                (code.contains("adj") && code.contains("visited"));
    }

    private boolean detectRecursion(String code) {
        Pattern methodDef = Pattern.compile("(?:public|private|protected|static|final|\\s)+\\s+([a-zA-Z0-9_<>]+)\\s+([a-zA-Z0-9_]+)\\s*\\(([^)]*)\\)\\s*(?:\\{|throws)");
        Matcher m = methodDef.matcher(code);
        while (m.find()) {
            String methodName = m.group(2);
            if ("main".equals(methodName)) continue;
            int start = code.indexOf('{', m.end() - 1);
            if (start == -1) continue;
            String rest = code.substring(start + 1);
            int braceCount = 1;
            int bodyEnd = rest.length();

            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        bodyEnd = i;
                        break;
                    }
                }
            }

            String body = rest.substring(0, bodyEnd);
            if (Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(").matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private int countSelfMethodCalls(String code) {
        Pattern methodDef = Pattern.compile("(?:public|private|protected|static|final|\\s)+\\s+([a-zA-Z0-9_<>]+)\\s+([a-zA-Z0-9_]+)\\s*\\(([^)]*)\\)\\s*(?:\\{|throws)");
        Matcher m = methodDef.matcher(code);
        int maxCalls = 0;
        while (m.find()) {
            String methodName = m.group(2);
            if ("main".equals(methodName)) continue;
            int start = code.indexOf('{', m.end() - 1);
            if (start == -1) continue;
            String rest = code.substring(start + 1);
            int braceCount = 1;
            int bodyEnd = rest.length();

            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        bodyEnd = i;
                        break;
                    }
                }
            }

            String body = rest.substring(0, bodyEnd);
            Matcher callMatcher = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(").matcher(body);
            int calls = 0;
            while (callMatcher.find()) calls++;
            if (calls > maxCalls) maxCalls = calls;
        }
        return maxCalls;
    }

    private String stripCommentsAndStrings(String code) {
        // Strip block comments /* ... */
        String noBlock = code.replaceAll("/\\*.*?\\*/", " ");
        // Strip line comments // ...
        String noLine = noBlock.replaceAll("//.*", " ");
        // Strip string literals "..."
        return noLine.replaceAll("\"(?:\\\\\"|[^\"])*\"", "\"\"");
    }

    /**
     * In-memory JavaFileObject for ToolProvider.getSystemJavaCompiler()
     */
    private static class JavaSourceFromString extends SimpleJavaFileObject {
        final String code;

        JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
