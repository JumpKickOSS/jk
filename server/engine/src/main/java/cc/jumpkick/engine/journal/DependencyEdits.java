// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The dependency edit a resolve conflict or a mismatched JUnit line names. The text carries
 * {@code deps(pin, g:a:version)} or {@code deps(remove, g:a)} so the agent report and the results
 * page spell the same edit.
 */
@NullMarked
final class DependencyEdits {

    static final String CODE = "version-conflict";

    private static final Pattern PROJECT = Pattern.compile("The project depends on (\\S+:\\S+) (\\S+)");

    private static final Pattern REQUIRES =
            Pattern.compile("(\\S+:\\S+) \\S+ depends on (\\S+:\\S+) (\\[[^\\]]+\\]|\\([^)]+\\)|\\S+)");

    private static final Pattern LINE =
            Pattern.compile("Two versions of the \\S+ line on the test classpath:(.*)", Pattern.DOTALL);

    private static final Pattern VERSION_ROW = Pattern.compile("(?m)^\\s*(\\d[^:\\s]*):\\s+(.+)$");

    private DependencyEdits() {}

    /** The hint for {@code message} and {@code stack}, or null when neither names a pin to change. */
    static JkResultsHints.@Nullable Hint hint(String message, String stack) {
        String text = message + "\n" + stack;
        if (text.contains("Cannot resolve dependencies")) {
            JkResultsHints.Hint resolve = resolve(text);
            if (resolve != null) return resolve;
        }
        if (text.contains("Two versions of the ")) return junitLine(text);
        return null;
    }

    /**
     * A project pin whose version falls outside a range some dependency requires. The replacement
     * is that range's lower bound when the pin sits below it — the version the platform declared —
     * and the edit is to remove the pin when no single version is named.
     */
    private static JkResultsHints.@Nullable Hint resolve(String text) {
        Map<String, String> required = new LinkedHashMap<>();
        Matcher req = REQUIRES.matcher(text);
        while (req.find()) required.putIfAbsent(req.group(2), req.group(3));
        List<String> edits = new ArrayList<>();
        List<String> named = new ArrayList<>();
        boolean pin = false;
        Matcher project = PROJECT.matcher(text);
        while (project.find() && edits.size() < 2) {
            String ga = project.group(1);
            String pinned = project.group(2);
            if (pinned.startsWith("[") || pinned.startsWith("(")) continue;
            String constraint = required.get(ga);
            if (constraint == null || satisfies(pinned, constraint)) continue;
            String replacement = replacement(pinned, constraint);
            if (replacement != null) {
                edits.add("deps(pin, " + ga + ":" + replacement + ")");
                pin = true;
            } else {
                edits.add("deps(remove, " + ga + ")");
            }
            named.add("`" + ga + "` `" + pinned + "`");
        }
        if (edits.isEmpty()) return null;
        String prose = pin
                ? "the exact pin " + named.get(0) + " is outside what the rest of the graph allows"
                : "the exact pin " + named.get(0) + " conflicts with the rest of the graph";
        return new JkResultsHints.Hint(CODE, prose + ": `" + String.join("` and `", edits) + "`.");
    }

    /** The odd version of one JUnit line, pinned forward to the version the rest of the line resolved. */
    private static JkResultsHints.@Nullable Hint junitLine(String text) {
        Matcher block = LINE.matcher(text);
        if (!block.find()) return null;
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        Matcher row = VERSION_ROW.matcher(block.group(1));
        while (row.find()) {
            List<String> artifacts = new ArrayList<>();
            for (String part : row.group(2).split(",")) {
                String ga = part.strip();
                int paren = ga.indexOf(" (");
                if (paren >= 0) ga = ga.substring(0, paren).strip();
                if (ga.indexOf(':') > 0) artifacts.add(ga);
            }
            if (!artifacts.isEmpty()) byVersion.put(row.group(1), artifacts);
        }
        if (byVersion.size() < 2) return null;
        String platform = null;
        int most = -1;
        for (var e : byVersion.entrySet()) {
            int n = e.getValue().size();
            if (n > most || (n == most && platform != null && Versions.compare(e.getKey(), platform) > 0)) {
                most = n;
                platform = e.getKey();
            }
        }
        if (platform == null) return null;
        String lineVersion = platform;
        List<String> majority = byVersion.get(lineVersion);
        if (majority == null) return null;
        List<String> edits = new ArrayList<>();
        for (var e : byVersion.entrySet()) {
            if (e.getKey().equals(lineVersion)) continue;
            for (String ga : e.getValue()) {
                if (majority.contains(ga) || edits.size() == 2) continue;
                edits.add("deps(pin, " + ga + ":" + lineVersion + ")");
            }
        }
        if (edits.isEmpty()) return null;
        String first = edits.get(0);
        String ga = first.substring("deps(pin, ".length(), first.lastIndexOf(':'));
        return new JkResultsHints.Hint(
                CODE,
                "`" + ga + "` is behind the rest of its line, which resolved `" + lineVersion + "`: `"
                        + String.join("` and `", edits)
                        + "`.");
    }

    /** True when {@code version} is inside {@code constraint} (an exact version or a Maven range). */
    static boolean satisfies(String version, String constraint) {
        if (constraint.startsWith("[") || constraint.startsWith("(")) return inRange(version, constraint);
        return Versions.compare(version, constraint) == 0;
    }

    /**
     * A version inside {@code constraint} to write in place of {@code version}, or null when the
     * constraint does not name one. A pin below {@code [V,+∞)} is replaced with {@code V}.
     */
    static @Nullable String replacement(String version, String constraint) {
        if (!(constraint.startsWith("[") || constraint.startsWith("("))) return constraint;
        String body = constraint.substring(1, constraint.length() - 1);
        int comma = body.indexOf(',');
        String low = comma < 0 ? body : body.substring(0, comma).strip();
        String high = comma < 0 ? "" : body.substring(comma + 1).strip();
        boolean upperInc = constraint.endsWith("]");
        if (finite(low) && Versions.compare(version, low) < 0) return low;
        if (finite(high) && upperInc && Versions.compare(version, high) > 0) return high;
        return null;
    }

    private static boolean inRange(String version, String constraint) {
        boolean lowerInc = constraint.startsWith("[");
        boolean upperInc = constraint.endsWith("]");
        String body = constraint.substring(1, constraint.length() - 1);
        int comma = body.indexOf(',');
        String low = comma < 0 ? body : body.substring(0, comma).strip();
        String high = comma < 0 ? "" : body.substring(comma + 1).strip();
        if (finite(low)) {
            int cmp = Versions.compare(version, low);
            if (lowerInc ? cmp < 0 : cmp <= 0) return false;
        }
        if (finite(high)) {
            int cmp = Versions.compare(version, high);
            if (upperInc ? cmp > 0 : cmp >= 0) return false;
        }
        return true;
    }

    private static boolean finite(String bound) {
        return !bound.isEmpty() && !bound.equals("*") && !bound.equals("+∞") && !bound.equals("+inf");
    }
}
