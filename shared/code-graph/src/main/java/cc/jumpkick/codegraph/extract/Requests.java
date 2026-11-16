// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP requests written in text: a URL to this service in a command line, a workflow step or a
 * code block ({@code curl -X POST http://localhost:8080/api/orders}), and a front-end call with a
 * literal path ({@code http.get('/api/orders')}, {@code fetch(`/api/orders/${id}`)}). Each is a
 * {@link Mention.Kind#ROUTE} mention, which the linker links only to a route a handler declares, so
 * a path that matches none adds nothing. A URL counts only when its host is this machine, a
 * variable or a one-label name such as a compose service: a public URL ({@code
 * https://docs.example.com/api/...}) is someone else's route.
 */
final class Requests {

    private Requests() {}

    private static final Pattern URL = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]"
            + "|\\$\\{?\\w+}?|[A-Za-z][\\w-]*)(?::(?:\\d+|\\$\\{?\\w+}?))?(/[^\\s'\"`)<>|;,\\\\]*)");
    private static final Pattern METHOD_FLAG = Pattern.compile("(?:-X|--request)\\s*['\"]?([A-Za-z]+)");
    private static final Pattern BODY_FLAG =
            Pattern.compile("(?:^|\\s)(?:-d|-F|-{2}(?:data(?:-raw|-binary)?|json|form))\\b");
    private static final Pattern CLIENT_CALL = Pattern.compile(
            "\\.(get|post|put|delete|patch|head|options)\\s*(?:<[^>()]*>)?\\s*\\(\\s*(['\"`])(/[^'\"`\\n]*)\\2",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCH = Pattern.compile("\\bfetch\\s*\\(\\s*(['\"`])(/[^'\"`\\n]*)\\1");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[^}]*}|\\$\\w+");

    /** The URLs to this service on one line of a command, a workflow, a config value or a code block. */
    static void urls(FragmentBuilder b, String from, String line, int n) {
        if (!line.contains("://")) return;
        Matcher u = URL.matcher(line);
        while (u.find()) {
            String path = variables(u.group(1));
            if (path.length() > 1) b.mention(from, Mention.Kind.ROUTE, method(line) + " " + path, n);
        }
    }

    /** Front-end calls with a literal or template path: {@code http.get('/api/x')}, {@code fetch(`/api/${id}`)}. */
    static void calls(FragmentBuilder b, String from, String text, Lines lines) {
        Matcher c = CLIENT_CALL.matcher(text);
        while (c.find()) {
            String path = variables(c.group(3));
            if (path.length() > 1) {
                b.mention(
                        from,
                        Mention.Kind.ROUTE,
                        c.group(1).toUpperCase(Locale.ROOT) + " " + path,
                        lines.at(c.start()));
            }
        }
        Matcher f = FETCH.matcher(text);
        while (f.find()) {
            String path = variables(f.group(2));
            if (path.length() > 1) b.mention(from, Mention.Kind.ROUTE, "* " + path, lines.at(f.start()));
        }
        for (int i = 0, n = 1; i < text.length(); n++) {
            int eol = text.indexOf('\n', i);
            String line = eol < 0 ? text.substring(i) : text.substring(i, eol);
            urls(b, from, line, n);
            if (eol < 0) break;
            i = eol + 1;
        }
    }

    /** The method a command line asks for: {@code -X}, else POST for curl with a body, GET for curl or wget, else any. */
    static String method(String line) {
        Matcher m = METHOD_FLAG.matcher(line);
        if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        boolean curl = line.contains("curl ");
        if (curl && BODY_FLAG.matcher(line).find()) return "POST";
        return curl || line.contains("wget ") ? "GET" : "*";
    }

    /** {@code ${id}} and {@code $ID} as a path variable, and a trailing query or fragment dropped. */
    static String variables(String path) {
        return VARIABLE.matcher(path).replaceAll("{}");
    }
}
