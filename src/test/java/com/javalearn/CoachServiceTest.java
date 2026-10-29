package com.javalearn;

import com.javalearn.CoachModels.CoachRequest;
import com.javalearn.CoachModels.QualityReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CoachServiceTest {

    private CoachService coachService;

    @BeforeEach
    public void setUp() {
        coachService = new CoachService();
    }

    @Test
    public void testEmptyCodeHandling() {
        QualityReport report = coachService.analyze(new CoachRequest("", null));
        assertEquals("error", report.status());
        assertNull(report.complexity());
    }

    @Test
    public void testConstantTimeAnalysis() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        int a = 5;
                        int b = 10;
                        int sum = a + b;
                        System.out.println(sum);
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertNotNull(report.complexity());
        assertEquals("O(1)", report.complexity().timeComplexity());
        assertEquals("O(1) auxiliary", report.complexity().spaceComplexity());
        assertTrue(report.complexity().confident());
    }

    @Test
    public void testLinearTimeAnalysis() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        int[] nums = {1, 2, 3, 4, 5};
                        int sum = 0;
                        for (int n : nums) {
                            sum += n;
                        }
                        System.out.println(sum);
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertEquals("O(n)", report.complexity().timeComplexity());
    }

    @Test
    public void testQuadraticTimeAnalysis() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        int n = 5;
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                System.out.println(i * j);
                            }
                        }
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertEquals("O(n²)", report.complexity().timeComplexity());
    }

    @Test
    public void testLogarithmicTimeAnalysis() {
        String code = """
                public class Main {
                    public static int binarySearch(int[] arr, int target) {
                        int low = 0;
                        int high = arr.length - 1;
                        while (low <= high) {
                            int mid = (low + high) / 2;
                            if (arr[mid] == target) return mid;
                            if (arr[mid] < target) low = mid + 1;
                            else high = mid - 1;
                        }
                        return -1;
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertEquals("O(log n)", report.complexity().timeComplexity());
    }

    @Test
    public void testDivideAndConquerSortAnalysis() {
        String code = """
                public class Main {
                    void mergeSort(int[] a, int left, int right) {
                        if (left < right) {
                            int mid = (left + right) / 2;
                            mergeSort(a, left, mid);
                            mergeSort(a, mid + 1, right);
                            merge(a, left, mid, right);
                        }
                    }
                    void merge(int[] a, int l, int m, int r) {}
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertEquals("O(n log n)", report.complexity().timeComplexity());
    }

    @Test
    public void testLinearRecursionAnalysis() {
        String code = """
                public class Main {
                    static int factorial(int n) {
                        if (n <= 1) return 1;
                        return n * factorial(n - 1);
                    }
                    public static void main(String[] args) {
                        System.out.println(factorial(5));
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertEquals("O(n)", report.complexity().timeComplexity());
        assertTrue(report.complexity().spaceExplanation().contains("call stack"));
    }

    @Test
    public void testCompilerDiagnosticsDetection() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        int count = "invalid";
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertTrue(report.issues().stream().anyMatch(i -> "Compiler Error".equals(i.category())));
    }

    @Test
    public void testSuspiciousLogicDetection() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        String name = "Java";
                        if (name == "Java") {
                            System.out.println("Match");
                        }
                    }
                }
                """;
        QualityReport report = coachService.analyze(new CoachRequest(code, null));
        assertEquals("ok", report.status());
        assertTrue(report.issues().stream().anyMatch(i -> "Suspicious Logic".equals(i.category())));
        assertTrue(report.suggestions().stream().anyMatch(s -> s.title().contains(".equals()")));
    }

    @Test
    public void testRuntimeErrorIntegration() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        int[] nums = new int[2];
                        nums[5] = 10;
                    }
                }
                """;
        Models.RunResult simulatedRuntimeErr = new Models.RunResult(
                "runtime_error",
                "Exception in thread \"main\" java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 2\n\tat Main.main(Main.java:4)",
                "",
                "java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 2\n\tat Main.main(Main.java:4)",
                null,
                "0.05",
                25000,
                "Java 21"
        );

        QualityReport report = coachService.analyze(new CoachRequest(code, simulatedRuntimeErr));
        assertEquals("ok", report.status());
        assertTrue(report.issues().stream().anyMatch(i -> "Confirmed Runtime Error".equals(i.category())));
        assertTrue(report.suggestions().stream().anyMatch(s -> s.title().contains("Array Boundaries")));
    }
}

