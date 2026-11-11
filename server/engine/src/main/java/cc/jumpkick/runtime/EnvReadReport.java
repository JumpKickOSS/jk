// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Os;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.EnvReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tells a run which environment variables its suite reads that the module never declares, and the
 * {@code [env]} line that would declare each. A test JVM gets only what the module declares, so
 * such a read sees nothing: the token a test needs, the switch a test checks.
 *
 * <p>Diagnostics only, never part of a key. The test classes' constant reads ({@link EnvReads})
 * are reported on every run that launched the suite. A failed suite reports more, since one
 * of them may be why: the main classes' and the dependencies' constant reads, and the classes that
 * read a computed name or the whole map.
 */
public final class EnvReadReport {

    /** A read of a variable the module does not declare; the key is the variable's name. */
    static final String UNDECLARED = "env-undeclared";

    /** A class that reads a name no check can see, on a failed suite. */
    static final String COMPUTED = "env-computed";

    private EnvReadReport() {}

    /** What one run found: each undeclared name with the first class reading it, and the opaque readers. */
    record Findings(Map<String, String> undeclared, Set<String> opaque) {}

    /**
     * Reports what {@code testClasses}' suite reads that {@code project} does not declare; when
     * {@code result} failed, what the module's main classes and the jars of {@code runtimeCp} read
     * too.
     */
    static void report(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild project,
            WorkerEnv testEnv,
            Path testClasses,
            List<Path> runtimeCp,
            TestSummary result) {
        if (testEnv.inherit()) return; // every variable reaches the suite
        boolean failed = result.failed() > 0 || !result.failures().isEmpty();
        String manifest = manifest(in.session().workingDir(), in.dir());
        Path mainClasses = ctx.require(BuildPlanner.LAYOUT).classesDir();
        Findings f = findings(project, testEnv, testClasses, mainClasses, runtimeCp, in.cache(), failed);
        f.undeclared().forEach((name, owner) -> ctx.keyedWarn(UNDECLARED, name, undeclared(owner, name, manifest)));
        if (!f.opaque().isEmpty()) {
            ctx.warn(
                    COMPUTED,
                    "the suite failed and " + simple(f.opaque().iterator().next())
                            + (f.opaque().size() > 1 ? " and " + (f.opaque().size() - 1) + " more" : "")
                            + " read the environment by a name jk cannot see");
        }
    }

    static Findings findings(
            JkBuild project,
            WorkerEnv testEnv,
            Path testClasses,
            Path mainClasses,
            List<Path> runtimeCp,
            Path cacheDir,
            boolean failed) {
        Set<String> declared = declared(project);
        Map<String, String> undeclared = new TreeMap<>();
        Set<String> opaque = new LinkedHashSet<>();
        collect(EnvReads.ofDirectory(testClasses), declared, testEnv, undeclared, opaque, failed);
        if (failed) {
            collect(EnvReads.ofDirectory(mainClasses), declared, testEnv, undeclared, opaque, true);
            Path cache = CacheTree.ENV_READS.under(cacheDir);
            for (Path entry : runtimeCp) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                    collect(EnvReads.ofJar(entry, cache), declared, testEnv, undeclared, new LinkedHashSet<>(), false);
                }
            }
        }
        return new Findings(new LinkedHashMap<>(undeclared), opaque);
    }

    private static void collect(
            List<EnvReads.Site> sites,
            Set<String> declared,
            WorkerEnv testEnv,
            Map<String, String> undeclared,
            Set<String> opaque,
            boolean keepOpaque) {
        for (EnvReads.Site s : sites) {
            switch (s.kind()) {
                case NAMED -> {
                    if (!reaches(s.name(), declared, testEnv)) undeclared.putIfAbsent(s.name(), s.owner());
                }
                case COMPUTED, WHOLE_MAP -> {
                    if (keepOpaque) opaque.add(s.owner());
                }
            }
        }
    }

    /** Whether {@code name} reaches the test JVM or is the module's to give: declared, set by jk, or allow-listed. */
    static boolean reaches(String name, Set<String> declared, WorkerEnv testEnv) {
        return declared.contains(name) || testEnv.extras().containsKey(name) || WorkerEnv.allowed(name, Os.isWindows());
    }

    /** Every name the module's manifest gives its test JVM, set or not. */
    static Set<String> declared(JkBuild project) {
        Set<String> out = new LinkedHashSet<>(project.build().env().declared());
        for (EnvDecl d : project.build().testEnvDecls()) out.add(d.name());
        project.profiles().byName().values().forEach(p -> out.addAll(p.env().keySet()));
        return out;
    }

    /** The one line an undeclared read is reported as, ending with the exact declaration. */
    static String undeclared(String owner, String name, String manifest) {
        return simple(owner) + " reads " + name + ", which " + manifest + " does not declare;"
                + " add to [env] optional: \"" + name + "\"";
    }

    /** The manifest of {@code moduleDir} spelled from {@code entryDir}, where the build was entered. */
    public static String manifest(Path entryDir, Path moduleDir) {
        Path file = ManifestPaths.manifestIn(moduleDir);
        try {
            String spelled = entryDir.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            return spelled.startsWith("..") ? file.toString() : spelled;
        } catch (IllegalArgumentException otherRoot) {
            return file.toString();
        }
    }

    private static String simple(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? binaryName : binaryName.substring(dot + 1);
    }
}
