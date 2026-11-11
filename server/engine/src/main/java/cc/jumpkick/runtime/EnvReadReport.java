// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.EnvReads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Tells a run which environment variables its suite reads that the module never declares, and the
 * {@code [env]} line that would declare each. A test JVM gets only what the module declares, so
 * such a read sees nothing: the token a test needs, the switch a test checks.
 *
 * <p>Diagnostics only, never part of a key. Two signals. What each test JVM looked up as it ran
 * (the test runner's probe, {@code EnvProbeJar}) names the variable and the test class exactly: a
 * passing suite reports what its test classes and JUnit's conditions looked up, a failing one what
 * anything looked up. The bytecode ({@link EnvReads}) stands in when no JVM was probed, and adds
 * to a failing suite: the test and main classes' and the dependencies' constant reads, and the
 * classes that read a computed name or the whole map.
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
        Path records = recordDir(in.dir());
        boolean probed = probed(records);
        List<Lookup> lookups = lookups(records);
        PathUtil.deleteRecursively(records); // the next run records afresh
        if (testEnv.inherit()) return; // every variable reaches the suite
        boolean failed = result.failed() > 0 || !result.failures().isEmpty();
        String manifest = manifest(in.session().workingDir(), in.dir());
        Path mainClasses = ctx.require(BuildPlanner.LAYOUT).classesDir();
        Map<String, String> looked = lookedUp(lookups, declared(project), testEnv, compiledInto(testClasses), failed);
        // What the suite looked up as it ran is the exact answer; the bytecode stands in without it.
        Findings f = probed && !failed
                ? new Findings(Map.of(), Set.of())
                : findings(project, testEnv, testClasses, mainClasses, runtimeCp, in.cache(), failed);
        int told = 0;
        for (Map.Entry<String, String> e : looked.entrySet()) {
            if (told++ < MAX_TOLD) ctx.keyedWarn(UNDECLARED, e.getKey(), lookedUp(e.getValue(), e.getKey(), manifest));
        }
        for (Map.Entry<String, String> e : f.undeclared().entrySet()) {
            if (looked.containsKey(e.getKey())) continue;
            if (told++ < MAX_TOLD)
                ctx.keyedWarn(UNDECLARED, e.getKey(), undeclared(e.getValue(), e.getKey(), manifest));
        }
        if (told > MAX_TOLD) {
            ctx.warn(
                    UNDECLARED,
                    (told - MAX_TOLD) + " more undeclared variables read; jk results lists the first " + MAX_TOLD);
        }
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

    /** How many undeclared variables one run names before it counts the rest. */
    static final int MAX_TOLD = 8;

    /** Where the suite's JVMs write the variables they looked up ({@code EnvProbeJar}); emptied each run. */
    public static Path recordDir(Path moduleDir) {
        return BuildLayout.moduleTargetDir(moduleDir).resolve(".jk").resolve("env-lookups");
    }

    /** One variable a test JVM looked up: the running test class, the class that asked, whether it was set. */
    record Lookup(String test, String caller, String name, boolean set) {}

    /** Every lookup recorded under {@code dir}, one file per JVM; empty when the probe wrote none. */
    static List<Lookup> lookups(Path dir) {
        List<Lookup> out = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                for (String line : Files.readAllLines(file)) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 4) out.add(new Lookup(f[0], f[1], f[2], "set".equals(f[3])));
                }
            });
        } catch (IOException | RuntimeException e) {
            return out; // a record the probe could not finish is a diagnostic not given
        }
        return out;
    }

    /**
     * Each variable the suite looked up by name that does not reach it, with the class that looked
     * it up: the test class running, else the class that asked. A lookup a test class made itself,
     * or JUnit made for its condition, always counts; one the code under test or a library made
     * counts when the suite {@code failed}, since a passing suite did without it. A whole-map read
     * names nothing.
     */
    static Map<String, String> lookedUp(
            List<Lookup> lookups,
            Set<String> declared,
            WorkerEnv testEnv,
            Predicate<String> testClass,
            boolean failed) {
        Map<String, String> out = new TreeMap<>();
        for (Lookup l : lookups) {
            if (l.name().equals(WHOLE_MAP) || reaches(l.name(), declared, testEnv)) continue;
            if (!failed && !testClass.test(l.caller()) && !l.caller().startsWith(JUNIT)) continue;
            out.putIfAbsent(l.name(), l.test().isEmpty() ? l.caller() : l.test());
        }
        return out;
    }

    /** Whether {@code binaryName} was compiled into {@code testClasses}. */
    static Predicate<String> compiledInto(Path testClasses) {
        return binaryName -> !binaryName.isEmpty()
                && Files.isRegularFile(testClasses.resolve(binaryName.replace('.', '/') + ".class"));
    }

    /** JUnit's own classes: they look a variable up for a test's {@code @EnabledIfEnvironmentVariable}. */
    private static final String JUNIT = "org.junit.";

    /** Whether a probed JVM wrote under {@code dir}: every one writes a file, empty when it looked nothing up. */
    static boolean probed(Path dir) {
        try {
            return PathUtil.anyRegularFile(dir, d -> false, file -> true);
        } catch (IOException e) {
            return false;
        }
    }

    /** The name the probe records a whole-map read under. */
    private static final String WHOLE_MAP = "*";

    /** The line a looked-up, undeclared variable is reported as: the test saw nothing, so it is required. */
    static String lookedUp(String owner, String name, String manifest) {
        return simple(owner) + " read " + name + ", which " + manifest + " does not declare; it saw no value."
                + " Add to [env] require: \"" + name + "\"";
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
