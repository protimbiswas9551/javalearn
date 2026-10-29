package com.javalearn;

import com.fasterxml.jackson.databind.JsonNode;
import com.javalearn.Models.Lesson;
import com.javalearn.Models.Project;
import com.javalearn.Models.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All the logic of JavaLearn, in three parts:
 *  1. Lessons     : the list of 20 lessons and their text (the text is at the bottom of this file)
 *  2. GitHub      : the only code that talks to GitHub, and it remembers the project list for 15 minutes
 *  3. README      : reads description, difficulty and concepts out of a README.md
 *
 * How the GitHub part works:
 *  - ONE call to the GitHub REST API ("Git Trees" endpoint) returns the list of every file in the repository.
 *  - From that list we find the project folders, their .java files and their README.md.
 *  - README.md and .java files are downloaded as plain text from raw.githubusercontent.com.
 */
@org.springframework.stereotype.Service
public class Service {

    /** Thrown when GitHub cannot be reached or returns an error. The message is safe to show to visitors. */
    public static class GitHubException extends RuntimeException {
        public GitHubException(String message) {
            super(message);
        }
    }

    // ==================================================================
    // 1. LESSONS
    // ==================================================================

    private static final String BASICS = "Java Basics";
    private static final String OOP = "Object-Oriented Programming";
    private static final String MORE = "Working with Data";

    private final List<Lesson> lessons = new ArrayList<>();

    private void addLessons() {
        add("introduction", "Introduction", BASICS, "What Java is, and how to write, compile and run your first program.", INTRODUCTION);
        add("variables-and-data-types", "Variables & Data Types", BASICS, "Storing values in named boxes, and the types of values Java knows.", VARIABLES_AND_DATA_TYPES);
        add("operators", "Operators", BASICS, "Doing maths, comparing values and combining conditions.", OPERATORS);
        add("input-output", "Input/Output", BASICS, "Printing results and reading what the user types.", INPUT_OUTPUT);
        add("conditions", "Conditions", BASICS, "Making decisions with if, else and switch.", CONDITIONS);
        add("loops", "Loops", BASICS, "Repeating work with for, while and do-while.", LOOPS);
        add("arrays", "Arrays", BASICS, "Keeping many values of the same type together.", ARRAYS);
        add("strings", "Strings", BASICS, "Working with text and the most useful String methods.", STRINGS);
        add("methods", "Methods", BASICS, "Giving a block of code a name so it can be reused.", METHODS);
        add("classes-and-objects", "Classes & Objects", OOP, "Describing real-world things with fields and methods.", CLASSES_AND_OBJECTS);
        add("constructors", "Constructors", OOP, "Setting up an object at the moment it is created.", CONSTRUCTORS);
        add("encapsulation", "Encapsulation", OOP, "Hiding data with private fields and giving controlled access.", ENCAPSULATION);
        add("inheritance", "Inheritance", OOP, "Building a new class from an existing one with extends.", INHERITANCE);
        add("polymorphism", "Polymorphism", OOP, "One method name, many behaviours.", POLYMORPHISM);
        add("abstraction", "Abstraction", OOP, "Showing what an object does and hiding how, using abstract classes.", ABSTRACTION);
        add("interfaces", "Interfaces", OOP, "A contract that different classes can promise to follow.", INTERFACES);
        add("exception-handling", "Exception Handling", MORE, "Dealing with errors without crashing the program.", EXCEPTION_HANDLING);
        add("collections", "Collections", MORE, "ArrayList, HashMap and HashSet: flexible ways to store data.", COLLECTIONS);
        add("file-handling", "File Handling", MORE, "Writing text to files and reading it back.", FILE_HANDLING);
        add("jdbc", "JDBC", MORE, "Connecting a Java program to a database.", JDBC);
    }

    private void add(String slug, String title, String stage, String summary, String content) {
        lessons.add(new Lesson(lessons.size() + 1, slug, title, stage, summary, content));
    }

    public List<Lesson> getAllLessons() {
        return Collections.unmodifiableList(lessons);
    }

    /** Lessons grouped by stage, in the same order as they were added. */
    public Map<String, List<Lesson>> getLessonsByStage() {
        Map<String, List<Lesson>> stages = new LinkedHashMap<>();
        for (Lesson lesson : lessons) {
            stages.computeIfAbsent(lesson.stage(), key -> new ArrayList<>()).add(lesson);
        }
        return stages;
    }

    public Optional<Lesson> findLesson(String slug) {
        return lessons.stream().filter(lesson -> lesson.slug().equals(slug)).findFirst();
    }

    public Optional<Lesson> previousOf(Lesson lesson) {
        int index = lesson.number() - 1; // position of this lesson in the list
        return index > 0 ? Optional.of(lessons.get(index - 1)) : Optional.empty();
    }

    public Optional<Lesson> nextOf(Lesson lesson) {
        int index = lesson.number() - 1;
        return index < lessons.size() - 1 ? Optional.of(lessons.get(index + 1)) : Optional.empty();
    }

    // ==================================================================
    // 2. GITHUB
    // ==================================================================

    private static final Duration KEEP_FOR = Duration.ofMinutes(15);
    private static final int MAX_FILES_SHOWN = 15;

    private final RestClient restClient;
    private final String owner;
    private final String repo;
    private final String branch;
    private final String projectsPath; // e.g. "Projects", or "" when projects are in the repository root

    // The remembered list of projects, and when we got it.
    private List<Project> cachedProjects;
    private Instant cachedAt;

    public Service(@Value("${github.owner}") String owner,
                   @Value("${github.repo}") String repo,
                   @Value("${github.branch}") String branch,
                   @Value("${github.projects-path:}") String projectsPath) {
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
        this.projectsPath = projectsPath.strip().replaceAll("^/+|/+$", ""); // remove slashes at both ends

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);  // give up connecting after 5 seconds
        requestFactory.setReadTimeout(15000);    // give up waiting for an answer after 15 seconds

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "JavaLearn") // GitHub requires a User-Agent
                .build();

        addLessons();
    }

    public String getRepoUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** All projects in the repository (sorted by name). Reloaded from GitHub when the saved list is old. */
    public synchronized List<Project> getProjects() {
        boolean listIsFresh = cachedProjects != null
                && Duration.between(cachedAt, Instant.now()).compareTo(KEEP_FOR) < 0;
        if (!listIsFresh) {
            cachedProjects = loadProjectsFromGitHub();
            cachedAt = Instant.now();
        }
        return cachedProjects;
    }

    /** Finds one project by its folder name. Only names that really exist in the repository are accepted. */
    public Optional<Project> findProject(String folderName) {
        return getProjects().stream()
                .filter(project -> project.folderName().equals(folderName))
                .findFirst();
    }

    /** Downloads the source code of the project's Java files (at most MAX_FILES_SHOWN files). */
    public List<SourceFile> getSourceFiles(Project project) {
        String folderPath = pathPrefix() + project.folderName() + "/";
        List<SourceFile> files = new ArrayList<>();

        for (String path : project.javaFiles()) {
            if (files.size() == MAX_FILES_SHOWN) {
                break;
            }
            String code;
            try {
                code = downloadText(path);
            } catch (GitHubException e) {
                // One broken file should not break the whole page.
                code = "// This file could not be loaded from GitHub.\n// " + e.getMessage();
            }
            String shortName = path.substring(folderPath.length()); // e.g. "src/Main.java"
            files.add(new SourceFile(shortName, code, githubUrl("blob", path)));
        }
        return files;
    }

    private List<Project> loadProjectsFromGitHub() {
        // Step 1: one API call gives us the path of every file in the repository.
        JsonNode response = callGitHubApi();
        List<String> allFiles = new ArrayList<>();
        for (JsonNode item : response.path("tree")) {
            if ("blob".equals(item.path("type").asText())) { // "blob" = file, "tree" = folder
                allFiles.add(item.path("path").asText());
            }
        }

        // Step 2: group the .java files by project folder.
        // Example path: "Projects/Banking-System/src/Main.java" -> folder "Banking-System"
        String prefix = pathPrefix();
        Map<String, List<String>> javaFilesByFolder = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String path : allFiles) {
            if (!path.startsWith(prefix) || !path.endsWith(".java")) {
                continue;
            }
            String insideBase = path.substring(prefix.length());
            int slash = insideBase.indexOf('/');
            if (slash <= 0) {
                continue; // a file directly in the base folder is not inside a project folder
            }
            String folder = insideBase.substring(0, slash);
            if (folder.startsWith(".")) {
                continue; // hidden folders such as .github or .idea
            }
            javaFilesByFolder.computeIfAbsent(folder, key -> new ArrayList<>()).add(path);
        }

        // Step 3: remember where each README.md is (README.md / readme.md both work).
        Map<String, String> readmePaths = new HashMap<>();
        for (String path : allFiles) {
            if (path.toLowerCase().endsWith("/readme.md")) {
                readmePaths.put(path.toLowerCase(), path);
            }
        }

        // Step 4: download all READMEs. A small group of helper threads does this at the same time,
        // otherwise a repository with many projects would take a long time to load.
        List<Project> projects = new ArrayList<>();
        try (ExecutorService helpers = Executors.newFixedThreadPool(8)) {
            List<Future<Project>> waiting = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : javaFilesByFolder.entrySet()) {
                String folder = entry.getKey();
                List<String> javaFiles = entry.getValue();
                String readmePath = readmePaths.get((prefix + folder + "/readme.md").toLowerCase());
                waiting.add(helpers.submit(() -> buildProject(folder, javaFiles, readmePath)));
            }
            for (Future<Project> future : waiting) {
                projects.add(future.get());
            }
        } catch (Exception e) {
            throw new GitHubException("Could not read the project READMEs from GitHub.");
        }
        return projects;
    }

    /** Creates one Project object. Works even when the README is missing. */
    private Project buildProject(String folder, List<String> javaFiles, String readmePath) {
        String readme = null;
        if (readmePath != null) {
            try {
                readme = downloadText(readmePath);
            } catch (GitHubException e) {
                readme = null; // treat an unreadable README like a missing one
            }
        }
        return new Project(
                folder,
                folder.replaceAll("[-_]+", " "),
                findDescription(readme),
                findDifficulty(readme),
                findConcepts(readme),
                javaFiles.stream().sorted().toList(),
                readme != null,
                githubUrl("tree", pathPrefix() + folder));
    }

    /** Calls the GitHub REST API: GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1 */
    private JsonNode callGitHubApi() {
        try {
            JsonNode response = restClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=1",
                            owner, repo, branch)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new GitHubException("GitHub sent an empty answer. Please try again later.");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw new GitHubException(explainStatus(e.getStatusCode().value()));
        } catch (RestClientException e) {
            throw new GitHubException("Could not connect to GitHub. Please check the internet connection and try again.");
        }
    }

    /** Downloads one text file (README or .java) from raw.githubusercontent.com. */
    private String downloadText(String path) {
        String url = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/"
                + UriUtils.encodePath(path, StandardCharsets.UTF_8); // turns spaces into %20 etc.
        try {
            byte[] bytes = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw new GitHubException(explainStatus(e.getStatusCode().value()));
        } catch (RestClientException e) {
            throw new GitHubException("Could not connect to GitHub.");
        }
    }

    private String explainStatus(int status) {
        if (status == 404) {
            return "The GitHub repository or file was not found. Check github.owner, github.repo and github.branch in application.properties.";
        }
        if (status == 403 || status == 429) {
            return "GitHub is limiting requests right now. Please try again in a few minutes.";
        }
        return "GitHub returned an error (HTTP " + status + "). Please try again later.";
    }

    /** "Projects/" when a base folder is set, otherwise "" */
    private String pathPrefix() {
        return projectsPath.isEmpty() ? "" : projectsPath + "/";
    }

    /** Builds a link like https://github.com/owner/repo/tree/HEAD/Projects/My%20Project */
    private String githubUrl(String type, String path) {
        return getRepoUrl() + "/" + type + "/" + branch + "/" + UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 3. README PARSER
    // Nothing is invented: when a fact is not in the README, null (or an empty list) is returned.
    //  - Description : the first normal paragraph of text
    //  - Difficulty  : the word "Difficulty" followed by a level, e.g. "**Difficulty:** Beginner"
    //  - Concepts    : bullet points under a heading that contains "Concept" or "Feature"
    // ==================================================================

    /** Known difficulty words, from easiest to hardest. Also used to order the filter list. */
    public static final List<String> DIFFICULTY_LEVELS =
            List.of("Beginner", "Easy", "Intermediate", "Medium", "Advanced", "Hard");

    private static final Pattern DIFFICULTY = Pattern.compile(
            "difficulty\\W{0,10}(" + String.join("|", DIFFICULTY_LEVELS) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** A bullet ("- item", "* item", "+ item") or numbered ("1. item") list line. */
    private static final Pattern LIST_ITEM = Pattern.compile("^([-*+]|\\d+\\.)\\s+(.*)$");

    private static String findDifficulty(String readme) {
        if (readme == null) {
            return null;
        }
        Matcher matcher = DIFFICULTY.matcher(readme);
        if (!matcher.find()) {
            return null;
        }
        String word = matcher.group(1).toLowerCase();
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String findDescription(String readme) {
        if (readme == null) {
            return null;
        }
        StringBuilder paragraph = new StringBuilder();
        boolean insideCodeBlock = false;

        for (String rawLine : readme.split("\\R")) {
            String line = rawLine.strip();

            if (line.startsWith("```")) {
                insideCodeBlock = !insideCodeBlock;
            }
            boolean isNormalText = !insideCodeBlock
                    && !line.isEmpty()
                    && !line.startsWith("```")
                    && !line.startsWith("#")     // headings
                    && !line.startsWith("!")     // images
                    && !line.startsWith("[!")    // badges
                    && !line.startsWith("<")     // html tags
                    && !line.startsWith(">")     // quotes
                    && !line.startsWith("|")     // tables
                    && !LIST_ITEM.matcher(line).matches()
                    && !line.toLowerCase().startsWith("difficulty")
                    && !line.matches("^[-=_*]{3,}$"); // horizontal lines

            if (isNormalText) {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            } else if (!paragraph.isEmpty()) {
                break; // the first paragraph is finished
            }
        }
        String text = cleanMarkdown(paragraph.toString());
        return text.isEmpty() ? null : text;
    }

    private static List<String> findConcepts(String readme) {
        List<String> concepts = new ArrayList<>();
        if (readme == null) {
            return concepts;
        }
        boolean insideWantedSection = false;

        for (String rawLine : readme.split("\\R")) {
            String line = rawLine.strip();

            if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#+\\s*", "").toLowerCase();
                insideWantedSection = heading.contains("concept") || heading.contains("feature");
                continue;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (insideWantedSection && item.matches()) {
                String text = cleanMarkdown(item.group(2));
                if (!text.isEmpty()) {
                    concepts.add(text);
                }
            }
        }
        return concepts;
    }

    /** Removes simple Markdown symbols so the text can be shown as plain text. */
    private static String cleanMarkdown(String text) {
        return text
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1") // [text](link) -> text
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .strip();
    }

    // ==================================================================
    // LESSON TEXT (HTML). Each lesson has: Explanation, Syntax, Examples, Important Points.
    // To add a lesson: add one add(...) line in addLessons() and one text block below.
    // ==================================================================

    private static final String INTRODUCTION = """
<h2>Explanation</h2>
<p>Java is a general-purpose programming language used for desktop programs, web servers, Android apps and much more. You write source code in a <code>.java</code> file, the <strong>compiler</strong> (<code>javac</code>) turns it into <strong>bytecode</strong>, and the <strong>Java Virtual Machine (JVM)</strong> runs that bytecode. Because of the JVM, the same compiled program can run on Windows, Linux and macOS.</p>
<p>To write Java you install the <strong>JDK</strong> (Java Development Kit). It contains the compiler, the JVM and the standard libraries. Every Java program starts running from a method called <code>main</code>.</p>
<p>To try the example below: save it as <code>HelloWorld.java</code>, compile it with <code>javac HelloWorld.java</code>, then run it with <code>java HelloWorld</code>.</p>
<h2>Syntax</h2>
<pre><code>public class ClassName {
    public static void main(String[] args) {
        // statements go here
    }
}</code></pre>
<h2>Examples</h2>
<h3>Your first program</h3>
<pre><code>public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Hello, World!</code></pre>
<h3>Comments</h3>
<pre><code>public class CommentsDemo {
    public static void main(String[] args) {
        // This is a single-line comment
        /* This is a
           multi-line comment */
        System.out.println("Comments are ignored by the compiler.");
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Comments are ignored by the compiler.</code></pre>
<h2>Important Points</h2>
<ul>
<li>The file name must match the name of the <code>public</code> class: <code>HelloWorld</code> is saved as <code>HelloWorld.java</code>.</li>
<li>Java is <strong>case-sensitive</strong>: <code>Main</code> and <code>main</code> are different names.</li>
<li>Every statement ends with a semicolon <code>;</code>.</li>
<li>Code blocks are wrapped in curly braces <code>{ }</code>.</li>
<li>Some later examples show only the statements that go inside <code>main</code>; put them inside the method shown above to run them.</li>
</ul>
""";

    private static final String VARIABLES_AND_DATA_TYPES = """
<h2>Explanation</h2>
<p>A <strong>variable</strong> is a named place in memory that stores a value. In Java every variable has a <strong>data type</strong> that tells what kind of value it can hold.</p>
<p>Java has <strong>primitive types</strong> for simple values: <code>byte</code>, <code>short</code>, <code>int</code>, <code>long</code> (whole numbers), <code>float</code>, <code>double</code> (decimal numbers), <code>char</code> (one character) and <code>boolean</code> (true or false). <code>String</code> is not primitive; it is a class used for text.</p>
<p>Use <code>int</code> for most whole numbers and <code>double</code> for most decimal numbers. Add the keyword <code>final</code> to make a value that can never change (a constant).</p>
<h2>Syntax</h2>
<pre><code>dataType variableName = value;

int age = 20;
final double PI = 3.14159;   // constant</code></pre>
<h2>Examples</h2>
<h3>Declaring and printing variables</h3>
<pre><code>public class Variables {
    public static void main(String[] args) {
        int age = 20;
        double price = 99.5;
        char grade = 'A';
        boolean passed = true;
        String name = "Riya";

        System.out.println(name + " is " + age + " years old.");
        System.out.println("Grade: " + grade + ", Passed: " + passed);
        System.out.println("Price: " + price);
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Riya is 20 years old.
Grade: A, Passed: true
Price: 99.5</code></pre>
<h3>Type casting</h3>
<pre><code>public class CastingDemo {
    public static void main(String[] args) {
        int a = 10;
        double b = a;          // widening: automatic
        double pi = 3.99;
        int c = (int) pi;      // narrowing: written by you, decimals are cut off

        System.out.println(b);
        System.out.println(c);
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>10.0
3</code></pre>
<h2>Important Points</h2>
<ul>
<li>A variable must be declared with a type before it is used.</li>
<li>Variable names start with a letter, <code>_</code> or <code>$</code>, and by convention use <code>camelCase</code> (for example <code>studentName</code>).</li>
<li><code>char</code> uses single quotes <code>'A'</code>; <code>String</code> uses double quotes <code>"Hello"</code>.</li>
<li>A <code>long</code> value needs an <code>L</code> at the end (<code>5000000000L</code>) and a <code>float</code> needs an <code>f</code> (<code>2.5f</code>).</li>
<li>Local variables have no default value; you must give them a value before you use them.</li>
</ul>
""";

    private static final String OPERATORS = """
<h2>Explanation</h2>
<p>Operators perform work on values. <strong>Arithmetic</strong> operators do maths (<code>+ - * / %</code>). <strong>Relational</strong> operators compare values (<code>== != &lt; &gt; &lt;= &gt;=</code>) and give <code>true</code> or <code>false</code>. <strong>Logical</strong> operators combine conditions (<code>&amp;&amp;</code> and, <code>||</code> or, <code>!</code> not).</p>
<p><strong>Assignment</strong> operators store values (<code>=</code>, <code>+=</code>, <code>-=</code>). <code>++</code> and <code>--</code> add or subtract 1. The <strong>ternary</strong> operator <code>? :</code> is a short form of if-else.</p>
<h2>Syntax</h2>
<pre><code>result = a + b;          // arithmetic
isTrue  = a &gt; b;          // relational
both    = x &amp;&amp; y;         // logical
a += 5;                   // same as a = a + 5
a++;                      // same as a = a + 1
text = (a &gt; b) ? "yes" : "no";   // ternary</code></pre>
<h2>Examples</h2>
<h3>Arithmetic</h3>
<pre><code>int a = 17, b = 5;
System.out.println(a + b);
System.out.println(a - b);
System.out.println(a * b);
System.out.println(a / b);
System.out.println(a % b);
System.out.println(17 / 5.0);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>22
12
85
3
2
3.4</code></pre>
<h3>Relational and logical</h3>
<pre><code>int marks = 72;
boolean passed = marks &gt;= 40;
boolean distinction = marks &gt;= 75;

System.out.println(passed);
System.out.println(distinction);
System.out.println(passed &amp;&amp; !distinction);
System.out.println(passed || distinction);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>true
false
true
true</code></pre>
<h3>Assignment, increment and ternary</h3>
<pre><code>int x = 5;
x++;          // 6
x += 4;       // 10
String size = (x &gt; 8) ? "big" : "small";

System.out.println(x);
System.out.println(size);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>10
big</code></pre>
<h2>Important Points</h2>
<ul>
<li>Dividing two <code>int</code> values gives an <code>int</code>: <code>17 / 5</code> is <code>3</code>, not 3.4. Make one side a <code>double</code> to keep the decimals.</li>
<li><code>%</code> gives the remainder of a division. <code>x % 2 == 0</code> is a common check for an even number.</li>
<li>Use <code>==</code> to compare values and <code>=</code> to assign. Mixing them up is a common mistake.</li>
<li><code>&amp;&amp;</code> and <code>||</code> stop early: if the answer is already known, the right side is not evaluated.</li>
</ul>
""";

    private static final String INPUT_OUTPUT = """
<h2>Explanation</h2>
<p><strong>Output</strong> is printed with <code>System.out</code>. <code>println</code> prints and moves to a new line, <code>print</code> stays on the same line, and <code>printf</code> prints formatted text using placeholders such as <code>%d</code> (whole number), <code>%.2f</code> (decimal with 2 digits) and <code>%s</code> (text).</p>
<p><strong>Input</strong> from the keyboard is read with the <code>Scanner</code> class from <code>java.util</code>. Create a Scanner for <code>System.in</code>, then call methods such as <code>nextInt()</code>, <code>nextDouble()</code> and <code>nextLine()</code>.</p>
<h2>Syntax</h2>
<pre><code>System.out.println(value);
System.out.printf("format", values);

Scanner sc = new Scanner(System.in);
int number  = sc.nextInt();
String line = sc.nextLine();</code></pre>
<h2>Examples</h2>
<h3>Reading a name and an age</h3>
<pre><code>import java.util.Scanner;

public class InputDemo {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        System.out.print("Enter your name: ");
        String name = sc.nextLine();

        System.out.print("Enter your age: ");
        int age = sc.nextInt();

        System.out.println("Hello " + name + ", you are " + age + " years old.");
        sc.close();
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Enter your name: Amit
Enter your age: 21
Hello Amit, you are 21 years old.</code></pre>
<h3>Formatted output with printf</h3>
<pre><code>System.out.printf("Total: %d items, price %.2f%n", 3, 149.5);
System.out.printf("Name: %s%n", "Java");</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Total: 3 items, price 149.50
Name: Java</code></pre>
<h2>Important Points</h2>
<ul>
<li>Add <code>import java.util.Scanner;</code> at the top of the file.</li>
<li><code>%n</code> in <code>printf</code> means a new line.</li>
<li>If you call <code>nextInt()</code> and then <code>nextLine()</code>, the <code>nextLine()</code> reads the leftover Enter key. Call <code>sc.nextLine()</code> once extra to clear it.</li>
<li>Close the Scanner with <code>close()</code> when you are done.</li>
</ul>
""";

    private static final String CONDITIONS = """
<h2>Explanation</h2>
<p>Conditions let a program choose what to do. <code>if</code> runs a block only when a condition is <code>true</code>. Add <code>else</code> for the other case and <code>else if</code> for extra cases. A <code>switch</code> compares one value against many fixed choices and is neater than a long chain of <code>else if</code>.</p>
<h2>Syntax</h2>
<pre><code>if (condition) {
    // runs when condition is true
} else if (otherCondition) {
    // runs when otherCondition is true
} else {
    // runs in all other cases
}

switch (value) {
    case 1: /* ... */ break;
    default: /* ... */
}</code></pre>
<h2>Examples</h2>
<h3>if / else if / else</h3>
<pre><code>int marks = 72;
String grade;

if (marks &gt;= 90) {
    grade = "A";
} else if (marks &gt;= 75) {
    grade = "B";
} else if (marks &gt;= 60) {
    grade = "C";
} else if (marks &gt;= 40) {
    grade = "D";
} else {
    grade = "Fail";
}
System.out.println("Grade: " + grade);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Grade: C</code></pre>
<h3>switch statement</h3>
<pre><code>int day = 3;

switch (day) {
    case 1:
        System.out.println("Monday");
        break;
    case 2:
        System.out.println("Tuesday");
        break;
    case 3:
        System.out.println("Wednesday");
        break;
    default:
        System.out.println("Unknown day");
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Wednesday</code></pre>
<h3>Modern switch with arrows (Java 14+)</h3>
<pre><code>int day = 3;

String name = switch (day) {
    case 1 -&gt; "Monday";
    case 2 -&gt; "Tuesday";
    case 3 -&gt; "Wednesday";
    default -&gt; "Unknown day";
};
System.out.println(name);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Wednesday</code></pre>
<h2>Important Points</h2>
<ul>
<li>The condition inside <code>if ( )</code> must be a <code>boolean</code>.</li>
<li>Only the first matching branch of an if / else if chain runs, so order the checks carefully (highest first in the grade example).</li>
<li>In the old <code>switch</code> style, forgetting <code>break</code> makes the program continue into the next case (called fall-through).</li>
<li>The arrow style of <code>switch</code> does not fall through, so <code>break</code> is not needed.</li>
</ul>
""";

    private static final String LOOPS = """
<h2>Explanation</h2>
<p>A loop repeats a block of code. Use <code>for</code> when you know how many times to repeat, <code>while</code> when you repeat until a condition becomes false, and <code>do-while</code> when the code must run at least once. <code>break</code> leaves a loop early and <code>continue</code> skips to the next round.</p>
<h2>Syntax</h2>
<pre><code>for (initialise; condition; update) {
    // body
}

while (condition) {
    // body
}

do {
    // body
} while (condition);</code></pre>
<h2>Examples</h2>
<h3>for loop: sum of 1 to 5</h3>
<pre><code>int sum = 0;
for (int i = 1; i &lt;= 5; i++) {
    sum += i;
}
System.out.println("Sum = " + sum);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Sum = 15</code></pre>
<h3>while and do-while</h3>
<pre><code>int count = 3;
while (count &gt; 0) {
    System.out.println(count);
    count--;
}

int n = 10;
do {
    System.out.println("Runs once, n = " + n);
} while (n &lt; 5);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>3
2
1
Runs once, n = 10</code></pre>
<h3>break and continue</h3>
<pre><code>for (int i = 1; i &lt;= 6; i++) {
    if (i == 2) continue;   // skip 2
    if (i == 5) break;      // stop at 5
    System.out.println(i);
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>1
3
4</code></pre>
<h3>Nested loops: a triangle</h3>
<pre><code>for (int i = 1; i &lt;= 3; i++) {
    for (int j = 1; j &lt;= i; j++) {
        System.out.print("*");
    }
    System.out.println();
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>*
**
***</code></pre>
<h2>Important Points</h2>
<ul>
<li>A loop that never becomes false runs forever (an infinite loop). Make sure the variable in the condition changes.</li>
<li>The variable declared in a <code>for</code> header (<code>int i</code>) exists only inside that loop.</li>
<li>A <code>while</code> loop may run zero times; a <code>do-while</code> loop always runs at least once.</li>
<li>In nested loops, the inner loop finishes completely for each round of the outer loop.</li>
</ul>
""";

    private static final String ARRAYS = """
<h2>Explanation</h2>
<p>An <strong>array</strong> stores many values of the same type under one name. Each value has an <strong>index</strong> starting from <code>0</code>. The size is fixed when the array is created, and <code>length</code> tells the size. A <strong>2D array</strong> is an array of arrays, like a table with rows and columns.</p>
<h2>Syntax</h2>
<pre><code>int[] numbers = new int[5];          // 5 zeros
int[] marks = {80, 65, 90};          // create with values
int first = marks[0];                // read
marks[1] = 70;                       // change
int size = marks.length;             // size

int[][] table = new int[2][3];       // 2 rows, 3 columns</code></pre>
<h2>Examples</h2>
<h3>Total and average</h3>
<pre><code>int[] marks = {80, 65, 90, 75};
int total = 0;

for (int i = 0; i &lt; marks.length; i++) {
    total += marks[i];
}
double average = (double) total / marks.length;

System.out.println("Total: " + total);
System.out.println("Average: " + average);</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Total: 310
Average: 77.5</code></pre>
<h3>Enhanced for loop and Arrays class</h3>
<pre><code>import java.util.Arrays;

public class ArrayDemo {
    public static void main(String[] args) {
        int[] nums = {5, 2, 9, 1};
        Arrays.sort(nums);
        System.out.println(Arrays.toString(nums));

        for (int n : nums) {
            System.out.print(n + " ");
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>[1, 2, 5, 9]
1 2 5 9 </code></pre>
<h3>2D array</h3>
<pre><code>int[][] matrix = {
    {1, 2, 3},
    {4, 5, 6}
};

for (int row = 0; row &lt; matrix.length; row++) {
    for (int col = 0; col &lt; matrix[row].length; col++) {
        System.out.print(matrix[row][col] + " ");
    }
    System.out.println();
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>1 2 3 
4 5 6 </code></pre>
<h2>Important Points</h2>
<ul>
<li>Valid indexes are <code>0</code> to <code>length - 1</code>. Using any other index throws <code>ArrayIndexOutOfBoundsException</code>.</li>
<li>Use <code>length</code> (no brackets) for arrays, but <code>length()</code> for Strings.</li>
<li>Printing an array directly shows a strange address; use <code>Arrays.toString(array)</code> instead.</li>
<li>If you need a list that can grow, use <code>ArrayList</code> (see the Collections lesson).</li>
</ul>
""";

    private static final String STRINGS = """
<h2>Explanation</h2>
<p>A <code>String</code> holds text. Strings are <strong>immutable</strong>: methods such as <code>toUpperCase()</code> do not change the original, they return a new String. Always compare the <em>content</em> of Strings with <code>equals()</code>, not with <code>==</code>.</p>
<p>When you need to build a long text by adding pieces many times, use <code>StringBuilder</code>, which changes the same object instead of creating new ones.</p>
<h2>Syntax</h2>
<pre><code>String s = "Hello";
s.length();            s.charAt(0);
s.substring(1, 3);     s.indexOf("l");
s.toUpperCase();       s.trim();
s.contains("ell");     s.replace("l", "L");
s.equals("Hello");     s.split(",");</code></pre>
<h2>Examples</h2>
<h3>Common String methods</h3>
<pre><code>String text = "  Java Programming  ";
String clean = text.trim();

System.out.println(clean.length());
System.out.println(clean.charAt(0));
System.out.println(clean.substring(0, 4));
System.out.println(clean.indexOf("Programming"));
System.out.println(clean.toUpperCase());
System.out.println(clean.contains("Program"));
System.out.println(clean.replace("Java", "Kotlin"));</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>16
J
Java
5
JAVA PROGRAMMING
true
Kotlin Programming</code></pre>
<h3>equals() versus ==</h3>
<pre><code>String a = "hello";
String b = new String("hello");

System.out.println(a == b);
System.out.println(a.equals(b));</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>false
true</code></pre>
<h3>split and StringBuilder</h3>
<pre><code>String csv = "apple,banana,mango";
String[] fruits = csv.split(",");
for (String fruit : fruits) {
    System.out.println(fruit);
}

StringBuilder sb = new StringBuilder();
sb.append("Java");
sb.append(" is fun");
System.out.println(sb.toString());</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>apple
banana
mango
Java is fun</code></pre>
<h2>Important Points</h2>
<ul>
<li>String indexes start at 0, like arrays.</li>
<li><code>==</code> checks whether two variables point to the same object; <code>equals()</code> checks whether the text is the same.</li>
<li><code>substring(start, end)</code> includes <code>start</code> but excludes <code>end</code>.</li>
<li>Use <code>equalsIgnoreCase()</code> to compare text without caring about upper or lower case.</li>
</ul>
""";

    private static final String METHODS = """
<h2>Explanation</h2>
<p>A <strong>method</strong> is a named block of code that can be called many times. It can receive values (<strong>parameters</strong>) and send a value back with <code>return</code>. A method that returns nothing uses the return type <code>void</code>.</p>
<p>Two methods can share a name if their parameters differ; this is called <strong>method overloading</strong>. A method can also call itself, which is called <strong>recursion</strong>.</p>
<h2>Syntax</h2>
<pre><code>returnType methodName(type param1, type param2) {
    // body
    return value;
}

static int add(int a, int b) {
    return a + b;
}</code></pre>
<h2>Examples</h2>
<h3>Methods and overloading</h3>
<pre><code>public class MethodDemo {

    static int add(int a, int b) {
        return a + b;
    }

    static double add(double a, double b) {   // overloaded
        return a + b;
    }

    static void greet(String name) {
        System.out.println("Hello, " + name + "!");
    }

    public static void main(String[] args) {
        greet("Java");
        System.out.println(add(2, 3));
        System.out.println(add(2.5, 3.5));
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Hello, Java!
5
6.0</code></pre>
<h3>Recursion: factorial</h3>
<pre><code>public class Factorial {

    static int factorial(int n) {
        if (n &lt;= 1) {
            return 1;               // stopping condition
        }
        return n * factorial(n - 1);
    }

    public static void main(String[] args) {
        System.out.println(factorial(5));
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>120</code></pre>
<h2>Important Points</h2>
<ul>
<li>Methods called from <code>main</code> without creating an object must be <code>static</code>.</li>
<li>Java passes arguments <strong>by value</strong>: a method gets a copy of a primitive, so changing it does not change the caller's variable.</li>
<li>Every recursive method needs a stopping condition, otherwise it ends in a <code>StackOverflowError</code>.</li>
<li>A method with a return type other than <code>void</code> must return a value on every path.</li>
</ul>
""";

    private static final String CLASSES_AND_OBJECTS = """
<h2>Explanation</h2>
<p>A <strong>class</strong> is a blueprint that describes a kind of thing. It has <strong>fields</strong> (the data it stores) and <strong>methods</strong> (what it can do). An <strong>object</strong> is one real thing created from the class with the keyword <code>new</code>. You can create many objects from one class, and each has its own copy of the fields.</p>
<h2>Syntax</h2>
<pre><code>class ClassName {
    // fields
    type fieldName;

    // methods
    void methodName() {
        // body
    }
}

ClassName obj = new ClassName();
obj.fieldName = value;
obj.methodName();</code></pre>
<h2>Examples</h2>
<h3>A Student class</h3>
<pre><code>class Student {
    String name;
    int marks;

    void display() {
        System.out.println(name + " scored " + marks);
    }
}

public class Main {
    public static void main(String[] args) {
        Student s1 = new Student();
        s1.name = "Asha";
        s1.marks = 85;

        Student s2 = new Student();
        s2.name = "Ravi";
        s2.marks = 72;

        s1.display();
        s2.display();
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Asha scored 85
Ravi scored 72</code></pre>
<h3>Objects are accessed through references</h3>
<pre><code>Student s1 = new Student();
s1.name = "Asha";
s1.marks = 85;

Student s3 = s1;      // s3 points to the SAME object
s3.marks = 90;

s1.display();</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Asha scored 90</code></pre>
<h2>Important Points</h2>
<ul>
<li>A file can contain several classes, but only one of them can be <code>public</code>, and it must match the file name.</li>
<li>The dot <code>.</code> is used to reach a field or method of an object.</li>
<li>A variable of a class type stores a <strong>reference</strong> to the object, not the object itself. Copying the variable copies the reference.</li>
<li>Fields get default values automatically: <code>0</code> for numbers, <code>false</code> for boolean, <code>null</code> for objects.</li>
</ul>
""";

    private static final String CONSTRUCTORS = """
<h2>Explanation</h2>
<p>A <strong>constructor</strong> is a special method that runs automatically when an object is created with <code>new</code>. It has the same name as the class and no return type. It is normally used to give the fields their first values.</p>
<p>If you write no constructor, Java gives the class an empty <strong>default constructor</strong>. You can write several constructors with different parameters (constructor overloading). Inside a constructor, <code>this</code> means the current object and helps when a parameter has the same name as a field.</p>
<h2>Syntax</h2>
<pre><code>class ClassName {
    ClassName() {
        // no-argument constructor
    }

    ClassName(type param) {
        this.field = param;
    }
}</code></pre>
<h2>Examples</h2>
<h3>Two constructors</h3>
<pre><code>class Book {
    String title;
    double price;

    Book() {
        title = "Unknown";
        price = 0;
    }

    Book(String title, double price) {
        this.title = title;
        this.price = price;
    }

    void show() {
        System.out.println(title + " - Rs. " + price);
    }
}

public class Main {
    public static void main(String[] args) {
        Book b1 = new Book();
        Book b2 = new Book("Java Basics", 350);
        b1.show();
        b2.show();
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Unknown - Rs. 0.0
Java Basics - Rs. 350.0</code></pre>
<h2>Important Points</h2>
<ul>
<li>A constructor has no return type, not even <code>void</code>.</li>
<li>Once you write any constructor yourself, Java no longer creates the default one. If you still need <code>new Book()</code>, write it yourself.</li>
<li>Without <code>this</code>, the line <code>title = title;</code> would assign the parameter to itself and leave the field unchanged.</li>
<li><code>this(...)</code> can call another constructor of the same class; <code>super(...)</code> calls the parent's constructor.</li>
</ul>
""";

    private static final String ENCAPSULATION = """
<h2>Explanation</h2>
<p><strong>Encapsulation</strong> means keeping the data of a class <code>private</code> and letting other classes reach it only through public methods. These are usually <strong>getters</strong> (read a value) and <strong>setters</strong> (change a value). The big benefit is control: the class can check a value before accepting it.</p>
<h2>Syntax</h2>
<pre><code>class ClassName {
    private type field;

    public type getField() {
        return field;
    }

    public void setField(type value) {
        this.field = value;   // add checks here if needed
    }
}</code></pre>
<h2>Examples</h2>
<h3>A safe bank account</h3>
<pre><code>class Account {
    private double balance;

    public double getBalance() {
        return balance;
    }

    public void deposit(double amount) {
        if (amount &gt; 0) {
            balance += amount;
        } else {
            System.out.println("Invalid amount");
        }
    }
}

public class Main {
    public static void main(String[] args) {
        Account acc = new Account();
        acc.deposit(500);
        acc.deposit(-50);
        System.out.println("Balance: " + acc.getBalance());
        // acc.balance = 1000;   // error: balance is private
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Invalid amount
Balance: 500.0</code></pre>
<h2>Important Points</h2>
<ul>
<li>Access modifiers: <code>private</code> (this class only), <code>public</code> (everywhere), <code>protected</code> (same package and subclasses), and no modifier (same package).</li>
<li>Not every private field needs a setter. A field with only a getter is read-only from outside.</li>
<li>Naming convention: <code>getName()</code> and <code>setName(...)</code>; for boolean fields, <code>isActive()</code>.</li>
<li>Validation inside setters and methods stops the object from ever holding invalid data.</li>
</ul>
""";

    private static final String INHERITANCE = """
<h2>Explanation</h2>
<p><strong>Inheritance</strong> lets a new class (the <strong>subclass</strong>) reuse the fields and methods of an existing class (the <strong>superclass</strong>). It is written with <code>extends</code> and models an &ldquo;is-a&rdquo; relationship: a Dog <em>is an</em> Animal.</p>
<p>A subclass can add new members and can <strong>override</strong> an inherited method to give its own version. <code>super</code> refers to the parent class; <code>super(...)</code> calls the parent's constructor.</p>
<h2>Syntax</h2>
<pre><code>class Parent {
    // fields and methods
}

class Child extends Parent {
    Child() {
        super();          // call the parent constructor
    }

    @Override
    void someMethod() { /* new behaviour */ }
}</code></pre>
<h2>Examples</h2>
<h3>Dog extends Animal</h3>
<pre><code>class Animal {
    String name;

    Animal(String name) {
        this.name = name;
    }

    void eat() {
        System.out.println(name + " is eating.");
    }
}

class Dog extends Animal {
    Dog(String name) {
        super(name);
    }

    void bark() {
        System.out.println(name + " says Woof!");
    }
}

public class Main {
    public static void main(String[] args) {
        Dog d = new Dog("Tommy");
        d.eat();     // inherited
        d.bark();    // own method
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Tommy is eating.
Tommy says Woof!</code></pre>
<h3>Overriding a method</h3>
<pre><code>class Animal {
    void sound() {
        System.out.println("Some generic sound");
    }
}

class Cat extends Animal {
    @Override
    void sound() {
        System.out.println("Meow");
    }
}

public class Main {
    public static void main(String[] args) {
        new Cat().sound();
        new Animal().sound();
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Meow
Some generic sound</code></pre>
<h2>Important Points</h2>
<ul>
<li>Java allows only <strong>one</strong> superclass per class (no multiple inheritance of classes). Interfaces solve this.</li>
<li><code>private</code> members of the parent are not directly accessible in the child.</li>
<li>The parent constructor always runs first. If you do not call <code>super(...)</code> yourself, Java calls the parent's no-argument constructor.</li>
<li>Use <code>@Override</code>: the compiler then reports an error if you did not really override anything.</li>
<li>A class marked <code>final</code> cannot be extended.</li>
</ul>
""";

    private static final String POLYMORPHISM = """
<h2>Explanation</h2>
<p><strong>Polymorphism</strong> means &ldquo;many forms&rdquo;: the same method call can behave differently depending on the object. There are two kinds. <strong>Compile-time</strong> polymorphism is method overloading (same name, different parameters). <strong>Run-time</strong> polymorphism is method overriding: a parent-type variable can hold a child object, and Java calls the child's version of the method when the program runs.</p>
<h2>Syntax</h2>
<pre><code>Parent ref = new Child();   // parent reference, child object
ref.method();               // the Child's version runs</code></pre>
<h2>Examples</h2>
<h3>Run-time polymorphism with shapes</h3>
<pre><code>class Shape {
    double area() {
        return 0;
    }
}

class Circle extends Shape {
    double radius;
    Circle(double radius) { this.radius = radius; }

    @Override
    double area() {
        return 3.14 * radius * radius;
    }
}

class Rectangle extends Shape {
    double width, height;
    Rectangle(double width, double height) {
        this.width = width;
        this.height = height;
    }

    @Override
    double area() {
        return width * height;
    }
}

public class Main {
    public static void main(String[] args) {
        Shape[] shapes = { new Circle(2), new Rectangle(3, 4) };

        for (Shape s : shapes) {
            System.out.println(s.area());
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>12.56
12.0</code></pre>
<h3>Compile-time polymorphism (overloading)</h3>
<pre><code>class Printer {
    void print(int number) {
        System.out.println("Number: " + number);
    }

    void print(String text) {
        System.out.println("Text: " + text);
    }
}

public class Main {
    public static void main(String[] args) {
        Printer p = new Printer();
        p.print(10);
        p.print("Hello");
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Number: 10
Text: Hello</code></pre>
<h2>Important Points</h2>
<ul>
<li>Overloading is decided by the compiler from the argument types; overriding is decided at run time from the real object.</li>
<li>The loop over <code>Shape[]</code> works for any future shape too, without changing the loop. This is the main benefit.</li>
<li>Through a parent-type variable you can only call methods that exist in the parent class.</li>
<li>Overriding needs the same name, same parameters and a compatible return type.</li>
</ul>
""";

    private static final String ABSTRACTION = """
<h2>Explanation</h2>
<p><strong>Abstraction</strong> means showing what an object does while hiding how it does it. In Java this is done with <strong>abstract classes</strong> (and interfaces). An abstract class cannot be instantiated. It may contain <strong>abstract methods</strong>, which have no body and must be written by every concrete subclass, along with normal methods that all subclasses share.</p>
<h2>Syntax</h2>
<pre><code>abstract class ClassName {
    abstract returnType methodName();   // no body

    void normalMethod() {               // normal method with a body
        // shared code
    }
}</code></pre>
<h2>Examples</h2>
<h3>Employees with different salary rules</h3>
<pre><code>abstract class Employee {
    String name;

    Employee(String name) {
        this.name = name;
    }

    abstract double salary();     // every employee type must define this

    void show() {
        System.out.println(name + " earns " + salary());
    }
}

class FullTimeEmployee extends Employee {
    double monthly;

    FullTimeEmployee(String name, double monthly) {
        super(name);
        this.monthly = monthly;
    }

    @Override
    double salary() {
        return monthly;
    }
}

class PartTimeEmployee extends Employee {
    int hours;
    double ratePerHour;

    PartTimeEmployee(String name, int hours, double ratePerHour) {
        super(name);
        this.hours = hours;
        this.ratePerHour = ratePerHour;
    }

    @Override
    double salary() {
        return hours * ratePerHour;
    }
}

public class Main {
    public static void main(String[] args) {
        Employee e1 = new FullTimeEmployee("Meena", 30000);
        Employee e2 = new PartTimeEmployee("Karan", 20, 500);
        e1.show();
        e2.show();
        // new Employee("X");   // error: Employee is abstract
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Meena earns 30000.0
Karan earns 10000.0</code></pre>
<h2>Important Points</h2>
<ul>
<li>You cannot create an object of an abstract class, but you can use it as a variable type.</li>
<li>A class that has at least one abstract method must itself be declared <code>abstract</code>.</li>
<li>A subclass must implement all abstract methods, or it must be abstract too.</li>
<li>Abstract classes can have fields, constructors and normal methods.</li>
</ul>
""";

    private static final String INTERFACES = """
<h2>Explanation</h2>
<p>An <strong>interface</strong> is a contract: it lists methods that a class must provide, without saying how. A class promises to follow the contract with <code>implements</code>. A class can implement <strong>many</strong> interfaces, which is how Java supports multiple inheritance of behaviour.</p>
<p>Methods in an interface are <code>public abstract</code> by default. Since Java 8 an interface can also have <code>default</code> methods with a body.</p>
<h2>Syntax</h2>
<pre><code>interface InterfaceName {
    void method();                 // abstract by default

    default void helper() {        // optional ready-made method
        // body
    }
}

class MyClass implements InterfaceName {
    public void method() { /* ... */ }
}</code></pre>
<h2>Examples</h2>
<h3>Payment options</h3>
<pre><code>interface Payment {
    void pay(double amount);
}

class UpiPayment implements Payment {
    public void pay(double amount) {
        System.out.println("Paid " + amount + " using UPI");
    }
}

class CardPayment implements Payment {
    public void pay(double amount) {
        System.out.println("Paid " + amount + " using Card");
    }
}

public class Main {
    public static void main(String[] args) {
        Payment p = new UpiPayment();
        p.pay(250);

        p = new CardPayment();
        p.pay(1200.5);
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Paid 250.0 using UPI
Paid 1200.5 using Card</code></pre>
<h3>Several interfaces and a default method</h3>
<pre><code>interface Flyable {
    void fly();
}

interface Swimmable {
    void swim();

    default void rest() {
        System.out.println("Resting...");
    }
}

class Duck implements Flyable, Swimmable {
    public void fly()  { System.out.println("Duck flies"); }
    public void swim() { System.out.println("Duck swims"); }
}

public class Main {
    public static void main(String[] args) {
        Duck d = new Duck();
        d.fly();
        d.swim();
        d.rest();
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Duck flies
Duck swims
Resting...</code></pre>
<h2>Important Points</h2>
<ul>
<li>Methods that implement an interface must be declared <code>public</code>.</li>
<li>Interface fields are automatically <code>public static final</code> (constants).</li>
<li>Use an interface for &ldquo;can do&rdquo; abilities (Flyable, Payment) and an abstract class for a shared base with common code.</li>
<li>You cannot create an object of an interface, but you can use it as a variable type.</li>
</ul>
""";

    private static final String EXCEPTION_HANDLING = """
<h2>Explanation</h2>
<p>An <strong>exception</strong> is an error that happens while the program is running, such as dividing by zero or opening a file that does not exist. Without handling, the program stops. With <code>try</code> and <code>catch</code> you can handle the problem and keep going. Code in <code>finally</code> always runs, whether or not an exception happened.</p>
<p>You can create an exception yourself with <code>throw</code>. A method that might pass a <em>checked</em> exception to its caller declares it with <code>throws</code>. You can also write your own exception class by extending <code>Exception</code>.</p>
<h2>Syntax</h2>
<pre><code>try {
    // code that might fail
} catch (ExceptionType e) {
    // what to do when it fails
} finally {
    // always runs (optional)
}

throw new ExceptionType("message");
void method() throws ExceptionType { }</code></pre>
<h2>Examples</h2>
<h3>try, catch and finally</h3>
<pre><code>try {
    int result = 10 / 0;
    System.out.println(result);
} catch (ArithmeticException e) {
    System.out.println("Error: " + e.getMessage());
} finally {
    System.out.println("Done");
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Error: / by zero
Done</code></pre>
<h3>throw and a custom exception</h3>
<pre><code>class InvalidAgeException extends Exception {
    InvalidAgeException(String message) {
        super(message);
    }
}

public class ExceptionDemo {

    static void checkAge(int age) throws InvalidAgeException {
        if (age &lt; 18) {
            throw new InvalidAgeException("Age must be 18 or more");
        }
        System.out.println("Access granted");
    }

    public static void main(String[] args) {
        try {
            checkAge(20);
            checkAge(15);
        } catch (InvalidAgeException e) {
            System.out.println("Caught: " + e.getMessage());
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Access granted
Caught: Age must be 18 or more</code></pre>
<h3>Converting text to a number</h3>
<pre><code>try {
    int number = Integer.parseInt("abc");
    System.out.println(number);
} catch (NumberFormatException e) {
    System.out.println("That is not a valid number.");
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>That is not a valid number.</code></pre>
<h2>Important Points</h2>
<ul>
<li><strong>Checked</strong> exceptions (like <code>IOException</code>) must be caught or declared with <code>throws</code>. <strong>Unchecked</strong> exceptions (like <code>ArithmeticException</code>) do not have to be.</li>
<li>Put more specific catch blocks before general ones. <code>Exception</code> catches almost everything.</li>
<li>Do not leave a catch block empty; at least show or log the problem.</li>
<li><code>throw</code> creates an exception; <code>throws</code> only announces that a method may pass one on.</li>
</ul>
""";

    private static final String COLLECTIONS = """
<h2>Explanation</h2>
<p>The <strong>Collections Framework</strong> (in <code>java.util</code>) gives ready-made classes for storing groups of objects. Unlike arrays, they can grow and shrink. Three commonly used ones:</p>
<p><code>ArrayList</code> is an ordered list that allows duplicates. <code>HashMap</code> stores key-value pairs and finds a value quickly by its key. <code>HashSet</code> stores unique values and ignores duplicates. Collections hold objects, so for numbers use wrapper types such as <code>Integer</code> and <code>Double</code>.</p>
<h2>Syntax</h2>
<pre><code>import java.util.*;

List&lt;String&gt; list = new ArrayList&lt;&gt;();
Map&lt;String, Integer&gt; map = new HashMap&lt;&gt;();
Set&lt;Integer&gt; set = new HashSet&lt;&gt;();</code></pre>
<h2>Examples</h2>
<h3>ArrayList</h3>
<pre><code>import java.util.ArrayList;
import java.util.List;

public class ListDemo {
    public static void main(String[] args) {
        List&lt;String&gt; names = new ArrayList&lt;&gt;();
        names.add("Asha");
        names.add("Ravi");
        names.add("Meena");

        System.out.println(names.size());
        names.remove("Ravi");

        for (String n : names) {
            System.out.println(n);
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>3
Asha
Meena</code></pre>
<h3>HashMap</h3>
<pre><code>Map&lt;String, Integer&gt; marks = new HashMap&lt;&gt;();
marks.put("Asha", 85);
marks.put("Ravi", 72);

System.out.println(marks.get("Asha"));
System.out.println(marks.containsKey("Meena"));
System.out.println(marks.getOrDefault("Meena", 0));</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>85
false
0</code></pre>
<h3>HashSet</h3>
<pre><code>Set&lt;Integer&gt; numbers = new HashSet&lt;&gt;();
numbers.add(1);
numbers.add(2);
numbers.add(2);   // duplicate, ignored
numbers.add(3);

System.out.println(numbers.size());</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>3</code></pre>
<h2>Important Points</h2>
<ul>
<li>The type in angle brackets, like <code>&lt;String&gt;</code>, is a <strong>generic</strong>; it tells the collection what it may store.</li>
<li>Program to the interface: write <code>List&lt;String&gt; list = new ArrayList&lt;&gt;();</code>.</li>
<li>A <code>HashMap</code> and <code>HashSet</code> do not promise any order when you loop over them.</li>
<li>Loop over a map with <code>for (Map.Entry&lt;K, V&gt; e : map.entrySet())</code> and use <code>e.getKey()</code> and <code>e.getValue()</code>.</li>
<li>Use <code>size()</code> for collections, <code>length</code> for arrays.</li>
</ul>
""";

    private static final String FILE_HANDLING = """
<h2>Explanation</h2>
<p>Java reads and writes files with classes from <code>java.io</code> and <code>java.nio.file</code>. <code>FileWriter</code> writes text and <code>BufferedReader</code> with <code>FileReader</code> reads it line by line. The newer <code>Files</code> class can read or write a whole small file in one call.</p>
<p>File operations can fail (file missing, disk full), so they throw <code>IOException</code>, which you must handle. The <strong>try-with-resources</strong> form closes the file for you automatically.</p>
<h2>Syntax</h2>
<pre><code>try (FileWriter writer = new FileWriter("file.txt")) {
    writer.write("text");
} catch (IOException e) {
    // handle the problem
}

Files.writeString(Path.of("file.txt"), "text");
String text = Files.readString(Path.of("file.txt"));</code></pre>
<h2>Examples</h2>
<h3>Write a file, then read it back</h3>
<pre><code>import java.io.*;

public class FileDemo {
    public static void main(String[] args) {
        try (FileWriter writer = new FileWriter("notes.txt")) {
            writer.write("Line one\\n");
            writer.write("Line two\\n");
        } catch (IOException e) {
            System.out.println("Write error: " + e.getMessage());
        }

        try (BufferedReader reader = new BufferedReader(new FileReader("notes.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            System.out.println("Read error: " + e.getMessage());
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Line one
Line two</code></pre>
<h3>The Files class</h3>
<pre><code>import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FilesDemo {
    public static void main(String[] args) throws IOException {
        Path path = Path.of("hello.txt");
        Files.writeString(path, "Hello file");

        System.out.println(Files.readString(path));
        System.out.println(Files.exists(path));
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>Hello file
true</code></pre>
<h2>Important Points</h2>
<ul>
<li><code>new FileWriter("notes.txt")</code> replaces the old content; use <code>new FileWriter("notes.txt", true)</code> to append.</li>
<li>A relative path like <code>notes.txt</code> is looked up in the folder the program is started from.</li>
<li>Always close files. try-with-resources does it automatically, even when an error happens.</li>
<li>For binary files (images, PDFs) use byte streams such as <code>FileInputStream</code> instead of readers and writers.</li>
</ul>
""";

    private static final String JDBC = """
<h2>Explanation</h2>
<p><strong>JDBC</strong> (Java Database Connectivity) is the standard way for a Java program to talk to a relational database. The steps are always the same: (1) connect with <code>DriverManager.getConnection</code>, (2) create a <code>PreparedStatement</code> with your SQL, (3) run it with <code>executeQuery()</code> for SELECT or <code>executeUpdate()</code> for INSERT, UPDATE and DELETE, (4) read the <code>ResultSet</code> row by row, (5) close everything.</p>
<p>You also need the <strong>JDBC driver</strong> of your database (for example the MySQL connector) on the classpath; in a Maven project this is one extra dependency. The examples below assume a database named <code>college</code> with a table <code>students(id, name)</code>.</p>
<h2>Syntax</h2>
<pre><code>Connection con = DriverManager.getConnection(url, user, password);
PreparedStatement ps = con.prepareStatement("SELECT * FROM t WHERE id = ?");
ps.setInt(1, 5);
ResultSet rs = ps.executeQuery();
while (rs.next()) {
    rs.getInt("id");
    rs.getString("name");
}</code></pre>
<h2>Examples</h2>
<h3>Reading rows with SELECT</h3>
<pre><code>import java.sql.*;

public class JdbcDemo {
    public static void main(String[] args) {
        String url = "jdbc:mysql://localhost:3306/college";
        String user = "root";
        String password = "your_password";
        String sql = "SELECT id, name FROM students WHERE id &gt; ?";

        try (Connection con = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, 0);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getInt("id") + " - " + rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
        }
    }
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>1 - Asha
2 - Ravi
(this output assumes the table contains those two rows)</code></pre>
<h3>Adding a row with INSERT</h3>
<pre><code>String sql = "INSERT INTO students (id, name) VALUES (?, ?)";

try (Connection con = DriverManager.getConnection(url, user, password);
     PreparedStatement ps = con.prepareStatement(sql)) {

    ps.setInt(1, 3);
    ps.setString(2, "Meena");
    int rows = ps.executeUpdate();
    System.out.println(rows + " row inserted");
} catch (SQLException e) {
    System.out.println("Database error: " + e.getMessage());
}</code></pre>
<p class="output-label">Output</p>
<pre class="output"><code>1 row inserted</code></pre>
<h2>Important Points</h2>
<ul>
<li>Always use <code>PreparedStatement</code> with <code>?</code> placeholders instead of joining text into the SQL. This protects against SQL injection.</li>
<li>JDBC methods throw <code>SQLException</code>, so they must be inside try-catch.</li>
<li><code>Connection</code>, <code>PreparedStatement</code> and <code>ResultSet</code> should be closed; try-with-resources does this.</li>
<li>Do not write real passwords in your source code or upload them to GitHub.</li>
<li>The same JDBC code works with other databases; only the URL and the driver change.</li>
</ul>
""";
}
