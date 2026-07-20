// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TomlValues;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.tomlj.TomlTable;

/**
 * Project-local <strong>build logic</strong> (ticket-1037 / Mill-style escape hatch without TOML
 * scripts). Convention directory is {@code jk-build/}; override with {@code [build].logic}.
 *
 * <pre>
 * # optional — omit to use jk-build/ when that directory exists
 * [build]
 * logic = "jk-build"           # project-relative dir (or "off" to disable)
 * logic-main = "demo.LineCount"  # optional main class
 * </pre>
 *
 * Java under the logic dir is compiled and run with {@code --project} / {@code --out}; outputs are
 * action-cached and merged into the classes tree as resources.
 */
public final class BuildLogicSupport {

    /** Default project-relative directory for build logic sources. */
    public static final String DEFAULT_DIR = "jk-build";

    private BuildLogicSupport() {}

    public record Config(Path logicDir, String mainClass) {}

    /**
     * Resolve build-logic config: {@code [build].logic} overrides the directory (default {@link
     * #DEFAULT_DIR}); absent dir → empty. {@code logic = "off"} / {@code "false"} / {@code "none"}
     * disables even when {@code jk-build/} exists.
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
     * Compile + run build logic (or restore from action cache), writing generated resources into
     * {@code classesDir}. Returns whether logic ran; false when no logic dir is configured/present.
     */
    public static boolean run(
            Path projectDir,
            BuildLayout layout,
            ActionCache actionCache,
            Path classesDir,
            java.util.function.Consumer<String> label)
            throws IOException, InterruptedException {
        Optional<Config> cfg = config(projectDir);
        if (cfg.isEmpty()) return false;
        Config c = cfg.get();

        List<Path> sources = listJava(c.logicDir());
        if (sources.isEmpty()) {
            label.accept("build-logic: no .java sources in " + c.logicDir().getFileName());
            return true;
        }

        // Compile once; each main is an independently action-cached task (ticket-1039).
        Path logicClasses = layout.generatedSourcesDir("jk-build-classes");
        deleteContents(logicClasses);
        Files.createDirectories(logicClasses);
        compile(sources, logicClasses);

        List<String> mains;
        if (c.mainClass() != null && !c.mainClass().isBlank()) {
            mains = List.of(c.mainClass().trim());
        } else {
            mains = discoverMains(logicClasses);
        }
        if (mains.isEmpty()) {
            throw new IllegalStateException(
                    "[build] logic has no main class — set [build].logic-main = \"pkg.Class\""
                            + " (public static void main), or name classes *Build / BuildMain");
        }

        // Shared source fingerprint (whole logic tree) — per-task keys also include the main class.
        List<String> sourceTokens = new ArrayList<>();
        sourceTokens.add("dir:" + projectDir.relativize(c.logicDir()));
        for (Path src : sources) {
            sourceTokens.add("src:" + c.logicDir().relativize(src) + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
        }

        for (String main : mains) {
            String simple = simpleName(main);
            Path outDir = layout.generatedSourcesDir("jk-build-out-" + simple);
            Files.createDirectories(outDir);
            String taskId = ActionKey.qualifiedTaskId("build-logic-" + simple, projectDir);
            List<String> tokens = new ArrayList<>(sourceTokens);
            tokens.add("main:" + main);
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

            label.accept("build-logic:" + simple + ": compile + run");
            deleteContents(outDir);
            Files.createDirectories(outDir);
            int exit = runMain(logicClasses, main, projectDir, outDir);
            if (exit != 0) {
                throw new IllegalStateException("[build] logic " + main + " exited " + exit);
            }
            actionCache.store(taskId, key, java.util.Map.of("build-logic", key), outDir);
            mergeIntoClasses(outDir, classesDir);
        }
        return true;
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

    private static void compile(List<Path> sources, Path classes) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("[build] logic: no system javac");
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classes.toString());
        for (Path s : sources) args.add(s.toString());
        int rc = javac.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("[build] logic: javac failed (exit " + rc + ")");
    }

    /** Prefer every *Build / *BuildMain class; fall back to first *Logic/*Generator or first class. */
    private static List<String> discoverMains(Path classes) throws IOException {
        try (Stream<Path> s = Files.walk(classes)) {
            List<String> names = s.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                    .map(p -> classes.relativize(p)
                            .toString()
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .toList();
            List<String> builds = names.stream()
                    .filter(n -> n.endsWith("BuildMain") || n.equals("BuildMain") || n.endsWith("Build"))
                    .toList();
            if (!builds.isEmpty()) return builds;
            List<String> alts = names.stream()
                    .filter(n -> n.endsWith("Logic") || n.endsWith("Generator"))
                    .toList();
            if (!alts.isEmpty()) return alts;
            return names.isEmpty() ? List.of() : List.of(names.getFirst());
        }
    }

    private static int runMain(Path classes, String main, Path projectDir, Path outDir)
            throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                java,
                "-cp",
                classes.toString(),
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
