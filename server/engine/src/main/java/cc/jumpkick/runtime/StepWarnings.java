// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.CompileWarnings;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The warnings one compile step's compilers said, kept in the module's {@link
 * CompileWarnings#currentFile warnings file} for the step when it ends: what a build that finds
 * the step up to date, or does not run the module at all, says again.
 */
public final class StepWarnings {

    private final List<CompileWarnings.Kept> kept = new ArrayList<>();

    /** {@code warnings}, said by {@code tool}, join the step's. */
    synchronized void add(String tool, List<CompileResult.Diagnostic> warnings) {
        for (CompileResult.Diagnostic d : warnings) kept.add(new CompileWarnings.Kept(tool, d));
    }

    /** Replace {@code step}'s file with every warning added. */
    synchronized void write(TaskContext ctx, String step) {
        CompileWarnings.writeCurrent(file(ctx, step), List.copyOf(kept));
    }

    /** Replace {@code step}'s file with {@code warnings}, every one said by {@code tool}. */
    static void keep(TaskContext ctx, String step, String tool, List<CompileResult.Diagnostic> warnings) {
        CompileWarnings.writeCurrent(file(ctx, step), tool, warnings);
    }

    /** {@code step}'s file for a run that compiled nothing: it says nothing. */
    static void clear(TaskContext ctx, String step) {
        CompileWarnings.writeCurrent(file(ctx, step), List.of());
    }

    /**
     * The warnings {@code step}'s file keeps, resolved against {@code sources}; empty when it has
     * none, and on a {@code rerun}, which compiles whatever is kept.
     */
    static Optional<List<CompileResult.Diagnostic>> read(
            TaskContext ctx, String step, List<Path> sources, boolean rerun) {
        if (rerun) return Optional.empty();
        return CompileWarnings.readCurrent(file(ctx, step), sources).map(CompileWarnings::warningsOf);
    }

    /** Say the warnings of {@code tool}'s compile {@code r} that succeeded, and keep them for {@code step}. */
    static void sayAndKeep(TaskContext ctx, String step, String tool, LangCompile.Result r) {
        JavacDiagnostics.reportWarnings(ctx, tool, r.freshWarnings(), r.replayed());
        keep(ctx, step, tool, r.warnings());
    }

    /**
     * Whether the module at {@code layout} keeps a warnings file for {@code step}: a step found up to
     * date says that file, so without one it is not up to date. The forecast asks it as the build does.
     */
    static boolean kept(BuildLayout layout, String step) {
        return kept(layout.moduleRoot(), step);
    }

    /** As {@link #kept(BuildLayout, String)} for the module at {@code moduleDir} and the warnings {@code file}. */
    static boolean kept(Path moduleDir, String file) {
        Path incremental = BuildLayout.moduleTargetDir(moduleDir).resolve("incremental");
        return Files.isRegularFile(CompileWarnings.currentFile(incremental, file));
    }

    /**
     * Say again, through {@code listener}, the warnings the module at {@code dir} ({@code group:name}
     * {@code module}) kept for each compile step, for a build that does not run it because it is up
     * to date: what a build of the module would show. The test steps only when {@code tests}, the
     * build compiling tests.
     */
    public static void replayModule(Path dir, String module, boolean tests, WorkspaceBuildListener listener) {
        Path incremental = BuildLayout.moduleTargetDir(dir).resolve("incremental");
        for (String name : CompileWarnings.FILES) {
            String step = CompileWarnings.stepOf(name);
            if (!tests && CompileWarnings.TEST_STEPS.contains(step)) continue;
            Path file = CompileWarnings.currentFile(incremental, name);
            for (CompileWarnings.Kept k : CompileWarnings.readCurrentUnder(file, dir)) {
                listener.onModuleWarning(
                        dir, module, step, k.tool(), k.warning().describe());
            }
        }
    }

    /**
     * Forget the kept warnings of every compile step the module at {@code dir} no longer runs, after
     * its plan of {@code steps} succeeded: a language the module dropped says nothing again. A test
     * step's only when the plan compiled tests, since a build that skips them leaves their classes.
     */
    public static void forgetStepsNotIn(Path dir, Collection<String> steps) {
        boolean tests = steps.contains(TaskNames.COMPILE_TEST);
        Path incremental = BuildLayout.moduleTargetDir(dir).resolve("incremental");
        for (String name : CompileWarnings.FILES) {
            String step = CompileWarnings.stepOf(name);
            if (steps.contains(step) || (!tests && CompileWarnings.TEST_STEPS.contains(step))) continue;
            try {
                Files.deleteIfExists(CompileWarnings.currentFile(incremental, name));
            } catch (IOException e) {
                Log.debug("kept warnings of " + name + " not forgotten under " + dir, e);
            }
        }
    }

    private static Path file(TaskContext ctx, String step) {
        return CompileWarnings.currentFile(PlannerCompile.incremental(ctx), step);
    }
}
