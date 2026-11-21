// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

/** Short display forms of the Java member and type names a test or step reports. */
public final class MemberLabel {

    private MemberLabel() {}

    /**
     * Human-facing member label: drop package FQCNs so clients never paint wire-shaped names.
     * {@code cc.jumpkick.FooTest.bar(java.nio.file.Path)} → {@code FooTest.bar(Path)}.
     * Preserves a trailing {@code  [wN]} worker tag when present. Leaves ordinary prose, versions,
     * and jar names untouched.
     */
    public static String shorten(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        String worker = "";
        String body = raw.strip();
        int w = body.lastIndexOf("  [w");
        if (w > 0 && body.endsWith("]")) {
            worker = body.substring(w);
            body = body.substring(0, w).strip();
        }
        if (!looksLikeJavaishLabel(body)) {
            return worker.isEmpty() ? body : body + worker;
        }
        body = simplifyMethodParams(body);
        int paren = body.indexOf('(');
        int searchEnd = paren >= 0 ? paren : body.length();
        int dot = body.lastIndexOf('.', searchEnd - 1);
        if (dot > 0 && dot < body.length() - 1) {
            String after = body.substring(dot + 1, searchEnd);
            // method / <init> after the last pre-paren dot → class is everything before it
            if (!after.isEmpty()
                    && (Character.isLowerCase(after.charAt(0)) || after.charAt(0) == '_' || after.startsWith("<"))) {
                String cls = simpleName(body.substring(0, dot));
                String method = simplifyMethodParams(body.substring(dot + 1));
                body = cls.isEmpty() ? method : cls + "." + method;
            } else {
                // package.Class or package.Class(…) — keep suffix from '(' onward
                body = simpleName(body.substring(0, searchEnd)) + body.substring(searchEnd);
            }
        }
        return worker.isEmpty() ? body : body + worker;
    }

    /**
     * True for Java member / type labels we may shorten — not versions, jar names, or free prose.
     * Accepts already-simple {@code FooTest.bar(Path)} and wire-shaped FQCNs.
     */
    private static boolean looksLikeJavaishLabel(String body) {
        if (body == null || body.isEmpty()) return false;
        // Spaces are only allowed inside a trailing param list: Foo.bar(A, B).
        int open = body.indexOf('(');
        String head = open >= 0 ? body.substring(0, open) : body;
        if (head.indexOf(' ') >= 0) return false;
        if (open >= 0) {
            int close = body.lastIndexOf(')');
            if (close < open) return false;
            if (close + 1 < body.length() && body.substring(close + 1).indexOf(' ') >= 0) return false;
        }
        char c0 = body.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_' || c0 == '$')) return false;
        // Param list with a package-looking token, or a package.Class segment.
        if (open >= 0) {
            // method(...) — shorten when params contain dots or the receiver is a type name
            return body.indexOf('.') >= 0 || Character.isUpperCase(c0);
        }
        // Type or Class.method without params: require either a capital segment (type) after a
        // package, or a simple Capitalized identifier / Class.method form.
        if (body.indexOf('.') < 0) {
            return Character.isUpperCase(c0); // FooTest / AssertionFailedError
        }
        // package.Class / package.Class.method / FooTest.bar
        return body.matches("(?:[a-z][\\w$]*\\.)*[A-Z][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?");
    }

    /**
     * Keep {@code (…)} but strip package prefixes inside params: {@code (java.nio.file.Path)} →
     * {@code (Path)}. Arrays / varargs suffixes are preserved ({@code String[]}, {@code Path...}).
     */
    private static String simplifyMethodParams(String method) {
        if (method == null || method.isEmpty()) return "";
        int open = method.indexOf('(');
        int close = method.lastIndexOf(')');
        if (open < 0 || close <= open) return method.strip();
        String name = method.substring(0, open).strip();
        String inside = method.substring(open + 1, close).strip();
        String after = method.substring(close + 1);
        if (inside.isEmpty()) return name + "()" + after;
        StringBuilder simplified = new StringBuilder();
        for (String part : inside.split(",")) {
            String p = part.strip();
            String arraySuffix = "";
            while (p.endsWith("...") || p.endsWith("[]")) {
                if (p.endsWith("...")) {
                    arraySuffix = "..." + arraySuffix;
                    p = p.substring(0, p.length() - 3).strip();
                } else {
                    arraySuffix = "[]" + arraySuffix;
                    p = p.substring(0, p.length() - 2).strip();
                }
            }
            int d = p.lastIndexOf('.');
            if (d >= 0) p = p.substring(d + 1);
            if (!simplified.isEmpty()) simplified.append(", ");
            simplified.append(p).append(arraySuffix);
        }
        return name + "(" + simplified + ")" + after;
    }

    /** The last dot-separated segment of {@code fqcn}: {@code a.b.Foo} → {@code Foo}. */
    public static String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return "";
        int d = fqcn.lastIndexOf('.');
        return d >= 0 ? fqcn.substring(d + 1) : fqcn;
    }
}
