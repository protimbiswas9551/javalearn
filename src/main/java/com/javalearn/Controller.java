package com.javalearn;

import com.javalearn.Models.Lesson;
import com.javalearn.Models.Project;
import com.javalearn.Service.GitHubException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * All the web pages (routes) of JavaLearn. Each method returns the name of a Thymeleaf template.
 *
 *   /                 home page                 -> index.html
 *   /about /privacy /terms   simple pages       -> pages.html
 *   /learn            list of lessons           -> learn.html
 *   /learn/{topic}    one lesson                -> lesson.html
 *   /projects         project list + search     -> projects.html
 *   /projects/{name}  project details and code  -> project.html
 *   /error            error page (404, 500...)  -> pages.html
 */
@org.springframework.stereotype.Controller
public class Controller implements ErrorController {

    private final Service service;

    public Controller(Service service) {
        this.service = service;
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
        return "lesson";
    }

    // ---------------- Projects (from GitHub) ----------------

    @GetMapping("/projects")
    public String projectList(@RequestParam(defaultValue = "") String q,
                              @RequestParam(defaultValue = "") String difficulty,
                              Model model) {
        // Send the search values back so the form keeps what the visitor typed.
        model.addAttribute("q", q);
        model.addAttribute("selectedDifficulty", difficulty);

        try {
            List<Project> allProjects = service.getProjects();

            String search = q.strip().toLowerCase();
            List<Project> matching = allProjects.stream()
                    .filter(p -> search.isEmpty()
                            || p.displayName().toLowerCase().contains(search)
                            || p.folderName().toLowerCase().contains(search))
                    .filter(p -> difficulty.isEmpty() || difficulty.equalsIgnoreCase(p.difficulty()))
                    .toList();

            // Only difficulty levels that really appear in some README are offered in the filter.
            List<String> difficulties = allProjects.stream()
                    .map(Project::difficulty)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted(Comparator.comparingInt(Service.DIFFICULTY_LEVELS::indexOf))
                    .toList();

            model.addAttribute("totalProjects", allProjects.size());
            model.addAttribute("projects", matching);
            model.addAttribute("difficulties", difficulties);
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
            model.addAttribute("sourceFiles", service.getSourceFiles(project));
            return "project";

        } catch (GitHubException e) {
            response.setStatus(HttpStatus.BAD_GATEWAY.value());
            return errorPage("GitHub is not available", e.getMessage(), model);
        }
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
