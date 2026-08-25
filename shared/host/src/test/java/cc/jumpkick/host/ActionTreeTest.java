// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Closure over the action index's directory vocabulary: <strong>no production source spells one of
 * {@link ActionTree}'s entry names as a path literal</strong>.
 *
 * <p>This is the second half of what {@code checkNoBareTierName} (G15) does for {@link CacheTree}.
 * G15 owns the cache root's top-level names and stops at the tier boundary, so the tree inside
 * {@code actions/} was six unowned literals — and one of them was load-bearing in a number a user
 * reads. {@code jk status}'s "Actions Cached" counted the whole {@code actions/} subtree: 315 on
 * the live dogfood cache where 123 actions were cached.
 *
 * <p><strong>What this can see</strong>, because a guard's reach is bounded by its pattern and not
 * by the defect:
 *
 * <ul>
 *   <li><b>Arm 1, every name</b> — the literal as an argument to a path API ({@code resolve},
 *       {@code Path.of}, {@code Paths.get}). This is exemption <em>by spec</em>: a directory name
 *       always reaches a path API, and nothing else does, so {@code TasksCommand.name()} returning
 *       {@code "tasks"} and the journal's {@code "tasks"} JSON key are not exceptions to write
 *       down — they are outside the rule.
 *   <li><b>Arm 2, the three names with no homonym</b> ({@code synced}, {@code incremental-java},
 *       {@code incremental-kotlin}) — the literal anywhere. These words mean exactly one thing in
 *       jk, so a stricter arm costs nothing and covers the shape arm 1 cannot see: a name held in
 *       a {@code String[]} or {@code List.of(…)} and resolved through a variable. That shape is
 *       precisely what JK-2508 deleted from {@code CacheSnapshot}, {@code CacheInventoryOps} and
 *       {@code ActionCachePrune}, so leaving it undetectable would invite it straight back.
 * </ul>
 *
 * <p>Comments and imports are stripped before matching, so a javadoc {@code {@code "tasks"}} is not
 * a hit. Measured when written: 5 entry names, 2 arms, <strong>1,236</strong> production sources
 * scanned, one pending exemption.
 */
class ActionTreeTest {

    /**
     * Production sources on the day this landed. A much smaller number means the walk broke, and a
     * guard that scans nothing passes for the wrong reason — five of batch 1's twenty were blind.
     */
    private static final int SOURCES_WHEN_WRITTEN = 1_150;

    /** Names with no other meaning anywhere in jk, so arm 2 can ban them outright. */
    private static final Set<String> NO_HOMONYM = Set.of("synced", "incremental-java", "incremental-kotlin");

    /**
     * A site that still types a name, and why it is not fixed. Each entry has to stay
     * <em>necessary</em>: the test fails when an exempted site stops matching, so the fix deletes
     * its line here in the same change rather than leaving a stale exemption behind.
     */
    private static final Map<String, String> PENDING = Map.of(
            "server/engine/src/main/java/cc/jumpkick/runtime/TaskForecaster.java",
            "PENDING: 2 x \"incremental-java\". The file is 1,136 lines — above the 800-line hard cap"
                    + " and pinned at exactly that number in size-baseline.txt, so the one import"
                    + " this fix needs would fail checkFileSizeCaps. Fold it into the peel ticket"
                    + " that takes the file under the cap, then delete this line.");

    @Test
    void the_action_index_names_its_own_directories() {
        assertThat(ActionTree.entries())
                .containsExactly("keys", "tasks", "synced", "incremental-java", "incremental-kotlin");
        Path actions = Path.of("/cache", "actions");
        assertThat(ActionTree.KEYS.under(actions)).isEqualTo(Path.of("/cache/actions/keys"));
        // The two owners compose; neither reaches into the other's half.
        assertThat(ActionTree.KEYS.under(CacheTree.ACTIONS.under(Path.of("/cache"))))
                .isEqualTo(Path.of("/cache/actions/keys"));
    }

    @Test
    void the_separately_budgeted_trees_are_one_list() {
        assertThat(ActionTree.incremental())
                .as("three hand-typed copies of this pair are what the owner replaced")
                .containsExactly(ActionTree.INCREMENTAL_JAVA, ActionTree.INCREMENTAL_KOTLIN);
        assertThat(ActionTree.incrementalUnder(Path.of("/cache/actions")))
                .containsExactly(
                        Path.of("/cache/actions/incremental-java"), Path.of("/cache/actions/incremental-kotlin"));
    }

    @Test
    void no_production_source_types_an_action_directory_name() throws IOException {
        Path root = repoRoot();
        Path owner = root.resolve("shared/host/src/main/java/cc/jumpkick/host/ActionTree.java");
        assertThat(owner).as("the owner this guard reads its ban list out of").isRegularFile();

        List<String> names = ActionTree.entries();
        assertThat(names).as("an empty ban list would pass over anything").hasSizeGreaterThanOrEqualTo(5);

        List<Path> scanned = new ArrayList<>();
        Map<String, String> hits = new LinkedHashMap<>();
        Map<String, Boolean> pendingSeen = new LinkedHashMap<>();
        PENDING.keySet().forEach(k -> pendingSeen.put(k, false));

        for (Path file : productionSources(root)) {
            if (file.equals(owner)) continue;
            scanned.add(file);
            String rel = root.relativize(file).toString().replace('\\', '/');
            String code = stripComments(Files.readString(file));
            List<String> found = new ArrayList<>();
            for (String name : names) {
                boolean pathShaped = PATH_ARG.matcher(code).results().anyMatch(m -> name.equals(m.group(1)));
                boolean bare = NO_HOMONYM.contains(name) && code.contains('"' + name + '"');
                if (pathShaped || bare) found.add(name);
            }
            if (found.isEmpty()) continue;
            if (PENDING.containsKey(rel)) {
                pendingSeen.put(rel, true);
                continue;
            }
            hits.put(rel, String.join(", ", found));
        }

        assertThat(scanned)
                .as("the tree walk found almost nothing, so a green result here means nothing")
                .hasSizeGreaterThan(SOURCES_WHEN_WRITTEN);
        assertThat(pendingSeen)
                .as("a PENDING entry that no longer matches is a stale exemption; delete its line")
                .doesNotContainValue(false);
        assertThat(hits)
                .as("an action-index directory is named once, in cc.jumpkick.host.ActionTree "
                        + "(JK-2508). Use ActionTree.<ENTRY>.under(CacheTree.ACTIONS.under(cacheRoot)); "
                        + "it is on every production module's classpath.")
                .isEmpty();
    }

    /** A string literal in {@code resolve(…)} / {@code Path.of(…)} / {@code Paths.get(…)} position. */
    private static final Pattern PATH_ARG = Pattern.compile("(?:resolve|Path\\.of|Paths\\.get)\\(\\s*\"([^\"]*)\"");

    /**
     * Two levels of directory, then {@code src/main/java} — the shape {@code settings.gradle.kts}
     * declares and the one {@code shared/host/build.gradle.kts} registers as this task's input, so
     * the walk and the up-to-date check cannot disagree. Walking for {@code /src/main/java/}
     * anywhere instead picks up 100+ files that are nobody's production code: the
     * {@code docs/user/examples} fixtures, jk's self-hosted {@code target/} output, and — worst —
     * the third-party git checkouts an engine suite leaves under
     * {@code server/engine/build/test-jk-home/data/store/git/}, where somebody else's
     * {@code resolve("keys")} would fail this guard.
     */
    private static List<Path> productionSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path group : children(root)) {
            for (Path module : children(group)) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> walk = Files.walk(src)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".java"))
                            .forEach(out::add);
                }
            }
        }
        out.sort(null);
        return out;
    }

    private static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    /** Blank out {@code //} and {@code /* *}{@code /} comments, keeping string literals verbatim. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                String close = (c == '"' && src.startsWith("\"\"\"", i)) ? "\"\"\"" : String.valueOf(c);
                out.append(close);
                i += close.length();
                while (i < src.length()) {
                    if (src.charAt(i) == '\\') {
                        out.append(src, i, Math.min(i + 2, src.length()));
                        i += 2;
                    } else if (src.startsWith(close, i)) {
                        out.append(close);
                        i += close.length();
                        break;
                    } else {
                        out.append(src.charAt(i++));
                    }
                }
            } else if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Path repoRoot() {
        return RepoRoot.find(ActionTreeTest.class);
    }
}
