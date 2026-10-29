# JavaLearn

A small Java web application for learning Java.

* **Learn**: 20 text lessons, from "Introduction" to "JDBC".
* **Quizzes**: every lesson ends with a 5-question quiz (multiple choice + fill in the blank).
* **Projects**: ALL Java projects read automatically from a GitHub repository, with search, filters, sorting and
  expandable source files.
* **Playground**: write and run Java in the browser (code runs on an external service, never on this server).
* **Progress**: lessons completed and best quiz scores, saved in the browser (localStorage).

Built with Java 21, Spring Boot, Spring MVC, Thymeleaf and Maven. There is no database, no login and no JavaScript framework.

## Run it

You need **JDK 21** and **Maven 3.9+**, and an internet connection (for GitHub).

```bash
mvn spring-boot:run
```

Open <http://localhost:8080>.

To build a jar instead:

```bash
mvn clean package
java -jar target/javalearn-1.0.0.jar
```

## Settings (`src/main/resources/application.properties`)

| Property | Meaning | Current value |
|---|---|---|
| `github.owner` | GitHub user or organisation | `protimbiswas9551` |
| `github.repo` | Repository name | `Java-Projects-Collections` |
| `github.branch` | Branch (`HEAD` = default branch) | `HEAD` |
| `github.projects-path` | Folder that contains the project folders. Leave empty if they are in the repository root. | `Projects` |
| `github.token` | Optional GitHub token (env `GITHUB_TOKEN`). Raises the API limit and enables the "Recently updated" sort. | empty |
| `sandbox.enabled` | Turns the code playground's Run button on or off | `true` |
| `sandbox.url` | Judge0-compatible execution service | `https://ce.judge0.com` |
| `sandbox.api-key` / `sandbox.api-key-header` | Optional token for the service (env `SANDBOX_API_KEY`), kept on the server | empty / `X-Auth-Token` |
| `sandbox.language-id` | Java language id of the service; `0` = newest "Java (...)" found automatically | `0` |

### Code playground setup

Submitted code is **never** compiled or run inside Spring Boot. `POST /api/run` forwards it to an external
Judge0 service, which isolates it (CPU, memory, time, no network). By default the free public Judge0 CE instance is used:
no key, but it is shared, rate limited and may be unavailable. For reliable use, self-host Judge0 (free, open source)
and set `sandbox.url` (and `sandbox.api-key` if you enabled authentication). If the service cannot be reached the page says so
and nothing is reported as run. The server also limits each visitor to 10 runs per minute, code to 64,000 characters.
The Java version is whatever the service provides (it is shown under the result).

### Progress and quizzes

Progress is stored in `localStorage` (key `javalearn.progress.v1`): completed lessons and the best score per quiz.
It does not sync between browsers or devices. Invalid or outdated data is ignored safely. Quiz questions are in
`src/main/resources/quizzes.json`; the server refuses to start if a lesson has no questions.
Answers are checked in the browser, so this is for practice, not for exams.

## Project structure

```
javalearn/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/javalearn/
    │   ├── JavaLearnApplication.java   starts Spring Boot
    │   ├── Controller.java             all routes: /, /about, /privacy, /terms, /learn, /learn/{topic},
    │   │                               /projects, /projects/{project}, /projects/{project}/source,
    │   │                               /sandbox, /api/run, /progress, /error
    │   ├── Service.java                lessons, quizzes, ALL GitHub communication, project search, playground client, README parser
    │   └── Models.java                 Lesson, Project, ProjectFile, SourceFile, RunRequest, RunResult (records)
    └── resources/
        ├── application.properties
        ├── static/css/style.css
        ├── static/js/site.js           colours the code blocks
        ├── static/js/learn.js          progress storage, quizzes, progress page
        ├── static/js/projects.js       project filters, expandable source files
        ├── static/js/sandbox.js        playground
        ├── quizzes.json                100 quiz questions
        └── templates/
            ├── index, learn, lesson, projects, project, sandbox, progress .html
            └── pages.html              shared head/menu/footer + About, Privacy, Terms and error pages
```

Request flow: browser (HTML/CSS/JS) -> Spring Boot -> `Controller` -> `Service` -> GitHub API.

## How the GitHub integration works

1. `Service` makes **one** call to the GitHub REST API:
   `GET https://api.github.com/repos/{owner}/{repo}/git/trees/HEAD?recursive=1`.
   The answer is the path of every file in the repository.
2. From those paths it finds the **project folders**: every folder inside `github.projects-path`
   that contains at least one `.java` file (folders starting with `.` are ignored).
3. For each folder it downloads `README.md` (if there is one) from `raw.githubusercontent.com`.
   The README-parser methods in `Service` read the description, difficulty and concepts. Nothing is invented: missing facts stay empty.
4. The project page lists **every** `.java` file (grouped by folder, no limit). A file's complete code is downloaded
   only when it is expanded (`/projects/{name}/source?path=...`); files over 1 MB are refused with a message, never cut.
   If GitHub marks the repository tree as `truncated` (very large repositories), the project folders are read one by one.
5. The project list is remembered in memory for **15 minutes** so pages open fast and the GitHub
   limit (60 API requests per hour without a token) is not reached.

If GitHub cannot be reached, is rate limited, or the repository is not found, the page shows a short
message instead of crashing. A project without a README still works: it shows the folder name and files.

### What the README parser recognises

| Information | How it is found |
|---|---|
| Description | The first normal paragraph of text (not a heading, image, list, table or code block) |
| Difficulty | The word `Difficulty` followed by `Beginner`, `Easy`, `Intermediate`, `Medium`, `Advanced` or `Hard`, for example `**Difficulty:** Beginner` |
| Concepts / features | Bullet points under a heading that contains "Concept" or "Feature" |

The difficulty filter on `/projects` is only shown when at least one README has a difficulty.
To recognise other words, change `Service.DIFFICULTY_LEVELS`.

## Adding a lesson

Add one `add(...)` line in `addLessons()` of `Service.java`, and one text block with the lesson HTML
at the bottom of the same file (copy an existing lesson for the layout).
