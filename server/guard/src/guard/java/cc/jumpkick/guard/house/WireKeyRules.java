// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.OwnerMissing;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Closure over jk's JSON field vocabulary: <strong>no decoder reads a key no encoder writes</strong>.
 *
 * <p>A {@code WireKeys} constant table would move every literal into one file and still permit
 * {@code Jsonl.str(line, WireKeys.TESTS_TOTAL)} against an encoder that stopped writing it: the table
 * proves both sides spell the string the same way, not that the string is on the wire. A record with
 * {@code encode()} and {@code decode(String)} in one file closes that for its own message; this
 * closes it for the whole tree, including the hand-rolled reads in {@code engine.verbs} and the CLI
 * that no record covers.
 *
 * <p>A guard and not a JUnit class in the wire module: the check reads every module's production
 * source, so it is keyed to that text here, and it never walks the checkout from a test JVM while
 * the build that runs it is writing the tree (JK-YFNCP).
 *
 * <p><strong>What the patterns can see</strong>, because a guard's count is bounded by its detection
 * pattern and not by the defect:
 *
 * <ul>
 *   <li>Reads: a {@code Jsonl.<reader>(…, "literal")} call, arguments split with real paren and
 *       string-literal balancing so a nested call in receiver position is not a blind spot. A key
 *       passed as a <em>constant</em> ({@code ProtoJobs.JDKS_DIR}) is deliberately invisible: it is
 *       owned by definition, and its writer spells it from the same constant.
 *   <li>Writes: a {@code \"key\":} JSON-object literal inside a Java string, a {@code .put("key", …)}
 *       on one of the map/JsonOut builders, or a typed field call on {@code RequestJson}.
 * </ul>
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class WireKeyRules {

    private static final String MAIN_JAVA = "**/src/main/java/**/*.java";

    /** This guard's own fixture trees, which break the rule on purpose and are no wire of jk's. */
    private static final String OWN_FIXTURES = "server/guard/fixtures/wire-key-closure/";

    private static final String GROOVY = "written by jk-import-model.init.gradle, read by GradleModel";

    /**
     * A key read with no writer in the tree, and why that is not a defect. Each entry has to stay
     * <em>necessary</em>: the guard fails when one of these gains a writer or stops being read, so a
     * fix deletes its line here in the same change instead of leaving a stale exemption behind. They
     * are also this guard's floor: a scan that misses the tree reads none of them and fails.
     */
    static final Map<String, String> EXEMPT = Map.ofEntries(
            // jbang-catalog.json is somebody else's document format. jk reads it and never writes one.
            Map.entry("java-options", "foreign format: jbang-catalog.json"),
            Map.entry("script-ref", "foreign format: jbang-catalog.json"),
            // jk-import-model.init.gradle writes the xjc and schema-registry plugins' settings, in Groovy.
            Map.entry("xsdDir", GROOVY),
            Map.entry("defaultPackage", GROOVY),
            Map.entry("bindingFiles", GROOVY),
            Map.entry("auth", GROOVY),
            Map.entry("usernameEnv", GROOVY),
            Map.entry("passwordEnv", GROOVY),
            Map.entry("registers", GROOVY),
            Map.entry("specPath", GROOVY),
            Map.entry("modelPackages", GROOVY),
            Map.entry("generatesCode", GROOVY),
            Map.entry("specArtifact", GROOVY));

    @Guard(
            id = "wire-key-closure",
            why = "a JSON key read that nothing in the tree writes is a decoder for a field no encoder sends:"
                    + " the encoder dropped it, or the reader spells it differently from the writer",
            instead = "a wire message as a record with encode() and decode(String) in one file; a key read"
                    + " anywhere else is written by the encoder it reads, spelled the same")
    @Fixture("server/guard/fixtures/wire-key-closure")
    void wireKeyClosure(Text text, Violations v) {
        // Each literal-key read: the key, then a site per file that reads it.
        Map<String, List<TextSite>> reads = new TreeMap<>();
        Set<String> writes = new LinkedHashSet<>();
        List<String> files = text.files(MAIN_JAVA).stream()
                .filter(rel -> !rel.startsWith(OWN_FIXTURES))
                .toList();
        for (String rel : files) {
            String body = text.blanked(rel, Blank.NONE);
            readKeys(rel, body, reads);
            writtenKeys(body, writes);
        }
        reads.forEach((key, sites) -> {
            String exempt = EXEMPT.get(key);
            boolean written = writes.contains(key);
            if (exempt == null && !written) {
                for (TextSite s : sites) v.add(s, "reads JSON key \"" + key + "\", which nothing in the tree writes");
            } else if (exempt != null && written) {
                for (TextSite s : sites)
                    v.add(
                            s,
                            "JSON key \"" + key + "\" now has a writer, so its WireKeyRules.EXEMPT entry (" + exempt
                                    + ") is stale: delete it");
            }
        });
        List<String> unread = EXEMPT.keySet().stream()
                .filter(k -> !reads.containsKey(k))
                .sorted()
                .toList();
        if (!unread.isEmpty()) {
            throw new OwnerMissing("WireKeyRules.EXEMPT names keys nothing reads any more: " + unread
                    + "; delete their entries, a stale allowlist is how this guard goes blind");
        }
        v.population(files.size());
    }

    // ---- scanning ------------------------------------------------------------------------------

    private static final Pattern READER =
            Pattern.compile("Jsonl\\.(?:str|intValue|longValue|doubleValue|bool|has|strArray|nested|strMap)\\s*\\(");

    private static final Pattern JSON_KEY_LITERAL = Pattern.compile("\\\\\"([^\"\\\\]+)\\\\\"\\s*:");

    private static final Pattern MAP_PUT = Pattern.compile("\\.put\\(\\s*\"([^\"]+)\"\\s*,");

    /**
     * The typed field calls on {@code RequestJson}: {@code bool("key", …)}, {@code
     * optionalString("key", …)} and the rest, each name a whole word followed at once by its paren.
     * Found by name at each {@code (}, not by a regex alternation tried at every character.
     */
    private static final Set<String> REQUEST_FIELD_CALLS = Set.of(
            "bool",
            "number",
            "string",
            "array",
            "map",
            "token",
            "optionalTrue",
            "optionalString",
            "optionalNonBlankString",
            "optionalNonEmptyString",
            "optionalNumber",
            "optionalArray",
            "optionalMap",
            "optionalToken");

    /** The first argument of a request field call, a string literal followed by a comma. */
    private static final Pattern REQUEST_FIELD_KEY = Pattern.compile("\\s*\"([^\"]+)\"\\s*,");

    /** Every key read as a string literal: the second argument of a {@code Jsonl} reader call. */
    private static void readKeys(String rel, String body, Map<String, List<TextSite>> reads) {
        if (!body.contains("Jsonl.")) return;
        Matcher m = READER.matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            List<String> args = arguments(body, m.end());
            if (args.size() < 2) continue;
            String key = stringLiteral(args.get(1).strip());
            if (key == null || !seen.add(key)) continue;
            reads.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TextSite(rel, lineOf(body, m.start()), "\"" + key + "\""));
        }
    }

    private static void writtenKeys(String body, Set<String> keys) {
        // Each pattern starts with a fixed fragment; a file without it is skipped before the regex.
        if (body.contains("\\\"")) {
            Matcher literal = JSON_KEY_LITERAL.matcher(body);
            while (literal.find()) keys.add(literal.group(1));
        }
        if (body.contains(".put(")) {
            Matcher put = MAP_PUT.matcher(body);
            while (put.find()) keys.add(put.group(1));
        }
        Matcher requestField = REQUEST_FIELD_KEY.matcher(body);
        for (int paren = body.indexOf('('); paren >= 0; paren = body.indexOf('(', paren + 1)) {
            int start = paren;
            while (start > 0 && isWordChar(body.charAt(start - 1))) start--;
            if (start == paren || !REQUEST_FIELD_CALLS.contains(body.substring(start, paren))) continue;
            requestField.region(paren + 1, body.length());
            if (requestField.lookingAt()) keys.add(requestField.group(1));
        }
    }

    /** A regex word character ({@code \w}), so a call name starts where {@code \b} would put it. */
    private static boolean isWordChar(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static int lineOf(String body, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (body.charAt(i) == '\n') line++;
        return line;
    }

    /**
     * Split the argument list that starts at {@code from} (just past the opening paren) on top-level
     * commas, respecting nested parens/brackets/braces and string and char literals. A regex that
     * gives up on a comma inside a receiver call is exactly the blind spot that makes a green scan
     * meaningless.
     */
    private static List<String> arguments(String body, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' || c == '\'') {
                int end = literalEnd(body, i);
                current.append(body, i, Math.min(end, body.length()));
                i = end - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    args.add(current.toString());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return args;
    }

    /** Index just past the string or char literal whose opening quote sits at {@code start}. */
    private static int literalEnd(String body, int start) {
        char quote = body.charAt(start);
        for (int i = start + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) return i + 1;
        }
        return body.length();
    }

    /** The content of {@code arg} when it is a plain string literal, else {@code null}. */
    private static @Nullable String stringLiteral(String arg) {
        if (arg.length() < 2 || arg.charAt(0) != '"' || arg.charAt(arg.length() - 1) != '"') return null;
        String inner = arg.substring(1, arg.length() - 1);
        return inner.contains("\"") ? null : inner;
    }
}
