// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in integration suite is stamped like a declared one: what it claims (the integration
 * tag) is a suite token with no table written, a table that widens it moves that token, and the
 * suite's own directory appearing or going moves the key of a run that selects it, so neither a
 * new src/integration nor a removed one replays a green marker stored without it.
 */
class IntegrationSuiteStampTest {

    private static final String MANIFEST = """
            name = "demo"
            group = "t"
            version = "0.0.1"
            java = 25
            %s
            """;

    @Test
    void the_built_in_suite_s_claim_is_a_suite_token_and_a_table_that_widens_it_moves_it(@TempDir Path dir)
            throws Exception {
        List<String> bare = extras(dir, "", TestSelection.DEFAULT);
        assertThat(bare).as("no table: the convention's claim").contains("suite:integration=@integration");

        List<String> widened = extras(dir, "[test.suites.integration]\ntags = [\"db\"]\n", TestSelection.DEFAULT);
        assertThat(widened)
                .contains("suite:integration=@integration,db")
                .doesNotContain("suite:integration=@integration");
    }

    @Test
    void src_integration_appearing_or_going_moves_the_key_of_a_run_that_selects_it(@TempDir Path dir) throws Exception {
        Path unit = dir.resolve("src/test/java/app/CartTest.java");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, "package app;\nclass CartTest {}\n");
        Files.writeString(dir.resolve("jk-lock.toml"), "");
        TestSelection integration = TestSelection.of(List.of(TestSuites.INTEGRATION), false, List.of(), List.of());

        String without = key(dir, integration);
        Path it = dir.resolve("src/integration/java/app/DbIT.java");
        Files.createDirectories(it.getParent());
        Files.writeString(it, "package app;\nclass DbIT {}\n");
        String with = key(dir, integration);
        assertThat(with).as("src/integration appeared").isNotEqualTo(without);

        Files.delete(it);
        assertThat(key(dir, integration)).as("and went again").isEqualTo(without);
    }

    private static List<String> extras(Path dir, String tables, TestSelection sel) throws Exception {
        JkBuild project = parse(dir, tables);
        return SessionContext.where(Session.defaults().withTestSelection(sel), () -> TestStampInputs.of(
                        TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras());
    }

    private static String key(Path dir, TestSelection sel) throws Exception {
        JkBuild project = parse(dir, "");
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        String key = SessionContext.where(Session.defaults().withTestSelection(sel), () -> TestStampInputs.of(
                        TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .forecastKey(false, classes, null, dir.resolve("jk-lock.toml")));
        return Objects.requireNonNull(key, "every input readable");
    }

    private static JkBuild parse(Path dir, String tables) throws Exception {
        return JkBuildParser.parseLocal(Files.writeString(dir.resolve("jk.toml"), MANIFEST.formatted(tables)));
    }
}
