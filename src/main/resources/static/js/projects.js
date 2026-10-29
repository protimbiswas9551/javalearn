// Projects pages. (1) List page: a changed drop-down applies the filters at once.
// (2) Detail page: a file's source is downloaded only when its name is opened, and shown completely.
(function () {
    // ---- 1. filter form ----
    var form = document.getElementById("project-filters");
    if (form) {
        var selects = form.querySelectorAll("select");
        for (var i = 0; i < selects.length; i++) {
            selects[i].addEventListener("change", function () { form.submit(); });
        }
    }

    // ---- 2. project page ----
    var section = document.getElementById("source-files");
    if (!section) return;
    var sourceUrl = section.getAttribute("data-source-url");
    var toggles = section.querySelectorAll(".source-toggle");

    section.querySelector(".file-controls").hidden = false; // the buttons need JavaScript, so they start hidden

    function body(toggle) { return document.getElementById(toggle.getAttribute("aria-controls")); }

    // At most 4 downloads at the same time; the others wait their turn.
    var active = 0, waiting = [];
    function schedule(task) {
        return new Promise(function (resolve) {
            waiting.push(function () { task().then(resolve, resolve).then(function () { active--; next(); }); });
            next();
        });
    }
    function next() {
        while (active < 4 && waiting.length) { active++; waiting.shift()(); }
    }

    function load(toggle) {
        var box = body(toggle);
        if (box.getAttribute("data-state") === "loaded" || box.getAttribute("data-state") === "loading") {
            return Promise.resolve();
        }
        var status = box.querySelector(".source-status");
        var pre = box.querySelector("pre");
        box.setAttribute("data-state", "loading");
        status.hidden = false;
        status.className = "muted source-status";
        status.textContent = "Loading\u2026";

        return schedule(function () { return fetch(sourceUrl + "?path=" + encodeURIComponent(toggle.getAttribute("data-path")))
            .then(function (response) {
                return response.json().catch(function () { return {}; }).then(function (data) {
                    if (!response.ok || typeof data.code !== "string") {
                        throw new Error(data.error || "The file could not be loaded (HTTP " + response.status + ").");
                    }
                    return data;
                });
            })
            .then(function (data) {
                var code = pre.querySelector("code");
                code.textContent = data.code;
                window.JavaLearn.highlight(code);
                pre.hidden = false;
                status.hidden = true;
                var link = box.querySelector(".gh-link");
                link.href = data.githubUrl;
                box.setAttribute("data-state", "loaded");
            })
            .catch(function (error) {
                box.removeAttribute("data-state"); // allows trying again by closing and opening
                status.className = "notice error source-status";
                status.setAttribute("role", "alert");
                status.textContent = (error && error.message ? error.message : "The file could not be loaded.")
                    + " Close and open the file to try again, or read it on GitHub.";
            }); });
    }

    function setOpen(toggle, open) {
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
        body(toggle).hidden = !open;
        toggle.querySelector(".chevron").textContent = open ? "\u25BE" : "\u25B8";
        if (open) load(toggle);
    }

    for (var j = 0; j < toggles.length; j++) {
        toggles[j].addEventListener("click", function (event) {
            var t = event.currentTarget;
            setOpen(t, t.getAttribute("aria-expanded") !== "true");
        });
    }

    document.getElementById("expand-all").addEventListener("click", function () {
        var queue = Array.prototype.slice.call(toggles);
        queue.forEach(function (t) { setOpen(t, true); });
    });
    document.getElementById("collapse-all").addEventListener("click", function () {
        for (var k = 0; k < toggles.length; k++) setOpen(toggles[k], false);
    });
})();
