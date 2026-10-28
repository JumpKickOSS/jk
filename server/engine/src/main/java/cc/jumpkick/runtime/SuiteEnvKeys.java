// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ProcessEnv;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What reaches a suite besides its declared inputs, as run-tests stamp tokens: the locale and
 * terminal variables every test JVM inherits, the whole inherited environment of a module that
 * says {@code [env] inherit}, jk's test runner by content, and the inputs its runs were found to
 * read ({@link SuiteReads}).
 */
final class SuiteEnvKeys {

    private SuiteEnvKeys() {}

    /** The variables every suite inherits whose values change what a test sees. */
    private static boolean shapesOutput(String name) {
        return name.equals("LANG") || name.equals("LANGUAGE") || name.equals("TERM") || name.startsWith("LC_");
    }

    static List<String> tokens(BuildBlock build, Path moduleDir, TestSelection selection) {
        List<String> out = new ArrayList<>();
        Map<String, String> machine = BuildEnv.machine();
        Set<String> names = new TreeSet<>(machine.keySet());
        names.addAll(ProcessEnv.all().keySet());
        for (String name : names) {
            if (!shapesOutput(name)) continue;
            String value = machine.getOrDefault(name, ProcessEnv.get(name));
            if (value != null) out.add("machine-env:" + name + "=" + value);
        }
        if (build.env().inherit()) {
            // The whole environment reaches the suite: every value keys it, by digest.
            Map<String, String> inherited = new TreeMap<>(ProcessEnv.all());
            inherited.putAll(BuildEnv.fromRequest());
            out.add("inherited-env:" + Hashing.sha256Hex(inherited.toString()));
        }
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
