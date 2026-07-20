// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TomlValues;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.plugin.buildlogic.BuildLogicAnchor;
import cc.jumpkick.plugin.buildlogic.BuildLogicContext;
import cc.jumpkick.plugin.buildlogic.BuildLogicContributor;
import cc.jumpkick.plugin.buildlogic.BuildLogicGraph;
import cc.jumpkick.plugin.buildlogic.BuildLogicTask;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.tomlj.TomlTable;

/**
 * Project-local <strong>build logic</strong> (tickets 1037 / 1039 / 1044). Convention directory is
 * {@code .jk-build/}; override with {@code [build].logic}.
 *
 * <p>Supports:
 *
 * <ul>
 *   <li><strong>SPI</strong> — classes implementing {@link BuildLogicContributor} register named
 *       tasks at {@link BuildLogicAnchor}s
 *   <li><strong>Legacy mains</strong> — {@code *Build} / {@code *BuildMain} with {@code main}
 *       run at {@link BuildLogicAnchor#AFTER_RESOURCES}
 * </ul>
 */
public final class BuildLogicSupport {

    /** Default project-relative directory for build logic sources (dot-dir: not product noise). */
    public static final String DEFAULT_DIR = ".jk-build";

    private BuildLogicSupport() {}

    public record Config(Path logicDir, String mainClass) {}

    /**
     * Resolve build-logic config: {@code [build].logic} overrides the directory (default {@link
     * #DEFAULT_DIR}); absent dir → empty. {@code logic = "off"} / {@code "false"} / {@code "none"}
     * disables even when {@code .jk-build/} exists.
     */
    public static Optional<Config> config(Path projectDir) {
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = projectDir.resolve("jk.toml");
        String logicRel = DEFAULT_DIR;
        String main = null;
        Optional<TomlTable> build = TomlValues.parse(toml).map(t -> t.getTable("build"));
        if (build.isPresent() && build.get() != null) {
            TomlTable b = build.get();
            String logic = b.getString("logic");
            if (logic != null && !logic.isBlank()) {
                String n = logic.trim().toLowerCase(Locale.ROOT);
                if (n.equals("off") || n.equals("false") || n.equals("none") || n.equals("disable")) {
                    return Optional.empty();
                }
                logicRel = logic.trim();
            }
            String lm = b.getString("logic-main");
            if (lm != null && !lm.isBlank()) main = lm.trim();
        }

        Path logicDir = root.resolve(logicRel).normalize();
        if (!logicDir.startsWith(root)) {
            throw new IllegalStateException("[build].logic must stay under the project root: " + logicRel);
        }
        if (!Files.isDirectory(logicDir)) return Optional.empty();
        return Optional.of(new Config(logicDir, main));
    }

    /**
     * Compile + run build logic tasks for {@code anchor} (or restore from action cache), merging
     * outputs into {@code classesDir}. Returns whether any logic is configured for this project
     * (even if this anchor has zero tasks).
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            BuildLogicAnchor anchor,
            java.util.function.Consumer<String> label)
            throws IOException, InterruptedException {
        Optional<Config> cfg = config(projectDir);
        if (cfg.isEmpty()) return false;
        Config c = cfg.get();

        List<Path> sources = listJava(c.logicDir());
        if (sources.isEmpty()) {
            if (anchor == BuildLogicAnchor.AFTER_RESOURCES) {
                label.accept("build-logic: no .java sources in " + c.logicDir().getFileName());
            }
            return true;
        }

        Path logicClasses = layout.generatedSourcesDir("jk-build-classes");
        deleteContents(logicClasses);
        Files.createDirectories(logicClasses);
        Path apiCp = apiClasspath();
        compile(sources, logicClasses, apiCp);

        Map<BuildLogicAnchor, List<RegisteredTask>> byAnchor = discoverTasks(c, logicClasses, apiCp);
        List<RegisteredTask> tasks = byAnchor.getOrDefault(anchor, List.of());
        if (tasks.isEmpty()) return true;

        List<String> sourceTokens = new ArrayList<>();
        sourceTokens.add("dir:" + projectDir.relativize(c.logicDir()));
        for (Path src : sources) {
            sourceTokens.add(
                    "src:" + c.logicDir().relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        sourceTokens.add("anchor:" + anchor.name());

        for (RegisteredTask task : tasks) {
            String simple = task.name();
            Path outDir = layout.generatedSourcesDir("jk-build-out-" + simple);
            Files.createDirectories(outDir);
            String taskId = ActionKey.qualifiedTaskId("build-logic-" + simple, projectDir);
            List<String> tokens = new ArrayList<>(sourceTokens);
            tokens.add("task:" + simple);
            tokens.add("kind:" + task.kind());
            String key = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);

            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent() && !hit.get().outputs().isEmpty()) {
                label.accept("build-logic:" + simple + ": cache hit");
                deleteContents(outDir);
                Files.createDirectories(outDir);
                actionCache.restore(hit.get(), outDir);
                mergeIntoClasses(outDir, classesDir);
                continue;
            }

            label.accept("build-logic:" + simple + ": " + anchor.name().toLowerCase(Locale.ROOT));
            deleteContents(outDir);
            Files.createDirectories(outDir);
            BuildLogicContext ctx = new BuildLogicContext(
                    projectDir.toAbsolutePath().normalize(),
                    outDir.toAbsolutePath().normalize(),
                    classesDir.toAbsolutePath().normalize());
            try {
                task.task().run(ctx);
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) throw ie;
                if (e instanceof IOException ioe) throw ioe;
                throw new IllegalStateException("[build] logic task " + simple + " failed: " + e.getMessage(), e);
            }
            actionCache.store(taskId, key, java.util.Map.of("build-logic", key), outDir);
            mergeIntoClasses(outDir, classesDir);
        }
        return true;
    }

    /** Back-compat: run {@link BuildLogicAnchor#AFTER_RESOURCES} only. */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            java.util.function.Consumer<String> label)
            throws IOException, InterruptedException {
        return run(projectDir, layout, actionCache, classesDir, BuildLogicAnchor.AFTER_RESOURCES, label);
    }

    private record RegisteredTask(String name, String kind, BuildLogicTask task) {}

    private static Map<BuildLogicAnchor, List<RegisteredTask>> discoverTasks(
            Config c, Path logicClasses, Path apiCp) throws IOException {
        Map<BuildLogicAnchor, List<RegisteredTask>> out = new EnumMap<>(BuildLogicAnchor.class);
        for (BuildLogicAnchor a : BuildLogicAnchor.values()) {
            out.put(a, new ArrayList<>());
        }

        // Graph collector
        Map<String, BuildLogicAnchor> nameAnchors = new LinkedHashMap<>();
        BuildLogicGraph graph = (name, anchor, task) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("build-logic task name must be non-blank");
            }
            String n = name.trim();
            if (nameAnchors.containsKey(n)) {
                throw new IllegalStateException("duplicate build-logic task name: " + n);
            }
            nameAnchors.put(n, anchor);
            out.get(anchor).add(new RegisteredTask(n, "spi", task));
        };

        URL[] urls = toUrls(logicClasses, apiCp);
        try (URLClassLoader cl = new URLClassLoader(urls, BuildLogicContributor.class.getClassLoader())) {
            // SPI contributors
            for (String binary : listClassNames(logicClasses)) {
                Class<?> clazz;
                try {
                    clazz = Class.forName(binary, false, cl);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    continue;
                }
                if (clazz.isInterface()
                        || clazz.isEnum()
                        || !BuildLogicContributor.class.isAssignableFrom(clazz)) {
                    continue;
                }
                try {
                    Object inst = clazz.getDeclaredConstructor().newInstance();
                    ((BuildLogicContributor) inst).register(graph);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "[build] logic SPI " + binary + " failed to construct/register: " + e.getMessage(), e);
                }
            }

            // Legacy mains at AFTER_RESOURCES (unless logic-main pins one class)
            List<String> mains;
            if (c.mainClass() != null && !c.mainClass().isBlank()) {
                mains = List.of(c.mainClass().trim());
            } else {
                mains = discoverLegacyMains(logicClasses, cl);
            }
            for (String main : mains) {
                // Skip if the same class already registered via SPI (contributor implements both)
                String simple = simpleName(main);
                if (nameAnchors.containsKey(simple) || nameAnchors.containsKey(main)) continue;
                String name = simple;
                final String mainClass = main;
                BuildLogicTask task = ctx -> {
                    int exit = runMain(logicClasses, apiCp, mainClass, ctx.projectDir(), ctx.outDir());
                    if (exit != 0) {
                        throw new IllegalStateException("[build] logic " + mainClass + " exited " + exit);
                    }
                };
                nameAnchors.put(name, BuildLogicAnchor.AFTER_RESOURCES);
                out.get(BuildLogicAnchor.AFTER_RESOURCES).add(new RegisteredTask(name, "main", task));
            }
        }

        if (nameAnchors.isEmpty()) {
            throw new IllegalStateException(
                    "[build] logic has no tasks — implement "
                            + BuildLogicContributor.class.getName()
                            + " or provide a *Build / *BuildMain with public static void main"
                            + " (or set [build].logic-main)");
        }
        return out;
    }

    private static List<String> discoverLegacyMains(Path classes, ClassLoader cl) throws IOException {
        List<String> names = listClassNames(classes);
        List<String> builds = names.stream()
                .filter(n -> n.endsWith("BuildMain") || n.equals("BuildMain") || n.endsWith("Build"))
                .filter(n -> hasMain(n, cl) && !isContributor(n, cl))
                .toList();
        if (!builds.isEmpty()) return builds;
        List<String> alts = names.stream()
                .filter(n -> n.endsWith("Logic") || n.endsWith("Generator"))
                .filter(n -> hasMain(n, cl) && !isContributor(n, cl))
                .toList();
        if (!alts.isEmpty()) return alts;
        return names.stream().filter(n -> hasMain(n, cl) && !isContributor(n, cl)).findFirst().stream()
                .toList();
    }

    private static boolean isContributor(String binary, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(binary, false, cl);
            return BuildLogicContributor.class.isAssignableFrom(c) && !c.isInterface();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    private static boolean hasMain(String binary, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(binary, false, cl);
            c.getMethod("main", String[].class);
            return true;
        } catch (ReflectiveOperationException | NoClassDefFoundError e) {
            return false;
        }
    }

    private static List<String> listClassNames(Path classes) throws IOException {
        try (Stream<Path> s = Files.walk(classes)) {
            return s.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                    .map(p -> classes.relativize(p)
                            .toString()
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .toList();
        }
    }

    private static String simpleName(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? binaryName : binaryName.substring(dot + 1);
    }

    private static List<Path> listJava(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private static void compile(List<Path> sources, Path classes, Path apiCp) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("[build] logic: no system javac");
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classes.toString());
        if (apiCp != null && Files.exists(apiCp)) {
            args.add("-cp");
            args.add(apiCp.toString());
        }
        for (Path s : sources) args.add(s.toString());
        int rc = javac.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("[build] logic: javac failed (exit " + rc + ")");
    }

    /** Location of the plugin-sdk jar / classes dir that hosts the build-logic API. */
    static Path apiClasspath() {
        try {
            var pd = BuildLogicContributor.class.getProtectionDomain();
            var cs = pd == null ? null : pd.getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                throw new IllegalStateException(
                        "[build] logic: build-logic API classpath unknown (null code source — exotic packaging)");
            }
            return Path.of(cs.getLocation().toURI());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("[build] logic: cannot locate build-logic API classpath", e);
        }
    }

    private static URL[] toUrls(Path logicClasses, Path apiCp) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(logicClasses.toUri().toURL());
        if (apiCp != null && Files.exists(apiCp)) {
            urls.add(apiCp.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    private static int runMain(Path classes, Path apiCp, String main, Path projectDir, Path outDir)
            throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = classes.toString();
        if (apiCp != null && Files.exists(apiCp)) {
            cp = cp + java.io.File.pathSeparator + apiCp;
        }
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                cp,
                main,
                "--project",
                projectDir.toString(),
                "--out",
                outDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0 && !log.isBlank()) System.err.println(log);
        return exit;
    }

    private static void mergeIntoClasses(Path generated, Path classesDir) throws IOException {
        if (!Files.isDirectory(generated)) return;
        Files.createDirectories(classesDir);
        try (Stream<Path> s = Files.walk(generated)) {
            for (Path file : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(file)) continue;
                Path dest = classesDir.resolve(generated.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) Files.deleteIfExists(p);
            }
        }
    }
}
