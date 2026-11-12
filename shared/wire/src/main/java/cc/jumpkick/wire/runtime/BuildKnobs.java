// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The request's knobs every module's plan is built under, read once from a build request or an
 * explain request: the build, its preflight forecast and {@code jk explain} plan each module under
 * the same value.
 *
 * @param profile the {@code --profile}, or null for none
 * @param variant the variant selection; {@code ""} takes the declared defaults
 * @param clientEnv the client-resolved env for {@code env:} indirection
 * @param skipTests the goal's test policy applied: true when the goal runs no tests or the request
 *     skips them
 * @param target the goal
 * @param selection the selected module dirs; empty for the whole workspace
 * @param ephemeral a throwaway build: its actions persist nothing
 */
public record BuildKnobs(
        @Nullable String profile,
        String variant,
        Map<String, String> clientEnv,
        boolean skipTests,
        WorkspaceTarget target,
        Set<Path> selection,
        boolean ephemeral) {

    /** {@code jk build}'s knobs with no flags: no profile or variant, tests run, the whole workspace. */
    public static final BuildKnobs DEFAULTS =
            new BuildKnobs(null, "", Map.of(), false, WorkspaceTarget.PACKAGE, Set.of(), false);

    public BuildKnobs {
        clientEnv = Map.copyOf(clientEnv);
        selection = Set.copyOf(selection);
        skipTests = skipTests || target.tests() == WorkspaceTarget.Tests.NOT_RUN;
    }

    /** The knobs {@code request} plans its modules under. */
    public static BuildKnobs of(WorkspaceRequest request) {
        WorkspaceSpec spec = request.spec();
        return new BuildKnobs(
                request.profile(),
                Objects.requireNonNullElse(request.variant(), ""),
                Objects.requireNonNullElse(request.clientEnv(), Map.of()),
                request.skipTests(),
                request.target(),
                spec == null ? Set.of() : spec.selectedModules(),
                request.ephemeralActions());
    }

    /**
     * {@code jk build}'s knobs under {@code session}, which carries the request's variant and client
     * env: no profile, tests run, the whole workspace. What {@code jk explain} and a forecast start
     * from.
     */
    public static BuildKnobs of(Session session) {
        return DEFAULTS.withVariant(Objects.requireNonNullElse(session.variant(), ""))
                .withClientEnv(Objects.requireNonNullElse(session.clientEnv(), Map.of()));
    }

    public BuildKnobs withProfile(@Nullable String next) {
        return new BuildKnobs(next, variant, clientEnv, skipTests, target, selection, ephemeral);
    }

    public BuildKnobs withVariant(String next) {
        return new BuildKnobs(profile, next, clientEnv, skipTests, target, selection, ephemeral);
    }

    public BuildKnobs withClientEnv(Map<String, String> next) {
        return new BuildKnobs(profile, variant, next, skipTests, target, selection, ephemeral);
    }

    /** These knobs skipping tests on {@code next}; a goal that runs none skips them whatever it says. */
    public BuildKnobs withSkipTests(boolean next) {
        return new BuildKnobs(profile, variant, clientEnv, next, target, selection, ephemeral);
    }

    /** These knobs under goal {@code next}, the test policy applied once more. */
    public BuildKnobs withTarget(WorkspaceTarget next) {
        return new BuildKnobs(profile, variant, clientEnv, skipTests, next, selection, ephemeral);
    }

    public BuildKnobs withSelection(Set<Path> next) {
        return new BuildKnobs(profile, variant, clientEnv, skipTests, target, next, ephemeral);
    }

    /**
     * The request a module's plan is built from under these knobs, for a planner with no request of
     * its own (the forecast): every knob as here, no workers, no JDK root.
     */
    public WorkspaceRequest request(Path entryDir, Path cache) {
        return new WorkspaceRequest(
                entryDir,
                cache,
                null,
                0,
                profile,
                skipTests,
                false,
                0,
                null,
                false,
                false,
                false,
                variant,
                clientEnv,
                ephemeral,
                new WorkspaceSpec(target, selection, Map.of(), null),
                List.of(),
                false);
    }

    /** Whether a module's plan under these knobs runs no JUnit, as {@link #skipsTests} decides it. */
    public boolean planSkipsTests(TestSelection selection) {
        return skipsTests(selection, target.testOnly(), skipTests);
    }

    /**
     * Whether a plan runs no JUnit: a test-only plan runs its tests unless {@code --scripts-only}
     * (guard scripts, no JUnit), any other plan skips them on {@code skipTests} too.
     */
    public static boolean skipsTests(TestSelection selection, boolean testOnly, boolean skipTests) {
        return selection.scriptsOnly() || (!testOnly && skipTests);
    }
}
