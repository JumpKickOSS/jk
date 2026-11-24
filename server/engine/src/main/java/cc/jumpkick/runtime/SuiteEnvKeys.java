// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.SelfHostEngine.needsNestedEngineIsolation;
import static cc.jumpkick.runtime.SelfHostEngine.nestedEngineTestEnv;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.EnvKeyText;
import cc.jumpkick.task.ModuleEnvKeys;
import cc.jumpkick.task.WorkerClosure;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.test.TestWorkerEnv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What reaches a suite besides its declared inputs, as run-tests stamp tokens: the locale every
 * test JVM runs in, jk's test runner by content, and the inputs its runs were found to read
 * ({@link SuiteReads}), the caller's variables among them ({@link SuiteEnvReads}), and what jk sets in
 * its environment on every host ({@link #environment}). What the manifest hands it of the
 * environment is {@link ModuleEnvKeys}'s.
 */
final class SuiteEnvKeys {

    private SuiteEnvKeys() {}

    static List<String> tokens(BuildBlock build, Path moduleDir, TestSelection selection) {
        return tokens(build, moduleDir, selection, SuiteEnvReads.Policy.NONE);
    }

    /**
     * The tokens, with what the suites read of the caller's environment keyed under the module's
     * {@code policy} ({@link SuiteEnvReads}).
     */
    static List<String> tokens(BuildBlock build, Path moduleDir, TestSelection selection, SuiteEnvReads.Policy policy) {
        List<String> out = new ArrayList<>();
        // The locale the test JVM runs in (TestEnv.localeEnv): the module's fixed tag, which also
        // rides its -Duser.* flags, or under "inherit" the caller's variables, over the engine's.
        // A fixed locale keys no variable of the shell, so en_US, C.UTF-8 and de_DE hosts share the
        // suite. Its TERM is fixed, so it keys nothing.
        if (build.testJvm().inheritsLocale())
            BuildEnv.locale().forEach((name, value) -> out.add("machine-env:" + name + "=" + value));
        else out.add("test-locale:" + build.testJvm().locale());
        // The zone it runs in: the module's fixed one, or under "inherit" the caller's TZ, else the
        // machine's zone the JVM then reads.
        out.add("test-timezone:"
                + (build.testJvm().inheritsTimezone()
                        ? "inherit="
                                + Objects.requireNonNullElseGet(BuildEnv.timeZone(), () -> ZoneId.systemDefault()
                                        .getId())
                        : build.testJvm().timezone()));
        // The runner that launches the suite and reads its outcome is part of every run.
        try {
            for (String token : WorkerClosure.tokens(PluginJar.TEST_RUNNER)) out.add("runner:" + token);
        } catch (IOException | IllegalStateException unlocated) {
            out.add("runner:unlocated");
        }
        Path root = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        // How much of the caller's environment the test JVMs may read: turning strict on or off, or
        // narrowing the scope, changes what a test sees, so it retests the suite.
        if (policy.strict()) out.add("env-policy:strict");
        if (!policy.scope().isEmpty()) out.add("env-policy:scope=" + String.join(",", policy.scope()));
        out.addAll(SuiteReads.tokens(moduleDir, selection, root, policy, build.testTools()));
        return out;
    }

    /** The active profile's {@code env} as stamp extras, sorted: a profile's environment retests the suite. */
    static List<String> profileEnv(JkBuild project, @Nullable String profileName) {
        Profile profile = CompileSupport.resolveProfile(project.profiles(), profileName);
        if (profile == null || profile.env().isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        // By digest of a constant: a profile's env is written literally in the manifest, never read
        // from the caller's shell, and a record names no value a test JVM is handed.
        new TreeMap<>(profile.env()).forEach((k, v) -> out.add("profile-env:" + k + "=" + EnvKeyText.literal(v)));
        return out;
    }

    /** The stand-in for the module's sandbox home: each host's own directory, so out of the token. */
    static final Path HOME = Path.of("@home");

    /** The stand-in for the sandbox's temp root. */
    static final Path TMP = Path.of("@tmp");

    /** The stand-in for the user's home directory. */
    static final Path USER_HOME = Path.of("@user-home");

    /** The stand-in for a pooled worker's temp root. */
    static final Path WORKER_TMP = Path.of("@worker-tmp");

    /** The stand-in for one run's directory under the nested engines' state. */
    static final String RUN = "@run";

    /**
     * The stamp token of what {@code project}'s suite JVMs get from jk on every host ({@link
     * SuiteEnvironment}), built by the functions the launch builds their environment with: the names and constant values of the
     * sandbox ({@link TestEnv#constants}), the nested-engine isolation, what a pooled worker's
     * environment changes ({@link TestWorkerEnv#overrides}) and which trees warm the sandbox store
     * ({@link TestEnv#seeds}). Each host's directories are stand-ins, so the token holds no path and
     * two hosts key alike; the caller's and the module's declared variables are keyed apart.
     */
    static String environment(JkBuild project) {
        Map<String, String> env =
                new LinkedHashMap<>(TestEnv.constants(project.build().testJvm(), HOME, TMP, USER_HOME));
        if (needsNestedEngineIsolation(project)) {
            env.putAll(nestedEngineTestEnv(HOME, HOME.resolve("engine-state").resolve(RUN)));
        }
        List<String> parts = new ArrayList<>();
        new TreeMap<>(env).forEach((name, value) -> parts.add(name + "=" + portable(value)));
        new TreeMap<>(TestWorkerEnv.overrides(WorkerEnv.strict().with(env), WORKER_TMP, true))
                .forEach((name, value) -> parts.add("worker:" + name + "=" + portable(value)));
        parts.add("seed:" + TestEnv.seeds());
        return "suite-env:" + String.join(" ", parts);
    }

    /** {@code value} with this host's file and path separators spelled as on Unix. */
    private static String portable(String value) {
        return value.replace(File.separatorChar, '/').replace(SearchPath.SEPARATOR, ":");
    }
}
