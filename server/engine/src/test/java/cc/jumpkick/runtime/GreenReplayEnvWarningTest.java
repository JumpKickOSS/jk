// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite served from its green marker says again the undeclared variables its run named, while
 * the module still does not declare them.
 */
class GreenReplayEnvWarningTest {

    private static final String MANIFEST = "group = \"ex\"\nname = \"m\"\nversion = \"1.0.0\"\n";

    @TempDir
    Path tmp;

    @Test
    void a_replayed_green_run_says_the_undeclared_variable_its_run_named() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(dir.resolve("jk.toml"), MANIFEST);
        BuildPlanner.Inputs in = BuildPlanner.Inputs.of(
                dir, tmp.resolve("cache"), Session.defaults().withWorkingDir(dir));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        List<String> said = EnvReadReport.said(List.of(new EnvReadReport.Told("FOO", "ex.EnvTest", true)));
        TestLaunch.recordOutcome(
                new Recording(),
                in,
                cache,
                "run-tests:m",
                "stamp",
                new TestSummary(1, 1, 0, 0, List.of()),
                true,
                new TestFailureSource.Cache(),
                said);

        Recording replay = new Recording();
        boolean hit = RequestShell.where(
                Map.of("FOO", "bar"),
                () -> TestLaunch.replayGreenRun(
                        replay, in, cache, "stamp", JkBuildParser.parse(MANIFEST), WorkerEnv.strict()));

        assertThat(hit).isTrue();
        assertThat(replay.replayed)
                .containsExactly("env-undeclared FOO: EnvTest read FOO, which jk.toml does not declare;"
                        + " it saw no value. Add to [env] require: \"FOO\"");

        JkBuild declares = JkBuildParser.parse(MANIFEST + "\n[env]\noptional = [\"FOO\"]\n");
        Recording declared = new Recording();
        TestLaunch.replayGreenRun(declared, in, cache, "stamp", declares, WorkerEnv.strict());
        assertThat(declared.replayed).as("a variable the module now declares").isEmpty();
    }

    @Test
    void past_the_cap_a_replay_counts_the_rest_as_its_run_did() throws Exception {
        List<EnvReadReport.Told> told = new ArrayList<>();
        for (int i = 0; i < EnvReadReport.MAX_TOLD + 2; i++) told.add(new EnvReadReport.Told("V" + i, "ex.T", false));
        Path dir = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(dir.resolve("jk.toml"), MANIFEST);
        BuildPlanner.Inputs in = BuildPlanner.Inputs.of(
                dir, tmp.resolve("cache"), Session.defaults().withWorkingDir(dir));

        Recording replay = new Recording();
        EnvReadReport.replay(replay, in, JkBuildParser.parse(MANIFEST), WorkerEnv.strict(), EnvReadReport.said(told));

        assertThat(replay.replayed).hasSize(EnvReadReport.MAX_TOLD + 1);
        assertThat(replay.replayed.getLast())
                .isEqualTo("env-undeclared : 2 more undeclared variables read; jk results lists the first 8");
        assertThat(EnvReadReport.split(replay.replayed.getFirst().substring("env-undeclared V0: ".length())))
                .isEqualTo(new EnvReadReport.Said(
                        "T reads V0, which jk.toml does not declare", "add to [env] optional: \"V0\"", 1));
    }

    /** A step context that keeps what it was told to say again. */
    private static final class Recording implements TaskContext {
        final List<String> replayed = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {
            throw new AssertionError("a replay says only what its run said: " + message);
        }

        @Override
        public void replayedWarn(String code, String key, String message) {
            replayed.add(code + " " + key + ": " + message);
        }

        @Override
        public void error(String code, String message) {
            throw new AssertionError(message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
