// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * refreshVersions' artifact-version-key rules: the spelling of the short {@code version.<key>} a
 * coordinate's pin is kept under in {@code versions.properties}. A rule is two lines — an artifact
 * pattern ({@code org.jetbrains.kotlinx:kotlinx-???(-*)}, where {@code *} spans a segment, {@code
 * ???} a word without dashes and {@code (-*)} an optional dashed suffix) over a line whose {@code ^}
 * marks the characters of the match that form the key and whose {@code .} inserts a dot — read
 * exactly as the plugin reads them: the rule with the most significant pattern characters that
 * matches wins, except that a rule without a wildcard wins outright. The plugin's own rule files
 * are bundled ({@link #bundled()}); a build's {@code extraArtifactVersionKeyRules} join them.
 */
final class RefreshVersionsRules {

    /** The plugin's rule files, concatenated, on the classpath beside this class. */
    private static final String BUNDLED_RESOURCE = "refreshVersions-rules.txt";

    private static final String SEGMENT = "[a-zA-Z0-9_\\-\\.]+?";
    private static final String WORD = "[a-zA-Z0-9_]+?";

    private static final RefreshVersionsRules BUNDLED = new RefreshVersionsRules(parse(bundledText()));

    private final List<Rule> rules;

    private RefreshVersionsRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** The rules the refreshVersions plugin ships. */
    static RefreshVersionsRules bundled() {
        return BUNDLED;
    }

    /** These rules plus the ones {@code text} spells, in the plugin's own file format. */
    RefreshVersionsRules plus(String text) {
        List<Rule> all = new ArrayList<>(rules);
        all.addAll(parse(text));
        return new RefreshVersionsRules(all);
    }

    /**
     * The key the plugin writes {@code group:artifact}'s pin under — {@code kotlinx.coroutines} for
     * {@code org.jetbrains.kotlinx:kotlinx-coroutines-core} — or empty when no rule matches, in
     * which case the plugin writes the exact {@code group..artifact} key.
     */
    Optional<String> keyFor(String group, String artifact) {
        Rule found = null;
        for (Rule rule : rules) {
            if (!rule.matches(group, artifact)) continue;
            if (found == null) found = rule;
            if (rule.exact()) {
                found = rule;
                break;
            }
        }
        return found == null ? Optional.empty() : Optional.of(found.key(group, artifact));
    }

    /**
     * The rules {@code text} spells, in the order the plugin tries them: {@code //} comments and
     * blank lines dropped, the rest read in pairs, sorted most significant first.
     */
    static List<Rule> parse(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            int comment = line.indexOf("//");
            String bare = (comment < 0 ? line : line.substring(0, comment)).stripTrailing();
            if (!bare.isBlank()) lines.add(bare);
        }
        if (lines.size() % 2 != 0) {
            throw new IllegalArgumentException(
                    "refreshVersions rules come in pairs of lines; " + lines.size() + " lines were read");
        }
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) rules.add(Rule.of(lines.get(i), lines.get(i + 1)));
        rules.sort(Comparator.comparingInt(Rule::significantChars)
                .thenComparing(Rule::artifactPattern)
                .thenComparing(Rule::keyPattern)
                .reversed());
        return List.copyOf(rules);
    }

    private static String bundledText() {
        try (InputStream in = RefreshVersionsRules.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            return new String(Objects.requireNonNull(in, BUNDLED_RESOURCE).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One rule: the two lines as written and the regex the plugin derives from them, whose groups
     * carry the key's characters in order — a group named {@code dotN} stands for a dot.
     */
    record Rule(String artifactPattern, String keyPattern, Pattern regex, int significantChars, boolean exact) {

        static Rule of(String artifactPattern, String keyPattern) {
            if (keyPattern.length() > artifactPattern.length()) {
                throw new IllegalArgumentException(
                        "a version key pattern cannot outrun its artifact pattern: " + artifactPattern);
            }
            int significant = 0;
            for (char c : artifactPattern.toCharArray()) {
                if (" ()*".indexOf(c) < 0) significant++;
            }
            return new Rule(
                    artifactPattern,
                    keyPattern,
                    regex(artifactPattern, keyPattern),
                    significant,
                    artifactPattern.indexOf('*') < 0);
        }

        boolean matches(String group, String artifact) {
            return regex.matcher(group + ":" + artifact).matches();
        }

        /** The key for a coordinate this rule matches: the captured characters, a dot for each dot group. */
        String key(String group, String artifact) {
            Matcher m = regex.matcher(group + ":" + artifact);
            if (!m.matches())
                throw new IllegalArgumentException(artifactPattern + " does not match " + group + ":" + artifact);
            Map<String, Integer> named = regex.namedGroups();
            StringBuilder key = new StringBuilder();
            for (int i = 1; i <= m.groupCount(); i++) {
                if (named.containsValue(i)) {
                    key.append('.');
                } else {
                    String value = m.group(i);
                    if (value != null) key.append(value);
                }
            }
            return key.toString();
        }

        /**
         * The plugin's regex, built right to left: a run of {@code ^} in the key pattern opens a
         * capturing group over the artifact characters under it, a {@code .} a named group over the
         * character under it, a parenthesised suffix an optional non-capturing group.
         */
        private static Pattern regex(String artifactPattern, String keyPattern) {
            // Dots and parentheses take surrogates first, so the artifact's own are told apart from the regex's.
            String artifact = artifactPattern.replace('.', '=');
            String key = String.format("%-" + artifact.length() + "s", keyPattern);
            StringBuilder re = new StringBuilder();
            boolean capturing = false;
            int dots = 0;
            for (int i = key.length() - 1; i >= 0; i--) {
                char k = key.charAt(i);
                if (!capturing) {
                    if (k == '^') {
                        re.insert(0, '}');
                        capturing = true;
                    }
                } else if (k != '^') {
                    re.insert(0, '{');
                    capturing = false;
                }
                char a = artifact.charAt(i);
                if (k == '.') {
                    re.insert(0, "(?<dot" + dots + ">" + a + ")");
                    dots++;
                } else if (a == ')') {
                    re.insert(0, "}?");
                } else if (a == '(') {
                    re.insert(0, "{?:");
                } else {
                    re.insert(0, a);
                }
            }
            if (capturing) re.insert(0, '{');
            String pattern = re.toString()
                    .replace("=", "\\.")
                    .replace("???", WORD)
                    .replace("*", SEGMENT)
                    .replace("{", "(")
                    .replace("}", ")");
            return Pattern.compile(pattern + "$");
        }
    }
}
