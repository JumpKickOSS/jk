// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Closure over jk's JSON field vocabulary: <strong>no decoder reads a key no encoder writes</strong>
 * (JK-2431).
 *
 * <p>This is the assertion that justifies the record shape over the alternative the house rule
 * rejects. A {@code WireKeys} constant table moves every literal into one file and still permits
 * {@code Jsonl.str(line, WireKeys.TESTS_TOTAL)} against an encoder that stopped writing it — the
 * table proves the two sides spell the string the same way, not that the string is on the wire. A
 * record with {@code encode()} and {@code decode(String)} in one file closes that for its own
 * message; this test closes it for the whole tree, including the hand-rolled reads in
 * {@code engine.verbs} and the CLI that no record covers.
 *
 * <p>Modelled on {@code TestCountWireSpellingTest}'s tree scan, which proved a closure by walking
 * production sources rather than by asserting on one encoder's output.
 *
 * <p><strong>What the patterns can see</strong>, because a guard's count is bounded by its
 * detection pattern and not by the defect:
 *
 * <ul>
 *   <li>Reads: a {@code Jsonl.<reader>(…, "literal")} call, arguments split with real paren and
 *       string-literal balancing so a nested call in receiver position is not a blind spot. A key
 *       passed as a <em>constant</em> ({@code ProtoJobs.JDKS_DIR}) is deliberately invisible — it is
 *       owned by definition, and its writer spells it from the same constant.
 *   <li>Writes: a {@code \"key\":} JSON-object literal inside a Java string, or a {@code
 *       .put("key", …)} on one of the map/JsonOut builders. 34 of the tree's keys are written only
 *       through the second form, so dropping it would manufacture 34 false orphans.
 * </ul>
 *
 * <p>Measured when written: 1,338 production sources, 1,017 literal-key reads, 443 distinct read
 * keys, 824 distinct written keys, <strong>6</strong> read with no writer — all six listed below.
 */
class WireKeyClosureTest {

    /**
     * A key read with no writer in the tree, and why that is not a defect. Each entry has to stay
     * <em>necessary</em>: the test fails when one of these stops being an orphan, so a fix deletes
     * its line here in the same change instead of leaving a stale exemption behind.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            // jbang-catalog.json is somebody else's document format. jk reads it and never writes one.
            "aliases", "foreign format: jbang-catalog.json, read by JBangCatalog",
            "arguments", "foreign format: jbang-catalog.json",
            "java-options", "foreign format: jbang-catalog.json",
            "script-ref", "foreign format: jbang-catalog.json",
            // The dashboard SPA writes this one, in JavaScript, as a query param and a POST body field.
            "project", "written by clients/web, not by Java: HttpProjectApi request field",
            // NOT a legitimate exemption — a dual read left behind, kept here only because both of
            // its sites are outside this ticket's scope. `worker` is the live spelling; nothing in
            // the tree writes `w`, and only JUnitLauncherAggregatorTest fabricates one. Delete the
            // two reads (EngineEventDecoder, JUnitLauncher) and then delete this line.
            "w", "PENDING: dead legacy dual-read of `worker`; no production writer exists");

    /** Production sources on the day this landed. A much smaller number means the walk broke. */
    private static final int SOURCES_WHEN_WRITTEN = 1_300;

    @Test
    void no_json_key_is_read_that_nothing_writes() throws IOException {
        Path root = repoRoot();
        List<Path> scanned = new ArrayList<>();
        Map<String, Set<String>> reads = new TreeMap<>();
        Set<String> writes = new LinkedHashSet<>();

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!isProductionJava(rel)) continue;
                scanned.add(f);
                String body = Files.readString(f, StandardCharsets.UTF_8);
                for (String key : readKeys(body)) {
                    reads.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(rel);
                }
                writes.addAll(writtenKeys(body));
            }
        }

        assertThat(scanned)
                .as("production Java scanned under %s — a small corpus means the walk missed the tree", root)
                .hasSizeGreaterThan(SOURCES_WHEN_WRITTEN);
        assertThat(reads)
                .as("literal-key reads found — zero would make this test green over nothing")
                .hasSizeGreaterThan(300);

        Map<String, Set<String>> orphans = new TreeMap<>();
        reads.forEach((key, files) -> {
            if (!writes.contains(key) && !EXEMPT.containsKey(key)) orphans.put(key, files);
        });

        assertThat(orphans)
                .as("these JSON keys are read and nothing in the tree writes them (JK-2431). Either the "
                        + "encoder dropped the field, or the reader spells it differently from the writer. "
                        + "A wire message is a record with encode() and decode(String) in one file so that "
                        + "this cannot happen; a key read from somewhere else needs the same closure by hand.")
                .isEmpty();

        // The ratchet's other direction: an exemption that is no longer an orphan is stale, and a
        // stale allowlist is how a guard goes quietly blind over vocabulary it no longer covers.
        List<String> unnecessary = EXEMPT.keySet().stream()
                .filter(k -> reads.containsKey(k) && writes.contains(k))
                .toList();
        assertThat(unnecessary)
                .as("these keys now have a writer, so their EXEMPT entries are stale — delete them")
                .isEmpty();
        List<String> unread =
                EXEMPT.keySet().stream().filter(k -> !reads.containsKey(k)).toList();
        assertThat(unread)
                .as("these keys are no longer read at all, so their EXEMPT entries are stale — delete them")
                .isEmpty();
    }

    // ---- scanning ------------------------------------------------------------------------------

    private static final Pattern READER = Pattern.compile(
            "Jsonl\\.(?:str|topStr|intValue|longValue|doubleValue|bool|has|strArray|nested|strMap)\\s*\\(");

    private static final Pattern JSON_KEY_LITERAL = Pattern.compile("\\\\\"([^\"\\\\]+)\\\\\"\\s*:");

    private static final Pattern MAP_PUT = Pattern.compile("\\.put\\(\\s*\"([^\"]+)\"\\s*,");

    /** Every key read as a string literal — the second argument of a {@code Jsonl} reader call. */
    private static Set<String> readKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = READER.matcher(body);
        while (m.find()) {
            List<String> args = arguments(body, m.end());
            if (args.size() < 2) continue;
            String key = stringLiteral(args.get(1).strip());
            if (key != null) keys.add(key);
        }
        return keys;
    }

    private static Set<String> writtenKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher literal = JSON_KEY_LITERAL.matcher(body);
        while (literal.find()) keys.add(literal.group(1));
        Matcher put = MAP_PUT.matcher(body);
        while (put.find()) keys.add(put.group(1));
        return keys;
    }

    /**
     * Split the argument list that starts at {@code from} (just past the opening paren) on
     * top-level commas, respecting nested parens/brackets/braces and string and char literals. A
     * regex cannot do this, and a regex that gives up on a comma inside a receiver call is exactly
     * the blind spot that makes a green scan meaningless.
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
    private static String stringLiteral(String arg) {
        if (arg.length() < 2 || arg.charAt(0) != '"' || arg.charAt(arg.length() - 1) != '"') return null;
        String inner = arg.substring(1, arg.length() - 1);
        return inner.contains("\"") ? null : inner;
    }

    /** Java under {@code src/main/java} — never build output, never tests, never fixtures. */
    private static boolean isProductionJava(String rel) {
        if (rel.contains("/build/classes/")
                || rel.contains("/build/generated")
                || rel.contains("/build/tmp")
                || rel.contains("/build/resources")
                || rel.startsWith("build/")
                || rel.contains("/target/")) {
            return false;
        }
        return rel.contains("/src/main/java/") && rel.endsWith(".java");
    }

    /** The checkout root, walking up from this class's own location — never {@code user.dir}. */
    private static Path repoRoot() throws IOException {
        Path here;
        try {
            here = Path.of(WireKeyClosureTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IOException("cannot locate this test's own class output", e);
        }
        for (Path d = here; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("settings.gradle.kts")) && Files.isDirectory(d.resolve("shared/wire"))) {
                return d;
            }
        }
        throw new IOException("cannot locate the jk checkout root from " + here);
    }
}
