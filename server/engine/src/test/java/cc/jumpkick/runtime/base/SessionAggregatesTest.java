// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session parses its ledger once: repeated reads are freshness stats, a write is picked up by
 * the next read and parsed once, and two checkouts of one project read one row set, each expanded
 * to its own path.
 */
class SessionAggregatesTest {

    private String previousStateDir;
    private Session previousSession;

    @BeforeEach
    void isolate() {
        previousStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        previousSession = SessionContext.current();
    }

    @AfterEach
    void restore() {
        SessionContext.install(previousSession);
        SessionAggregates.clear();
        if (previousStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", previousStateDir);
    }

    private static Path checkout(Path state, String name) throws Exception {
        Path dir = Files.createDirectories(state.resolve("wt").resolve(name));
        Files.writeString(dir.resolve("jk.toml"), "id = \"demo\"\nname = \"demo\"\n");
        return dir;
    }

    private static Path ledger(Path state, Path checkout, String body) throws Exception {
        Path home = ProjectBuilds.projectHome(state.resolve("builds"), null, checkout);
        Files.createDirectories(home);
        Path file = home.resolve(ProjectBuilds.PROJECT_METRICS);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void a_read_after_a_read_does_not_reparse_and_a_write_is_parsed_once(@TempDir Path state) throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path work = checkout(state, "a");
        Path file = ledger(state, work, """
                [mean]
                module.server/io.task.compile-java.wall-ms = 800
                """);
        SessionContext.install(Session.defaults().withWorkingDir(work));
        SessionAggregates.clear();

        long parses = AggregatedMetrics.parseCount();
        for (int i = 0; i < 5; i++) {
            assertThat(SessionAggregates.current()
                            .taskWallMs(work.resolve("server/io").toString(), "compile-java"))
                    .hasValue(800);
        }
        assertThat(AggregatedMetrics.parseCount() - parses)
                .as("five reads, one parse")
                .isEqualTo(1);

        Files.writeString(file, """
                [mean]
                module.server/io.task.compile-java.wall-ms = 950
                """);
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        for (int i = 0; i < 5; i++) {
            assertThat(SessionAggregates.current()
                            .taskWallMs(work.resolve("server/io").toString(), "compile-java"))
                    .hasValue(950);
        }
        assertThat(AggregatedMetrics.parseCount() - parses)
                .as("the write is parsed once, the reads after it not at all")
                .isEqualTo(2);
    }

    @Test
    void two_checkouts_of_one_project_read_one_row_set_each_at_its_own_path(@TempDir Path state) throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path a = checkout(state, "a");
        Path b = checkout(state, "b");
        ledger(state, a, """
                [mean]
                module._.task.guard.wall-ms = 70
                module.server/io.task.compile-java.wall-ms = 800
                module.server/io.test-class.com.example.IoTest.wall-ms = 300
                """);
        assertThat(ProjectBuilds.projectHome(state.resolve("builds"), null, b))
                .isEqualTo(ProjectBuilds.projectHome(state.resolve("builds"), null, a));

        SessionContext.install(Session.defaults().withWorkingDir(a));
        SessionAggregates.clear();
        AggregatedMetrics fromA = SessionAggregates.current();
        SessionContext.install(Session.defaults().withWorkingDir(b));
        AggregatedMetrics fromB = SessionAggregates.current();

        assertThat(fromA.taskWallMs(a.resolve("server/io").toString(), "compile-java"))
                .hasValue(800);
        assertThat(fromB.taskWallMs(b.resolve("server/io").toString(), "compile-java"))
                .hasValue(800);
        assertThat(fromB.taskWallMs(a.resolve("server/io").toString(), "compile-java"))
                .as("b's view is keyed by b's paths")
                .isEmpty();
        assertThat(fromB.taskWallMs(b.toString(), "guard")).hasValue(70);
        assertThat(fromB.testClassWallMs(b.resolve("server/io").toString(), "com.example.IoTest"))
                .hasValue(300);
        assertThat(BuildMetrics.load(BuildMetrics.defaultFile())
                        .step(b.resolve("server/io").toString(), "compile-java"))
                .as("the folded step view carries the same expansion")
                .isPresent();
    }
}
