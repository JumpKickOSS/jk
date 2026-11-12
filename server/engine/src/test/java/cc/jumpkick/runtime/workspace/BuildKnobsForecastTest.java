// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast plans each module under the knobs the build planned it under: the variant, the
 * goal and the goal's test policy. Right after a build, the forecast of the same request reads
 * every module clean.
 */
@Tag("integration")
class BuildKnobsForecastTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_variant_build_forecasts_clean_under_its_variant() throws Exception {
        Path ws = workspace();
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        build(request(ws, cache).withVariant("mode=b", Map.of()));

        assertThat(dirty(ws, cache, request(ws, cache).withVariant("mode=b", Map.of())))
                .as("the forecast of the variant that was built")
                .isEmpty();
        assertThat(dirty(ws, cache, request(ws, cache)))
                .as("the forecast of the default variant compiles a's other source root")
                .singleElement()
                .asString()
                .startsWith("a ");
    }

    @Test
    void a_default_build_after_a_variant_build_builds_the_default_variant() throws Exception {
        Path ws = workspace();
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        build(request(ws, cache).withVariant("mode=b", Map.of()));
        Path classes = ws.resolve("a/target/classes/com/example");
        assertThat(classes.resolve("OnlyB.class")).exists();

        build(request(ws, cache));
        assertThat(classes.resolve("OnlyA.class"))
                .as("the default variant's class")
                .exists();
        assertThat(classes.resolve("OnlyB.class"))
                .as("the variant's class does not survive")
                .doesNotExist();
    }

    @Test
    void jk_compile_twice_reads_up_to_date() throws Exception {
        Path ws = workspace();
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        WorkspaceRequest compile = request(ws, cache).withSpec(WorkspaceSpec.compile(Set.of()));
        build(compile);

        assertThat(dirty(ws, cache, compile))
                .as("the forecast right after jk compile")
                .isEmpty();
        List<String> labels = build(compile);
        assertThat(labels).as("the second jk compile's preflight").contains("All modules up to date");
    }

    @Test
    void a_goal_that_runs_no_tests_forecasts_its_own_plan() throws Exception {
        // As a client that leaves the goal's test policy to the engine sends it: the raw flag.
        Path ws = workspace();
        Files.writeString(ws.resolve("jk-guards.toml"), RULES);
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        WorkspaceRequest run = new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false)
                .withSpec(WorkspaceSpec.of(WorkspaceTarget.RUN));
        build(run);

        assertThat(dirty(ws, cache, run)).as("the forecast right after jk run").isEmpty();
    }

    @Test
    void a_guard_lane_that_indexes_no_tests_forecasts_under_its_own_key() throws Exception {
        // The lane runs on --guard, and a goal that runs no tests compiles none for it to index.
        Path ws = workspace();
        Files.writeString(ws.resolve("jk-guards.toml"), RULES);
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        TestSelection guard = TestSelection.of(List.of(), false, List.of(), List.of(), false, true);
        WorkspaceRequest run = new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false)
                .withSpec(WorkspaceSpec.of(WorkspaceTarget.RUN));
        build(run, guard);

        // The members' module lanes; the root's workspace and tree lanes are priced apart from them.
        assertThat(dirty(ws, cache, run, guard))
                .as("the members' forecast right after jk run --guard")
                .noneMatch(m -> m.startsWith("a ") || m.startsWith("b "));
    }

    private static final String RULES = """
            [guards.no-banned]
            kind       = "forbid"
            signatures = ["com.example.Banned"]
            instead    = "nothing"
            why        = "a test rule"
            """;

    /** The modules the preflight's walk finds dirty or to restore under {@code request}, with no memo. */
    private static List<String> dirty(Path ws, Path cache, WorkspaceRequest request) throws Exception {
        return dirty(ws, cache, request, TestSelection.DEFAULT);
    }

    /** As above under the session's test {@code selection}. */
    private static List<String> dirty(Path ws, Path cache, WorkspaceRequest request, TestSelection selection)
            throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        Session session = session(request, selection);
        BuildKnobs knobs = BuildKnobs.of(request)
                .withTarget(Objects.requireNonNull(WorkspacePreflightPhase.coreTarget(request.target())));
        BuildForecasting.Preflight pf = SessionContext.where(
                session, () -> BuildForecasting.forecastWithFingerprints(graph, cache, null, knobs, false));
        List<String> out = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            if (!pf.dirty().contains(u.dir()) && !pf.restoreNeeded().contains(u.dir())) continue;
            List<String> steps = pf.modules().stream()
                    .filter(m -> m.dir().equals(u.dir()))
                    .flatMap(m -> m.steps().stream())
                    .filter(s -> !s.cached())
                    .map(s -> s.name() + " (" + s.text() + ")")
                    .toList();
            out.add(u.dir().getFileName() + " " + steps);
        }
        return out;
    }

    private static WorkspaceRequest request(Path ws, Path cache) {
        return new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false);
    }

    /** The session a client sends beside {@code request}: its variant and env, and the test {@code selection}. */
    private static Session session(WorkspaceRequest request, TestSelection selection) {
        return Session.defaults()
                .withCacheDir(request.cache())
                .withVariant(request.variant(), request.clientEnv())
                .withTestSelection(selection);
    }

    /** Builds {@code request}, returning the preflight's labels. */
    private static List<String> build(WorkspaceRequest request) throws Exception {
        return build(request, TestSelection.DEFAULT);
    }

    /** As above under the session's test {@code selection}. */
    private static List<String> build(WorkspaceRequest request, TestSelection selection) throws Exception {
        List<String> labels = new ArrayList<>();
        Session session = session(request, selection);
        WorkspaceResult result = SessionContext.where(
                session,
                () -> WorkspaceExecute.buildWorkspace(request, new WorkspaceBuildListener() {
                    @Override
                    public void onPreflight(String stage, int done, int total, String label) {
                        labels.add(label);
                    }
                }));
        assertThat(result.errors()).as("build errors").isEmpty();
        assertThat(result.success()).as("build success").isTrue();
        return labels;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        LockFlow.Result lock = LockFlow.run(ws, cache, List.of(), false, null);
        assertThat(lock.status()).as("workspace lock: %s", lock.error()).isZero();
    }

    /** Module {@code a} with two variant values, each adding its own source root, beside plain {@code b}. */
    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.knobs"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(ws.resolve("a"));
        Files.writeString(ws.resolve("a/jk.toml"), """
                group   = "com.knobs"
                name    = "a"
                version = "1.0.0"
                java    = 25

                [variants.mode]
                default = "a"
                [variants.mode.a]
                extra-src = ["src-a"]
                [variants.mode.b]
                extra-src = ["src-b"]
                """);
        write(ws.resolve("a/src/com/example/Lib.java"), "package com.example;\n\npublic final class Lib {}\n");
        write(ws.resolve("a/src-a/com/example/OnlyA.java"), "package com.example;\n\npublic final class OnlyA {}\n");
        write(ws.resolve("a/src-b/com/example/OnlyB.java"), "package com.example;\n\npublic final class OnlyB {}\n");
        Files.createDirectories(ws.resolve("b"));
        Files.writeString(ws.resolve("b/jk.toml"), """
                group   = "com.knobs"
                name    = "b"
                version = "1.0.0"
                java    = 25
                """);
        write(ws.resolve("b/src/com/example/b/B.java"), "package com.example.b;\n\npublic final class B {}\n");
        return ws;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
