// Adds simple colours to Java code blocks. No library is used.
// It only changes how the text looks; the text itself stays the same.
(function () {
    var keywords = [
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default",
        "do", "double", "else", "extends", "final", "finally", "float", "for", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
        "protected", "public", "return", "short", "static", "super", "switch", "this", "throw",
        "throws", "true", "false", "try", "void", "while"
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

    // Every code block except the grey "Output" blocks.
    var blocks = document.querySelectorAll("pre:not(.output) > code");
    for (var i = 0; i < blocks.length; i++) {
        highlight(blocks[i]);
    }
})();
