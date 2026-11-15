// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * The {@code jk.toml} keys and tables JumpKick no longer reads: the one list the parser refuses
 * from, the writers' tests check every scaffold and import against, and {@code docs/user/projects.md}
 * prints. A refusal of a key that used to work is an entry here, never a message typed elsewhere.
 */
public final class RetiredKeys {

    private RetiredKeys() {}

    /**
     * One retired key.
     *
     * @param table where it sat: {@code ""} for the top level, a table path such as {@code "test"},
     *     or {@code "profiles.*"} for that key in every named profile
     * @param key the key, or null when the whole table is retired
     * @param since the first release that refuses it
     * @param replacement what to write instead, for the docs table
     * @param message the refusal's remedy, after the key's name and release
     */
    public record Retired(String table, @Nullable String key, String since, String replacement, String message) {

        /** The key as the docs name it: {@code [profiles.<name>] include-tags}, {@code `catalog`}, {@code [project]}. */
        public String name() {
            return where(table.endsWith(".*") ? table.substring(0, table.length() - 1) + "<name>" : table);
        }

        /** The refusal for this key found under {@code table}, a concrete table path. */
        String refusal(String concreteTable) {
            return where(concreteTable) + " was retired in JumpKick " + since + ": " + message;
        }

        private String where(String tablePath) {
            if (key == null) return "[" + tablePath + "]";
            return tablePath.isEmpty() ? "`" + key + "`" : "[" + tablePath + "] " + key;
        }
    }

    private static final String SUITES = "suites choose tests. A tier is [test.suites.<name>] tags = [...]"
            + " (exclude-tags = [...] settles a test two suites claim), run with jk test -s <name>";

    private static final String JVM_ARGS = "write jvm-args = [...], the one name for these JVM flags";

    private static final String WORKERS = "write [test] workers = N (1 runs this module's tests in one JVM)";

    /** Every retired key, oldest release first. */
    public static final List<Retired> ALL = List.of(
            new Retired(
                    "",
                    "catalog",
                    "0.12.0",
                    "short names in `jk-libs.toml` at the workspace root",
                    "put short names in " + ManifestPaths.LIBRARIES + " at the workspace root"),
            new Retired(
                    "libraries",
                    null,
                    "0.12.0",
                    "`[libraries]` in `jk-libs.toml` at the workspace root",
                    "put it in " + ManifestPaths.LIBRARIES + " at the workspace root"),
            new Retired(
                    "project",
                    null,
                    "0.13.0",
                    "`group`, `name`, `version` and the rest at the top of `jk.toml`",
                    "move its keys to the top of jk.toml"),
            new Retired("", "m2integration", "0.41.1", "`[m2] integration`", "use [m2] integration = true or false"),
            new Retired(
                    "profiles.*",
                    "include-tags",
                    "0.51.0",
                    "`[test.suites.<name>] tags`, run with `jk test -s <name>`",
                    SUITES),
            new Retired("profiles.*", "exclude-tags", "0.51.0", "`[test.suites.<name>] exclude-tags`", SUITES),
            new Retired(
                    "profiles.*",
                    "all-suites",
                    "0.51.0",
                    "`jk test -g`, which runs the suites `[test] guard-suites` names",
                    "suites choose tests; jk test -g runs the suites in [test] guard-suites"),
            new Retired(
                    "test",
                    "include-tags",
                    "0.51.0",
                    "`[test.suites.<name>] tags`; `-I` narrows one run",
                    "the default suite runs what no suite claims. A tier is [test.suites.<name>] tags"
                            + " = [...], run with jk test -s <name>; -I / -E narrow one run"),
            new Retired(
                    "test",
                    "exclude-tags",
                    "0.51.0",
                    "`[test.suites.<name>] tags`; `-E` narrows one run",
                    "the default suite runs what no suite claims. A tier is [test.suites.<name>] tags"
                            + " = [...], run with jk test -s <name>; -I / -E narrow one run"),
            new Retired("application", "java-opts", "0.51.1", "`jvm-args`", JVM_ARGS),
            new Retired("test", "java-opts", "0.51.1", "`jvm-args`", JVM_ARGS),
            new Retired("profiles.*", "java-opts", "0.51.1", "`jvm-args`", JVM_ARGS),
            new Retired("test", "parallel", "0.51.1", "`[test] workers = 1`", WORKERS),
            new Retired("build", "test-workers", "0.51.1", "`[test] workers`", WORKERS),
            new Retired("build", "test-parallel", "0.51.1", "`[test] workers = 1`", WORKERS));

    /** Refuse the first retired key {@code root} carries, with its remedy. */
    public static void refuse(TomlTable root) {
        List<String> found = found(root);
        if (!found.isEmpty()) throw new JkBuildParseException(found.getFirst());
    }

    /** The refusal of every retired key {@code root} carries, in {@link #ALL} order; empty when none. */
    public static List<String> found(TomlTable root) {
        List<String> out = new ArrayList<>();
        for (Retired r : ALL) {
            for (List<String> path : tables(root, r.table())) {
                TomlTable at = path.isEmpty() ? root : root.getTable(path);
                if (at == null) continue;
                if (r.key() == null || at.contains(List.of(r.key()))) out.add(r.refusal(String.join(".", path)));
            }
        }
        return out;
    }

    /** The tables {@code pattern} names in {@code root}, as key paths: a {@code .*} pattern expands to each subtable. */
    private static List<List<String>> tables(TomlTable root, String pattern) {
        if (pattern.isEmpty()) return List.of(List.of());
        boolean each = pattern.endsWith(".*");
        List<String> path = List.of((each ? pattern.substring(0, pattern.length() - 2) : pattern).split("\\."));
        if (!root.isTable(path)) return List.of();
        if (!each) return List.of(path);
        TomlTable parent = root.getTable(path);
        List<List<String>> out = new ArrayList<>();
        for (String name : parent == null ? List.<String>of() : parent.keySet()) {
            List<String> child = new ArrayList<>(path);
            child.add(name);
            if (root.isTable(child)) out.add(List.copyOf(child));
        }
        return out;
    }

    /** The docs table, between the markers {@code docs/user/projects.md} keeps it in. */
    public static String markdown() {
        StringBuilder sb = new StringBuilder("<!-- retired-keys:start -->\n");
        sb.append("| Key | Refused since | Write instead |\n");
        sb.append("|---|---|---|\n");
        for (Retired r : ALL) {
            sb.append("| `")
                    .append(r.name().replace("`", ""))
                    .append("` | ")
                    .append(r.since())
                    .append(" | ")
                    .append(r.replacement())
                    .append(" |\n");
        }
        return sb.append("<!-- retired-keys:end -->").toString();
    }
}
