// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A verb that plans, builds or forecasts from a request runs under the session {@code
 * ProtoSession.sessionOf} derives from that request, never one it assembled itself.
 *
 * <p>Textual, because the defect is an omission and nothing fails when a hand-built session merely
 * lacks a field: a forecast keyed against a selection the build never used, the daemon's
 * environment standing in for the caller's, a {@code --jdk} switch that selected nothing. Each was
 * one verb's chain missing one wither. One constructor makes that class of bug unwritable; this
 * test keeps every verb on it.
 */
class VerbSessionOriginTest {

    /**
     * Spelled from the checkout root, not from the working directory: a module-rooted run has CWD
     * at its module, a workspace {@code jk build} runs it from the engine state dir. {@link RepoRoot}
     * is the anchor both layouts agree on.
     */
    private static final Path VERBS = RepoRoot.dir(VerbSessionOriginTest.class, "server/engine")
            .resolve("src/main/java/cc/jumpkick/engine/verbs");

    /** Reaching any of these means the verb plans, builds, forecasts or keys under a session. */
    private static final Pattern BUILD_PATH = Pattern.compile(
            "BuildPlanner|BuildService|PlannerSupport|TestStamp|BuildEnv|ExplainReport|ExecPlans\\.|PluginCommands\\.");

    /** Verb sources that read a request line and reach the build path. */
    private static List<Path> planningVerbs() throws IOException {
        List<Path> verbs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VERBS)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                if (src.contains("requestLine") && BUILD_PATH.matcher(src).find()) verbs.add(f);
            }
        }
        // The scan must have seen the build, test, explain and forecast verbs at least, or an empty
        // walk would satisfy every assertion below.
        assertThat(verbs.stream().map(p -> p.getFileName().toString()))
                .contains("WorkspaceBuildVerb.java", "TestVerb.java", "ExplainVerb.java", "ForecastVerb.java");
        return verbs;
    }

    @Test
    void a_planning_verb_never_assembles_its_own_session() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : planningVerbs()) {
            if (Files.readString(f).contains("Session.defaults()"))
                offenders.add(f.getFileName().toString());
        }
        assertThat(offenders)
                .as("verbs assembling a Session by hand — every field the chain forgets is one the build"
                        + " and its forecast can disagree on; take it from ProtoSession.sessionOf")
                .isEmpty();
    }

    @Test
    void a_planning_verb_runs_under_the_request_session() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : planningVerbs()) {
            if (!Files.readString(f).contains("ProtoSession.sessionOf("))
                offenders.add(f.getFileName().toString());
        }
        assertThat(offenders)
                .as("verbs that plan from a requestLine without ProtoSession.sessionOf — every toolchain"
                        + " and env resolver they reach would read the daemon's own state")
                .isEmpty();
    }
}
