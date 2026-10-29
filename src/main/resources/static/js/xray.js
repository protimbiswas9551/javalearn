/**
 * Java X-Ray visualizer.
 * Sends code to /api/xray, receives a list of execution step snapshots,
 * and lets the user navigate through them, seeing variables, call stack,
 * objects and console output at each step.
 *
 * All displayed state comes from actual JDI snapshots - nothing is invented.
 */
(function () {
    "use strict";

    /* ============================================================
       DOM elements
       ============================================================ */
    var root = document.getElementById("sandbox");
    if (!root) return;

    var xrayUrl     = root.getAttribute("data-xray-url");
    var xrayEnabled = root.getAttribute("data-xray-enabled") === "true";

    // Tabs
    var tabRun    = document.getElementById("tab-run");
    var tabXray   = document.getElementById("tab-xray");
    var tabAi     = document.getElementById("tab-ai");
    var panelRun  = document.getElementById("panel-run");
    var panelXray = document.getElementById("panel-xray");
    var panelAi   = document.getElementById("panel-ai");

    // Controls
    var runControls  = document.getElementById("run-controls");
    var xrayControls = document.getElementById("xray-controls");
    var xrayStart    = document.getElementById("xray-start");
    var xrayStop     = document.getElementById("xray-stop");
    var xrayResetBtn = document.getElementById("xray-reset");

    // Status / nav
    var statusMsg   = document.getElementById("xray-status-msg");
    var stepBadge   = document.getElementById("xray-step-badge");
    var statusBar   = document.getElementById("xray-status-bar");
    var navBar      = document.getElementById("xray-nav");
    var xrayGrid    = document.getElementById("xray-grid");
    var posLabel    = document.getElementById("xray-pos");
    var btnFirst    = document.getElementById("xray-first");
    var btnPrev     = document.getElementById("xray-prev");
    var btnNext     = document.getElementById("xray-next");
    var btnLast     = document.getElementById("xray-last");

    // Inspector panels
    var varsDiv     = document.getElementById("xray-vars");
    var stackDiv    = document.getElementById("xray-stack");
    var objectsDiv  = document.getElementById("xray-objects");
    var consoleEl   = document.getElementById("xray-console");

    // Timeline
    var timelineSection = document.getElementById("xray-timeline-section");
    var timelineEl      = document.getElementById("xray-timeline");

    // Editor + gutter
    var codeTextarea = document.getElementById("code");
    var gutter       = document.getElementById("editor-gutter");

    /* ============================================================
       State
       ============================================================ */
    var steps       = [];       // XRayResult.steps[]
    var lineToSteps = {};       // XRayResult.lineToSteps
    var cursor      = -1;       // currently displayed step index (into steps[])
    var running     = false;
    var xrayAbortController = null;

    /* ============================================================
       Tab switching
       ============================================================ */
    if (tabRun)  tabRun.addEventListener("click", function () { switchTab("run"); });
    if (tabXray) tabXray.addEventListener("click", function () { switchTab("xray"); });
    if (tabAi)   tabAi.addEventListener("click", function () { switchTab("ai"); });

    function switchTab(tab) {
        var isRun  = (tab === "run");
        var isXray = (tab === "xray");
        var isAi   = (tab === "ai");

        if (tabRun) {
            tabRun.classList.toggle("active", isRun);
            tabRun.setAttribute("aria-selected", String(isRun));
        }
        if (tabXray) {
            tabXray.classList.toggle("active", isXray);
            tabXray.setAttribute("aria-selected", String(isXray));
        }
        if (tabAi) {
            tabAi.classList.toggle("active", isAi);
            tabAi.setAttribute("aria-selected", String(isAi));
        }

        if (panelRun)  panelRun.hidden  = !isRun;
        if (panelXray) panelXray.hidden = !isXray;
        if (panelAi)   panelAi.hidden   = !isAi;

        // In AI mode, we keep runControls visible so Run & Analyze are readily available
        if (runControls)  runControls.hidden  = isXray;
        if (xrayControls) xrayControls.hidden = !isXray;

        updateGutter();
    }

    /* ============================================================
       Editor gutter (line numbers + execution indicators)
       ============================================================ */
    function updateGutter() {
        var lines = (codeTextarea.value || "").split("\n");
        var count = lines.length;
        var inXray = !panelXray.hidden;

        var html = "";
        for (var i = 1; i <= count; i++) {
            var cls = "gutter-line";
            if (inXray && steps.length > 0) {
                var step = cursor >= 0 && cursor < steps.length ? steps[cursor] : null;
                var isCurrentLine = step && step.lineNumber === i;
                var hasExecuted = lineToSteps[i] && lineToSteps[i].length > 0;
                if (isCurrentLine) cls += " gutter-current";
                else if (hasExecuted) cls += " gutter-executed";
            }
            html += "<div class=\"" + cls + "\" data-line=\"" + i + "\">" + i + "</div>";
        }
        gutter.innerHTML = html;

        // Sync gutter scroll with textarea
        gutter.scrollTop = codeTextarea.scrollTop;

        // Attach click listeners for line selection in X-Ray mode
        if (inXray) {
            gutter.querySelectorAll(".gutter-line").forEach(function (el) {
                el.addEventListener("click", function () {
                    var line = parseInt(el.getAttribute("data-line"), 10);
                    handleLineClick(line);
                });
            });
        }
    }

    codeTextarea.addEventListener("scroll", function () { gutter.scrollTop = codeTextarea.scrollTop; });
    codeTextarea.addEventListener("input",  updateGutter);

    /* ============================================================
       Gutter: clicking a source line in X-Ray mode
       ============================================================ */
    function handleLineClick(lineNumber) {
        if (!steps.length) return;
        var hits = lineToSteps[lineNumber];
        if (!hits || hits.length === 0) {
            setStatus("Line " + lineNumber + " was not executed (or has no debug information).", "warn");
            return;
        }
        // Navigate to the first step that hit this line, or next occurrence if already there
        var nextHit = hits[0];
        for (var i = 0; i < hits.length; i++) {
            if (hits[i] > cursor) { nextHit = hits[i]; break; }
        }
        goToStep(nextHit);
    }

    /* ============================================================
       Navigation
       ============================================================ */
    btnFirst.addEventListener("click", function () { goToStep(0); });
    btnPrev.addEventListener ("click", function () { goToStep(cursor - 1); });
    btnNext.addEventListener ("click", function () { goToStep(cursor + 1); });
    btnLast.addEventListener ("click", function () { goToStep(steps.length - 1); });

    function goToStep(index) {
        if (!steps.length) return;
        index = Math.max(0, Math.min(steps.length - 1, index));
        cursor = index;
        renderStep(steps[cursor]);
        updateGutter();
        updateNavButtons();
        scrollTimelineToCursor();
        highlightCurrentLine();
    }

    function updateNavButtons() {
        btnFirst.disabled = cursor <= 0;
        btnPrev.disabled  = cursor <= 0;
        btnNext.disabled  = cursor >= steps.length - 1;
        btnLast.disabled  = cursor >= steps.length - 1;
        posLabel.textContent = "Step " + (cursor + 1) + " / " + steps.length;
    }

    /* ============================================================
       X-Ray: kick off trace
       ============================================================ */
    xrayStart.addEventListener("click", function () { startXRay(); });
    xrayStop.addEventListener ("click", function () { stopXRay(); });
    xrayResetBtn.addEventListener("click", function () { resetXRay(); });

    function startXRay() {
        if (running || !xrayEnabled) return;
        var code = codeTextarea.value.trim();
        if (!code) { setStatus("Write some code first.", "warn"); return; }

        running = true;
        steps = [];
        lineToSteps = {};
        cursor = -1;

        xrayStart.disabled = true;
        xrayStop.disabled  = false;
        navBar.hidden      = true;
        xrayGrid.hidden    = true;
        timelineSection.hidden = true;
        stepBadge.hidden   = true;

        setStatus("Compiling and tracing\u2026 this may take a few seconds.", "busy");
        updateGutter();

        xrayAbortController = new AbortController();
        var timer = setTimeout(function () {
            if (xrayAbortController) xrayAbortController.abort();
        }, 45000);

        fetch(xrayUrl, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ code: code }),
            signal: xrayAbortController.signal
        })
        .then(function (resp) {
            return resp.json().catch(function () { return null; }).then(function (data) {
                if (!data || typeof data.status !== "string") {
                    throw new Error("Unexpected server response (HTTP " + resp.status + ").");
                }
                return data;
            });
        })
        .then(function (data) {
            handleXRayResult(data);
        })
        .catch(function (err) {
            var msg = err && err.name === "AbortError"
                ? "The trace was cancelled."
                : (err && err.message) || "Could not reach the server.";
            setStatus(msg, "error");
        })
        .then(function () {
            clearTimeout(timer);
            running = false;
            xrayStart.disabled = !xrayEnabled;
            xrayStop.disabled  = true;
            xrayAbortController = null;
        });
    }

    function stopXRay() {
        if (xrayAbortController) xrayAbortController.abort();
    }

    function resetXRay() {
        stopXRay();
        steps = [];
        lineToSteps = {};
        cursor = -1;
        navBar.hidden = true;
        xrayGrid.hidden = true;
        timelineSection.hidden = true;
        stepBadge.hidden = true;
        timelineEl.innerHTML = "";
        varsDiv.innerHTML   = "<p class=\"xray-empty\">No variables yet.</p>";
        stackDiv.innerHTML  = "<p class=\"xray-empty\">No frames yet.</p>";
        objectsDiv.innerHTML= "<p class=\"xray-empty\">No objects yet.</p>";
        consoleEl.innerHTML = "<span class=\"xray-empty\">No output yet.</span>";
        setStatus("Click <strong>\uD83D\uDD2C Trace</strong> to start.", "idle");
        clearLineHighlight();
        updateGutter();
    }

    /* ============================================================
       Handle the trace result
       ============================================================ */
    function handleXRayResult(data) {
        if (data.status === "compile_error") {
            setStatus("Compilation failed.", "error");
            if (data.compileOutput) {
                consoleEl.innerHTML = "";
                consoleEl.appendChild(makeErrorPre(data.compileOutput));
                xrayGrid.hidden = false;
            }
            return;
        }
        if (data.status === "timeout") {
            setStatus("Timed out: " + data.message, "error");
            if (data.steps && data.steps.length) {
                loadSteps(data);
                setStatus("Timed out after " + data.steps.length + " steps.", "warn");
            }
            return;
        }
        if (data.status !== "ok") {
            setStatus((data.message || "An error occurred."), "error");
            return;
        }
        if (!data.steps || data.steps.length === 0) {
            setStatus("Trace complete but no steps were recorded (the program may have exited immediately).", "warn");
            return;
        }
        loadSteps(data);
    }

    function loadSteps(data) {
        steps = data.steps || [];
        lineToSteps = data.lineToSteps || {};
        cursor = 0;

        buildTimeline();
        navBar.hidden = false;
        xrayGrid.hidden = false;
        timelineSection.hidden = false;
        stepBadge.hidden = false;
        stepBadge.textContent = steps.length + " step" + (steps.length === 1 ? "" : "s");

        goToStep(0);
        setStatus("Trace complete \u2014 " + steps.length + " step(s). Click a line or use the controls to navigate.", "ok");
    }

    /* ============================================================
       Render one step
       ============================================================ */
    function renderStep(step) {
        if (!step) return;

        // Variables
        renderVars(step.locals || []);

        // Call stack
        renderStack(step.callStack || []);

        // Objects
        renderObjects(step.objects || []);

        // Console
        renderConsole(step.stdoutSoFar || "");

        // Note / event label
        var eventLabel = eventKindLabel(step.eventKind);
        var noteHtml = "";
        if (step.note) {
            noteHtml = "<div class=\"xray-note\">" + esc(step.note) + "</div>";
        }

        // Update status to show current line
        var loc = step.lineNumber > 0
            ? "Line " + step.lineNumber
              + " in " + shortName(step.className) + "." + step.methodName + "()"
            : step.note || "End of trace";
        statusMsg.innerHTML = "<span class=\"xray-event-kind " + step.eventKind + "\">"
            + eventLabel + "</span> " + esc(loc);
        if (noteHtml) statusMsg.innerHTML += " " + noteHtml;
    }

    function renderVars(locals) {
        if (!locals.length) {
            varsDiv.innerHTML = "<p class=\"xray-empty\">No visible variables.</p>";
            return;
        }
        var html = "<table class=\"xray-table\"><thead><tr>"
            + "<th>Name</th><th>Type</th><th>Value</th><th>Scope</th>"
            + "</tr></thead><tbody>";
        locals.forEach(function (v) {
            var valClass = v.isNull ? "xray-null" : "xray-val";
            html += "<tr>"
                + "<td class=\"xray-varname\">" + esc(v.name) + "</td>"
                + "<td class=\"xray-type\">"    + esc(shortTypeName(v.type)) + "</td>"
                + "<td class=\"" + valClass + "\">" + esc(v.value) + (v.objectId ? " <span class=\"xray-ref\">\u2192obj#" + v.objectId + "</span>" : "") + "</td>"
                + "<td class=\"xray-scope " + esc(v.scope) + "\">" + esc(v.scope) + "</td>"
                + "</tr>";
        });
        html += "</tbody></table>";
        varsDiv.innerHTML = html;
    }

    function renderStack(frames) {
        if (!frames.length) {
            stackDiv.innerHTML = "<p class=\"xray-empty\">No stack frames.</p>";
            return;
        }
        var html = "<ol class=\"xray-stack-list\">";
        frames.forEach(function (f, i) {
            var active = i === 0 ? " xray-stack-top" : "";
            var native = f.isNative ? " <em class=\"xray-native\">(native)</em>" : "";
            html += "<li class=\"xray-stack-frame" + active + "\">"
                + "<span class=\"xray-stack-method\">" + esc(shortName(f.className)) + "." + esc(f.methodName) + "()</span>"
                + native
                + (f.lineNumber > 0 ? " <span class=\"xray-stack-line\">line " + f.lineNumber + "</span>" : "")
                + "</li>";
        });
        html += "</ol>";
        stackDiv.innerHTML = html;
    }

    function renderObjects(objects) {
        if (!objects.length) {
            objectsDiv.innerHTML = "<p class=\"xray-empty\">No heap objects captured at this step.</p>";
            return;
        }
        var html = "";
        objects.forEach(function (obj) {
            html += "<div class=\"xray-object\">"
                + "<div class=\"xray-object-header\">"
                + "<span class=\"xray-obj-type\">" + esc(shortName(obj.typeName)) + "</span>"
                + " <span class=\"xray-obj-id\">@" + esc(obj.objectId) + "</span>"
                + "</div>";
            if (obj.fields && obj.fields.length) {
                html += "<table class=\"xray-table\"><thead><tr>"
                    + "<th>Field</th><th>Type</th><th>Value</th>"
                    + "</tr></thead><tbody>";
                obj.fields.forEach(function (f) {
                    var valCls = f.isNull ? "xray-null" : "xray-val";
                    html += "<tr>"
                        + "<td class=\"xray-varname\">" + esc(f.name) + "</td>"
                        + "<td class=\"xray-type\">" + esc(shortTypeName(f.type)) + "</td>"
                        + "<td class=\"" + valCls + "\">" + esc(f.value) + "</td>"
                        + "</tr>";
                });
                html += "</tbody></table>";
            } else {
                html += "<p class=\"xray-empty\">No fields.</p>";
            }
            html += "</div>";
        });
        objectsDiv.innerHTML = html;
    }

    function renderConsole(text) {
        if (!text) {
            consoleEl.innerHTML = "<span class=\"xray-empty\">No output yet.</span>";
            return;
        }
        consoleEl.textContent = text;
    }

    /* ============================================================
       Timeline
       ============================================================ */
    function buildTimeline() {
        timelineEl.innerHTML = "";
        steps.forEach(function (step, i) {
            var el = document.createElement("div");
            el.className = "tl-item tl-" + (step.eventKind || "line");
            el.setAttribute("role", "listitem");
            el.setAttribute("data-idx", String(i));
            el.setAttribute("aria-label",
                "Step " + (i + 1)
                + (step.lineNumber > 0 ? ", line " + step.lineNumber : "")
                + (step.note ? ": " + step.note : ""));

            var lineNum = step.lineNumber > 0
                ? "<span class=\"tl-line\">L" + step.lineNumber + "</span>"
                : "<span class=\"tl-line\">-</span>";
            var note = step.note
                ? "<span class=\"tl-note\">" + esc(step.note.substring(0, 30)) + "</span>"
                : "";
            el.innerHTML = lineNum + note;

            el.addEventListener("click", function () { goToStep(i); });
            timelineEl.appendChild(el);
        });
    }

    function scrollTimelineToCursor() {
        var items = timelineEl.querySelectorAll(".tl-item");
        items.forEach(function (el, i) {
            el.classList.toggle("tl-active", i === cursor);
        });
        var active = timelineEl.querySelector(".tl-active");
        if (active) active.scrollIntoView({ block: "nearest", inline: "nearest" });
    }

    /* ============================================================
       Source line highlighting in the editor
       ============================================================ */
    function highlightCurrentLine() {
        if (cursor < 0 || !steps.length) return;
        var step = steps[cursor];
        var line = step.lineNumber;
        if (!line || line <= 0) return;

        // Scroll the textarea to make the current line visible
        var allLines = codeTextarea.value.split("\n");
        var totalLines = allLines.length;
        var lineHeight = codeTextarea.scrollHeight / totalLines;
        var targetScroll = Math.max(0, (line - 5) * lineHeight);
        codeTextarea.scrollTop = targetScroll;
        gutter.scrollTop = targetScroll;
    }

    function clearLineHighlight() {
        // Handled via gutter CSS classes rebuilt in updateGutter()
    }

    /* ============================================================
       Status helpers
       ============================================================ */
    function setStatus(html, kind) {
        statusBar.className = "xray-status-bar xray-status-" + (kind || "idle");
        statusMsg.innerHTML = html;
    }

    /* ============================================================
       Utility
       ============================================================ */
    function esc(s) {
        if (s == null) return "";
        return String(s)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function shortName(fqn) {
        if (!fqn) return "";
        var dot = fqn.lastIndexOf(".");
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    function shortTypeName(t) {
        if (!t) return "";
        t = t.replace(/^java\.lang\./, "").replace(/^java\.util\./, "");
        var dot = t.lastIndexOf(".");
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    function eventKindLabel(kind) {
        var labels = {
            line: "Line",
            method_entry: "Enter",
            method_exit: "Exit",
            exception: "Exception",
            start: "Start",
            end: "End"
        };
        return labels[kind] || (kind || "Step");
    }

    function makeErrorPre(text) {
        var pre = document.createElement("pre");
        pre.className = "output err";
        pre.textContent = text;
        return pre;
    }

    /* ============================================================
       Keyboard navigation within X-Ray panel
       ============================================================ */
    document.addEventListener("keydown", function (e) {
        if (panelXray.hidden || !steps.length) return;
        if (e.target === codeTextarea) return;
        if (e.key === "ArrowLeft"  || (e.key === "ArrowUp"   && e.altKey)) { e.preventDefault(); goToStep(cursor - 1); }
        if (e.key === "ArrowRight" || (e.key === "ArrowDown"  && e.altKey)) { e.preventDefault(); goToStep(cursor + 1); }
        if (e.key === "Home") { e.preventDefault(); goToStep(0); }
        if (e.key === "End")  { e.preventDefault(); goToStep(steps.length - 1); }
    });

    /* ============================================================
       Initial render
       ============================================================ */
    updateGutter();

})();
