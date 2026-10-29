package com.javalearn;

import java.util.List;

/**
 * The small data classes of JavaLearn, grouped in one file.
 * They are Java "records": simple classes that only hold data.
 */
public final class Models {

    private Models() {
        // only a container for the records below
    }

    /** One lesson. The HTML text of the lesson is stored in "content". */
    public record Lesson(int number, String slug, String title, String stage, String summary, String content) {
    }

    /**
     * One project folder found in the GitHub repository.
     * Fields such as description or difficulty are null / empty when the README does not contain them.
     */
    public record Project(
            String folderName,      // exact folder name on GitHub, e.g. "Banking-System"
            String displayName,     // folder name made easier to read, e.g. "Banking System"
            String description,     // from README, or null
            String difficulty,      // from README, or null
            List<String> concepts,  // from README, may be empty
            List<String> javaFiles, // full repository paths of EVERY .java file in the folder
            boolean hasReadme,
            String githubUrl,       // link to the folder on GitHub
            String lastUpdated) {   // ISO date of the newest commit touching the folder, or null when unknown
    }

    /** A Java file listed on the project page (no code yet: the code is loaded when the visitor opens it). */
    public record ProjectFile(String path, String name, String dir, int index) {
    }

    /** The files of one directory inside a project, for the grouped file list. */
    public record FileGroup(String dir, List<ProjectFile> files) {
    }

    /** One Java file with its source code (sent to the browser as JSON). */
    public record SourceFile(String path, String code, String githubUrl) {
    }

    /** What the browser sends to the code playground. */
    public record RunRequest(String code, String stdin) {
    }

    /**
     * What the playground sends back. status is one of:
     * "success", "compile_error", "runtime_error", "timeout", "unavailable", "error".
     */
    public record RunResult(String status, String message, String stdout, String stderr,
                            String compileOutput, String time, Integer memoryKb, String language) {
    }
}
