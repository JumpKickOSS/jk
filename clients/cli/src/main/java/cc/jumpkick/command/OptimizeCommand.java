// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code jk optimize} — schedule java-compiler/kotlinc AOT on the engine idle worker and exercise
 * tiny Java/Kotlin fixtures so cold ETAs / first builds are warmer (JK-1388).
 *
 * <ol>
 *   <li>Ensure the engine is running; materialize Mill-style fixtures into the cache.
 *   <li>Schedule engine-side java-compiler + kotlinc AOT (idle boundary; HotSpot 25+).
 *   <li>Build Java/Kotlin fixtures (Java touch-rebuild for worker path); measure language walls.
 *   <li>Write {@code [mean.by_language.*]} into host-metrics.toml.
 *   <li>Delete temps; synthetic journal entries are purged by the engine (JK-1390).
 * </ol>
 *
 * <p>Groovy is not in the pre-train battery — it trains on-demand. Test-runner AOT is not
 * attempted (per-project suite classpath; JK-1398).
 */
public final class OptimizeCommand implements CliCommand {

    /** Pre-train battery: Java + Kotlin only. Groovy and other langs train on-demand (JK-1396). */
    private static final String[] LANGS = {"java", "kotlin"};

    private static final String[] FIXTURES = {"java-train", "kotlin-train"};

    @Override
    public String name() {
        return "optimize";
    }

    @Override
    public String description() {
        return "Pre-train compiler workers and language fixtures for faster cold builds";
    }

    @Override
    public List<Opt> options() {
        // --force is global
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        boolean force = global.force;
        EnginePaths.Paths paths = EnginePaths.current();
        boolean animate =
                PipelineConsole.isInteractiveTerminal() && PipelineConsole.modeFor(global) == PipelineConsole.Mode.AUTO;
        Path scratchRoot = null;
        Map<String, Long> langWallMs = new LinkedHashMap<>();
        try (CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Optimizing", animate)) {
            view.solveLabel("Starting engine…");
            EngineClient.ensureRunning(paths, Jk.VERSION);

            // Materialize fixtures into cache first (classpath / monorepo) so remote installs work.
            ensureFixturesInCache();

            view.solveLabel("Scheduling worker AOT…");
            Optional<String> ack = EngineClient.optimize(paths, force);
            if (ack.isEmpty()) {
                view.finishPipelineFailure("engine did not return optimize-ack");
                return Exit.SOFTWARE;
            }
            // Engine ACKs immediately and trains on the idle-boundary worker (JK-1396).
            String ackSummary = nullToEmpty(Jsonl.str(ack.get(), "summary"));
            if (!Jsonl.bool(ack.get(), "ok", false)) {
                CliOutput.err("  note: worker AOT schedule failed: " + ackSummary);
            }

            scratchRoot = Files.createTempDirectory("jk-optimize-");
            System.setProperty("jk.build.trigger", "optimize");
            System.setProperty("jk.java.forceWorker", "true");
            try {
                for (int i = 0; i < LANGS.length; i++) {
                    String lang = LANGS[i];
                    String fixture = FIXTURES[i];
                    view.solveLabel("Optimizing " + capitalize(lang) + "…");
                    Path dest = scratchRoot.resolve(fixture);
                    if (!copyFixture(fixture, dest)) {
                        CliOutput.err("  note: fixture " + fixture + " not found — skipped");
                        continue;
                    }
                    long t0 = System.nanoTime();
                    // Latest pinned workers only (Java 25 + Kotlin 2.4.10). Groovy / older
                    // versions train on-demand via PluginAot train-on-miss on real builds.
                    int code = runJkIn(dest, List.of("build", "--skip-tests"));
                    if (code != 0) {
                        CliOutput.err("  note: " + lang + " build exited " + code);
                    }
                    if ("java".equals(lang)) {
                        touchAll(dest, ".java");
                        code = runJkIn(dest, List.of("build", "--skip-tests", "--redo"));
                        if (code != 0) {
                            CliOutput.err("  note: " + lang + " rebuild exited " + code);
                        }
                    }
                    // Test wall for language calibration only — does not produce reusable
                    // test-runner AOT (classpath includes project classes; see JK-1398).
                    runJkIn(dest, List.of("test"));
                    long wall = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                    langWallMs.put(lang, wall);
                }
            } finally {
                System.clearProperty("jk.build.trigger");
                System.clearProperty("jk.java.forceWorker");
            }

            writeLanguageBuckets(langWallMs);
            view.finishPipelineSuccess("Done optimizing JumpKick! Hi-yah!");
            if (!ackSummary.isBlank()) {
                for (String row : ackSummary.split("\n")) {
                    if (!row.isBlank()) CliOutput.out("  " + row);
                }
            }
            for (var e : langWallMs.entrySet()) {
                CliOutput.out("  " + e.getKey() + " fixture wall: " + e.getValue() + " ms");
            }
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err(CommandWedge.fail("Optimizing", e.getMessage()));
            return Exit.SOFTWARE;
        } finally {
            if (scratchRoot != null) deleteTree(scratchRoot);
        }
    }

    /** Locate classpath or monorepo {@code templates/optimize/<name>} and copy into {@code dest}. */
    static boolean copyFixture(String name, Path dest) throws IOException {
        Path src = findFixture(name);
        if (src == null) return false;
        copyTree(src, dest);
        return true;
    }

    static Path findFixture(String name) {
        // 1) Cached at install / dogfood materialize: <cache>/templates/optimize/<name>
        Path cached = JkDirs.cache().resolve("templates").resolve("optimize").resolve(name);
        if (isFixture(cached)) return cached;
        // 2) Data root (some installs share templates next to versions/)
        Path data = JkDirs.data().resolve("templates").resolve("optimize").resolve(name);
        if (isFixture(data)) return data;
        // 3) Walk up from CWD for monorepo dogfood (templates/optimize next to build.gradle)
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path cand = p.resolve("templates").resolve("optimize").resolve(name);
            if (isFixture(cand)) return cand;
        }
        // 4) Classpath resources (shipped with CLI jar / native image — JK-1400)
        if (extractClasspathFixture(name, cached)) return cached;
        return null;
    }

    /**
     * Copy all optimize fixtures from the CLI classpath into the cache root so subsequent
     * installs and out-of-tree {@code jk optimize} runs find them without a monorepo checkout.
     */
    static void ensureFixturesInCache() {
        for (String name : FIXTURES) {
            Path dest = JkDirs.cache().resolve("templates").resolve("optimize").resolve(name);
            if (isFixture(dest)) continue;
            extractClasspathFixture(name, dest);
        }
    }

    /**
     * Extract {@code templates/optimize/<name>/…} from the classloader into {@code dest}. Returns
     * true when a usable fixture tree is present after the call.
     */
    static boolean extractClasspathFixture(String name, Path dest) {
        if (name == null || dest == null) return false;
        String prefix = "templates/optimize/" + name + "/";
        ClassLoader cl = OptimizeCommand.class.getClassLoader();
        try {
            // Prefer a single known file to probe packaging
            var probe = cl.getResource(prefix + "jk.toml");
            if (probe == null) return false;
            // Mill-style simple layout (src/, test/src/) — fixed tiny shape, not a jar walk.
            List<String> rels = new ArrayList<>();
            rels.add("jk.toml");
            if (name.startsWith("java")) {
                for (int i = 0; i < 10; i++) rels.add("src/train/T" + i + ".java");
                rels.add("test/src/train/T0Test.java");
                rels.add("test/src/train/T1Test.java");
            } else if (name.startsWith("kotlin")) {
                for (int i = 0; i < 10; i++) rels.add("src/T" + i + ".kt");
                rels.add("test/src/T0Test.kt");
                rels.add("test/src/T1Test.kt");
            }
            int copied = 0;
            for (String rel : rels) {
                try (var in = cl.getResourceAsStream(prefix + rel)) {
                    if (in == null) continue;
                    Path out = dest.resolve(rel);
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            return copied > 0 && isFixture(dest);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isFixture(Path cand) {
        return Files.isRegularFile(cand.resolve("jk.toml")) || Files.isDirectory(cand.resolve("src"));
    }

    private static int runJkIn(Path projectDir, List<String> args) {
        try {
            List<String> cmd = new ArrayList<>();
            // Prefer PATH jk so we use the installed client
            cmd.add("jk");
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            MapEnv(pb);
            Process p = pb.start();
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return 124;
            }
            return p.exitValue();
        } catch (Exception e) {
            return 70;
        }
    }

    private static void MapEnv(ProcessBuilder pb) {
        pb.environment().put("JK_BUILD_TRIGGER", "optimize");
        pb.environment().put("JK_JAVA_FORCE_WORKER", "1");
    }

    static void touchAll(Path root, String suffix) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(suffix)) {
                    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Writes language fixture walls under {@code [mean.by_language.*]} and seeds sane
     * {@code compile-*-per-source-ms} keys under {@code [mean]} so {@link
     * cc.jumpkick.runtime.Calibration} cold ETA can prefer language-specific priors.
     *
     * <p>Fixture walls include resolve + test and must <strong>not</strong> be used as
     * absolute ms/source (that poisons ETA). We keep walls for diagnostics and derive
     * compile priors from the product baseline scaled by <em>relative</em> language walls.
     */
    static void writeLanguageBuckets(Map<String, Long> wallMs) {
        if (wallMs.isEmpty()) return;
        Path host = JkDirs.builds().resolve("host-metrics.toml");
        try {
            String existing = Files.isRegularFile(host) ? Files.readString(host) : "# host-metrics\n";
            StringBuilder sb = new StringBuilder();
            boolean inByLang = false;
            for (String line : existing.split("\n", -1)) {
                String t = line.trim();
                if (t.startsWith("[mean.by_language.")) {
                    inByLang = true;
                    continue;
                }
                if (inByLang) {
                    if (t.startsWith("[")) {
                        inByLang = false;
                    } else {
                        continue; // drop keys under mean.by_language.*
                    }
                }
                // Drop previous language seed keys under [mean] — we re-append below
                if (t.startsWith("compile-java-per-source-ms")
                        || t.startsWith("compile-kotlin-per-source-ms")
                        || t.startsWith("compile-groovy-per-source-ms")
                        || t.startsWith("# language fixture walls")
                        || t.startsWith("# language seed from optimize")) {
                    continue;
                }
                sb.append(line).append('\n');
            }

            long javaWall = wallMs.getOrDefault("java", 0L);
            Map<String, Long> perSource = new LinkedHashMap<>();
            for (var e : wallMs.entrySet()) {
                perSource.put(e.getKey(), languageCompilePerSourceMs(e.getKey(), e.getValue(), javaWall));
            }

            // Seed [mean] HostLearnedRates keys Calibration already folds (JK-1389 ETA path).
            if (!perSource.isEmpty()) {
                sb.append("\n# language seed from optimize (relative walls × product baseline)\n");
                // Ensure we are under [mean] — if the file already has [mean], append keys after it
                // by writing a fresh [mean] block with only the language keys (toml last-wins OK).
                sb.append("[mean]\n");
                Long j = perSource.get("java");
                Long k = perSource.get("kotlin");
                if (j != null) sb.append("compile-java-per-source-ms = ").append(j).append('\n');
                if (k != null) sb.append("compile-kotlin-per-source-ms = ").append(k).append('\n');
                // Groovy is not in the optimize battery — rates come from harvest on real projects.
            }

            sb.append("\n# language fixture walls from jk optimize (JK-1389)\n");
            for (var e : wallMs.entrySet()) {
                String lang = e.getKey();
                sb.append("[mean.by_language.").append(lang).append("]\n");
                sb.append("fixture_wall_ms = ").append(e.getValue()).append('\n');
                sb.append("compile_per_source_ms = ")
                        .append(perSource.getOrDefault(lang, 18L))
                        .append('\n');
            }
            AtomicWrites.replace(host, sb.toString());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Product baseline (18 ms/source) × relative fixture wall vs Java, clamped. Fixture walls
     * include resolve/test so absolute wall/sources would overshoot ETA badly.
     */
    static long languageCompilePerSourceMs(String lang, long wallMs, long javaWallMs) {
        final long baseline = 18L;
        double rel = 1.0;
        if (javaWallMs > 0 && wallMs > 0 && !"java".equals(lang)) {
            rel = (double) wallMs / (double) javaWallMs;
        }
        // Dampen first-install cold resolve noise; keep language signal.
        rel = Math.max(0.7, Math.min(3.0, rel));
        return Math.max(8L, Math.round(baseline * rel));
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dest.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
