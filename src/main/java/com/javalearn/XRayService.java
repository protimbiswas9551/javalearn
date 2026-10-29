package com.javalearn;

import com.javalearn.XRayModels.*;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.StepRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java X-Ray: compiles user code in a temporary directory and runs it as a
 * separate JVM process with JDWP debugging enabled. Attaches via JDI (Java
 * Debug Interface) to record each source-line execution event, capturing local
 * variables, call stacks, and object state. The user process is strictly
 * isolated from the Spring Boot process.
 *
 * Security notes:
 *  - User code runs in a DIFFERENT JVM process, never in Spring's process.
 *  - Output and event counts are capped.
 *  - The process is killed after a configurable timeout.
 *  - No application secrets are passed to the user process.
 */
@org.springframework.stereotype.Service
public class XRayService {

    private static final Logger log = LoggerFactory.getLogger(XRayService.class);

    // Limits
    private static final int MAX_CODE_CHARS   = 64_000;
    private static final int MAX_STEPS        = 2_000;
    private static final int MAX_STDOUT_CHARS = 50_000;
    private static final int MAX_OBJECT_DEPTH = 2;
    private static final int MAX_FIELDS       = 30;
    private static final int MAX_ARRAY_ELEMS  = 50;
    private static final int TIMEOUT_SECONDS  = 20;
    private static final int MAX_STRING_LEN   = 200;

    @Value("${xray.enabled:true}")
    private boolean xrayEnabled;

    // Rate limiting per remote address
    private final Map<String, Deque<Long>> recentXRayRuns = new HashMap<>();
    private static final int XRAY_RUNS_PER_MINUTE = 5;

    public boolean isXRayEnabled() { return xrayEnabled; }

    /**
     * Entry point. Compiles the code, runs it under JDI, returns the trace.
     * Never throws; all errors are returned as XRayResult with status != "ok".
     */
    public XRayResult trace(XRayRequest request, String clientIp) {
        if (!xrayEnabled) {
            return error("unavailable", "Java X-Ray is disabled on this server.", null, null);
        }
        if (request == null || request.code() == null || request.code().isBlank()) {
            return error("error", "No code to trace.", null, null);
        }
        if (request.code().length() > MAX_CODE_CHARS) {
            return error("error", "Code exceeds " + MAX_CODE_CHARS + " characters.", null, null);
        }
        if (!allowRun(clientIp)) {
            return error("error", "Too many X-Ray requests. Please wait a minute.", null, null);
        }

        Path tmpDir = null;
        Process userProcess = null;
        try {
            tmpDir = Files.createTempDirectory("xray-");
            String code = request.code();
            String className = detectPublicClass(code);
            if (className == null) className = "Main";
            Path sourceFile = tmpDir.resolve(className + ".java");
            Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

            String compileOutput = compile(sourceFile, tmpDir);
            if (compileOutput != null) {
                return error("compile_error", "Compilation failed.", compileOutput, null);
            }

            int debugPort = findFreePort();

            String javaExe = ProcessHandle.current().info().command().orElse("java");
            List<String> cmd = buildCommand(javaExe, tmpDir, className, debugPort);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(tmpDir.toFile());
            pb.redirectErrorStream(true);
            userProcess = pb.start();
            final Process proc = userProcess;

            StringBuilder stdout = new StringBuilder();
            InputStream procOut = proc.getInputStream();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(procOut, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (stdout) {
                            if (stdout.length() < MAX_STDOUT_CHARS) {
                                stdout.append(line).append("\n");
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }, "xray-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            VirtualMachine vm = attachWithRetry(debugPort, 8000);
            if (vm == null) {
                proc.destroyForcibly();
                return error("error",
                        "Could not connect to the debug process. Please try again.", null, null);
            }

            List<StepSnapshot> steps = runTrace(vm, stdout, proc);

            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (!finished) proc.destroyForcibly();

            Map<Integer, List<Integer>> lineToSteps = new LinkedHashMap<>();
            for (StepSnapshot s : steps) {
                if (s.lineNumber() > 0) {
                    lineToSteps.computeIfAbsent(s.lineNumber(), k -> new ArrayList<>())
                               .add(s.stepIndex());
                }
            }

            return new XRayResult("ok",
                    "Trace complete. " + steps.size() + " step(s) recorded.",
                    null, steps, lineToSteps);

        } catch (TimeoutException e) {
            if (userProcess != null) userProcess.destroyForcibly();
            return error("timeout",
                    "The program timed out after " + TIMEOUT_SECONDS + " seconds.", null, null);
        } catch (Exception e) {
            log.warn("X-Ray trace failed", e);
            if (userProcess != null) userProcess.destroyForcibly();
            return error("error",
                    "An internal error occurred during tracing: " + sanitize(e.getMessage()),
                    null, null);
        } finally {
            if (tmpDir != null) deleteTree(tmpDir);
        }
    }

    // ---- Compilation ----

    private String compile(Path sourceFile, Path outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "javax.tools.JavaCompiler not available (not running on a JDK).");
        }
        StringWriter errors = new StringWriter();
        try (StandardJavaFileManager fm =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(sourceFile.toFile());
            List<String> options = List.of(
                    "-d", outputDir.toString(),
                    "-g",           // include debug info so JDI can see variable names
                    "-source", "21",
                    "-target", "21");
            JavaCompiler.CompilationTask task =
                    compiler.getTask(errors, fm, null, options, null, units);
            boolean ok = task.call();
            if (ok) return null;
            String out = errors.toString().strip();
            return out.replace(sourceFile.toString(), sourceFile.getFileName().toString())
                      .replace(outputDir.toString(), ".");
        } catch (IOException e) {
            return "Compiler I/O error: " + e.getMessage();
        }
    }

    // ---- Process launch ----

    private List<String> buildCommand(String javaExe, Path classDir,
                                      String mainClass, int debugPort) {
        return List.of(
                javaExe,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + debugPort,
                "-Xmx128m",
                "-Xss512k",
                "-cp", classDir.toString(),
                mainClass
        );
    }

    // ---- JDI attach ----

    private VirtualMachine attachWithRetry(int port, long timeoutMs) {
        AttachingConnector connector = Bootstrap.virtualMachineManager()
                .attachingConnectors().stream()
                .filter(c -> c.name().contains("SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SocketAttach JDI connector"));

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(String.valueOf(port));
        args.get("timeout").setValue("2000");

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return connector.attach(args);
            } catch (Exception e) {
                try { Thread.sleep(200); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return null;
    }

    // ---- Main tracing loop ----

    private List<StepSnapshot> runTrace(VirtualMachine vm, StringBuilder stdout, Process proc)
            throws TimeoutException, InterruptedException {

        List<StepSnapshot> steps = new ArrayList<>();
        EventRequestManager erm = vm.eventRequestManager();

        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();

        ExceptionRequest exr = erm.createExceptionRequest(null, false, true);
        exr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        exr.enable();

        vm.resume();

        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        boolean timedOut = false;
        Map<ThreadReference, StepRequest> stepRequests = new HashMap<>();

        outer:
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                timedOut = true;
                break;
            }
            EventQueue eventQueue = vm.eventQueue();
            EventSet eventSet;
            try {
                eventSet = eventQueue.remove(500);
            } catch (VMDisconnectedException e) {
                break;
            }
            if (eventSet == null) {
                if (!proc.isAlive()) break;
                continue;
            }

            for (Event event : eventSet) {
                if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    break outer;
                }

                if (event instanceof ClassPrepareEvent cpe) {
                    ThreadReference thread = cpe.thread();
                    if (!stepRequests.containsKey(thread)) {
                        StepRequest sr = erm.createStepRequest(
                                thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
                        sr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        sr.addClassExclusionFilter("java.*");
                        sr.addClassExclusionFilter("javax.*");
                        sr.addClassExclusionFilter("jdk.*");
                        sr.addClassExclusionFilter("sun.*");
                        sr.addClassExclusionFilter("com.sun.*");
                        sr.addClassExclusionFilter("org.*");
                        sr.enable();
                        stepRequests.put(thread, sr);
                    }
                }

                if (event instanceof StepEvent se) {
                    if (steps.size() >= MAX_STEPS) {
                        StepRequest sr = stepRequests.get(se.thread());
                        if (sr != null) sr.disable();
                        steps.add(new StepSnapshot(steps.size(), -1, "end", "", "", List.of(),
                                List.of(), List.of(), currentStdout(stdout),
                                "Step limit (" + MAX_STEPS + ") reached. Trace ended."));
                        break outer;
                    }

                    Location loc = se.location();
                    int line = loc.lineNumber();
                    String className = loc.declaringType().name();
                    String methodName = loc.method().name();

                    ThreadReference thread = se.thread();
                    List<VarSnapshot> locals = captureLocals(thread);
                    List<ObjectSnapshot> objects = captureObjects(thread, locals);
                    List<FrameSnapshot> stack = captureStack(thread);

                    steps.add(new StepSnapshot(
                            steps.size(), line, "line",
                            className, methodName,
                            locals, objects, stack, currentStdout(stdout), null));
                }

                if (event instanceof ExceptionEvent ee) {
                    Location loc = ee.location();
                    String exMsg = ee.exception().type().name();
                    try {
                        ObjectReference exObj = ee.exception();
                        List<com.sun.jdi.Method> toStringMethods =
                                exObj.referenceType().methodsByName("toString");
                        if (!toStringMethods.isEmpty()) {
                            com.sun.jdi.Value msgVal = exObj.invokeMethod(
                                    ee.thread(), toStringMethods.get(0), List.of(), 0);
                            if (msgVal instanceof StringReference sr) exMsg = sr.value();
                        }
                    } catch (Exception ignored) {}
                    steps.add(new StepSnapshot(
                            steps.size(),
                            loc != null ? loc.lineNumber() : -1,
                            "exception",
                            loc != null ? loc.declaringType().name() : "",
                            loc != null ? loc.method().name() : "",
                            List.of(), List.of(),
                            captureStack(ee.thread()),
                            currentStdout(stdout),
                            "Exception: " + exMsg));
                }
            }

            try { eventSet.resume(); } catch (VMDisconnectedException e) { break; }
        }

        try { vm.exit(0); } catch (Exception ignored) {}

        if (timedOut) throw new TimeoutException("Program exceeded " + TIMEOUT_SECONDS + "s");
        return steps;
    }

    // ---- Variable / object capture ----

    private List<VarSnapshot> captureLocals(ThreadReference thread) {
        try {
            if (thread.frameCount() == 0) return List.of();
            StackFrame frame = thread.frame(0);
            List<LocalVariable> vars;
            try {
                vars = frame.visibleVariables();
            } catch (AbsentInformationException e) {
                return List.of(new VarSnapshot("(no debug info)", "?", "?", "local", false, null));
            }
            List<VarSnapshot> result = new ArrayList<>();
            Map<LocalVariable, com.sun.jdi.Value> values = frame.getValues(vars);
            for (LocalVariable lv : vars) {
                com.sun.jdi.Value v = values.get(lv);
                result.add(toVarSnapshot(lv.name(), lv.typeName(), v,
                        lv.isArgument() ? "parameter" : "local"));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ObjectSnapshot> captureObjects(ThreadReference thread, List<VarSnapshot> locals) {
        List<ObjectSnapshot> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            if (thread.frameCount() == 0) return List.of();
            StackFrame frame = thread.frame(0);
            List<LocalVariable> vars;
            try {
                vars = frame.visibleVariables();
            } catch (AbsentInformationException e) {
                return List.of();
            }
            Map<LocalVariable, com.sun.jdi.Value> values = frame.getValues(vars);
            for (LocalVariable lv : vars) {
                com.sun.jdi.Value v = values.get(lv);
                inspectObject(v, seen, result, 0, thread);
            }
        } catch (Exception e) {
            log.trace("Object capture error", e);
        }
        return result;
    }

    private void inspectObject(com.sun.jdi.Value v, Set<String> seen,
                               List<ObjectSnapshot> result, int depth,
                               ThreadReference thread) {
        if (depth >= MAX_OBJECT_DEPTH || result.size() > 50) return;
        if (!(v instanceof ObjectReference objRef)) return;
        if (v instanceof StringReference) return;
        if (v instanceof ArrayReference) return;

        String id = String.valueOf(objRef.uniqueID());
        if (!seen.add(id)) return;

        ReferenceType rt = objRef.referenceType();
        String typeName = rt.name();
        if (typeName.startsWith("java.") || typeName.startsWith("javax.") ||
            typeName.startsWith("sun.") || typeName.startsWith("jdk.")) return;

        List<FieldSnapshot> fields = new ArrayList<>();
        try {
            List<Field> allFields = rt.allFields();
            int count = 0;
            for (Field f : allFields) {
                if (count++ >= MAX_FIELDS) {
                    fields.add(new FieldSnapshot("...", "", "(" + (allFields.size() - MAX_FIELDS)
                            + " more fields)", false, null));
                    break;
                }
                com.sun.jdi.Value fv;
                try { fv = objRef.getValue(f); } catch (Exception e) { continue; }
                fields.add(new FieldSnapshot(f.name(), f.typeName(), renderValue(fv),
                        fv == null, objectId(fv)));
            }
        } catch (Exception e) {
            log.trace("Field capture error", e);
        }
        result.add(new ObjectSnapshot(id, typeName, fields));

        if (depth + 1 < MAX_OBJECT_DEPTH) {
            for (Field f : rt.allFields()) {
                try {
                    com.sun.jdi.Value fv = objRef.getValue(f);
                    inspectObject(fv, seen, result, depth + 1, thread);
                } catch (Exception ignored) {}
            }
        }
    }

    private List<FrameSnapshot> captureStack(ThreadReference thread) {
        try {
            List<FrameSnapshot> stack = new ArrayList<>();
            for (StackFrame f : thread.frames()) {
                Location loc = f.location();
                stack.add(new FrameSnapshot(
                        loc.declaringType().name(),
                        loc.method().name(),
                        loc.lineNumber(),
                        loc.method().isNative()));
            }
            return stack;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- Value rendering ----

    private VarSnapshot toVarSnapshot(String name, String typeName,
                                      com.sun.jdi.Value v, String scope) {
        boolean isNull = (v == null);
        return new VarSnapshot(name, typeName, renderValue(v), scope, isNull, objectId(v));
    }

    private String renderValue(com.sun.jdi.Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference sr) {
            String s = sr.value();
            if (s.length() > MAX_STRING_LEN) s = s.substring(0, MAX_STRING_LEN) + "\u2026";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                           .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (v instanceof BooleanValue bv) return String.valueOf(bv.value());
        if (v instanceof ByteValue bv) return String.valueOf(bv.value());
        if (v instanceof CharValue cv) return "'" + cv.value() + "'";
        if (v instanceof DoubleValue dv) return String.valueOf(dv.value());
        if (v instanceof FloatValue fv) return String.valueOf(fv.value());
        if (v instanceof IntegerValue iv) return String.valueOf(iv.value());
        if (v instanceof LongValue lv) return String.valueOf(lv.value());
        if (v instanceof ShortValue sv) return String.valueOf(sv.value());
        if (v instanceof ArrayReference ar) {
            int len = ar.length();
            if (len == 0) return "[]";
            StringBuilder sb = new StringBuilder("[");
            int show = Math.min(len, MAX_ARRAY_ELEMS);
            for (int i = 0; i < show; i++) {
                if (i > 0) sb.append(", ");
                try { sb.append(renderValue(ar.getValue(i))); } catch (Exception e) { sb.append("?"); }
            }
            if (len > MAX_ARRAY_ELEMS) sb.append(", \u2026 (").append(len - MAX_ARRAY_ELEMS).append(" more)");
            sb.append("]");
            return sb.toString();
        }
        if (v instanceof ObjectReference or) {
            String typeName = or.type().name();
            int dot = typeName.lastIndexOf('.');
            String simpleName = dot >= 0 ? typeName.substring(dot + 1) : typeName;
            return simpleName + "@" + or.uniqueID();
        }
        return v.toString();
    }

    private String objectId(com.sun.jdi.Value v) {
        if (v instanceof ObjectReference or
                && !(v instanceof StringReference)
                && !(v instanceof ArrayReference)) {
            return String.valueOf(or.uniqueID());
        }
        return null;
    }

    // ---- Helpers ----

    private String currentStdout(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            return s.length() > MAX_STDOUT_CHARS
                    ? s.substring(0, MAX_STDOUT_CHARS) + "\n[output truncated]"
                    : s;
        }
    }

    private XRayResult error(String status, String message,
                             String compileOutput, List<StepSnapshot> steps) {
        return new XRayResult(status, message, compileOutput,
                steps != null ? steps : List.of(), Map.of());
    }

    static String detectPublicClass(String code) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+(?:class|enum|interface|record)\\s+(\\w+)")
                .matcher(code);
        return m.find() ? m.group(1) : null;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private synchronized boolean allowRun(String clientIp) {
        if (clientIp == null) clientIp = "unknown";
        long now = System.currentTimeMillis();
        Deque<Long> times = recentXRayRuns.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
        if (times.size() >= XRAY_RUNS_PER_MINUTE) return false;
        times.addLast(now);
        return true;
    }

    private static String sanitize(String msg) {
        if (msg == null) return "unknown error";
        return msg.replaceAll("(?i)[a-z]:\\\\[^\\s]+", "[path]")
                  .replaceAll("/[^\\s]+/xray-[^\\s]+", "[path]");
    }

    private static void deleteTree(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
