package com.javalearn;

import java.util.List;
import java.util.Map;

/**
 * Data models for the Java X-Ray execution visualizer.
 * These records are serialized to JSON and sent to the browser.
 */
public final class XRayModels {

    private XRayModels() {}

    /** One recorded variable at a specific execution step. */
    public record VarSnapshot(
            String name,
            String type,
            String value,
            String scope,       // "local", "parameter", "field", "static"
            boolean isNull,
            String objectId     // non-null when this var refers to a heap object
    ) {}

    /** One field of a heap object. */
    public record FieldSnapshot(
            String name,
            String type,
            String value,
            boolean isNull,
            String objectId
    ) {}

    /** A heap object (instance) captured at one step. */
    public record ObjectSnapshot(
            String objectId,
            String typeName,
            List<FieldSnapshot> fields
    ) {}

    /** One frame in the call stack at a step. */
    public record FrameSnapshot(
            String className,
            String methodName,
            int lineNumber,
            boolean isNative
    ) {}

    /** The complete state of the program at one execution event. */
    public record StepSnapshot(
            int stepIndex,
            int lineNumber,          // 1-based source line
            String eventKind,        // "line", "method_entry", "method_exit", "exception", "start", "end"
            String className,
            String methodName,
            List<VarSnapshot> locals,
            List<ObjectSnapshot> objects,
            List<FrameSnapshot> callStack,
            String stdoutSoFar,      // program output accumulated up to this step (truncated at limit)
            String note              // explanation when info is missing or restricted
    ) {}

    /** Request sent by the browser to the X-Ray endpoint. */
    public record XRayRequest(String code) {}

    /** Response from the X-Ray endpoint. */
    public record XRayResult(
            String status,          // "ok", "compile_error", "timeout", "error", "unavailable"
            String message,
            String compileOutput,
            List<StepSnapshot> steps,
            Map<Integer, List<Integer>> lineToSteps  // source line -> list of step indices that hit it
    ) {}
}
