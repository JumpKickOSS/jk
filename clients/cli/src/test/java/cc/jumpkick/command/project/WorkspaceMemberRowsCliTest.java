// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.DefaultTestDepsFixture;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A two-member workspace whose members disagree: {@code app} holds a BOM that lifts {@code
 * com.foo:leaf} to 2.0, {@code lib} reaches leaf through {@code middle}, whose POM declares 1.0.
 * The lock partitions leaf per member, and every surface that reads one member's rows says so.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class WorkspaceMemberRowsCliTest {

    private static final String LIB_NOTE = "lib reads its own rows for 1 coordinate: com.foo:leaf 1.0 (workspace 2.0)";

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
        assertThat(out).contains("Workspace lock successful").contains(LIB_NOTE);
        assertThat(out.indexOf(LIB_NOTE))
                .as("the note follows the summary line")
                .isGreaterThan(out.indexOf("Workspace lock successful"));

        String results = Files.readString(root.resolve("target/jk-results.md"));
        assertThat(results).contains("## Lock notes").contains(LIB_NOTE);
        Matcher details =
                Pattern.compile("transcript: `([^`]+details\\.jsonl)`").matcher(results);
        assertThat(details.find())
                .as("the results file names its details.jsonl:\n" + results)
                .isTrue();
        Path transcript = Path.of(details.group(1));
        assertThat(transcript).as("the lock's run record has a transcript").exists();
        assertThat(Files.readString(transcript)).contains("lock-note").contains("lib reads its own rows");
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
