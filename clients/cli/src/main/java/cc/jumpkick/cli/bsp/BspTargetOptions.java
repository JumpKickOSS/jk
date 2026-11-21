// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The per-target answers a Scala-aware BSP client (Metals) asks for beside the build targets:
 * {@code buildTarget/scalacOptions} and {@code buildTarget/javacOptions} — the compiler
 * arguments jk's Zinc session passes, the compile classpath and the class directory —
 * {@code buildTarget/dependencySources}, the Scala main and test class listings, and the JVM
 * environments {@code buildTarget/jvmRunEnvironment} and {@code jvmTestEnvironment}. Every
 * answer is built from the engine's {@link IdeWireModel}; the target ids are the ones
 * {@code workspace/buildTargets} handed out.
 */
final class BspTargetOptions {

    /** The {@code dataKind} and organisation Metals reads a Scala target by. */
    static final String SCALA_DATA_KIND = "scala";

    static final String SCALA_ORGANIZATION = "org.scala-lang";

    private BspTargetOptions() {}

    /** The {@code ScalaBuildTarget} data of module {@code i}, or {@code null} for a module that compiles no Scala. */
    static @Nullable String scalaTargetData(IdeWireModel model, int i) {
        String version = model.scalaVersionOf(i);
        if (version == null) return null;
        List<String> jars = new ArrayList<>();
        for (Path jar : rowsOf(model.scalaJars(), i)) jars.add(uri(jar));
        JsonFields data = JsonFields.object()
                .string("scalaOrganization", SCALA_ORGANIZATION)
                .string("scalaVersion", version)
                .string("scalaBinaryVersion", binaryVersion(version))
                .number("platform", 1)
                .array("jars", jars);
        String jvm = jvmTargetData(model, i);
        if (jvm != null) data.token("jvmBuildTarget", jvm);
        return data.finish();
    }

    /** The {@code JvmBuildTarget} data of module {@code i}: its JDK home and version, when the model names one. */
    static @Nullable String jvmTargetData(IdeWireModel model, int i) {
        String home = i < model.sdkHomes().size() ? model.sdkHomes().get(i) : "";
        if (home == null || home.isBlank()) return null;
        String version = i < model.sdkVersions().size() ? model.sdkVersions().get(i) : "";
        return JsonFields.object()
                .string("javaHome", uri(Path.of(home)))
                .string("javaVersion", version == null ? "" : version)
                .finish();
    }

    /** {@code 3} for Scala 3, else the first two segments ({@code 2.13}). */
    static String binaryVersion(String version) {
        if (version.startsWith("3.") || version.equals("3")) return "3";
        int first = version.indexOf('.');
        int second = first < 0 ? -1 : version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    /**
     * {@code ScalacOptionsItem}: the arguments jk's Zinc session passes scalac for the module's
     * Java release, the compile classpath and the class directory.
     */
    static String scalacOptionsItem(String tid, IdeWireModel model, int i) {
        List<String> options = new ArrayList<>();
        String release = releaseOf(model, i);
        if (!release.isEmpty()) {
            options.add("-java-output-version");
            options.add(release);
        }
        return optionsItem(tid, model, i, options);
    }

    /** {@code JavacOptionsItem}: {@code --release} for the module's Java level, the classpath and the class directory. */
    static String javacOptionsItem(String tid, IdeWireModel model, int i) {
        List<String> options = new ArrayList<>();
        String release = releaseOf(model, i);
        if (!release.isEmpty()) {
            options.add("--release");
            options.add(release);
        }
        return optionsItem(tid, model, i, options);
    }

    private static String optionsItem(String tid, IdeWireModel model, int i, List<String> options) {
        return BspServer.itemFor(tid)
                .array("options", options)
                .array("classpath", classpath(model, i))
                .string("classDirectory", uri(Path.of(model.classesDirs().get(i))))
                .finish();
    }

    /** The names of the workspace modules module {@code i} depends on, in any scope, in the model's order. */
    static List<String> siblingNames(IdeWireModel model, int i) {
        List<String> out = new ArrayList<>();
        for (String row : model.siblingRefs()) {
            String[] parts = row.split("\\|", 3);
            if (parts.length < 2 || Integer.parseInt(parts[0]) != i) continue;
            if (model.names().contains(parts[1])) out.add(parts[1]);
        }
        return out;
    }

    /**
     * The module's compile classpath as URIs: its own library jars, the Scala library jars
     * of its compiler closure, and the main classes of the siblings it depends on.
     */
    static List<String> classpath(IdeWireModel model, int i) {
        Set<String> out = new LinkedHashSet<>();
        for (Path jar : rowsOf(model.scalaJars(), i)) {
            String name = jar.getFileName().toString();
            if (name.startsWith("scala-library") || name.startsWith("scala3-library_3")) out.add(uri(jar));
        }
        for (String row : model.siblingRefs()) {
            String[] parts = row.split("\\|", 3);
            if (parts.length < 2 || Integer.parseInt(parts[0]) != i) continue;
            int sibling = model.names().indexOf(parts[1]);
            if (sibling >= 0 && sibling < model.classesDirs().size()) {
                out.add(uri(Path.of(model.classesDirs().get(sibling))));
            }
        }
        for (int j : libraries(model, i)) out.add(uri(libraryJar(model, j)));
        return new ArrayList<>(out);
    }

    /** {@code DependencySourcesItem}: the sources jars the model holds for module {@code i}'s libraries. */
    static String dependencySourcesItem(String tid, IdeWireModel model, int i) {
        List<String> sources = new ArrayList<>();
        List<String> libSources = model.libSources() != null ? model.libSources() : List.of();
        for (int j : libraries(model, i)) {
            String src = j < libSources.size() ? libSources.get(j) : null;
            if (src != null && !src.isBlank()) sources.add(uri(Path.of(src)));
        }
        return BspServer.itemFor(tid).array("sources", sources).finish();
    }

    /**
     * The libraries module {@code i} declares, as indices into the model's parallel library lists,
     * in the model's order; every library with a jar when the model carries no per-module rows.
     */
    static List<Integer> libraries(IdeWireModel model, int i) {
        return libraries(model, i, true);
    }

    /** As {@link #libraries(IdeWireModel, int)}; {@code test} false leaves out a library only tests use. */
    static List<Integer> libraries(IdeWireModel model, int i, boolean test) {
        Set<String> declared = null;
        if (model.libEntries() != null && !model.libEntries().isEmpty() && model.libNames() != null) {
            declared = new HashSet<>();
            for (String row : model.libEntries()) {
                String[] parts = row.split("\\|", 3);
                if (parts.length < 2 || Integer.parseInt(parts[0]) != i) continue;
                if (test || parts.length < 3 || !testOnly(parts[2])) declared.add(parts[1]);
            }
        }
        List<String> jars = model.libJars() != null ? model.libJars() : List.of();
        List<String> names = model.libNames() != null ? model.libNames() : List.of();
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < jars.size(); j++) {
            String jar = jars.get(j);
            if (jar == null || jar.isBlank()) continue;
            if (declared != null && j < names.size() && !declared.contains(names.get(j))) continue;
            out.add(j);
        }
        return out;
    }

    /** The jar of library {@code j}; a {@code libJars} entry may carry a prefix up to its last {@code |}. */
    static Path libraryJar(IdeWireModel model, int j) {
        String jar = model.libJars().get(j);
        return Path.of(jar.contains("|") ? jar.substring(jar.lastIndexOf('|') + 1) : jar);
    }

    /** The scopes a library row lists that put it on the test classpath alone. */
    private static final Set<String> TEST_SCOPES = Set.of("TEST", "TEST_DEV", "TEST_PROCESSOR");

    /** Whether every scope of a library row's comma-joined {@code scopes} is a test one. */
    static boolean testOnly(String scopes) {
        for (String scope : scopes.split(",")) {
            if (!TEST_SCOPES.contains(scope.trim())) return false;
        }
        return true;
    }

    /**
     * One {@code JvmEnvironmentItem} per target in {@code requested}, every module's when it is
     * empty; a suite's target gets its module's test environment. {@code rootUri} is the project's
     * URI the target ids start with.
     */
    static List<String> jvmEnvironmentItems(
            List<String> requested, @Nullable IdeWireModel model, Path projectDir, String rootUri, boolean test) {
        List<String> items = new ArrayList<>();
        if (model == null) return items;
        for (int i = 0; i < moduleCount(model); i++) {
            String tid = rootUri + "#" + targetName(model, i);
            for (String asked : requested.isEmpty() ? List.of(tid) : requested) {
                String suite = BspServer.suiteOf(asked);
                boolean ofSuite = test
                        && suite != null
                        && tid.equals(asked.substring(
                                0, asked.length() - suite.length() - BspServer.SUITE_SEPARATOR.length()));
                if (asked.equals(tid) || ofSuite) {
                    items.add(jvmEnvironmentItem(asked, model, i, moduleDir(model, i, projectDir), test));
                }
            }
        }
        return items;
    }

    /** How many module targets the model has: one per module, or the single root target. */
    static int moduleCount(IdeWireModel model) {
        return model.moduleDirs() == null || model.moduleDirs().isEmpty()
                ? 1
                : model.moduleDirs().size();
    }

    /** The name after {@code #} in module {@code i}'s target id; {@code root} when the model names no modules. */
    static String targetName(IdeWireModel model, int i) {
        List<String> dirs = model.moduleDirs() != null ? model.moduleDirs() : List.of();
        if (dirs.isEmpty()) return "root";
        List<String> names = model.names() != null ? model.names() : List.of();
        return i < names.size()
                ? names.get(i)
                : Path.of(dirs.get(i)).getFileName().toString();
    }

    /** Module {@code i}'s directory; {@code projectDir} when the model names no modules. */
    static Path moduleDir(IdeWireModel model, int i, Path projectDir) {
        List<String> dirs = model.moduleDirs() != null ? model.moduleDirs() : List.of();
        return i < dirs.size() ? Path.of(dirs.get(i)) : projectDir;
    }

    /**
     * {@code JvmEnvironmentItem}: the classpath ({@link #runtimeClasspath}) and working directory
     * ({@code dir}, the module's) a JVM running module {@code i}'s main needs, or with {@code test}
     * its tests. A run also gets what {@code jk run} gives the app: the module's {@code [application]
     * jvm-args}, and the values of its {@code .env} files ({@link EnvLookup#dotEnv}) that this
     * server's own environment, the IDE's, does not set; and the main class when the module declares
     * one.
     */
    static String jvmEnvironmentItem(String tid, IdeWireModel model, int i, Path dir, boolean test) {
        JsonFields env = JsonFields.object();
        if (!test) {
            EnvLookup.dotEnv(dir).forEach((name, value) -> {
                if (ProcessEnv.get(name) == null) env.string(name, value);
            });
        }
        JsonFields item = BspServer.itemFor(tid)
                .array("classpath", runtimeClasspath(model, i, test))
                .array("jvmOptions", test ? List.of() : model.runJvmArgsOf(i))
                .string("workingDirectory", uri(dir))
                .token("environmentVariables", env.finish());
        String main = test || i >= model.mainClasses().size()
                ? null
                : model.mainClasses().get(i);
        if (main != null && !main.isBlank()) {
            String mainClass = JsonFields.object()
                    .string("className", main)
                    .array("arguments", List.of())
                    .finish();
            item.token("mainClasses", "[" + mainClass + "]");
        }
        return item.finish();
    }

    /**
     * The classpath, as URIs, of a JVM running module {@code i}: with {@code test} its test classes
     * first, then its classes; the classes of every workspace module it depends on, transitively,
     * a test-scoped one (and the test classes a {@code kind = "tests"} dependency adds) only with
     * {@code test}; the Scala library of each; and the libraries of each, the ones only tests use
     * only for module {@code i}'s own tests.
     */
    static List<String> runtimeClasspath(IdeWireModel model, int i, boolean test) {
        Set<String> out = new LinkedHashSet<>();
        if (test) addDir(out, model.testClassesDirs(), i);
        addDir(out, model.classesDirs(), i);
        List<Integer> modules = new ArrayList<>(List.of(i));
        Set<Integer> seen = new HashSet<>(modules);
        for (int k = 0; k < modules.size(); k++) {
            int m = modules.get(k);
            boolean ownTests = test && m == i;
            for (String row : model.siblingRefs()) {
                String[] parts = row.split("\\|", 3);
                if (parts.length < 3 || Integer.parseInt(parts[0]) != m) continue;
                String scope = parts[2];
                boolean main =
                        scope.equals(IdeWireModel.SCOPE_COMPILE) || scope.equals(IdeWireModel.SCOPE_COMPILE_TEST_KIND);
                int j = model.names().indexOf(parts[1]);
                if (j < 0 || !(main || ownTests)) continue;
                boolean testsKind = scope.equals(IdeWireModel.SCOPE_TEST_KIND)
                        || scope.equals(IdeWireModel.SCOPE_COMPILE_TEST_KIND);
                if (ownTests && testsKind) addDir(out, model.testClassesDirs(), j);
                if (seen.add(j)) modules.add(j);
            }
        }
        for (int m : modules) {
            if (m != i) addDir(out, model.classesDirs(), m);
        }
        for (int m : modules) {
            for (Path jar : rowsOf(model.scalaJars(), m)) {
                String name = jar.getFileName().toString();
                if (name.startsWith("scala-library") || name.startsWith("scala3-library_3")) out.add(uri(jar));
            }
        }
        for (int m : modules) {
            for (int j : libraries(model, m, test && m == i)) out.add(uri(libraryJar(model, j)));
        }
        return new ArrayList<>(out);
    }

    private static void addDir(Set<String> out, List<String> dirs, int i) {
        if (i < dirs.size() && dirs.get(i) != null && !dirs.get(i).isBlank()) out.add(uri(Path.of(dirs.get(i))));
    }

    /** {@code ScalaMainClassesItem}: the module's main class when it declares one. */
    static String scalaMainClassesItem(String tid, IdeWireModel model, int i) {
        List<String> classes = new ArrayList<>();
        String main = i < model.mainClasses().size() ? model.mainClasses().get(i) : "";
        if (main != null && !main.isBlank()) {
            classes.add(JsonFields.object()
                    .string("class", main)
                    .array("arguments", List.of())
                    .array("jvmOptions", List.of())
                    .array("environmentVariables", List.of())
                    .finish());
        }
        return BspServer.itemFor(tid)
                .token("classes", "[" + String.join(",", classes) + "]")
                .finish();
    }

    /** {@code ScalaTestClassesItem} with no classes: test discovery is the engine's, run through {@code buildTarget/test}. */
    static String scalaTestClassesItem(String tid) {
        return BspServer.itemFor(tid).array("classes", List.of()).finish();
    }

    /** The module's Java release as the compiler flag spells it; {@code ""} when none is known. */
    private static String releaseOf(IdeWireModel model, int i) {
        String r = i < model.javaReleases().size() ? model.javaReleases().get(i) : "";
        if (r == null || r.isBlank()) return "";
        try {
            return Integer.parseInt(r.trim()) > 0 ? r.trim() : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** The paths of the {@code i|path} rows that belong to module {@code i}. */
    private static List<Path> rowsOf(List<String> rows, int i) {
        List<Path> out = new ArrayList<>();
        for (String row : rows) {
            int bar = row.indexOf('|');
            if (bar > 0 && Integer.parseInt(row.substring(0, bar)) == i) out.add(Path.of(row.substring(bar + 1)));
        }
        return out;
    }

    private static String uri(Path p) {
        return WorkingDirectory.resolve(p).toUri().toString();
    }
}
