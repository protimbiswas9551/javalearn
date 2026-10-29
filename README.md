# JavaLearn

A small Java web application for learning Java.

* **Learn**: 20 text lessons, from "Introduction" to "JDBC".
* **Projects**: Java projects read automatically from a GitHub repository (GitHub is the source of truth).

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

## Project structure

```
javalearn/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/javalearn/
    │   ├── JavaLearnApplication.java   starts Spring Boot
    │   ├── Controller.java             all routes: /, /about, /privacy, /terms, /learn, /learn/{topic},
    │   │                               /projects, /projects/{project}, /error
    │   ├── Service.java                lessons (list + text), ALL GitHub communication, README parser
    │   └── Models.java                 Lesson, Project, SourceFile (records)
    └── resources/
        ├── application.properties
        ├── static/css/style.css
        ├── static/js/site.js           colours the code blocks (about 40 lines)
        └── templates/
            ├── index, learn, lesson, projects, project .html
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
4. The project page downloads the `.java` files the same way and shows the code.
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
