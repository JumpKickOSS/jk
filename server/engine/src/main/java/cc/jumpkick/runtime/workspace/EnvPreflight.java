// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The check that a module's {@code [env] require} names are set before anything of it compiles.
 *
 * <p>A required variable is one the module's suite or app cannot do without. Left unset it would
 * surface minutes later as a test failing on a null, or a suite that skips itself green; so a
 * goal that runs the module's tests, or launches it, fails the module at once instead, naming
 * the variable and the manifest line that requires it. A goal that runs neither does not check:
 * a {@code jk compile} or a {@code jk build -S} has no use for the value.
 */
@NullMarked
public final class EnvPreflight {

    /** The diagnostic code every missing variable is reported under; its key is the variable's name. */
    public static final String CODE = "env-required";

    /** One required name the request's environment does not set, and the manifest line declaring it. */
    public record Missing(String name, int line) {}

    private EnvPreflight() {}

    /** Whether {@code target} runs the module at {@code dir}'s tests or launches it. */
    static boolean applies(WorkspaceTarget target, boolean skipTests, boolean selectedForRun) {
        if (target == WorkspaceTarget.RUN) return selectedForRun;
        return switch (target.tests()) {
            case ALWAYS -> true;
            case UNLESS_SKIPPED -> !skipTests;
            case NOT_RUN, COMPILED -> false;
        };
    }

    /**
     * The required names {@code env} reads as unset, in declaration order. {@code env} is the
     * module's build environment: the shell running {@code jk}, then its {@code .env} files.
     */
    public static List<Missing> missing(EnvConfig config, Function<String, @Nullable String> env) {
        List<Missing> out = new ArrayList<>();
        for (String name : config.require()) {
            if (env.apply(name) == null) out.add(new Missing(name, config.line(name)));
        }
        return List.copyOf(out);
    }

    /** {@link #missing(EnvConfig, Function)} through the build environment for {@code moduleDir}. */
    public static List<Missing> missing(EnvConfig config, Path moduleDir) {
        if (config.require().isEmpty()) return List.of();
        return missing(config, BuildEnv.forModule(moduleDir));
    }

    /**
     * The one line a missing variable is reported as, on every surface: the terminal, a CI log, the
     * results file and an MCP answer. {@code manifest} is the module's {@code jk.toml} as the user
     * reads it, relative to where the build was entered.
     */
    public static String message(Missing m, String manifest) {
        String at = m.line() > 0 ? manifest + ":" + m.line() : manifest;
        return m.name() + " is not set; " + at + " requires it";
    }

    /**
     * The plan a module gets in place of its build when {@code missing} is not empty: one step that
     * reports every missing variable and fails, so the module fails before it compiles and a module
     * that depends on it is stopped the way any failed module stops it.
     */
    static BuildPlan failing(List<Missing> missing, String manifest) {
        Task check = Task.builder(TaskNames.ENV_CHECK)
                .forecast(StepForecast.always("a required environment variable is not set"))
                .stage(BuildStage.RESOLVE)
                .ticks(1)
                .execute(ctx -> {
                    for (Missing m : missing) ctx.keyedError(CODE, m.name(), message(m, manifest));
                    throw new IllegalStateException(
                            missing.size() == 1
                                    ? message(missing.getFirst(), manifest)
                                    : missing.size() + " required environment variables are not set");
                })
                .build();
        return BuildPlan.builder(TaskNames.ENV_CHECK).addTask(check).build();
    }
}
