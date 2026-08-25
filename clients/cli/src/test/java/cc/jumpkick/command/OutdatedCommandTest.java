// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.SysProps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @BeforeEach
    void seedRepo() {
        DefaultTestDepsFixture.seed(maven.served()); // implicit junit-platform-launcher, for the lock-based tests
    }

    @AfterEach
    void reset() {
        System.setOut(originalOut);
        SessionContext.reset();
        LockfileReader.clearCache();
    }

    @Test
    void reports_compatible_at_selector_ceiling_and_latest_beyond(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String json = json(tempDir, cache);
        assertThat(json).contains("\"dependency\":\"com.foo.outdated:leaf\"");
        assertThat(json).contains("\"current\":\"1.1\"");
        assertThat(json).contains("\"compatible\":\"1.1\"");
        assertThat(json).contains("\"latest\":\"2.0\"");
    }

    @Test
    void exclude_up_to_date_hides_current_but_keeps_behind(@TempDir Path tempDir) throws Exception {
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
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String all = json(tempDir, cache);
        assertThat(all).contains("com.foo.outdated:upToDate").contains("com.foo.outdated:behind");

        String filtered = jsonArgs(tempDir, cache, "--exclude-up-to-date");
        assertThat(filtered).contains("com.foo.outdated:behind");
        assertThat(filtered).doesNotContain("com.foo.outdated:upToDate");
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

        // The Module column shows in the human table for a workspace.
        assertThat(table(tempDir, tempDir.resolve("cache"))).contains("Module");
    }

    @Test
    void human_table_lists_dependency_and_versions(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo.outdated", "leaf", "1.0", "1.1", "2.0");
        maven.registerPom("com.foo.outdated", "leaf", "1.1", pom("com.foo.outdated", "leaf", "1.1"));
        maven.registerJar("com.foo.outdated", "leaf", "1.1", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");
        writeProject(tempDir, "leaf = { group = \"com.foo.outdated\", name = \"leaf\", version = \"^1.0\" }");
        assertThat(lock(tempDir, cache)).isEqualTo(0);

        String out = table(tempDir, cache);
        assertThat(out).contains("Dependency", "Compatible", "Latest");
        assertThat(out).contains("com.foo.outdated:leaf");
        assertThat(out).contains("2.0");
        // : footer points at graph inspection + intentional update
        assertThat(out).contains("jk why").contains("jk tree").contains("jk update");
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

    private int lock(Path dir, Path cache) {
        return run(
                "lock", "-C", dir.toString(), "--repo-url", maven.base().toString(), "--cache-dir", cache.toString());
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
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(concat(base, extra));
        } finally {
            System.setOut(originalOut);
        }
        assertThat(exit).isEqualTo(0);
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
