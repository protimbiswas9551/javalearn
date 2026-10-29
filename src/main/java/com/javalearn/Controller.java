package com.javalearn;

import com.javalearn.AIAssistantModels.AIRequest;
import com.javalearn.AIAssistantModels.AIResponse;
import com.javalearn.CoachModels.CoachRequest;
import com.javalearn.CoachModels.QualityReport;
import com.javalearn.Models.Lesson;
import com.javalearn.Models.Project;
import com.javalearn.Models.RunRequest;
import com.javalearn.Models.RunResult;
import com.javalearn.Models.SourceFile;
import com.javalearn.Service.GitHubException;
import com.javalearn.XRayModels.XRayRequest;
import com.javalearn.XRayModels.XRayResult;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All the web pages (routes) of JavaLearn. Each method returns the name of a Thymeleaf template.
 *
 *   /                 home page                 -> index.html
 *   /about /privacy /terms   simple pages       -> pages.html
 *   /learn            list of lessons           -> learn.html
 *   /learn/{topic}    one lesson                -> lesson.html
 *   /projects         project list + search     -> projects.html
 *   /projects/{name}  project details, file list -> project.html
 *   /projects/{name}/source?path=...  one source file as JSON (loaded when a file is expanded)
 *   /sandbox          Java code playground      -> sandbox.html
 *   /api/run          runs code on the external execution service (JSON)
 *   /progress         learning progress         -> progress.html
 *   /error            error page (404, 500...)  -> pages.html
 */
@org.springframework.stereotype.Controller
public class Controller implements ErrorController {

    private final Service service;
    private final XRayService xRayService;
    private final CoachService coachService;
    private final AIAssistantService aiAssistantService;

    public Controller(Service service, XRayService xRayService, CoachService coachService, AIAssistantService aiAssistantService) {
        this.service = service;
        this.xRayService = xRayService;
        this.coachService = coachService;
        this.aiAssistantService = aiAssistantService;
    }

    // ---------------- Home and simple pages ----------------

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("stages", service.getLessonsByStage()); // used for the learning path
        return "index";
    }

    @GetMapping("/about")
    public String about(Model model) {
        return page("about", "About - JavaLearn", "about", model);
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        return page("privacy", "Privacy - JavaLearn", "none", model);
    }

    @GetMapping("/terms")
    public String terms(Model model) {
        return page("terms", "Terms - JavaLearn", "none", model);
    }

    /** pages.html shows the content that matches the "page" value. */
    private String page(String page, String title, String active, Model model) {
        model.addAttribute("page", page);
        model.addAttribute("title", title);
        model.addAttribute("active", active); // which menu link is highlighted
        return "pages";
    }

    // ---------------- Lessons ----------------

    @GetMapping("/learn")
    public String lessonList(Model model) {
        model.addAttribute("stages", service.getLessonsByStage());
        return "learn";
    }

    @GetMapping("/learn/{topic}")
    public String lesson(@PathVariable String topic, Model model) {
        Lesson lesson = service.findLesson(topic)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); // shows the 404 page

        model.addAttribute("lesson", lesson);
        model.addAttribute("previous", service.previousOf(lesson).orElse(null));
        model.addAttribute("next", service.nextOf(lesson).orElse(null));
        model.addAttribute("allLessons", service.getAllLessons()); // for the side menu
        model.addAttribute("quizJson", service.getQuizJson(lesson.slug()));
        return "lesson";
    }

    // ---------------- Projects (from GitHub) ----------------

    @GetMapping("/projects")
    public String projectList(@RequestParam(defaultValue = "") String q,
                              @RequestParam(defaultValue = "") String difficulty,
                              @RequestParam(defaultValue = "") String concept,
                              @RequestParam(defaultValue = "name-asc") String sort,
                              Model model) {
        // Send the search values back so the form keeps what the visitor chose (they are also in the URL).
        model.addAttribute("q", q);
        model.addAttribute("selectedDifficulty", difficulty);
        model.addAttribute("selectedConcept", concept);

        try {
            List<Project> allProjects = service.getProjects();
            Map<String, String> sorts = Service.sortOptions(Service.hasUpdateDates(allProjects));
            String activeSort = sorts.containsKey(sort) ? sort : "name-asc"; // an unknown or unavailable sort falls back

            model.addAttribute("sort", activeSort);
            model.addAttribute("sortOptions", sorts);
            model.addAttribute("totalProjects", allProjects.size());
            model.addAttribute("projects", Service.searchProjects(allProjects, q, difficulty, concept, activeSort));
            model.addAttribute("difficulties", Service.difficultyOptions(allProjects));
            model.addAttribute("concepts", Service.conceptOptions(allProjects));
        } catch (GitHubException e) {
            model.addAttribute("errorMessage", e.getMessage());
        }
        return "projects";
    }

    @GetMapping("/projects/{name}")
    public String projectDetails(@PathVariable String name, Model model, HttpServletResponse response) {
        try {
            Optional<Project> found = service.findProject(name);

            if (found.isEmpty()) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return errorPage("Project not found",
                        "There is no project with this name in the GitHub repository.", model);
            }
            Project project = found.get();
            model.addAttribute("project", project);
            model.addAttribute("fileGroups", service.getFileGroups(project)); // names only, no code yet
            return "project";

        } catch (GitHubException e) {
            response.setStatus(HttpStatus.BAD_GATEWAY.value());
            return errorPage("GitHub is not available", e.getMessage(), model);
        }
    }

    /** The complete code of one file, as JSON. The browser asks for it when the visitor expands the file. */
    @GetMapping("/projects/{name}/source")
    @ResponseBody
    public ResponseEntity<?> projectSource(@PathVariable String name, @RequestParam String path) {
        try {
            Optional<Project> project = service.findProject(name);
            Optional<SourceFile> file = project.flatMap(p -> service.getSourceFile(p, path));
            if (file.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "This file is not part of the project."));
            }
            return ResponseEntity.ok(file.get());
        } catch (GitHubException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    // ---------------- Playground ----------------

    @GetMapping("/sandbox")
    public String sandbox(Model model) {
        model.addAttribute("sandboxEnabled", service.isSandboxEnabled());
        model.addAttribute("xrayEnabled", xRayService.isXRayEnabled());
        model.addAttribute("aiEnabled", aiAssistantService.isEnabled());
        model.addAttribute("aiConfigured", aiAssistantService.isConfigured());
        model.addAttribute("aiProvider", aiAssistantService.getActiveProviderName());
        return "sandbox";
    }

    /** Never throws: every problem comes back as a RunResult with a clear status and message. */
    @PostMapping("/api/run")
    @ResponseBody
    public RunResult run(@RequestBody RunRequest request, HttpServletRequest http) {
        return service.runCode(request, http.getRemoteAddr());
    }

    /**
     * X-Ray: compile and trace user code via JDI in an isolated JVM process.
     * Returns the full execution trace as JSON. Never throws.
     */
    @PostMapping("/api/xray")
    @ResponseBody
    public XRayResult xray(@RequestBody XRayRequest request, HttpServletRequest http) {
        return xRayService.trace(request, http.getRemoteAddr());
    }

    /**
     * Code Quality Coach: static complexity analysis, compiler & runtime error diagnostics,
     * anti-patterns, and beginner-friendly improvement suggestions.
     * Evaluates code safely without executing user code inside the server JVM. Never throws.
     */
    @PostMapping("/api/coach/analyze")
    @ResponseBody
    public QualityReport analyzeCode(@RequestBody CoachRequest request) {
        return coachService.analyze(request);
    }

    /**
     * AI Java Assistant: questions, explanations, code generation, and error fixing.
     * Proxied securely through server-side provider calls; API keys are never exposed. Never throws.
     */
    @PostMapping("/api/ai/chat")
    @ResponseBody
    public AIResponse aiChat(@RequestBody AIRequest request) {
        return aiAssistantService.chat(request);
    }

    // ---------------- Progress ----------------

    @GetMapping("/progress")
    public String progress(Model model) {
        model.addAttribute("allLessons", service.getAllLessons());
        model.addAttribute("service", service); // the page asks it for the number of questions of each quiz
        return "progress";
    }

    // ---------------- Errors ----------------

    /** Spring sends every unknown URL and every unexpected error to /error. */
    @RequestMapping("/error")
    public String error(HttpServletRequest request, HttpServletResponse response, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer code) {
            response.setStatus(code); // keep the real status (404, 500, ...)
        }
        return errorPage(null, null, model); // null = use the default texts in pages.html
    }

    private String errorPage(String title, String message, Model model) {
        model.addAttribute("errorTitle", title);
        model.addAttribute("errorMessage", message);
        return page("error", "Something went wrong - JavaLearn", "none", model);
    }
}
