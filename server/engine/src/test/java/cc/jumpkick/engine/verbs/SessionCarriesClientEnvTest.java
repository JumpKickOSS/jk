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
 * A verb that builds its own {@link cc.jumpkick.config.Session} from a request must put the
 * request's environment on it.
 *
 * <p>Textual, because the defect it catches is an omission and there is nothing to call. {@code
 * WorkspaceBuildVerb} and {@code TestVerb} each assembled a session for the build to run under and
 * left {@code withVariant} off it, while passing the same values to the request object beside it.
 * Nothing failed: {@code env:} indirection is handed the request's map directly and kept working,
 * so only the callers going through {@code BuildEnv} were affected — and those silently fell
 * through to the engine's own environment, which is a daemon's, so {@code FOO=x jk build} did
 * nothing while {@code FOO=x} exported before the daemon started worked for every later build.
 *
 * <p>Both halves of that are invisible in a passing suite, which is why this reads the source.
 */
class SessionCarriesClientEnvTest {

    /**
     * Spelled from the checkout root, not from the working directory: Gradle runs a test with CWD at
     * its module, a workspace {@code jk build} runs it from the engine state dir. {@link RepoRoot}
     * is the anchor both layouts agree on.
     */
    private static final Path VERBS = RepoRoot.dir(SessionCarriesClientEnvTest.class, "server/engine")
            .resolve("src/main/java/cc/jumpkick/engine/verbs");

    /** Reaching any of these means something downstream may consult {@code BuildEnv}. */
    private static final Pattern BUILD_PATH =
            Pattern.compile("BuildPlanner|BuildService|PlannerSupport|TestStamp|BuildEnv");

    @Test
    void every_verb_that_builds_a_session_from_a_request_carries_its_env() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VERBS)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                // Only verbs that assemble a session themselves AND read a request line: the ones
                // taking a session from EngineVerbBridge.resolveSession already get this right, and
                // a verb with no request has no caller environment to carry.
                if (!src.contains("Session.defaults()") || !src.contains("requestLine")) continue;
                // Only the verbs that plan, build or key. The rest never ask BuildEnv anything, so
                // the caller's environment has nothing to change for them, and requiring it there
                // would be ceremony that teaches the next reader the wrong reason for this rule.
                if (!BUILD_PATH.matcher(src).find()) continue;
                if (src.contains("withVariant(")) continue;
                offenders.add(f.getFileName().toString());
            }
        }
        assertThat(offenders)
                .as("verbs building a Session from a requestLine without .withVariant(variant, clientEnv) —"
                        + " BuildEnv would fall through to the engine daemon's own environment")
                .isEmpty();
    }

    @Test
    void every_verb_that_builds_a_session_from_a_request_carries_its_toolchain() throws IOException {
        // Same omission, one field over, and it stayed invisible for the same reason: the specs were
        // set on the *client's* Session and never serialised, so the engine's SWITCH tier was always
        // empty and `--jdk` / `JK_JDK` selected nothing. The consequence was a build whose JDK
        // depended on which shell had started the daemon, so `jk engine stop` changed what compiled.
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VERBS)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                if (!src.contains("Session.defaults()") || !src.contains("requestLine")) continue;
                if (!BUILD_PATH.matcher(src).find()) continue;
                if (src.contains("withToolchainSpecs(")) continue;
                offenders.add(f.getFileName().toString());
            }
        }
        assertThat(offenders)
                .as("verbs building a Session from a requestLine without .withToolchainSpecs(jdk, graal) —"
                        + " JdkResolution's SWITCH tier would see no selection and fall through to the"
                        + " daemon's own toolchain")
                .isEmpty();
    }

    @Test
    void every_verb_that_plans_without_building_a_session_installs_one() throws IOException {
        // The third shape of the same omission. ExecPlanVerb and PluginCommandVerb passed the
        // request's variant and env into helpers that assemble a BuildPlanner.Inputs rather than a
        // Session — so nothing installed the request's toolchain selection, and `jk run --jdk 21`
        // resolved the JVM that runs the app without the switch. The client was already sending it.
        //
        // A verb that reads a request and reaches the build path must therefore either build a
        // Session (the arms above) or install one for the extent of the call. Textual for the same
        // reason as the others: the defect is an omission, and nothing fails when it is present.
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VERBS)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                if (!src.contains("requestLine")) continue;
                if (!BUILD_PATH.matcher(src).find() && !src.contains("ExecPlans.") && !src.contains("PluginCommands.")) {
                    continue;
                }
                // Builds its own session, or takes one from the bridge, or installs one for the call.
                if (src.contains("Session.defaults()")
                        || src.contains("host.resolveSession(")
                        || src.contains("SessionContext.where(")
                        || src.contains("SessionContext.runWhere(")) {
                    continue;
                }
                offenders.add(f.getFileName().toString());
            }
        }
        assertThat(offenders)
                .as("verbs that plan from a requestLine without a session installed — every toolchain"
                        + " and env resolver they reach would read the daemon's own state")
                .isEmpty();
    }
}
