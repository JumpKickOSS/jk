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
import cc.jumpkick.task.WorkerClosure;
import cc.jumpkick.test.SuiteReads;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What reaches a suite besides its declared inputs, as run-tests stamp tokens: the locale every
 * test JVM inherits, jk's test runner by content, and the inputs its runs were found to read
 * ({@link SuiteReads}). What the manifest hands it of the environment is {@link ModuleEnvKeys}'s.
 */
final class SuiteEnvKeys {

    private SuiteEnvKeys() {}

    static List<String> tokens(BuildBlock build, Path moduleDir, TestSelection selection) {
        List<String> out = new ArrayList<>();
        // The locale every test JVM is seeded with (TestEnv), the caller's over the engine's. Its
        // TERM is fixed, so it keys nothing.
        BuildEnv.locale().forEach((name, value) -> out.add("machine-env:" + name + "=" + value));
        // The runner that launches the suite and reads its outcome is part of every run.
        try {
            for (String token : WorkerClosure.tokens(PluginJar.TEST_RUNNER)) out.add("runner:" + token);
        } catch (IOException | IllegalStateException unlocated) {
            out.add("runner:unlocated");
        }
        Path root = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        out.addAll(SuiteReads.tokens(moduleDir, selection, root));
        return out;
    }

    /** The active profile's {@code env} as stamp extras, sorted: a profile's environment retests the suite. */
    static List<String> profileEnv(JkBuild project, @Nullable String profileName) {
        Profile profile = CompileSupport.resolveProfile(project.profiles(), profileName);
        if (profile == null || profile.env().isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        new TreeMap<>(profile.env()).forEach((k, v) -> out.add("profile-env:" + k + "=" + v));
        return out;
    }
}
