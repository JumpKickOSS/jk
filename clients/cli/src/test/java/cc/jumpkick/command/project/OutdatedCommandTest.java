// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.DefaultTestDepsFixture;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.terminal.Width;
import cc.jumpkick.testing.SysProps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code jk outdated} against a mock Maven server (the version index only — outdated never
 * fetches artifacts) via the test-only in-process seam. Assertions mostly read the {@code --output
 * json} form so they don't depend on the human table's exact glyphs/spacing. Git tag/tip
 * enumeration is covered by the git-client and engine unit tests (no forked worker here).
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class OutdatedCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    private final PrintStream originalOut = System.out;

    private final PrintStream originalErr = System.err;

    @BeforeEach
    void seedRepo() {
        DefaultTestDepsFixture.seed(maven.served()); // implicit junit-platform-launcher, for the lock-based tests
    }

    @AfterEach
    void reset() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        LockfileReader.clearCache();
    }

    @Test
    void reports_compatible_at_selector_ceiling_and_latest_beyond(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        lockOrExplain(tempDir, cache);

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.foo.outdated:leaf\"");
        assertThat(json).contains("\"current\":\"1.1\"");
        assertThat(json).contains("\"compatible\":\"1.1\"");
        assertThat(json).contains("\"latest\":\"2.0\"");
    }

    @Test
    void default_hides_up_to_date_rows_and_all_restores_them(@TempDir Path tempDir) throws Exception {
        // upToDate: only 1.0 exists. behind: 1.0 pinned but 2.0 exists.
        maven.registerMetadata("com.foo.outdated", "upToDate", "1.0");
        maven.registerPom("com.foo.outdated", "upToDate", "1.0", pom("com.foo.outdated", "upToDate", "1.0"));
        maven.registerJar("com.foo.outdated", "upToDate", "1.0", "a".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo.outdated", "behind", "1.0", "2.0");
        maven.registerPom("com.foo.outdated", "behind", "1.0", pom("com.foo.outdated", "behind", "1.0"));
        maven.registerJar("com.foo.outdated", "behind", "1.0", "b".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        writeProject(
                tempDir,
                "upToDate = { group = \"com.foo.outdated\", name = \"upToDate\", version = \"=1.0\" }\n"
                        + "        behind = { group = \"com.foo.outdated\", name = \"behind\", version = \"=1.0\" }");
        lockOrExplain(tempDir, cache);

        String filtered = json(tempDir, cache);
        assertThat(filtered).contains("com.foo.outdated:behind");
        assertThat(filtered).doesNotContain("com.foo.outdated:upToDate");

        String all = jsonArgs(tempDir, cache, "--all");
        assertThat(all).contains("com.foo.outdated:upToDate").contains("com.foo.outdated:behind");

        String table = table(tempDir, cache);
        assertThat(table).contains("behind").doesNotContain("upToDate");
    }

    @Test
    void every_row_up_to_date_says_how_many_were_checked(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.0", pom("com.foo.outdated", "leaf", "1.0"));
        maven.registerJar("com.foo.outdated", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"=1.0\" }");
        lockOrExplain(tempDir, cache);

        assertThat(table(tempDir, cache)).contains("(all 1 dependency up to date)");
        assertThat(json(tempDir, cache).trim()).isEqualTo("[]");
        assertThat(table(tempDir, cache, "--all")).contains("c.f.o:leaf").contains("1.0");
    }

    @Test
    void an_unlocked_row_stays_visible_by_default(@TempDir Path tempDir) throws Exception {
        // Metadata only: the best-effort lock cannot resolve a POM, so Current is empty.
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0");
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"=1.0\" }");

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.foo.outdated:leaf\"").contains("\"current\":\"\"");
    }

    @Test
    void show_tip_adds_column_with_prerelease(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0-alpha1");
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        // No lock needed: latest/tip come from metadata regardless of current.

        String plain = table(tempDir, cache); // no --show-tip
        assertThat(plain).doesNotContain("Tip");

        String withTip = table(tempDir, cache, "--show-tip");
        assertThat(withTip).contains("Tip");
        assertThat(withTip).contains("2.0-alpha1"); // non-stable frontier ahead of latest stable 1.1
    }

    @Test
    void substitutes_short_catalog_name_in_json(@TempDir Path tempDir) throws Exception {
        // A coordinate that maps to a bundled short name.
        maven.registerMetadata("com.fasterxml.jackson.core", "jackson-databind", "2.18.0");
        Path cache = tempDir.resolve("cache");
        writeProject(
                tempDir,
                "jackson = { group = \"com.fasterxml.jackson.core\", name = \"jackson-databind\", version = \"^2.0\" }");

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.fasterxml.jackson.core:jackson-databind\"");
        assertThat(json).contains("\"display\":\"jackson2-databind\"");
    }

    @Test
    void workspace_cascade_tags_each_row_with_its_module(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "2.0");
        maven.registerMetadata("com.foo.outdated", "core", "3.0", "3.1");
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "lib"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                leaf = { group = "com.foo.outdated", name = "leaf", version = "^1.0" }
                """);
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group = "com.acme"
                name = "lib"
                version = "0.1.0"

                [dependencies]
                core = { group = "com.foo.outdated", name = "core", version = "^3.0" }
                """);

        String json = json(tempDir, tempDir.resolve("cache"));
        assertThat(json).contains("\"module\":\"com.acme:app\"").contains("\"dependency\":\"com.foo.outdated:leaf\"");
        assertThat(json).contains("\"module\":\"com.acme:lib\"").contains("\"dependency\":\"com.foo.outdated:core\"");

        // The default view is one row per coordinate with a Modules count and no module rows.
        Path cache = tempDir.resolve("cache");
        List<String> rollup = TestAnsi.strip(table(tempDir, cache)).lines().toList();
        assertThat(rollup).anyMatch(l -> l.contains("Modules")).noneMatch(l -> l.contains("com.acme:app"));
        assertThat(rollup.stream()
                        .filter(l -> l.contains("c.f.o:leaf"))
                        .findFirst()
                        .orElseThrow())
                .contains("│ 1 ");

        // --by-module: each module is a full-width group header above its rows, not a column.
        List<String> lines =
                TestAnsi.strip(table(tempDir, cache, "--by-module")).lines().toList();
        assertThat(lines).noneMatch(l -> l.contains("Module"));
        String appHeader = lines.stream()
                .filter(l -> l.contains("com.acme:app"))
                .findFirst()
                .orElseThrow();
        assertThat(appHeader).doesNotContain("c.f.o:leaf").doesNotContain("│ 1.0");
        String leafRow =
                lines.stream().filter(l -> l.contains("c.f.o:leaf")).findFirst().orElseThrow();
        assertThat(lines.indexOf(leafRow)).isGreaterThan(lines.indexOf(appHeader));

        // Content-sized columns: both tables stay well under a 100-column terminal.
        for (List<String> view : List.of(rollup, lines)) {
            int widest = view.stream()
                    .filter(l -> !l.isEmpty() && "│├╰|+".indexOf(l.charAt(0)) >= 0)
                    .mapToInt(Width::columns)
                    .max()
                    .orElse(0);
            assertThat(widest).isBetween(30, 99);
        }
    }

    @Test
    void two_modules_on_two_pins_roll_up_to_one_row_with_the_spread(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        for (String v : List.of("1.0", "1.1")) {
            maven.registerPom("com.foo.outdated", "leaf", v, pom("com.foo.outdated", "leaf", v));
            maven.registerJar("com.foo.outdated", "leaf", v, "leaf".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "lib"]
                """);
        for (String[] m : new String[][] {{"app", "=1.0"}, {"lib", "=1.1"}}) {
            Path dir = Files.createDirectories(tempDir.resolve(m[0]));
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "com.acme"
                    name = "%s"
                    version = "0.1.0"

                    [dependencies]
                    leaf = { group = "com.foo.outdated", name = "leaf", version = "%s" }
                    """.formatted(m[0], m[1]));
        }
        Path cache = tempDir.resolve("cache");
        lockOrExplain(tempDir, cache);

        List<String> rollup = TestAnsi.strip(table(tempDir, cache)).lines().toList();
        List<String> leafRows =
                rollup.stream().filter(l -> l.contains("c.f.o:leaf")).toList();
        assertThat(leafRows).hasSize(1);
        assertThat(leafRows.getFirst())
                .contains("1.0 ×1 · 1.1 ×1")
                .contains("2.0")
                .contains("│ 2 ");
        assertThat(rollup).noneMatch(l -> l.contains("com.acme:app"));

        List<String> byModule =
                TestAnsi.strip(table(tempDir, cache, "--by-module")).lines().toList();
        assertThat(byModule.stream().filter(l -> l.contains("c.f.o:leaf"))).hasSize(2);
        assertThat(byModule).anyMatch(l -> l.contains("com.acme:app")).anyMatch(l -> l.contains("com.acme:lib"));

        assertThat(json(tempDir, cache).split("\\{\\\"module\\\"", -1)).hasSize(3);
    }

    @Test
    void long_cells_are_clipped_so_one_dependency_cannot_widen_the_table(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata(
                "com.foo.outdated",
                "a-very-long-artifact-name-nobody-shortens",
                "7.7.1.202607240634-r",
                "7.8.0.202609011348-r");
        Path cache = tempDir.resolve("cache");
        writeProject(
                tempDir,
                "leaf = { group = \"com.foo.outdated\", name = \"a-very-long-artifact-name-nobody-shortens\", version = \"^7.7\" }");
        // No lock: Current is empty and Compatible/Latest come straight from the index.
        String out = TestAnsi.strip(table(tempDir, cache));
        assertThat(out).contains("c.f.o:a-very-long-artifac…").doesNotContain("nobody-shortens");
        assertThat(out).contains("7.8.0.20260…").doesNotContain("7.8.0.202609011348-r");
        // The by-module view has no Modules column and gives its cells the extra room.
        String byModule = TestAnsi.strip(table(tempDir, cache, "--by-module"));
        assertThat(byModule).contains("c.f.o:a-very-long-artifact-na…").contains("7.8.0.2026090…");
        assertThat(json(tempDir, cache))
                .contains("com.foo.outdated:a-very-long-artifact-name-nobody-shortens")
                .contains("7.8.0.202609011348-r");
    }

    @Test
    void human_table_lists_dependency_and_versions(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        lockOrExplain(tempDir, cache);

        String out = table(tempDir, cache);
        assertThat(out).contains("Dependency", "Compatible", "Latest");
        // The table shows the group as initials; JSON keeps the full coordinate.
        assertThat(out).contains("c.f.o:leaf").doesNotContain("com.foo.outdated:leaf");
        assertThat(json(tempDir, cache)).contains("\"dependency\":\"com.foo.outdated:leaf\"");
        // Locked at 1.1, the selector admits 1.1 and 2.0 is beyond it: Compatible repeats
        // Current as "=", Latest names the version.
        assertThat(TestAnsi.strip(out)).containsPattern("c\\.f\\.o:leaf\\s+│ 1\\.1\\s+│ =\\s+│ 2\\.0\\s+│");
        // : footer points at graph inspection + intentional update
        assertThat(out).contains("jk why").contains("jk tree").contains("jk update");
    }

    /**
     * The live row is taken before the request and given back before the table: on this ANSI
     * stdout the cursor is hidden and shown again ahead of the table title, and nothing of the
     * row survives after it. Under {@code --no-progress} the row is never taken.
     */
    @Test
    void the_progress_row_precedes_the_table_and_no_progress_paints_none(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        lockOrExplain(tempDir, cache);

        String live = table(tempDir, cache);
        int title = live.indexOf("Dependency versions");
        assertThat(title).isPositive();
        assertThat(live.substring(0, title)).contains(HIDE_CURSOR).contains(SHOW_CURSOR);
        assertThat(live.substring(title)).doesNotContain(HIDE_CURSOR).doesNotContain("Checking");

        String quiet = table(tempDir, cache, "--no-progress");
        assertThat(quiet).contains("c.f.o:leaf");
        assertThat(quiet).doesNotContain(HIDE_CURSOR).doesNotContain("Checking");
    }

    private static final String HIDE_CURSOR = "\u001b[?25l";
    private static final String SHOW_CURSOR = "\u001b[?25h";

    @Test
    void a_version_published_after_the_lock_shows_as_latest(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        lockOrExplain(tempDir, cache);
        assertThat(jsonArgs(tempDir, cache, "--all")).contains("\"latest\":\"1.1\"");

        // Published after the lock: the catalog on disk is within its TTL and the engine holds the
        // list it read, and the report still has to say what the repository publishes now.
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        assertThat(json(tempDir, cache)).contains("\"current\":\"1.1\"").contains("\"latest\":\"2.0\"");
    }

    @Test
    void offline_flag_prints_cache_only_note(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "2.0");
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");

        String out = table(tempDir, cache, "--offline");
        assertThat(out).containsIgnoringCase("offline");
        assertThat(out).containsIgnoringCase("cache");
    }

    @Test
    void json_array_schema_fields_are_stable(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "2.0");
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");

        String json = json(tempDir, cache).trim();
        assertThat(json).startsWith("[").endsWith("]");
        assertThat(json)
                .contains("\"dependency\":")
                .contains("\"current\":")
                .contains("\"compatible\":")
                .contains("\"latest\":")
                .contains("\"tip\":")
                .contains("\"scope\":")
                .contains("\"module\":")
                .contains("\"display\":");
    }

    @Test
    void every_run_writes_the_results_file_and_the_table_names_it(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        lockOrExplain(tempDir, cache);
        Path file = tempDir.resolve("target").resolve("jk-outdated-dependencies.md");

        String out = table(tempDir, cache);
        assertThat(out).contains("Report:").contains("target/jk-outdated-dependencies.md");
        assertThat(file).exists();
        String md = Files.readString(file);
        assertThat(md)
                .contains("# jk outdated dependencies")
                .contains("## Can move")
                .contains("## Up to date");
        assertThat(md).contains("| `com.foo.outdated:leaf` | 1.1 | 1.1 | 2.0 | main |");

        // JSON is still one array and nothing else; the file is written all the same.
        Files.delete(file);
        String json = json(tempDir, cache).trim();
        assertThat(json).startsWith("[").endsWith("]").doesNotContain("Report:");
        assertThat(file).exists();
    }

    @Test
    void the_old_exclude_flag_is_unrecognized(@TempDir Path tempDir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream prior = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run("outdated", "-C", tempDir.toString(), "--exclude-up-to-date");
        } finally {
            System.setErr(prior);
        }
        assertThat(exit).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unrecognized option");
    }

    @Test
    void without_jk_toml_fails_with_config_exit(@TempDir Path tempDir) {
        int exit = run(
                "outdated",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    /** {@code jk lock} must exit 0; a red names the coordinate the CLI complained about, not just the code. */
    private void lockOrExplain(Path dir, Path cache) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(
                    "lock",
                    "-C",
                    dir.toString(),
                    "--repo-url",
                    maven.base().toString(),
                    "--cache-dir",
                    cache.toString());
        } finally {
            System.setErr(originalErr);
        }
        assertThat(exit)
                .as("jk lock against %s; stderr:%n%s", maven.base(), err.toString(StandardCharsets.UTF_8))
                .isEqualTo(0);
    }

    private String json(Path dir, Path cache) {
        return jsonArgs(dir, cache);
    }

    private String jsonArgs(Path dir, Path cache, String... extra) {
        return capture(dir, cache, concat(new String[] {"--output", "json"}, extra));
    }

    private String table(Path dir, Path cache, String... extra) {
        return capture(dir, cache, extra);
    }

    private String capture(Path dir, Path cache, String[] extra) {
        String[] base = {
            "outdated", "-C", dir.toString(), "--repo-url", maven.base().toString(), "--cache-dir", cache.toString()
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(concat(base, extra));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertThat(exit)
                .as("jk outdated against %s; stderr:%n%s", maven.base(), err.toString(StandardCharsets.UTF_8))
                .isEqualTo(0);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void writeProject(Path dir, String depLines) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                %s
                """.formatted(depLines));
    }
}
