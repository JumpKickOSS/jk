// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.PlannerNative;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.TestClassMatch;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk native}: the {@link Tail} that adds {@link PlannerNative#nativeStep} for eligible
 * modules. The GraalVM home is always resolved client-side and passed in.
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
     * {@code jk native}'s tail: {@code native-image} with the command's main and arguments, for a
     * module the client resolved a GraalVM home for. A module without one gets no tail and still
     * builds through PACKAGE, so a selected sibling can depend on it.
     */
    public static Tail tail(@Nullable Path graalHome, @Nullable String mainOverride, List<String> extraArgs) {
        if (!isNativeEligible(graalHome)) return Tail.NONE;
        List<String> args = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        return new Tail() {
            @Override
            public void append(BuildPlan.Builder plan, BuildPlanner.Inputs in) {
                plan.extend(PlannerNative.nativeStep(
                        in.dir(),
                        in.cache(),
                        in.lockFile(),
                        in.jdksDir(),
                        graalHome,
                        resolveMain(in.buildFile(), mainOverride),
                        args,
                        /*allowShared*/ false));
            }

            @Override
            public boolean buildsNativeImage() {
                return true;
            }
        };
    }

    /**
     * Build-family exit-code mapping for a failed module plan: a native-step "main class"
     * misconfiguration or an image "no-main" diagnostic exits {@link Exit#USAGE}, a refused SNAPSHOT
     * publish {@link Exit#DATA_ERR}, a publish with no jar {@link Exit#NO_INPUT}, a test failure
     * exits 4, a {@code --class} that matched no test {@link Exit#USAGE}, anything else 1. Every
     * build-like goal's module exits through it.
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
            if ("snapshot".equals(d.code())) {
                return Exit.DATA_ERR;
            }
            if ("missing-jar".equals(d.code())) {
                return Exit.NO_INPUT;
            }
        }
        var testResult = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return Exit.TESTS_FAILED;
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            if (TestClassMatch.NO_TESTS_MATCHED.equals(d.code())) return Exit.USAGE;
        }
        return 1;
    }
}
