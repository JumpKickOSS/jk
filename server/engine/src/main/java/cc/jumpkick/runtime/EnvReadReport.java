// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
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
 *
 * <p>A green run's undeclared variables ride its marker ({@link #report}'s lines), and a replay of
 * that run says them again ({@link #replay}) while the module still does not give them.
 */
public final class EnvReadReport {

    /** A read of a variable the module does not declare; the key is the variable's name. */
    public static final String UNDECLARED = "env-undeclared";

    /** A class that reads a name no check can see, on a failed suite. */
    public static final String COMPUTED = "env-computed";

    private EnvReadReport() {}

    /** What one run found: each undeclared name with the first class reading it, and the opaque readers. */
    record Findings(Map<String, String> undeclared, Set<String> opaque) {}

    /**
     * One undeclared variable a run names: the class that read it, and whether a test JVM looked
     * it up as it ran ({@code ran}) or only the bytecode reads it.
     */
    record Told(String name, String owner, boolean ran) {}

    /**
     * Reports what {@code testClasses}' suite reads that {@code project} does not declare; when
     * {@code result} failed, what the module's main classes and the jars of {@code runtimeCp} read
     * too. Returns the undeclared variables as lines for the run's marker ({@link #replay}).
     */
    static List<String> report(
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
        if (testEnv.inherit()) return List.of(); // every variable reaches the suite
        boolean failed = result.failed() > 0 || !result.failures().isEmpty();
        Path mainClasses = ctx.require(BuildPlanner.MODULE).layout().classesDir();
        Map<String, String> looked = lookedUp(lookups, declared(project), testEnv, compiledInto(testClasses), failed);
        // What the suite looked up as it ran is the exact answer; the bytecode stands in without it.
        Findings f = probed && !failed
                ? new Findings(Map.of(), Set.of())
                : findings(project, testEnv, testClasses, mainClasses, runtimeCp, in.cache(), failed);
        List<Told> told = new ArrayList<>();
        looked.forEach((name, owner) -> told.add(new Told(name, owner, true)));
        f.undeclared().forEach((name, owner) -> {
            if (!looked.containsKey(name)) told.add(new Told(name, owner, false));
        });
        tell(ctx, told, manifest(in.session().workingDir(), in.dir()), false);
        if (!f.opaque().isEmpty()) {
            ctx.warn(
                    COMPUTED,
                    "the suite failed and " + simple(f.opaque().iterator().next())
                            + (f.opaque().size() > 1 ? " and " + (f.opaque().size() - 1) + " more" : "")
                            + " read the environment by a name jk cannot see");
        }
        return said(told);
    }

    /**
     * Says again, as replayed, the undeclared variables a green run's marker kept ({@code said}):
     * each one {@code project} still does not give its suite.
     */
    static void replay(TaskContext ctx, BuildPlanner.Inputs in, JkBuild project, WorkerEnv testEnv, List<String> said) {
        if (testEnv.inherit()) return;
        Set<String> declared = declared(project);
        List<Told> told = new ArrayList<>();
        for (Told t : fromSaid(said)) {
            if (!reaches(t.name(), declared, testEnv)) told.add(t);
        }
        if (!told.isEmpty()) tell(ctx, told, manifest(in.session().workingDir(), in.dir()), true);
    }

    /** One warning per variable in {@code told}, the first {@link #MAX_TOLD}, then the count of the rest. */
    private static void tell(TaskContext ctx, List<Told> told, String manifest, boolean replayed) {
        for (Told t : told.subList(0, Math.min(MAX_TOLD, told.size()))) {
            String message =
                    t.ran() ? lookedUp(t.owner(), t.name(), manifest) : undeclared(t.owner(), t.name(), manifest);
            if (replayed) ctx.replayedWarn(UNDECLARED, t.name(), message);
            else ctx.keyedWarn(UNDECLARED, t.name(), message);
        }
        if (told.size() > MAX_TOLD) {
            String more = (told.size() - MAX_TOLD) + MORE + MAX_TOLD;
            if (replayed) ctx.replayedWarn(UNDECLARED, "", more);
            else ctx.warn(UNDECLARED, more);
        }
    }

    /** {@code told} as a marker's lines, one JSON object each. */
    static List<String> said(List<Told> told) {
        List<String> out = new ArrayList<>(told.size());
        for (Told t : told) {
            out.add(JsonFields.object()
                    .string("code", UNDECLARED)
                    .string("name", t.name())
                    .string("owner", t.owner())
                    .bool("ran", t.ran())
                    .finish());
        }
        return out;
    }

    /** The undeclared variables {@link #said} wrote; a line of any other shape is not one. */
    static List<Told> fromSaid(List<String> said) {
        List<Told> out = new ArrayList<>(said.size());
        for (String line : said) {
            if (!UNDECLARED.equals(Jsonl.str(line, "code"))) continue;
            String name = Jsonl.str(line, "name");
            String owner = Jsonl.str(line, "owner");
            if (name == null || name.isBlank() || owner == null) continue;
            out.add(new Told(name, owner, Jsonl.bool(line, "ran", false)));
        }
        return out;
    }

    /**
     * An {@link #UNDECLARED} warning's message as what was read and the {@code [env]} line that
     * declares it. {@code variables} is how many it names: one, or the count past the cap, whose
     * {@code fix} is empty.
     */
    public record Said(String finding, String fix, int variables) {}

    /** {@code message}, an {@link #UNDECLARED} warning's, split as {@link Said}. */
    public static Said split(String message) {
        int more = message.indexOf(MORE);
        if (more > 0) {
            try {
                return new Said(message, "", Integer.parseInt(message.substring(0, more)));
            } catch (NumberFormatException notACount) {
                return new Said(message, "", 1);
            }
        }
        int fix = message.lastIndexOf(FIX);
        if (fix < 0) return new Said(message, "", 1);
        return new Said(message.substring(0, fix), "add" + message.substring(fix + ". Add".length()), 1);
    }

    /** Between what an undeclared warning found and the declaration that fixes it. */
    private static final String FIX = ". Add to [env] ";

    /** After the count of the variables past the cap. */
    private static final String MORE = " more undeclared variables read; jk results lists the first ";

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
        return simple(owner) + " read " + name + ", which " + manifest + " does not declare; it saw no value" + FIX
                + "require: \"" + name + "\"";
    }

    /** The one line an undeclared read is reported as, ending with the exact declaration. */
    static String undeclared(String owner, String name, String manifest) {
        return simple(owner) + " reads " + name + ", which " + manifest + " does not declare" + FIX + "optional: \""
                + name + "\"";
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
