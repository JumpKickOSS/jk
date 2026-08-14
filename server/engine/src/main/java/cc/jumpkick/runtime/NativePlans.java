// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code jk native} plan: {@link BuildPlanner} plus {@link BuildPlanner#nativeStep} for
 * eligible modules. GraalVM home is always resolved client-side and passed in.
 */
public final class NativePlans {

    private NativePlans() {}

    /**
     * {@code jk native} adds a native-image tail when the client resolved a GraalVM home for the
     * module (unique main + {@code GRAALVM_HOME}). {@code [native] always = true} is {@code jk
     * build}'s tail, not this gate.
     */
    public static boolean isNativeEligible(Path graalHome) {
        return graalHome != null;
    }

    /**
     * The native-image main class: {@code --main}, then {@code [native].main-class}, then {@code
     * [image].main}, then {@code [application] main}.
     */
    public static String resolveMain(Path buildFile, String mainOverride) {
        Path dir = buildFile.getParent();
        return dir == null ? mainOverride : cc.jumpkick.layout.NativePreflight.specifiedMain(dir, mainOverride);
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
            Path jdksDir,
            Path graalHome,
            String mainOverride,
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
     * homes for the selection alone (JK-1361).
     */
    public static BuildPlan moduleBuildPlan(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path jdksDir,
            Path graalHome,
            String mainOverride,
            List<String> extraArgs,
            boolean skipTests,
            boolean verbose,
            boolean allowNative) {
        Path buildFile = moduleDir.resolve("jk.toml");
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
        boolean compact = cc.jumpkick.layout.ModuleLayout.isCompact(moduleDir);
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
                cc.jumpkick.config.SessionContext.current());
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        // Assembly / sources tails only here — native carries CLI main/args from this command.
        BuildPlanner.appendDeclaredTails(builder, inputs, graalHome, /*allowNative*/ false);
        if (allowNative && isNativeEligible(graalHome)) {
            builder.addTask(BuildPlanner.nativeStep(
                    moduleDir,
                    cache,
                    lockFile,
                    jdksDir,
                    graalHome,
                    resolveMain(buildFile, mainOverride),
                    extraArgs == null ? List.of() : extraArgs,
                    /*allowShared*/ false));
        }
        return builder.terminal(TaskNames.NATIVE_IMAGE).build();
    }

    /**
     * {@code jk native}'s exit-code mapping for a failed module plan: a native-step "main class"
     * misconfiguration exits {@link Exit#USAGE}, a test failure exits 4, anything else 1.
     */
    public static int failureExitCode(BuildPlan plan, BuildPlanResult result) {
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message() != null && d.message().contains("main class")) {
                return Exit.USAGE;
            }
        }
        var testResult = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }
}
