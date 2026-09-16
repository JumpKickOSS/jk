// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.PlannerNative;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.TestSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk native} plan: {@link BuildPlanner} plus {@link PlannerNative#nativeStep} for
 * eligible modules. GraalVM home is always resolved client-side and passed in.
 */
public final class NativePlans {

    private NativePlans() {}

    /**
     * {@code jk native} adds a native-image tail when the client resolved a GraalVM home for the
     * module (unique main + {@code GRAALVM_HOME}). {@code [native] enabled = "always"} is {@code jk
     * build}'s tail, not this gate.
     */
    public static boolean isNativeEligible(@Nullable Path graalHome) {
        return graalHome != null;
    }

    /**
     * The native-image main class: {@code --main}, then {@code [native].main}, then {@code
     * [image].main}, then {@code [application] main}.
     */
    public static @Nullable String resolveMain(Path buildFile, @Nullable String mainOverride) {
        Path dir = ManifestPaths.moduleOf(buildFile);
        return dir == null ? mainOverride : NativePreflight.specifiedMain(dir, mainOverride);
    }

    /**
     * Construct (but do not run) one module's plan: core steps plus the native-image tail when the
     * module is native-eligible ({@code graalHome} then names the GraalVM the client resolved;
     * ignored otherwise). Non-eligible modules still compile and package so eligible siblings can
     * depend on them.
     */
    public static BuildPlan moduleBuildPlan(
            Path moduleDir,
            JkBuild module,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extraArgs,
            boolean skipTests,
            boolean verbose) {
        return moduleBuildPlan(
                moduleDir, module, cache, jdksDir, graalHome, mainOverride, extraArgs, skipTests, verbose, true);
    }

    /**
     * As {@link #moduleBuildPlan(Path, JkBuild, Path, Path, Path, String, List, boolean, boolean)}
     * with {@code allowNative}: a prereq the cascade pulled in for a {@code -m} selection builds to
     * a jar only — the user selected what gets native-compiled, and the client resolved GraalVM
     * homes for the selection alone.
     */
    public static BuildPlan moduleBuildPlan(
            Path moduleDir,
            JkBuild module,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extraArgs,
            boolean skipTests,
            boolean verbose,
            boolean allowNative) {
        return moduleBuildPlan(
                moduleDir,
                module,
                cache,
                jdksDir,
                graalHome,
                mainOverride,
                extraArgs,
                skipTests,
                verbose,
                allowNative,
                null);
    }

    /**
     * As above with {@code decorate}: request-level Inputs decoration (workers, profile, variant +
     * client env, module set, ephemeral actions) applied by the one orchestrator so the NATIVE
     * branch honors the same knobs as PACKAGE. {@code null} = none.
     */
    public static BuildPlan moduleBuildPlan(
            Path moduleDir,
            JkBuild module,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extraArgs,
            boolean skipTests,
            boolean verbose,
            boolean allowNative,
            @Nullable UnaryOperator<BuildPlanner.Inputs> decorate) {
        Path buildFile = ManifestPaths.manifestIn(moduleDir);
        Path lockFile = LockPaths.lockFile(moduleDir);
        boolean compact = ModuleLayout.isCompact(moduleDir);
        int estimatedTests = TestSupport.estimateAllSuiteTestCount(moduleDir, compact);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                moduleDir,
                cache,
                buildFile,
                lockFile,
                moduleDir,
                1,
                estimatedTests,
                null,
                jdksDir,
                skipTests,
                verbose,
                false,
                false,
                Set.of(),
                SessionContext.current());
        if (decorate != null) inputs = decorate.apply(inputs);
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        // Assembly / sources tails only here — native carries CLI main/args from this command.
        // Do not append [native] always via allowNative; that is the jk build path. jk native
        // attaches native-image only for modules the client marked with a Graal home (unique main).
        PlannerTails.appendDeclaredTails(builder, inputs, graalHome, /*allowNative*/ false);
        if (allowNative && isNativeEligible(graalHome)) {
            builder.addTask(PlannerNative.nativeStep(
                    moduleDir,
                    cache,
                    lockFile,
                    jdksDir,
                    graalHome,
                    resolveMain(buildFile, mainOverride),
                    extraArgs == null ? List.of() : extraArgs,
                    /*allowShared*/ false));
            // Re-root only when the native task is present. Prereq / non-main modules stay at
            // package-jar (or assembly/sources tails) so the workspace cascade can still build them.
            return builder.terminal(TaskNames.NATIVE_IMAGE).build();
        }
        return builder.build();
    }

    /**
     * Build-family exit-code mapping for a failed module plan: a native-step "main class"
     * misconfiguration or an image "no-main" diagnostic exits {@link Exit#USAGE}, a test failure
     * exits 4, anything else 1. Shared by the workspace path for native AND image terminals.
     */
    public static int failureExitCode(BuildPlan plan, BuildPlanResult result) {
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            if ("affected-refuse".equals(d.code())) {
                return Exit.CONFIG;
            }
            if ("native".equals(d.code()) && d.message() != null && d.message().contains("main class")) {
                return Exit.USAGE;
            }
            if ("no-main".equals(d.code())) {
                return Exit.USAGE;
            }
        }
        var testResult = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return Exit.TESTS_FAILED;
        return 1;
    }
}
