// Java playground page - Run mode.
// Code is sent to /api/run, which forwards to an external execution service.
// Nothing here claims success unless the server reports a real run result.
(function () {
    "use strict";

    var SAMPLES = [
        { name: "Hello World", stdin: "", code:
"public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, JavaLearn!\");\n    }\n}\n" },
        { name: "Read input (Scanner)", stdin: "Ava\n21\n", code:
"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        System.out.print(\"Name: \");\n        String name = sc.nextLine();\n        System.out.print(\"Age: \");\n        int age = sc.nextInt();\n        System.out.println(name + \" will be \" + (age + 1) + \" next year.\");\n    }\n}\n" },
        { name: "Loops: multiplication table", stdin: "", code:
"public class Main {\n    public static void main(String[] args) {\n        for (int i = 1; i <= 5; i++) {\n            for (int j = 1; j <= 5; j++) {\n                System.out.printf(\"%4d\", i * j);\n            }\n            System.out.println();\n        }\n    }\n}\n" },
        { name: "Classes and objects", stdin: "", code:
"class Account {\n    private double balance;\n\n    void deposit(double amount) {\n        if (amount > 0) {\n            balance += amount;\n        }\n    }\n\n    double getBalance() {\n        return balance;\n    }\n}\n\npublic class Main {\n    public static void main(String[] args) {\n        Account a = new Account();\n        a.deposit(100);\n        a.deposit(-5);\n        System.out.println(\"Balance: \" + a.getBalance());\n    }\n}\n" },
        { name: "Recursion (factorial)", stdin: "", code:
"public class Main {\n    static int factorial(int n) {\n        if (n <= 1) return 1;\n        return n * factorial(n - 1);\n    }\n    public static void main(String[] args) {\n        System.out.println(factorial(5));\n    }\n}\n" },
        { name: "Array iteration", stdin: "", code:
"public class Main {\n    public static void main(String[] args) {\n        int[] nums = {10, 20, 30, 40, 50};\n        int sum = 0;\n        for (int n : nums) sum += n;\n        System.out.println(\"Sum: \" + sum);\n    }\n}\n" },
        { name: "Compile error (try it)", stdin: "", code:
"public class Main {\n    public static void main(String[] args) {\n        int count = \"five\";\n        System.out.println(count)\n    }\n}\n" },
        { name: "Runtime error (try it)", stdin: "", code:
"public class Main {\n    public static void main(String[] args) {\n        int[] numbers = new int[3];\n        numbers[3] = 10;\n        System.out.println(\"not reached\");\n    }\n}\n" }
    ];

    var root = document.getElementById("sandbox");
    if (!root) return;
    var runUrl   = root.getAttribute("data-run-url");
    var coachUrl = root.getAttribute("data-coach-url");
    var aiUrl    = root.getAttribute("data-ai-url");
    var enabled  = root.getAttribute("data-enabled") === "true";

    var code      = document.getElementById("code");
    var stdin     = document.getElementById("stdin");
    var sample    = document.getElementById("sample");
    var runButton = document.getElementById("run");
    var analyzeButton = document.getElementById("analyze-btn");
    var status    = document.getElementById("run-status");
    var meta      = document.getElementById("run-meta");
    var panels    = {
        compile: document.getElementById("panel-compile"),
        stdout:  document.getElementById("panel-stdout"),
        stderr:  document.getElementById("panel-stderr")
    };
    var current = 0;
    var running = false;
    var analyzing = false;

    // Track execution and analyzed versions of code
    var lastRunCode = null;
    var lastRunResult = null;
    var lastAnalyzedCode = null;

    // Populate sample selector
    SAMPLES.forEach(function (s, i) {
        var option = document.createElement("option");
        option.value = String(i);
        option.textContent = s.name;
        sample.appendChild(option);
    });

    function loadSample(i) {
        current = i;
        code.value   = SAMPLES[i].code;
        if (stdin) stdin.value = SAMPLES[i].stdin;
        sample.value = String(i);
        clearOutput("Nothing has been run yet.");
        hideCoachReport();
        lastRunCode = null;
        lastRunResult = null;
        lastAnalyzedCode = null;
        // Notify xray.js of content change
        code.dispatchEvent(new Event("input"));
    }

    function clearOutput(text) {
        if (status) {
            status.className = "run-status";
            status.textContent = text;
        }
        if (meta) meta.textContent = "";
        for (var key in panels) { if (panels[key]) panels[key].hidden = true; }
    }

    function show(panel, text) {
        if (typeof text !== "string" || text === "" || !panel) return;
        panel.querySelector("code").textContent = text;
        panel.hidden = false;
    }

    function finish(kind, message, data) {
        if (!status) return;
        status.className = "run-status " + kind;
        status.textContent = message;
        if (data) {
            show(panels.compile, data.compileOutput);
            show(panels.stdout,  data.stdout);
            show(panels.stderr,  data.stderr);
            var bits = [];
            if (data.time)                     bits.push("CPU time: " + data.time + " s");
            if (typeof data.memoryKb === "number") bits.push("Memory: " + data.memoryKb + " KB");
            if (data.language)                 bits.push(data.language);
            if (meta) meta.textContent = bits.join(" \u00B7 ");
        }
    }

    function run() {
        if (running || !enabled) return;
        if (code.value.trim() === "") { clearOutput("Write some code first."); return; }
        running = true;
        var currentSubmittingCode = code.value;
        runButton.disabled = true;
        runButton.textContent = "Running\u2026";
        clearOutput("Compiling and running\u2026 this can take a few seconds.");
        if (status) status.className = "run-status busy";

        var controller = new AbortController();
        var timer = setTimeout(function () { controller.abort(); }, 40000);

        fetch(runUrl, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ code: code.value, stdin: stdin ? stdin.value : "" }),
            signal: controller.signal
        })
        .then(function (response) {
            return response.json().catch(function () { return null; }).then(function (data) {
                if (!data || typeof data.status !== "string") {
                    throw new Error("The server answered with an unexpected response (HTTP " + response.status + ").");
                }
                return data;
            });
        })
        .then(function (data) {
            lastRunCode = currentSubmittingCode;
            lastRunResult = data;
            var kind = { success: "ok", compile_error: "fail", runtime_error: "fail", timeout: "fail" }[data.status] || "warn";
            finish(kind, data.message || "Finished.", data);
        })
        .catch(function (error) {
            var message = error && error.name === "AbortError"
                ? "The run took too long and was cancelled. Please try again."
                : (error && error.message) || "The server could not be reached.";
            finish("warn", message, null);
        })
        .then(function () {
            clearTimeout(timer);
            running = false;
            runButton.disabled = !enabled;
            runButton.textContent = "Run";
        });
    }

    /* ============================================================
       Code Quality Coach Frontend Logic
       ============================================================ */
    var coachReportEl       = document.getElementById("coach-report");
    var coachOutdatedBadge  = document.getElementById("coach-outdated-badge");
    var coachCloseBtn       = document.getElementById("coach-close");
    var coachTimeVal        = document.getElementById("coach-time-val");
    var coachTimeDesc       = document.getElementById("coach-time-desc");
    var coachTimeCases      = document.getElementById("coach-time-cases");
    var coachSpaceVal       = document.getElementById("coach-space-val");
    var coachSpaceDesc      = document.getElementById("coach-space-desc");
    var coachAssumptions    = document.getElementById("coach-assumptions");
    var coachIssuesList     = document.getElementById("coach-issues-list");
    var coachSuggestionsList= document.getElementById("coach-suggestions-list");

    function hideCoachReport() {
        if (coachReportEl) coachReportEl.hidden = true;
    }

    if (coachCloseBtn) {
        coachCloseBtn.addEventListener("click", hideCoachReport);
    }

    // When the user edits code, mark previous analysis as outdated
    code.addEventListener("input", function () {
        if (coachReportEl && !coachReportEl.hidden) {
            if (lastAnalyzedCode !== null && code.value !== lastAnalyzedCode) {
                if (coachOutdatedBadge) coachOutdatedBadge.hidden = false;
            } else {
                if (coachOutdatedBadge) coachOutdatedBadge.hidden = true;
            }
        }
    });

    function analyzeCode() {
        if (analyzing) return;
        var currentSource = code.value;
        if (!currentSource || currentSource.trim() === "") {
            clearOutput("Write some code first to analyze.");
            return;
        }

        analyzing = true;
        if (analyzeButton) {
            analyzeButton.disabled = true;
            analyzeButton.textContent = "Analyzing\u2026";
        }

        // Only send execution result if it matches current editor source exactly
        var executionToPass = null;
        if (lastRunCode !== null && lastRunCode === currentSource && lastRunResult) {
            executionToPass = lastRunResult;
        }

        fetch(coachUrl || "/api/coach/analyze", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                code: currentSource,
                lastRunResult: executionToPass
            })
        })
        .then(function (res) {
            if (!res.ok) throw new Error("HTTP error " + res.status);
            return res.json();
        })
        .then(function (report) {
            if (!report || report.status === "error") {
                clearOutput(report ? report.message : "Analysis failed.");
                return;
            }
            lastAnalyzedCode = currentSource;
            renderCoachReport(report);
        })
        .catch(function (err) {
            clearOutput("Could not analyze code: " + (err.message || "Unknown error"));
        })
        .then(function () {
            analyzing = false;
            if (analyzeButton) {
                analyzeButton.disabled = false;
                analyzeButton.textContent = "\uD83D\uDCA1 Analyze Code";
            }
        });
    }

    function renderCoachReport(report) {
        if (!coachReportEl) return;
        coachReportEl.hidden = false;
        if (coachOutdatedBadge) coachOutdatedBadge.hidden = true;

        // 1. Complexity
        var c = report.complexity;
        if (c) {
            if (coachTimeVal) coachTimeVal.textContent = c.timeComplexity || "Undetermined";
            if (coachTimeDesc) coachTimeDesc.textContent = c.timeExplanation || "";
            if (coachTimeCases) {
                if (c.timeCases) {
                    coachTimeCases.textContent = c.timeCases;
                    coachTimeCases.hidden = false;
                } else {
                    coachTimeCases.hidden = true;
                }
            }

            if (coachSpaceVal) coachSpaceVal.textContent = c.spaceComplexity || "Undetermined";
            if (coachSpaceDesc) coachSpaceDesc.textContent = c.spaceExplanation || "";
            if (coachAssumptions) {
                coachAssumptions.textContent = c.assumptions ? "Assumptions: " + c.assumptions : "";
            }
        }

        // 2. Issues
        if (coachIssuesList) {
            coachIssuesList.innerHTML = "";
            var issues = report.issues || [];
            if (issues.length === 0) {
                var empty = document.createElement("p");
                empty.className = "coach-empty-msg";
                empty.textContent = "No compiler errors or significant anti-patterns detected.";
                coachIssuesList.appendChild(empty);
            } else {
                issues.forEach(function (iss) {
                    var div = document.createElement("div");
                    div.className = "coach-issue-item " + (iss.severity || "info");

                    var header = document.createElement("div");
                    header.className = "issue-header";

                    var badge = document.createElement("span");
                    badge.className = "issue-badge";
                    badge.textContent = iss.category || iss.severity;
                    header.appendChild(badge);

                    if (iss.lineNumber && iss.lineNumber > 0) {
                        var line = document.createElement("span");
                        line.className = "issue-line";
                        line.textContent = "Line " + iss.lineNumber;
                        header.appendChild(line);
                    }
                    div.appendChild(header);

                    var msg = document.createElement("div");
                    msg.className = "issue-message";
                    msg.textContent = iss.message;
                    div.appendChild(msg);

                    if (iss.explanation) {
                        var exp = document.createElement("p");
                        exp.className = "issue-explanation";
                        exp.textContent = iss.explanation;
                        div.appendChild(exp);
                    }

                    coachIssuesList.appendChild(div);
                });
            }
        }

        // 3. Suggestions
        if (coachSuggestionsList) {
            coachSuggestionsList.innerHTML = "";
            var suggestions = report.suggestions || [];
            if (suggestions.length === 0) {
                var emptyS = document.createElement("p");
                emptyS.className = "coach-empty-msg";
                emptyS.textContent = "Code is concise and clean. No immediate refactoring needed.";
                coachSuggestionsList.appendChild(emptyS);
            } else {
                suggestions.forEach(function (s) {
                    var sDiv = document.createElement("div");
                    sDiv.className = "coach-suggestion-item";

                    var title = document.createElement("div");
                    title.className = "suggestion-title";
                    title.textContent = s.title;
                    sDiv.appendChild(title);

                    var exp = document.createElement("p");
                    exp.className = "suggestion-explanation";
                    exp.textContent = s.explanation;
                    sDiv.appendChild(exp);

                    if (s.snippet) {
                        var wrap = document.createElement("div");
                        wrap.className = "suggestion-snippet-wrap";
                        var slabel = document.createElement("span");
                        slabel.className = "snippet-label";
                        slabel.textContent = "Example alternative:";
                        wrap.appendChild(slabel);
                        var pre = document.createElement("pre");
                        pre.className = "suggestion-snippet";
                        pre.textContent = s.snippet;
                        wrap.appendChild(pre);
                        sDiv.appendChild(wrap);
                    }

                    if (s.performanceImpact) {
                        var perf = document.createElement("div");
                        perf.className = "suggestion-perf";
                        perf.textContent = "\u2192 Impact: " + s.performanceImpact;
                        sDiv.appendChild(perf);
                    }

                    coachSuggestionsList.appendChild(sDiv);
                });
            }
        }

        // Smooth scroll to report if not visible
        coachReportEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    if (analyzeButton) analyzeButton.addEventListener("click", analyzeCode);

    /* ============================================================
       Tab switching (Fall-safe in case xray.js is cached or delayed)
       ============================================================ */
    var tabRunFallback  = document.getElementById("tab-run");
    var tabXrayFallback = document.getElementById("tab-xray");
    var tabAiFallback   = document.getElementById("tab-ai");
    var panelRunEl      = document.getElementById("panel-run");
    var panelXrayEl     = document.getElementById("panel-xray");
    var panelAiEl       = document.getElementById("panel-ai");
    var runControlsEl   = document.getElementById("run-controls");
    var xrayControlsEl  = document.getElementById("xray-controls");

    function activateTab(tabName) {
        var isRun  = (tabName === "run");
        var isXray = (tabName === "xray");
        var isAi   = (tabName === "ai");

        if (tabRunFallback) {
            tabRunFallback.classList.toggle("active", isRun);
            tabRunFallback.setAttribute("aria-selected", String(isRun));
        }
        if (tabXrayFallback) {
            tabXrayFallback.classList.toggle("active", isXray);
            tabXrayFallback.setAttribute("aria-selected", String(isXray));
        }
        if (tabAiFallback) {
            tabAiFallback.classList.toggle("active", isAi);
            tabAiFallback.setAttribute("aria-selected", String(isAi));
        }

        if (panelRunEl)  panelRunEl.hidden  = !isRun;
        if (panelXrayEl) panelXrayEl.hidden = !isXray;
        if (panelAiEl)   panelAiEl.hidden   = !isAi;

        if (runControlsEl)  runControlsEl.hidden  = isXray;
        if (xrayControlsEl) xrayControlsEl.hidden = !isXray;
    }

    if (tabRunFallback)  tabRunFallback.addEventListener("click", function () { activateTab("run"); });
    if (tabXrayFallback) tabXrayFallback.addEventListener("click", function () { activateTab("xray"); });
    if (tabAiFallback)   tabAiFallback.addEventListener("click", function () { activateTab("ai"); });

    /* ============================================================
       AI Assistant Frontend Logic
       ============================================================ */
    var aiChatArea      = document.getElementById("ai-chat-area");
    var aiPrompt        = document.getElementById("ai-prompt");
    var aiSendBtn       = document.getElementById("ai-send-btn");
    var aiClearBtn      = document.getElementById("ai-clear-btn");
    var aiChipExplain   = document.getElementById("ai-chip-explain");
    var aiChipFix       = document.getElementById("ai-chip-fix");
    var aiChipGenerate  = document.getElementById("ai-chip-generate");
    var tabAi           = document.getElementById("tab-ai");

    var aiHistory = [];
    var aiProcessing = false;

    // Load session history if present
    try {
        var savedHistory = sessionStorage.getItem("javaLearnAiHistory");
        if (savedHistory) {
            var parsed = JSON.parse(savedHistory);
            if (Array.isArray(parsed) && parsed.length > 0) {
                aiHistory = parsed;
                // Render saved history
                aiHistory.forEach(function (m) {
                    appendAiMessage(m.role, m.content, m.code, false);
                });
            }
        }
    } catch (e) {}

    function saveAiHistory() {
        try {
            sessionStorage.setItem("javaLearnAiHistory", JSON.stringify(aiHistory));
        } catch (e) {}
    }

    function appendAiMessage(role, text, codeBlock, save) {
        if (!aiChatArea) return;
        var msgDiv = document.createElement("div");
        msgDiv.className = "ai-msg ai-msg-" + (role === "user" ? "user" : (role === "error" ? "error" : "assistant"));

        // Format basic markdown text into paragraphs
        var paragraphs = (text || "").split(/\n\n+/);
        paragraphs.forEach(function (pText) {
            var p = document.createElement("p");
            p.textContent = pText.trim();
            msgDiv.appendChild(p);
        });

        // If runnable Java code was produced, attach the actionable code block
        if (codeBlock && codeBlock.trim()) {
            var box = document.createElement("div");
            box.className = "ai-code-box";

            var head = document.createElement("div");
            head.className = "ai-code-box-header";
            head.innerHTML = "<span>Java Code</span>";

            var actions = document.createElement("div");
            actions.className = "ai-code-actions";

            var copyBtn = document.createElement("button");
            copyBtn.type = "button";
            copyBtn.className = "ai-code-btn";
            copyBtn.textContent = "Copy";
            copyBtn.addEventListener("click", function () {
                navigator.clipboard.writeText(codeBlock).then(function () {
                    copyBtn.textContent = "Copied!";
                    setTimeout(function () { copyBtn.textContent = "Copy"; }, 2000);
                });
            });

            var replaceBtn = document.createElement("button");
            replaceBtn.type = "button";
            replaceBtn.className = "ai-code-btn";
            replaceBtn.textContent = "Replace Editor";
            replaceBtn.title = "Replace the code editor content with this solution";
            replaceBtn.addEventListener("click", function () {
                if (confirm("Replace editor with this Java code? Your current code will be replaced.")) {
                    code.value = codeBlock;
                    code.dispatchEvent(new Event("input"));
                    // Switch to Run mode so the user can immediately run/inspect
                    var tabRun = document.getElementById("tab-run");
                    if (tabRun) tabRun.click();
                    clearOutput("AI suggested code applied to editor. Click Run to test it.");
                }
            });

            actions.appendChild(copyBtn);
            actions.appendChild(replaceBtn);
            head.appendChild(actions);
            box.appendChild(head);

            var pre = document.createElement("pre");
            pre.className = "ai-code-pre";
            var cElem = document.createElement("code");
            cElem.textContent = codeBlock;
            pre.appendChild(cElem);
            box.appendChild(pre);

            if (window.JavaLearn && window.JavaLearn.highlight) {
                window.JavaLearn.highlight(cElem);
            }

            msgDiv.appendChild(box);
        }

        aiChatArea.appendChild(msgDiv);
        aiChatArea.scrollTop = aiChatArea.scrollHeight;

        if (save && (role === "user" || role === "assistant")) {
            aiHistory.push({ role: role, content: text, code: codeBlock });
            saveAiHistory();
        }
    }

    function sendAiRequest(action, customPrompt) {
        if (aiProcessing) return;
        var promptText = (customPrompt !== undefined ? customPrompt : (aiPrompt ? aiPrompt.value : "")).trim();
        var currentSource = code ? code.value : "";

        if (!promptText && !currentSource) {
            if (aiPrompt) aiPrompt.focus();
            return;
        }

        // Switch to AI tab if not already active
        if (tabAi && tabAi.getAttribute("aria-selected") !== "true") {
            tabAi.click();
        }

        // Show user message in chat
        var displayPrompt = promptText;
        if (action === "explain" && !displayPrompt) displayPrompt = "Please explain the current Java code in the editor.";
        if (action === "fix" && !displayPrompt) displayPrompt = "Please fix the errors in my current code.";

        appendAiMessage("user", displayPrompt, null, true);
        if (aiPrompt) aiPrompt.value = "";

        aiProcessing = true;
        if (aiSendBtn) {
            aiSendBtn.disabled = true;
            aiSendBtn.textContent = "Thinking\u2026";
        }

        // Include compiler or runtime errors if available from recent execution
        var errors = "";
        if (lastRunResult) {
            if (lastRunResult.compileOutput) errors += lastRunResult.compileOutput + "\n";
            if (lastRunResult.stderr) errors += lastRunResult.stderr + "\n";
        }

        // Create temporary thinking indicator
        var thinkingMsg = document.createElement("div");
        thinkingMsg.className = "ai-msg ai-msg-system";
        thinkingMsg.id = "ai-thinking-indicator";
        thinkingMsg.textContent = "AI Assistant is analyzing...";
        aiChatArea.appendChild(thinkingMsg);
        aiChatArea.scrollTop = aiChatArea.scrollHeight;

        fetch(aiUrl || "/api/ai/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                prompt: promptText,
                action: action || "ask",
                code: currentSource,
                errors: errors,
                history: aiHistory.slice(-8) // Send recent turns for context
            })
        })
        .then(function (res) {
            if (!res.ok) throw new Error("HTTP error " + res.status);
            return res.json();
        })
        .then(function (data) {
            var th = document.getElementById("ai-thinking-indicator");
            if (th) th.remove();

            if (!data || data.status === "error" || data.status === "unavailable") {
                appendAiMessage("error", data ? data.message : "The assistant encountered an unexpected error.", null, false);
                return;
            }

            appendAiMessage("assistant", data.reply || "Done.", data.code, true);
        })
        .catch(function (err) {
            var th = document.getElementById("ai-thinking-indicator");
            if (th) th.remove();
            appendAiMessage("error", "Could not reach AI Assistant: " + (err.message || "Unknown error"), null, false);
        })
        .then(function () {
            aiProcessing = false;
            if (aiSendBtn) {
                aiSendBtn.disabled = false;
                aiSendBtn.textContent = "Send";
            }
        });
    }

    if (aiSendBtn) {
        aiSendBtn.addEventListener("click", function () {
            sendAiRequest("ask");
        });
    }

    if (aiPrompt) {
        aiPrompt.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                sendAiRequest("ask");
            }
        });
    }

    if (aiChipExplain) {
        aiChipExplain.addEventListener("click", function () {
            sendAiRequest("explain", "Please explain my code step by step.");
        });
    }

    if (aiChipFix) {
        aiChipFix.addEventListener("click", function () {
            sendAiRequest("fix", "Please diagnose and fix any errors in my code.");
        });
    }

    if (aiChipGenerate) {
        aiChipGenerate.addEventListener("click", function () {
            var task = prompt("What Java program would you like the AI to generate?\n(e.g., 'Check if a string is palindrome' or 'Bubble sort an array')");
            if (task && task.trim()) {
                sendAiRequest("generate", task.trim());
            }
        });
    }

    if (aiClearBtn) {
        aiClearBtn.addEventListener("click", function () {
            if (confirm("Start a new chat conversation?")) {
                aiHistory = [];
                try { sessionStorage.removeItem("javaLearnAiHistory"); } catch (e) {}
                if (aiChatArea) {
                    aiChatArea.innerHTML = '<div class="ai-msg ai-msg-system"><p>New conversation started. Ask a question or use the quick actions above.</p></div>';
                }
            }
        });
    }

    if (runButton) runButton.addEventListener("click", run);

    var resetBtn = document.getElementById("reset");
    if (resetBtn) resetBtn.addEventListener("click", function () { loadSample(current); });

    if (sample) sample.addEventListener("change", function () {
        loadSample(parseInt(sample.value, 10));
    });

    code.addEventListener("keydown", function (event) {
        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
            event.preventDefault();
            // Run in Run mode, trace in X-Ray mode
            var tabXray = document.getElementById("tab-xray");
            if (tabXray && tabXray.getAttribute("aria-selected") === "true") {
                var xrayStart = document.getElementById("xray-start");
                if (xrayStart && !xrayStart.disabled) xrayStart.click();
            } else {
                run();
            }
            return;
        }
        if (event.key === "Escape") { code.setAttribute("data-free", "1"); return; }
        if (event.key === "Tab" && !event.shiftKey && !code.getAttribute("data-free")) {
            event.preventDefault();
            var start = code.selectionStart, end = code.selectionEnd;
            code.value = code.value.slice(0, start) + "    " + code.value.slice(end);
            code.selectionStart = code.selectionEnd = start + 4;
            code.dispatchEvent(new Event("input"));
        } else if (event.key !== "Shift") {
            code.removeAttribute("data-free");
        }
    });

    loadSample(0);
})();
