// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.MODULE;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A module's side of the workspace schedule's sibling waits, and the checks after each wait that
 * what the step reads of a sibling is on disk: a missing sibling output fails the step that would
 * have read it, naming the sibling, rather than a compile or a test of its own.
 */
final class SiblingWaits {

    private SiblingWaits() {}

    /**
     * The one shape of the missing-sibling guard for the compile classpaths. The resolve writes
     * the accurate cause into {@code missingSiblingClasses}; failing here attributes it to setup,
     * where the sibling is named — not to the compile that would otherwise surface a {@code
     * cannot find symbol} pointing at the wrong file.
     */
    static void requireSiblingsCompiled(TaskContext ctx, WorkspaceClasspath.Result siblings, String prefix) {
        if (siblings.missingSiblingClasses().isEmpty()) return;
        for (String missing : siblings.missingSiblingClasses()) {
            ctx.error("workspace", prefix + missing);
        }
        throw new RuntimeException("missing workspace siblings");
    }

    /**
     * The point a plan first reads a sibling's jar for its own artifacts: wait for every sibling of
     * its build-order closure to have published its artifacts, then require them on disk. The wait
     * is the module's side of the workspace schedule ({@link cc.jumpkick.runtime.base.SiblingArtifacts});
     * the check after it names the jar a failed or sourceless sibling did not produce, so the step
     * that would have read it fails on the sibling rather than on a {@code NoClassDefFoundError} of
     * its own.
     */
    static void awaitSiblingArtifacts(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        in.siblings().awaitArtifacts(ctx::cancelled);
        if (ctx.cancelled()) return;
        requireRuntimeSiblings(ctx, in);
    }

    /**
     * As {@link #awaitSiblingArtifacts} for a step that runs on the test runtime classpath: it also
     * waits for the siblings only the test scopes name, and the test classes and fixtures selected,
     * and requires them. Test-view artifacts are required only when this plan compiles tests: under
     * {@code --skip-tests} no sibling produces fixtures or test classes and nothing here reads them.
     */
    static void awaitSiblingTestRuntime(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        in.siblings().awaitTestRuntime(ctx::cancelled);
        if (ctx.cancelled()) return;
        requireRuntimeSiblings(ctx, in);
        if (!PlannerResources.skipJUnit(in)) {
            WorkspaceClasspath.Result testSiblings =
                    WorkspaceClasspath.resolve(in.dir(), ctx.require(MODULE).project(), WorkspaceClasspath.TEST_SCOPES);
            requireSiblingsBuilt(ctx, testSiblings, "test dependency module not built: ", in.siblings());
        }
    }

    private static void requireRuntimeSiblings(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        WorkspaceClasspath.Result mainSiblings =
                WorkspaceClasspath.resolve(in.dir(), ctx.require(MODULE).project(), WorkspaceClasspath.RUNTIME_SCOPES);
        requireSiblingsBuilt(ctx, mainSiblings, "dependency module not built: ", in.siblings());
    }

    /**
     * {@code workerJars}, once every workspace module whose jar is among them has published it: a
     * suite runs against the workers this build makes, whatever order the schedule took.
     */
    static Map<String, String> awaitWorkerSiblings(
            TaskContext ctx, BuildPlanner.Inputs in, Map<String, String> workerJars) throws InterruptedException {
        Path self = in.dir().toAbsolutePath().normalize();
        Set<Path> producers = new LinkedHashSet<>();
        for (String jar : workerJars.values()) {
            for (Path p = Path.of(jar).toAbsolutePath().normalize(); p != null; p = p.getParent()) {
                Path parent = p.getParent();
                if (parent != null
                        && BuildLayout.TARGET.equals(String.valueOf(p.getFileName()))
                        && Files.isRegularFile(ManifestPaths.manifestIn(parent))) {
                    if (!parent.equals(self)) producers.add(parent);
                    break;
                }
            }
        }
        in.siblings().awaitPublished(producers, ctx::cancelled);
        return workerJars;
    }

    /**
     * The test compile's side of the wait, then the check that what it compiles against is on disk.
     * Its classpath is the siblings' classes trees — the build-order ones the schedule admitted this
     * module on, and those only its test scopes name, which it waits for here — plus the test
     * classes and fixtures a {@code kind = "tests"} edge or {@code fixtures = true} selects, which it
     * waits for each sibling to have compiled. It never waits for a sibling to package or test.
     *
     * <p>Under {@code --skip-tests} a test-only sibling may be outside the build's cone, so its
     * absence is the cone working and nothing is required.
     */
    static void awaitSiblingTestCompile(TaskContext ctx, BuildPlanner.Inputs in) throws Exception {
        in.siblings().awaitTestCompile(ctx::cancelled);
        if (ctx.cancelled() || PlannerResources.skipJUnit(in)) return;
        JkBuild project = ctx.require(MODULE).project();
        WorkspaceClasspath.Result testSiblings =
                WorkspaceClasspath.resolve(in.dir(), project, WorkspaceClasspath.TEST_SCOPES);
        requireSiblingsCompiled(ctx, testSiblings, "test dependency module not compiled: ");
        WorkspaceClasspath.Result testProcessorSiblings =
                WorkspaceClasspath.resolve(in.dir(), project, ClasspathResolver.PROCESSOR_PATH);
        requireSiblingsCompiled(ctx, testProcessorSiblings, "test processor module not compiled: ");
        if (testSiblings.missingSiblingTestOutputs().isEmpty()) return;
        for (String missing : testSiblings.missingSiblingTestOutputs()) {
            ctx.error("workspace", missingSiblingMessage("test dependency module not built: ", missing, in.siblings()));
        }
        throw new RuntimeException("missing workspace siblings");
    }

    /** The runtime-view guard: {@code missingSiblingJars} names each jar, test output or fixtures dir absent. */
    private static void requireSiblingsBuilt(
            TaskContext ctx, WorkspaceClasspath.Result siblings, String prefix, SiblingArtifacts.Gate gate) {
        if (siblings.missingSiblingJars().isEmpty()) return;
        for (String missing : siblings.missingSiblingJars()) {
            ctx.error("workspace", missingSiblingMessage(prefix, missing, gate));
        }
        throw new RuntimeException("missing workspace siblings");
    }

    /**
     * The line for one absent sibling output. Every label leads with the sibling's coordinate; a
     * sibling whose plan failed this build is named with the step it failed at — the cause —
     * rather than as "not built", which is kept for a sibling this build did not run or that
     * finished without producing the output.
     */
    static String missingSiblingMessage(String prefix, String missing, SiblingArtifacts.Gate gate) {
        int space = missing.indexOf(' ');
        String coord = space < 0 ? missing : missing.substring(0, space);
        Optional<String> step = gate.failedStep(coord);
        if (step.isEmpty()) return prefix + missing;
        String subject = prefix.startsWith("test ") ? "test dependency module " : "dependency module ";
        return subject + coord + " failed at " + step.get() + ": "
                + missing.substring(coord.length()).trim();
    }
}
