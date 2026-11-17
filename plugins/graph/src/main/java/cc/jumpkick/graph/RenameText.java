// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The uses of a renamed name that no compiler resolves: a template's bean path, a config key, a
 * services file, a string, a comment, a Kotlin or Groovy source javac did not index. Each becomes a
 * candidate with the edit {@code also} would make, never an edit applied: the name matched, but no
 * compiler said it is this symbol.
 */
final class RenameText {

    private RenameText() {}

    /** Files the search reads, by extension: templates, config, SQL, docs and JVM sources. */
    private static final Set<String> TEXT = Set.of(
            "html",
            "htm",
            "jsp",
            "jspx",
            "tag",
            "ftl",
            "ftlh",
            "mustache",
            "vm",
            "peb",
            "hbs",
            "xml",
            "properties",
            "yml",
            "yaml",
            "toml",
            "sql",
            "md",
            "adoc",
            "txt",
            "json",
            "kt",
            "kts",
            "groovy",
            "java",
            "imports",
            "factories");

    static final Set<String> TEMPLATES =
            Set.of("html", "htm", "jsp", "jspx", "tag", "ftl", "ftlh", "mustache", "vm", "peb", "hbs");

    /** Changelogs and release notes keep the names they recorded. */
    private static final Pattern HISTORY =
            Pattern.compile("(?i)(?:^|/)(?:changes|changelog|history|news|release[-_]?notes)[^/]*$");

    private static final long MAX_BYTES = 1_000_000;

    /** Every text file of the workspace the search reads, root-relative, sorted; build output and VCS left out. */
    static List<String> files(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        PathUtil.forEachRegularFile(
                base, dir -> GraphSources.skipDir(base, dir) || target(base, dir), (file, attrs) -> {
                    String rel = base.relativize(file).toString().replace('\\', '/');
                    if (attrs.size() > MAX_BYTES
                            || GraphSources.skipFile(rel)
                            || HISTORY.matcher(rel).find()) return;
                    if (rel.contains("META-INF/services/") || TEXT.contains(ext(rel))) out.add(rel);
                });
        out.sort(null);
        return out;
    }

    /** A module's build output, which a walk for sources skips: {@code target/} beside a {@code jk.toml}. */
    private static boolean target(Path base, Path dir) {
        Path name = dir.getFileName();
        return name != null
                && (name.toString().equals(OutputDirs.TARGET) || name.toString().equals("build"))
                && dir.getParent() != null
                && (Files.isRegularFile(dir.getParent().resolve(ManifestNames.MANIFEST))
                        || Files.isRegularFile(dir.getParent().resolve("pom.xml"))
                        || dir.getParent().equals(base));
    }

    static String ext(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static @Nullable String read(Path root, String rel) {
        try {
            Path p = RenameStore.inside(root, rel);
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The whole word {@code name}: not part of a longer identifier. */
    static Pattern word(String name) {
        return Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
    }

    /**
     * How a line is judged: {@code null} to skip it, else whether it must change, the grade and the
     * reason. {@code inString} says the match sits in a string literal of a JVM source.
     */
    interface Judge {
        @Nullable
        Verdict judge(String rel, String line, int at, boolean inString, boolean inComment);
    }

    record Verdict(boolean must, RenamePlan.Grade grade, String reason) {}

    /**
     * Adds a candidate for each line of {@code files} where {@code pattern} matches and {@code judge}
     * keeps it; its edit replaces every match on the line with {@code replacement} applied to the
     * match. In an {@code indexed} Java file only strings and comments are searched: javac resolved
     * every name in its code, so a match there that it did not bind to the symbol is another one (a
     * local variable, another type's member).
     */
    static void scan(
            Path root,
            RenamePlan plan,
            List<String> files,
            Pattern pattern,
            Function<Matcher, String> replacement,
            Judge judge,
            Set<String> indexed) {
        for (String rel : files) {
            String text = read(root, rel);
            if (text == null || !pattern.matcher(text).find()) continue;
            boolean jvm =
                    rel.endsWith(".java") || rel.endsWith(".kt") || rel.endsWith(".kts") || rel.endsWith(".groovy");
            String current = plan.current(rel, text);
            String[] lines = current.split("\n", -1);
            boolean block = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean startsInBlock = block;
                if (jvm) block = blockAfter(line, block);
                Matcher m = pattern.matcher(line);
                List<int[]> spans = new ArrayList<>();
                List<Verdict> verdicts = new ArrayList<>();
                List<String> replacements = new ArrayList<>();
                while (m.find()) {
                    boolean inString = jvm && inString(line, m.start());
                    boolean inComment = jvm
                            && (startsInBlock && !line.substring(0, m.start()).contains("*/")
                                    || commentAt(line, m.start()));
                    if (indexed.contains(rel) && !inString && !inComment) continue;
                    Verdict here = judge.judge(rel, line, m.start(), inString, inComment);
                    if (here == null) continue;
                    spans.add(new int[] {m.start(), m.end()});
                    verdicts.add(here);
                    replacements.add(replacement.apply(m));
                }
                if (verdicts.isEmpty()) continue;
                // The line's candidate is its strongest match's; a weaker match on it (a message key
                // beside a bean path) is left as it is.
                Verdict v = verdicts.stream().filter(Verdict::must).findFirst().orElse(verdicts.getFirst());
                StringBuilder out = new StringBuilder();
                int last = 0;
                for (int k = 0; k < spans.size(); k++) {
                    if (verdicts.get(k).must() != v.must()) continue;
                    out.append(line, last, spans.get(k)[0]).append(replacements.get(k));
                    last = spans.get(k)[1];
                }
                out.append(line.substring(last));
                plan.candidates.add(new RenamePlan.Candidate(
                        v.must(),
                        v.grade(),
                        v.reason(),
                        plan.moves.getOrDefault(rel, rel),
                        i + 1,
                        line,
                        List.of(new RenamePlan.LineChange(
                                plan.moves.getOrDefault(rel, rel), i + 1, line, out.toString())),
                        null));
            }
        }
    }

    /** Whether a block comment is open after {@code line}, given whether it was open before. */
    static boolean blockAfter(String line, boolean open) {
        boolean in = open;
        int i = 0;
        while (i < line.length()) {
            if (in) {
                int end = line.indexOf("*/", i);
                if (end < 0) return true;
                in = false;
                i = end + 2;
            } else {
                int start = line.indexOf("/*", i);
                int lineComment = line.indexOf("//", i);
                if (start < 0 || lineComment >= 0 && lineComment < start) return false;
                in = true;
                i = start + 2;
            }
        }
        return in;
    }

    /** Whether {@code at} sits after a {@code //} on the line, or on a doc comment's {@code *} line. */
    static boolean commentAt(String line, int at) {
        String head = line.substring(0, at);
        String t = line.stripLeading();
        if (t.startsWith("*") || t.startsWith("/*")) return true;
        int c = unquotedIndex(head, "//");
        return c >= 0;
    }

    /** Whether offset {@code at} of {@code line} sits inside a string or character literal. */
    static boolean inString(String line, int at) {
        char quote = 0;
        for (int i = 0; i < at && i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return false;
            }
        }
        return quote != 0;
    }

    private static int unquotedIndex(String s, String needle) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (s.startsWith(needle, i)) {
                return i;
            }
        }
        return -1;
    }

    /** A services file, Spring's imports or factories, or {@code jk.toml}: formats that name a class by its full name. */
    static boolean classList(String rel) {
        return rel.contains("META-INF/services/")
                || rel.endsWith(".imports")
                || rel.endsWith("spring.factories")
                || rel.endsWith(ManifestNames.MANIFEST);
    }

    static boolean template(String rel) {
        return TEMPLATES.contains(ext(rel));
    }

    static boolean jvmSource(String rel) {
        return rel.endsWith(".java") || rel.endsWith(".kt") || rel.endsWith(".kts") || rel.endsWith(".groovy");
    }

    static boolean doc(String rel) {
        String e = ext(rel);
        return e.equals("md") || e.equals("adoc") || e.equals("txt");
    }

    /** {@code telephoneNumber} → {@code telephone-number}. */
    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /**
     * The environment variable Spring's relaxed binding reads for a config key: {@code
     * petclinic.visits.max-per-day} → {@code PETCLINIC_VISITS_MAXPERDAY} ({@code .} to {@code _}, dashes
     * dropped, upper case).
     */
    static String envName(String key) {
        return key.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /** {@code telephoneNumber} → {@code telephone_number}. */
    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code map}'s key that {@code m} matched, mapped: for one pattern over several names. */
    static Function<Matcher, String> mapped(Map<String, String> map) {
        return m -> map.getOrDefault(m.group(), m.group());
    }
}
