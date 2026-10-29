// Adds simple colours to Java code blocks. No library is used.
// It only changes how the text looks; the text itself stays the same.
(function () {
    var keywords = [
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
        "do", "double", "else", "extends", "final", "finally", "float", "for", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
        "protected", "public", "return", "short", "static", "super", "switch", "this", "throw",
        "throws", "true", "false", "try", "void", "while", "var", "enum", "assert", "synchronized", "volatile", "transient", "native", "strictfp"
    ];

    // One regular expression with 4 groups: comment | string | keyword | number
    var pattern = new RegExp(
        "(\\/\\/[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/)" +         // 1: comments
        "|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')" + // 2: strings and chars
        "|\\b(" + keywords.join("|") + ")\\b" +            // 3: keywords
        "|\\b(\\d+(?:\\.\\d+)?[LlFfDd]?)\\b",              // 4: numbers
        "g"
    );

    function escapeHtml(text) {
        return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    function highlight(code) {
        code.innerHTML = escapeHtml(code.textContent).replace(pattern, function (match, comment, string, keyword, number) {
            if (comment) return '<span class="tok-comment">' + match + "</span>";
            if (string) return '<span class="tok-string">' + match + "</span>";
            if (keyword) return '<span class="tok-keyword">' + match + "</span>";
            return '<span class="tok-number">' + match + "</span>";
        });
    }

    // Other scripts (project files, playground) can colour code they add later.
    window.JavaLearn = window.JavaLearn || {};
    window.JavaLearn.highlight = highlight;

    // Mobile sidebar toggle handler
    var sidebarToggle = document.getElementById("sidebar-toggle");
    var appSidebar = document.getElementById("app-sidebar");
    var sidebarBackdrop = document.getElementById("sidebar-backdrop");

    if (sidebarToggle && appSidebar) {
        sidebarToggle.addEventListener("click", function () {
            var open = appSidebar.classList.toggle("open");
            sidebarToggle.setAttribute("aria-expanded", String(open));
            if (sidebarBackdrop) sidebarBackdrop.classList.toggle("active", open);
        });
    }

    if (sidebarBackdrop && appSidebar) {
        sidebarBackdrop.addEventListener("click", function () {
            appSidebar.classList.remove("open");
            if (sidebarToggle) sidebarToggle.setAttribute("aria-expanded", "false");
            sidebarBackdrop.classList.remove("active");
        });
    }

    // Every code block except the grey "Output" blocks.
    var blocks = document.querySelectorAll("pre:not(.output) > code");
    for (var i = 0; i < blocks.length; i++) {
        highlight(blocks[i]);
    }

    // Smart Back Button behavior
    // Remember the last projects listing search/filter URL in sessionStorage
    if (window.location.pathname === "/projects") {
        try {
            sessionStorage.setItem("lastProjectsUrl", window.location.pathname + window.location.search);
        } catch (e) {}
    }

    document.addEventListener("click", function (e) {
        var backBtn = e.target.closest(".nav-back");
        if (!backBtn) return;

        // If on project details and returning to projects, prefer the saved filtered/searched listing URL
        var fallback = backBtn.getAttribute("data-fallback") || backBtn.getAttribute("href");
        if (fallback && fallback.indexOf("/projects") === 0) {
            try {
                var lastProjects = sessionStorage.getItem("lastProjectsUrl");
                if (lastProjects && lastProjects.indexOf("/projects") === 0) {
                    e.preventDefault();
                    window.location.href = lastProjects;
                    return;
                }
            } catch (err) {}
        }

        // Check if referrer is from the same origin
        if (document.referrer && window.history.length > 1) {
            try {
                var refUrl = new URL(document.referrer);
                if (refUrl.origin === window.location.origin && refUrl.pathname !== window.location.pathname) {
                    e.preventDefault();
                    window.history.back();
                    return;
                }
            } catch (err) {}
        }
        // If external or no history, allow default href fallback
    });
})();
