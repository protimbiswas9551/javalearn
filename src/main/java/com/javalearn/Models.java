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
            List<String> javaFiles, // full repository paths of every .java file in the folder
            boolean hasReadme,
            String githubUrl) {     // link to the folder on GitHub
    }

    /** One Java file of a project, with its source code, ready to show on the page. */
    public record SourceFile(String name, String code, String githubUrl) {
    }
}
