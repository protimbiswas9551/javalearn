// Learning progress, lesson quizzes and the progress page. No library is used.
// Everything is saved in the browser (localStorage) under one key. Nothing is sent to the server.
(function (root) {
    "use strict";

    var KEY = "javalearn.progress.v1";

    // ================= 1. Progress data (pure functions, no page needed) =================

    function isObject(x) { return x !== null && typeof x === "object" && !Array.isArray(x); }
    function whole(x, min, max) {
        return typeof x === "number" && isFinite(x) && Math.floor(x) === x && x >= min && x <= max ? x : null;
    }
    // Objects without a prototype: a stored key such as "__proto__" is then only an ordinary key.
    function emptyState() { return { v: 1, lessons: Object.create(null), quizzes: Object.create(null) }; }

    /** Accepts anything and returns valid data. Unknown versions and invalid entries are dropped. */
    function sanitize(raw) {
        var out = emptyState();
        if (!isObject(raw) || raw.v !== 1) return out;
        if (isObject(raw.lessons)) {
            Object.keys(raw.lessons).forEach(function (slug) {
                var lesson = raw.lessons[slug];
                if (isObject(lesson) && lesson.done === true) {
                    out.lessons[slug] = { done: true, at: typeof lesson.at === "number" ? lesson.at : 0 };
                }
            });
        }
        if (isObject(raw.quizzes)) {
            Object.keys(raw.quizzes).forEach(function (slug) {
                var q = raw.quizzes[slug];
                if (!isObject(q)) return;
                var total = whole(q.total, 1, 1000);
                var best = total === null ? null : whole(q.best, 0, total);
                var attempts = whole(q.attempts, 1, 1000000);
                if (total !== null && best !== null && attempts !== null) {
                    out.quizzes[slug] = { best: best, total: total, attempts: attempts, at: typeof q.at === "number" ? q.at : 0 };
                }
            });
        }
        return out;
    }

    /** The browser storage, or null when it is blocked (private mode, disabled cookies, ...). */
    function getStorage() {
        try {
            var s = root.localStorage;
            s.setItem("javalearn.test", "1");
            s.removeItem("javalearn.test");
            return s;
        } catch (e) { return null; }
    }

    /** Returns { state, corrupted }. Missing, broken or outdated data gives an empty, valid state. */
    function load(storage) {
        if (!storage) return { state: emptyState(), corrupted: false };
        var text;
        try { text = storage.getItem(KEY); } catch (e) { return { state: emptyState(), corrupted: false }; }
        if (text === null) return { state: emptyState(), corrupted: false };
        try { return { state: sanitize(JSON.parse(text)), corrupted: false }; }
        catch (e) { return { state: emptyState(), corrupted: true }; }
    }

    function save(storage, state) {
        if (!storage) return false;
        try { storage.setItem(KEY, JSON.stringify(state)); return true; } catch (e) { return false; }
    }

    function setLessonDone(state, slug, done) {
        if (done) state.lessons[slug] = { done: true, at: Date.now() };
        else delete state.lessons[slug];
    }

    /** Stores one FINISHED quiz attempt: the best score only ever goes up (unless the quiz length changed). */
    function recordQuiz(state, slug, score, total) {
        var old = state.quizzes[slug];
        var sameQuiz = old && old.total === total;
        state.quizzes[slug] = {
            best: sameQuiz ? Math.max(old.best, score) : score,
            total: total,
            attempts: (old ? old.attempts : 0) + 1,
            at: Date.now()
        };
    }

    /** null = never taken. outdated = the quiz has a different number of questions now, so the old score is not used. */
    function quizStatus(state, slug, currentTotal) {
        var q = state.quizzes[slug];
        if (!q) return null;
        if (q.total !== currentTotal) return { outdated: true, best: q.best, total: q.total, attempts: q.attempts, percent: 0 };
        return { outdated: false, best: q.best, total: q.total, attempts: q.attempts, percent: Math.round(q.best * 100 / q.total) };
    }

    /** lessons = [{slug, questions}] in lesson order. */
    function summarize(state, lessons) {
        var done = [], remaining = [], quizzesDone = 0;
        lessons.forEach(function (lesson) {
            (state.lessons[lesson.slug] ? done : remaining).push(lesson);
            var status = quizStatus(state, lesson.slug, lesson.questions);
            if (status && !status.outdated) quizzesDone++;
        });
        return {
            total: lessons.length,
            done: done,
            remaining: remaining,
            percent: lessons.length ? Math.round(done.length * 100 / lessons.length) : 0,
            quizzesDone: quizzesDone,
            next: remaining.length ? remaining[0] : null
        };
    }

    // ================= 2. Grading (pure) =================

    function normalise(text) { return String(text).trim().replace(/\s+/g, " "); }

    /** mcq: response is the chosen option index. blank: response is the typed text (case matters, extra spaces do not). */
    function grade(question, response) {
        if (question.type === "mcq") return response === question.answer;
        var typed = normalise(response);
        return question.answer.some(function (accepted) { return normalise(accepted) === typed; });
    }

    function expectedText(question) {
        return question.type === "mcq" ? question.options[question.answer] : question.answer.join("  or  ");
    }

    var api = {
        KEY: KEY, sanitize: sanitize, load: load, save: save, setLessonDone: setLessonDone,
        recordQuiz: recordQuiz, quizStatus: quizStatus, summarize: summarize, grade: grade, expectedText: expectedText
    };
    if (typeof module !== "undefined" && module.exports) { module.exports = api; }
    if (typeof document === "undefined") { return; }

    // ================= 3. Page code =================

    var storage = getStorage();

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }
    function codeBlock(text) {
        var pre = el("pre"), code = el("code", null, text);
        pre.appendChild(code);
        if (root.JavaLearn && root.JavaLearn.highlight) root.JavaLearn.highlight(code);
        return pre;
    }
    function storageNote() {
        return el("p", "notice", "Your browser blocks local storage, so progress cannot be saved here.");
    }

    // ---- "Done" badges in lesson lists ----
    function markBadges() {
        var data = load(storage).state;
        var items = document.querySelectorAll("[data-lesson-slug]");
        for (var i = 0; i < items.length; i++) {
            var old = items[i].querySelector(".done-badge");
            if (old) old.remove();
            if (data.lessons[items[i].getAttribute("data-lesson-slug")]) {
                var badge = el("span", "done-badge", "\u2713");
                badge.setAttribute("title", "Completed");
                badge.setAttribute("aria-label", "Completed");
                items[i].appendChild(badge);
            }
        }
    }

    // ---- Lesson page: complete button ----
    function initLessonButton() {
        var button = document.getElementById("mark-done");
        if (!button) return;
        var slug = button.getAttribute("data-slug");
        var note = document.getElementById("done-note");
        function paint() {
            var done = !!load(storage).state.lessons[slug];
            button.setAttribute("aria-pressed", done ? "true" : "false");
            button.textContent = done ? "\u2713 Lesson completed (click to undo)" : "Mark lesson as complete";
            button.className = done ? "button" : "button primary";
        }
        button.addEventListener("click", function () {
            var data = load(storage).state;
            setLessonDone(data, slug, !data.lessons[slug]);
            var saved = save(storage, data);
            note.textContent = saved ? "" : "Could not save: your browser blocks local storage.";
            paint();
            markBadges();
        });
        paint();
    }

    // ---- Lesson page: quiz, one question at a time ----
    function initQuiz() {
        var dataElement = document.getElementById("quiz-data");
        var mount = document.getElementById("quiz-root");
        if (!dataElement || !mount) return;
        var quiz;
        try { quiz = JSON.parse(dataElement.textContent); } catch (e) { quiz = null; }
        if (!quiz || !Array.isArray(quiz.questions) || !quiz.questions.length) {
            mount.textContent = "The quiz could not be loaded.";
            return;
        }
        var questions = quiz.questions, slug = quiz.slug, attempt;

        function previousBest() { return quizStatus(load(storage).state, slug, questions.length); }

        function showStart() {
            mount.textContent = "";
            mount.appendChild(el("p", null, questions.length + " questions: multiple choice and fill in the blank. "
                + "After each answer you see whether it was right and why."));
            var best = previousBest();
            if (best && !best.outdated) {
                mount.appendChild(el("p", "quiz-best", "Your best score: " + best.best + " of " + best.total
                    + " (" + best.percent + "%), " + best.attempts + (best.attempts === 1 ? " attempt." : " attempts.")));
            } else if (best) {
                mount.appendChild(el("p", "muted", "This quiz was updated since your last attempt, so the old score is not counted."));
            }
            if (!storage) mount.appendChild(storageNote());
            var start = el("button", "button primary", best ? "Take the quiz again" : "Start the quiz");
            start.type = "button";
            start.addEventListener("click", function () {
                attempt = { index: 0, score: 0, checked: false, saved: false, results: [] };
                showQuestion();
            });
            mount.appendChild(start);
        }

        function showQuestion() {
            var q = questions[attempt.index];
            attempt.checked = false;
            mount.textContent = "";

            var heading = el("h3", "quiz-question", q.q);
            heading.tabIndex = -1;
            mount.appendChild(el("p", "quiz-progress", "Question " + (attempt.index + 1) + " of " + questions.length));
            mount.appendChild(heading);
            if (q.code) mount.appendChild(codeBlock(q.code));

            var inputs = [], textInput = null;
            if (q.type === "mcq") {
                var set = el("fieldset", "quiz-options");
                set.appendChild(el("legend", "visually-hidden", "Choose one answer"));
                q.options.forEach(function (option, i) {
                    var label = el("label", "quiz-option");
                    var radio = el("input");
                    radio.type = "radio"; radio.name = "quiz-answer"; radio.value = String(i);
                    inputs.push(radio);
                    label.appendChild(radio);
                    label.appendChild(el("span", null, option));
                    set.appendChild(label);
                });
                mount.appendChild(set);
            } else {
                var field = el("div", "field");
                var label2 = el("label", null, "Type what replaces ___");
                label2.setAttribute("for", "quiz-text");
                textInput = el("input");
                textInput.type = "text"; textInput.id = "quiz-text";
                textInput.setAttribute("autocomplete", "off");
                textInput.setAttribute("autocapitalize", "none");
                textInput.setAttribute("spellcheck", "false");
                field.appendChild(label2); field.appendChild(textInput);
                mount.appendChild(field);
            }

            var feedback = el("div", "quiz-feedback");
            feedback.setAttribute("aria-live", "polite");
            var actions = el("div", "button-row");
            var check = el("button", "button primary", "Check answer");
            check.type = "button";
            actions.appendChild(check);
            mount.appendChild(actions);
            mount.appendChild(feedback);

            function answerOrNull() {
                if (q.type === "mcq") {
                    for (var i = 0; i < inputs.length; i++) if (inputs[i].checked) return i;
                    return null;
                }
                return textInput.value.trim() === "" ? null : textInput.value;
            }

            function doCheck() {
                if (attempt.checked) return; // a question is scored once per attempt
                var response = answerOrNull();
                if (response === null) {
                    feedback.className = "quiz-feedback warn";
                    feedback.textContent = "Choose or type an answer first.";
                    return;
                }
                attempt.checked = true;
                var correct = grade(q, response);
                if (correct) attempt.score++;
                attempt.results.push(correct);
                inputs.forEach(function (r) { r.disabled = true; });
                if (textInput) textInput.disabled = true;

                feedback.className = "quiz-feedback " + (correct ? "good" : "bad");
                feedback.textContent = "";
                feedback.appendChild(el("strong", null, correct ? "Correct. " : "Not quite. "));
                if (!correct) feedback.appendChild(el("span", null, "The answer is: " + expectedText(q) + ". "));
                feedback.appendChild(el("span", null, q.explanation));

                actions.textContent = "";
                var last = attempt.index === questions.length - 1;
                var next = el("button", "button primary", last ? "See my result" : "Next question");
                next.type = "button";
                next.addEventListener("click", function () {
                    if (last) { showResult(); } else { attempt.index++; showQuestion(); }
                });
                actions.appendChild(next);
                next.focus();
            }
            check.addEventListener("click", doCheck);
            if (textInput) textInput.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); doCheck(); } });
            heading.focus();
        }

        function showResult() {
            var total = questions.length, percent = Math.round(attempt.score * 100 / total);
            var savedOk = true;
            if (!attempt.saved) { // the result of one attempt is stored exactly once
                attempt.saved = true;
                var data = load(storage).state;
                recordQuiz(data, slug, attempt.score, total);
                savedOk = save(storage, data);
            }
            mount.textContent = "";
            var title = el("h3", "quiz-question", "Your result: " + attempt.score + " of " + total + " (" + percent + "%)");
            title.tabIndex = -1;
            mount.appendChild(title);
            mount.appendChild(el("p", null, "Quiz completed. " + (percent === 100
                ? "Perfect score."
                : percent >= 70 ? "Good work. You can retry to improve your score." : "Read the lesson again and try once more.")));
            var best = previousBest();
            if (best && !best.outdated) mount.appendChild(el("p", "quiz-best", "Best score: " + best.best + " of " + best.total + " (" + best.percent + "%)"));
            if (!savedOk) mount.appendChild(storageNote());
            var row = el("div", "button-row");
            var retry = el("button", "button primary", "Try again");
            retry.type = "button";
            retry.addEventListener("click", function () {
                attempt = { index: 0, score: 0, checked: false, saved: false, results: [] };
                showQuestion();
            });
            row.appendChild(retry);
            var progress = el("a", "button", "View my progress");
            progress.href = document.getElementById("quiz").getAttribute("data-progress-url");
            row.appendChild(progress);
            mount.appendChild(row);
            title.focus();
        }

        showStart();
    }

    // ---- Progress page ----
    function initProgressPage() {
        var page = document.getElementById("progress-page");
        if (!page) return;
        var rows = page.querySelectorAll("[data-slug]");
        var lessons = [];
        for (var i = 0; i < rows.length; i++) {
            lessons.push({
                slug: rows[i].getAttribute("data-slug"),
                title: rows[i].getAttribute("data-title"),
                url: rows[i].getAttribute("data-url"),
                questions: parseInt(rows[i].getAttribute("data-questions"), 10)
            });
        }

        function render() {
            var loaded = load(storage);
            var s = summarize(loaded.state, lessons);

            document.getElementById("p-count").textContent = s.done.length + " of " + s.total;
            document.getElementById("p-percent").textContent = s.percent + "%";
            var bar = document.getElementById("p-bar");
            bar.style.width = s.percent + "%";
            bar.parentNode.setAttribute("aria-valuenow", String(s.percent));
            document.getElementById("p-quizzes").textContent = s.quizzesDone + " of " + s.total;

            var notice = document.getElementById("p-notice");
            notice.textContent = "";
            if (!storage) notice.appendChild(storageNote());
            if (loaded.corrupted) notice.appendChild(el("p", "notice", "Saved progress was unreadable and has been ignored."));

            var cont = document.getElementById("p-continue");
            cont.textContent = "";
            if (s.next) {
                var link = el("a", "button primary", (s.done.length ? "Continue learning: " : "Start learning: ") + s.next.title);
                link.href = s.next.url;
                cont.appendChild(link);
            } else {
                cont.appendChild(el("p", "notice", "All " + s.total + " lessons are completed. Well done!"));
            }

            var table = document.getElementById("p-rows");
            table.textContent = "";
            lessons.forEach(function (lesson, index) {
                var tr = el("tr");
                var done = !!loaded.state.lessons[lesson.slug];
                var status = quizStatus(loaded.state, lesson.slug, lesson.questions);
                var name = el("td"), a = el("a", null, lesson.title);
                a.href = lesson.url; name.appendChild(a);
                tr.appendChild(el("td", "num", String(index + 1)));
                tr.appendChild(name);
                tr.appendChild(el("td", done ? "state-done" : "muted", done ? "\u2713 Completed" : "Not completed"));
                tr.appendChild(el("td", null, !status ? "Not taken"
                    : status.outdated ? "Quiz updated, retake"
                    : status.best + " / " + status.total + " (" + status.percent + "%)"));
                table.appendChild(tr);
            });
        }

        // Reset needs two clicks: "Reset" shows a question, "Yes, reset" does it.
        var ask = document.getElementById("p-reset"), sure = document.getElementById("p-confirm");
        var yes = document.getElementById("p-yes"), no = document.getElementById("p-no");
        ask.addEventListener("click", function () { ask.hidden = true; sure.hidden = false; yes.focus(); });
        no.addEventListener("click", function () { sure.hidden = true; ask.hidden = false; ask.focus(); });
        yes.addEventListener("click", function () {
            try { if (storage) storage.removeItem(KEY); } catch (e) { /* nothing more to do */ }
            sure.hidden = true; ask.hidden = false;
            render();
            document.getElementById("p-reset-done").textContent = "Progress was reset.";
        });
        render();
    }

    initLessonButton();
    initQuiz();
    initProgressPage();
    markBadges();
})(typeof window !== "undefined" ? window : globalThis);
