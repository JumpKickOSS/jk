// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.DefaultTestDepsFixture;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.FakeJdk;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A two-member workspace whose members disagree: {@code app} holds a BOM that manages {@code
 * com.foo:leaf} at 2.0, {@code lib} reaches leaf through {@code middle}, whose POM declares 1.0.
 * The workspace's row is the 1.0 the POM declares, {@code app} reads a row of its own at the BOM's
 * 2.0, and every surface that reads one member's rows says so.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class WorkspaceMemberRowsCliTest {

    /** The one-sentence form: every row of app's own is what its BOM manages. */
    private static final String APP_NOTE = "app reads its own rows for 1 coordinate org.example:the-bom:1.0 manages";

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @BeforeEach
    void publish() {
        DefaultTestDepsFixture.seed(maven.served());
        MavenStub upstream = new MavenStub(maven.served());
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0")));
        upstream.metadata("org.example", "the-bom", "1.0");
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "2.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "middle", "1.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom("com.foo", "leaf", v, MavenStub.emptyPom("com.foo", "leaf", v));
            upstream.jar("com.foo", "leaf", v);
        }
    }

    @AfterEach
    void reset() {
        LockfileReader.clearCache();
    }

    @Test
    void the_lock_names_the_member_that_reads_its_own_rows_on_the_terminal_and_in_the_run_record(@TempDir Path root)
            throws Exception {
        writeWorkspace(root);

        int[] exit = new int[1];
        Capture.Streams lock = Capture.both(() -> exit[0] = lock(root));
        assertThat(exit[0]).as(lock.out() + lock.err()).isEqualTo(0);
        String out = lock.out().replaceAll("\u001b\\[[\\d;]*m", "");
        assertThat(out).contains("Workspace lock successful").contains(APP_NOTE);
        assertThat(out.indexOf(APP_NOTE))
                .as("the note follows the summary line")
                .isGreaterThan(out.indexOf("Workspace lock successful"));

        String results = Files.readString(root.resolve("target/jk-results.md"));
        assertThat(results).contains("## Lock notes").contains(APP_NOTE);
        Matcher details =
                Pattern.compile("transcript: `([^`]+details\\.jsonl)`").matcher(results);
        assertThat(details.find())
                .as("the results file names its details.jsonl:\n" + results)
                .isTrue();
        Path transcript = Path.of(details.group(1));
        assertThat(transcript).as("the lock's run record has a transcript").exists();
        assertThat(Files.readString(transcript)).contains("lock-note").contains("app reads its own rows");
    }

    @Test
    void the_tree_shows_each_member_the_version_it_reads(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        assertThat(lock(root)).isEqualTo(0);

        String lib = tree(root, ":lib");
        assertThat(lib).contains("com.foo:leaf:1.0").doesNotContain("leaf:2.0");
        String app = tree(root, ":app");
        assertThat(app).contains("com.foo:leaf:2.0").doesNotContain("leaf:1.0");

        String whole = tree(root, null);
        int appNode = whole.indexOf("com.acme:app:0.1.0");
        int libNode = whole.indexOf("com.acme:lib:0.1.0");
        assertThat(appNode).isNotNegative();
        assertThat(libNode).isGreaterThan(appNode);
        // app reads its own leaf: its node names the row's readers and claims no 1.0 for app.
        assertThat(whole.substring(appNode, libNode))
                .contains("com.foo:leaf:2.0")
                .contains("(for app)")
                .doesNotContain("leaf:1.0");
        // lib reads the workspace's row, shown plain under its own expansion of middle.
        assertThat(whole.substring(libNode))
                .contains("com.foo:leaf:1.0")
                .doesNotContain("(for ")
                .doesNotContain("leaf:2.0");
    }

    /** The root step of a why path names the workspace units whose tables declare it, as the tree renders them. */
    @Test
    void why_names_the_units_that_declared_a_root(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        assertThat(lock(root)).isEqualTo(0);

        int[] exit = new int[1];
        Capture.Streams out =
                Capture.both(() -> exit[0] = run("why", "com.foo:middle", "-C", root.toString(), "--no-progress"));
        assertThat(exit[0]).as(out.out() + out.err()).isEqualTo(0);
        String why = out.out().replaceAll("\u001b\\[[\\d;]*m", "");
        assertThat(why).contains("com.foo:middle:1.0 (declared 1.0 by com.acme:app, com.acme:lib)");
    }

    @Test
    void the_intellij_export_gives_each_member_the_library_it_reads(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        assertThat(lock(root)).isEqualTo(0);
        Path jdks = root.resolve("jdks");
        FakeJdk.create(jdks.resolve("temurin-25.0.3"), "25.0.3");
        Path ideConfig = root.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));

        int exit = run(
                "ide",
                "--idea",
                "-C",
                root.toString(),
                "--cache-dir",
                root.resolve("cache").toString(),
                "--jdks-dir",
                jdks.toString(),
                "--ide-config-dir",
                ideConfig.toString());
        assertThat(exit).isEqualTo(0);

        String lib = Files.readString(root.resolve("lib/lib.iml"));
        assertThat(lib).contains("com.foo:leaf:jar::1.0").doesNotContain("com.foo:leaf:jar::2.0");
        String app = Files.readString(root.resolve("app/app.iml"));
        assertThat(app).contains("com.foo:leaf:jar::2.0").doesNotContain("com.foo:leaf:jar::1.0");
    }

    private String tree(Path root, @Nullable String module) {
        List<String> args = new ArrayList<>(List.of("tree", "-C", root.toString(), "--transitive", "--no-progress"));
        if (module != null) args.add(module);
        int[] exit = new int[1];
        Capture.Streams out = Capture.both(() -> exit[0] = run(args.toArray(String[]::new)));
        assertThat(exit[0]).as(out.out() + out.err()).isEqualTo(0);
        return out.out().replaceAll("\u001b\\[[\\d;]*m", "");
    }

    private int lock(Path root) {
        return run(
                "lock",
                "-C",
                root.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                root.resolve("cache").toString());
    }

    static void writeWorkspace(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "lib"]
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [platform-dependencies]
                the-bom = { group = "org.example", version = "1.0" }

                [dependencies]
                middle = { group = "com.foo", version = "1.0" }
                """);
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group = "com.acme"
                name = "lib"
                version = "0.1.0"

                [dependencies]
                middle = { group = "com.foo", version = "1.0" }
                """);
    }
}
