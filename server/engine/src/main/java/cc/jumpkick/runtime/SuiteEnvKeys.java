// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.EnvKeyText;
import cc.jumpkick.task.ModuleEnvKeys;
import cc.jumpkick.task.WorkerClosure;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReads;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What reaches a suite besides its declared inputs, as run-tests stamp tokens: the locale every
 * test JVM runs in, jk's test runner by content, and the inputs its runs were found to read
 * ({@link SuiteReads}), the caller's variables among them ({@link SuiteEnvReads}). What the manifest
 * hands it of the environment is {@link ModuleEnvKeys}'s.
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
}
