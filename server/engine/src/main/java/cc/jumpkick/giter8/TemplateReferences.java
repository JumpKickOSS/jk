// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.form.Field;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The lint over a template's StringTemplate source: an ask that can be left without a value (its
 * {@code when} can hide it, or it is not required) and has no default renders as an unset property,
 * so the template may read it only as a bare {@code $if(key)$} test or inside that test's branch.
 * Any other use renders nothing, and {@code key.truthy} fails the scaffold, so the lint names it
 * first. Reads every rendered file path and text file under the content root, and the values of
 * {@code default.properties}, the way {@link Giter8Apply} renders them.
 */
final class TemplateReferences {

    private static final Pattern IF = Pattern.compile("(?s)(else)?if\\s*\\((.*)\\)");
    private static final Pattern GUARD =
            Pattern.compile("\\(*\\s*([A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*)?\\s*\\)*");
    private static final Pattern NOT = Pattern.compile("!\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private TemplateReferences() {}

    /** One use of an unset-able ask outside its guard: where, and which ask. */
    record Use(String where, String key) {}

    /**
     * @throws IllegalArgumentException naming {@code displayPath}, the ask and the file that reads it
     */
    static void lint(JkTemplateToml.Meta meta, Path templateRoot, Map<String, String> properties, String displayPath)
            throws IOException {
        Set<String> unset = new LinkedHashSet<>();
        for (Field f : meta.asks()) {
            if (f.defaultValue() == null && (f.when() != null || !f.required())) unset.add(f.key());
        }
        if (unset.isEmpty()) return;
        Use use = firstUse(templateRoot, properties, unset);
        if (use == null) return;
        Field ask = meta.asks().stream()
                .filter(f -> f.key().equals(use.key()))
                .findFirst()
                .orElseThrow();
        String why = ask.when() != null ? "its when can hide it" : "is not required";
        throw new IllegalArgumentException(displayPath + ": [[ask]] '" + use.key() + "' has no default and " + why
                + ", but " + use.where() + " uses $" + use.key() + "$ outside $if(" + use.key()
                + ")$; add a default or guard the use");
    }

    static @Nullable Use firstUse(Path templateRoot, Map<String, String> properties, Set<String> unset)
            throws IOException {
        for (Map.Entry<String, String> e : properties.entrySet()) {
            String key = scan(e.getValue(), unset);
            if (key != null) return new Use("default.properties '" + e.getKey() + "'", key);
        }
        Path g8 = templateRoot.resolve("src/main/g8");
        Path contentRoot = Files.isDirectory(g8) ? g8 : templateRoot;
        List<Pattern> verbatim = Giter8Apply.verbatimPatterns(properties.get("verbatim"));
        Path display = templateRoot.toAbsolutePath().normalize();
        List<Use> found = new ArrayList<>(1);
        PathUtil.forEachEntry(contentRoot, dir -> ".git".equals(String.valueOf(dir.getFileName())), (file, attrs) -> {
            if (!attrs.isRegularFile()) return true;
            String fileName = file.getFileName().toString();
            if (fileName.equals("default.properties") || fileName.equals(JkTemplateToml.FILE_NAME)) return true;
            String rel = contentRoot.relativize(file).toString().replace('\\', '/');
            String key = scan(Giter8Render.rewritePathFormats(rel), unset);
            if (key == null && !Giter8Apply.matchesVerbatim(rel, fileName, verbatim)) {
                String text = Giter8Apply.decodeText(Files.readAllBytes(file));
                if (text != null) key = scan(text, unset);
            }
            if (key == null) return true;
            String where = display.relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            found.add(new Use(where, key));
            return false;
        });
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * The first ask in {@code unset} that {@code template} reads outside a guard, or {@code null}.
     * A guard is the branch of {@code $if(key)$} (or of {@code $if(a && key)$}, or the
     * {@code $else$} of {@code $if(!key)$}); a bare {@code key} in a condition is a test, not a use.
     */
    static @Nullable String scan(String template, Set<String> unset) {
        Deque<Branch> open = new ArrayDeque<>();
        int n = template.length();
        int i = 0;
        while (i < n) {
            char c = template.charAt(i);
            if (c == '\\' && i + 1 < n && template.charAt(i + 1) == '$') {
                i += 2;
                continue;
            }
            if (c != '$') {
                i++;
                continue;
            }
            if (template.startsWith("$!", i)) {
                int end = template.indexOf("!$", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            int close = closing(template, i + 1);
            if (close < 0) return null;
            String expr = template.substring(i + 1, close).strip();
            i = close + 1;
            Matcher m = IF.matcher(expr);
            if (m.matches()) {
                String condition = m.group(2);
                boolean elseIf = m.group(1) != null;
                if (elseIf && !open.isEmpty()) open.pop();
                String used = unguarded(condition, unset, true, open);
                if (used != null) return used;
                open.push(new Branch(positive(condition), elseIf ? Set.of() : negative(condition)));
            } else if (expr.equals("else")) {
                if (!open.isEmpty()) open.push(open.pop().otherwise());
            } else if (expr.equals("endif")) {
                if (!open.isEmpty()) open.pop();
            } else {
                String used = unguarded(options(expr), unset, false, open);
                if (used != null) return used;
            }
        }
        return null;
    }

    /** One open {@code $if$} chain: the keys its current branch proves set. */
    private record Branch(Set<String> guards, Set<String> elseGuards) {

        Branch otherwise() {
            return new Branch(elseGuards, Set.of());
        }
    }

    /** The index of the {@code $} closing an expression that starts at {@code from}, or -1. */
    private static int closing(String s, int from) {
        int depth = 0;
        boolean quoted = false;
        for (int j = from; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (quoted) {
                if (ch == '\\') j++;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (ch == '$' && depth <= 0) {
                return j;
            }
        }
        return -1;
    }

    /** The expression before its {@code ;} options and any anonymous {@code {…}} template. */
    private static String options(String expr) {
        boolean quoted = false;
        for (int j = 0; j < expr.length(); j++) {
            char ch = expr.charAt(j);
            if (ch == '"') quoted = !quoted;
            if (!quoted && (ch == ';' || ch == '{')) return expr.substring(0, j);
        }
        return expr;
    }

    /**
     * The first unset ask {@code expr} reads that no open branch guards. In a condition a bare
     * name only tests the property; {@code key.prop} reads it.
     */
    private static @Nullable String unguarded(String expr, Set<String> unset, boolean condition, Deque<Branch> open) {
        int n = expr.length();
        boolean quoted = false;
        for (int j = 0; j < n; j++) {
            char ch = expr.charAt(j);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted || !Character.isJavaIdentifierStart(ch) || ch == '$') continue;
            int end = j;
            while (end < n && Character.isJavaIdentifierPart(expr.charAt(end)) && expr.charAt(end) != '$') end++;
            String name = expr.substring(j, end);
            boolean member = j > 0 && expr.charAt(j - 1) == '.';
            boolean readsProperty = end < n && expr.charAt(end) == '.';
            j = end - 1;
            if (member || !unset.contains(name)) continue;
            if (condition && !readsProperty) continue;
            if (guarded(name, open)) continue;
            return name;
        }
        return null;
    }

    private static boolean guarded(String key, Deque<Branch> open) {
        for (Branch b : open) {
            if (b.guards().contains(key)) return true;
        }
        return false;
    }

    /** The keys a condition proves set in its own branch: every term of an {@code &&} chain. */
    private static Set<String> positive(String condition) {
        if (condition.contains("||") || condition.contains("!")) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String term : condition.split("&&")) {
            Matcher m = GUARD.matcher(term.strip());
            if (m.matches()) out.add(m.group(1));
        }
        return out;
    }

    /** The key a {@code !key} condition proves set in its {@code $else$}. */
    private static Set<String> negative(String condition) {
        Matcher m = NOT.matcher(condition.strip());
        return m.matches() ? Set.of(m.group(1)) : Set.of();
    }
}
