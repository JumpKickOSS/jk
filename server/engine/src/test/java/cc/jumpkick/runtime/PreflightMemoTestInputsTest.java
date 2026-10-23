// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PreflightMemoFixtures.writeProject;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memo decides whether a module is scheduled at all, so it must miss whenever an input of the
 * module's run-tests stamp moves, files or not: a hit leaves the stamp unconsulted and the suite
 * unrun on a tree {@code jk test} would fail.
 */
class PreflightMemoTestInputsTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static BuildGraph.Result project(Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk.toml"), Files.readString(tmp.resolve("jk.toml")) + """

                [test]
                env = ["PROBE"]
                """);
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    private static Session withProbe(String value) {
        return Session.defaults().withVariant(null, Map.of("PROBE", value));
    }

    private static boolean hits(Session session, Path tmp, BuildGraph.Result graph, boolean skipTests)
            throws Exception {
        return SessionContext.where(
                session, () -> PreflightMemo.tryLoadDirty(tmp, graph, skipTests).isPresent());
    }

    private static void store(Session session, Path tmp, BuildGraph.Result graph, boolean skipTests) throws Exception {
        SessionContext.where(session, () -> {
            PreflightMemo.storeDirty(
                    tmp,
                    graph,
                    skipTests,
                    Set.of(),
                    PreflightMemo.snapshotFingerprints(graph, skipTests).fingerprints());
            return null;
        });
    }

    @Test
    void a_forwarded_env_value_the_suite_sees_misses_the_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        store(withProbe("1"), tmp, graph, false);

        assertThat(hits(withProbe("1"), tmp, graph, false)).isTrue();
        assertThat(hits(withProbe("2"), tmp, graph, false))
                .as("PROBE=2 changes what the suite sees")
                .isFalse();
    }

    @Test
    void a_build_that_skips_tests_is_not_scheduled_by_a_test_input(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        store(withProbe("1"), tmp, graph, true);

        assertThat(hits(withProbe("2"), tmp, graph, true)).isTrue();
    }

    @Test
    void the_requests_jvm_flags_miss_the_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = project(tmp);
        store(Session.defaults(), tmp, graph, false);

        Session flagged = Session.defaults().withJvm(new PluginTuning(null, null, null, List.of("-Djprobe=2")));
        assertThat(hits(flagged, tmp, graph, false))
                .as("--jvm-arg reaches every suite JVM")
                .isFalse();
    }

    /** The stamp keys what the fork launches: the request's flags and the fast tier's timeout. */
    @Test
    void the_run_tests_stamp_keys_the_requests_flags_and_the_tier_timeout(@TempDir Path tmp) throws Exception {
        project(tmp);
        JkBuild plain = JkBuildParser.parse(tmp.resolve("jk.toml"));
        List<String> base = SessionContext.where(withProbe("1"), () -> PlannerSupport.testStampExtras(tmp, plain));
        List<String> flagged = SessionContext.where(
                withProbe("1").withJvm(new PluginTuning(null, null, null, List.of("-Djprobe=2"))),
                () -> PlannerSupport.testStampExtras(tmp, plain));
        assertThat(flagged).isNotEqualTo(base).contains("jvm-user-args:-Djprobe=2");

        Files.writeString(tmp.resolve("jk.toml"), Files.readString(tmp.resolve("jk.toml")) + """
                fast-method-budget = "250ms"
                """);
        JkBuild budgeted = JkBuildParser.parse(tmp.resolve("jk.toml"));
        List<String> withBudget =
                SessionContext.where(withProbe("1"), () -> PlannerSupport.testStampExtras(tmp, budgeted));
        assertThat(withBudget).isNotEqualTo(base);
    }
}
