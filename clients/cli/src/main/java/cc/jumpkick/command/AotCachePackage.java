// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk build --aot-cache}: extract the app under {@code target/aot-cache/} and train a JVM
 * startup cache (JEP 514 AOT on JDK 25+; AppCDS otherwise). Training needs a JVM that exits
 * (Boot uses {@code -Dspring.context.exit=onRefresh}; others get a watchdog).
 */
final class AotCachePackage {

    /** Non-Boot training must finish inside this window (Boot exits at refresh on its own). */
    private static final long TRAINING_TIMEOUT_SECONDS = 180;

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private AotCachePackage() {}

    /** Package + train. Returns the process exit code contract (0 = success). */
    static int run(Path projectDir, Path cacheDir) {
        try {
            return runInner(projectDir, cacheDir);
        } catch (IOException e) {
            CommandWedge.printFail("Build", e.getMessage());
            return Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CommandWedge.printFail("Build", "interrupted");
            return Exit.SOFTWARE;
        }
    }

    private static int runInner(Path projectDir, Path cacheDir) throws IOException, InterruptedException {
        // Thin client: the engine computes the layout inputs (boot-ness, tier, main jar,
        // coordinate-named libs, main class); this process does the file assembly + the
        // training fork it owns.
        ExecPlan plan = EngineClient.execPlan(EnginePaths.current(), projectDir, cacheDir, "aot-cache", null, null);
        if (plan.error() != null) {
            CommandWedge.printFail("Build", plan.error());
            return Exit.SOFTWARE;
        }

        // target/aot-cache next to the built artifact (mainJar sits in target/ or target/lib/).
        Path targetDir = Objects.requireNonNull(Path.of(plan.mainJar()).getParent(), "target dir");
        if ("lib".equals(String.valueOf(targetDir.getFileName()))) {
            targetDir = Objects.requireNonNull(targetDir.getParent(), "target dir");
        }
        Path outDir = targetDir.resolve("aot-cache");
        PathUtil.deleteRecursively(outDir);
        Files.createDirectories(outDir);

        Path javaHome = Path.of(plan.javaHome());
        String java = JdkFingerprint.java(javaHome).toString();
        boolean aotTier = "aot".equals(plan.tier());
        String cacheFile = aotTier ? "app.aot" : "app.jsa";
        boolean springBoot = plan.boot();

        // 1. The extracted layout: thin app jar + lib/ with original names.
        String appJarName;
        if (springBoot) {
            // Boot's own tooling produces the CDS/AOT-friendly layout (thin launcher jar
            // whose manifest Class-Path points at lib/) — use it rather than re-implement.
            appJarName = extractBootLayout(java, Path.of(plan.mainJar()), outDir, projectDir);
        } else {
            appJarName = assemblePlainLayout(plan, outDir);
        }

        // 2. Training run: one full startup that exits, recorded into the cache.
        List<String> training = new ArrayList<>();
        training.add(java);
        if (aotTier) {
            training.add("-XX:AOTCacheOutput=" + cacheFile);
        } else {
            training.add("-XX:ArchiveClassesAtExit=" + cacheFile);
        }
        if (springBoot) {
            // Refresh the full context, then exit — the exact startup path worth caching.
            training.add("-Dspring.context.exit=onRefresh");
        }
        training.add("-jar");
        training.add(appJarName);

        CliOutput.err("jk: training the " + (aotTier ? "AOT cache (JEP 514)" : "AppCDS archive") + " — one "
                + (springBoot ? "context refresh" : "run of the app") + " ...");
        Process process = new ProcessBuilder(training)
                .directory(outDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(l -> output.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        if (!process.waitFor(TRAINING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            CommandWedge.printFail(
                    "Build",
                    "the training run did not exit within " + TRAINING_TIMEOUT_SECONDS
                            + "s. Training needs one run that terminates — Spring Boot apps exit automatically;"
                            + " other apps must exit on their own (a server main loop can't be trained this way yet).");
            return Exit.SOFTWARE;
        }
        reader.join(5_000);
        if (process.exitValue() != 0) {
            CommandWedge.printFail(
                    "Build", "training run failed (exit " + process.exitValue() + "):\n" + tail(output.toString()));
            return Exit.SOFTWARE;
        }
        // JEP 514's one-step flow assembles the cache in a child JVM at exit; it is written
        // before the parent's waitFor returns. Verify the artifact exists either way.
        Path cachePath = outDir.resolve(cacheFile);
        if (!Files.isRegularFile(cachePath)) {
            CommandWedge.printFail(
                    "Build", "training completed but no " + cacheFile + " was produced:\n" + tail(output.toString()));
            return Exit.SOFTWARE;
        }

        String runFlag = aotTier ? "-XX:AOTCache=" + cacheFile : "-XX:SharedArchiveFile=" + cacheFile;

        // Prove the cache loads before claiming it exists. A cache is rejected in silence — wrong
        // JVM build, moved directory, changed jar — and the app just starts cold, so an unverified
        // artifact is indistinguishable from a working one until someone measures.
        String rejection = verifyLoads(java, outDir, runFlag, appJarName, springBoot);
        if (rejection != null) {
            CommandWedge.printFail("Build", "the AOT cache was written but the JVM refused it:\n  " + rejection);
            return Exit.SOFTWARE;
        }

        String jvmIdent = jvmIdentity(java);
        writeManifest(outDir, Path.of(plan.mainJar()), projectDir, java, jvmIdent, cacheFile, runFlag, appJarName);
        Path launcher = writeLauncher(outDir, java, runFlag, appJarName);

        CliOutput.err("jk: wrote " + PathDisplay.styledRaw(outDir) + " (" + Files.size(cachePath) / (1024 * 1024)
                + " MiB cache, verified)");
        // The cache is keyed to the exact JVM build AND to these absolute paths. A different
        // vendor at the same version, or the same files moved elsewhere, falls back to a cold
        // start without saying so — hence the launcher, which pins both.
        CliOutput.err("jk: run it with:  " + PathDisplay.styledRaw(launcher));
        CliOutput.err("jk:   pinned to " + jvmIdent);
        CliOutput.err(
                "jk:   the cache is void if this directory moves, the jars change, or another" + " JVM build runs it");
        return 0;
    }

    /**
     * Start the app once with the cache and report why the JVM refused it, or null when it mapped.
     * {@code -Xlog:aot} is the only place the refusal is visible; at default log level a rejected
     * cache is indistinguishable from a working one.
     */
    private static String verifyLoads(String java, Path outDir, String runFlag, String appJarName, boolean springBoot)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Xlog:aot=info");
        command.add(runFlag);
        if (springBoot) command.add("-Dspring.context.exit=onRefresh");
        command.add("-jar");
        command.add(appJarName);
        Process process = new ProcessBuilder(command)
                .directory(outDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(l -> out.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        boolean exited = process.waitFor(TRAINING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            // A run that never came back proved nothing — reporting it as verified is exactly
            // the silent-cold-start trap verification exists to close.
            return "verification run did not exit within " + TRAINING_TIMEOUT_SECONDS + "s — cache not verified";
        }
        reader.join(5_000);
        return AotCacheFiles.refusal(out.toString());
    }

    /** {@code java -version}'s VM line — the identity the cache is keyed to. */
    private static String jvmIdentity(String java) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of(java, "-version"))
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        for (String line : out.split("\n")) {
            if (line.contains("Server VM") || line.contains("Client VM")) return line.trim();
        }
        return out.isBlank() ? "unknown" : out.split("\n")[0].trim();
    }

    /**
     * Drop {@code target/aot-cache/} when the application jar no longer matches the one it was
     * trained against. The JVM would reject it anyway, silently; a build that has just made it
     * void is the moment to say so, and the cost of keeping it is tens of MiB that look like a
     * deliverable.
     */
    static void discardIfStale(Path projectDir) {
        try {
            Path outDir = findCacheDir(projectDir);
            if (outDir == null) return;
            Path manifest = outDir.resolve(MANIFEST);
            if (!Files.isRegularFile(manifest)) return;
            String text = Files.readString(manifest);
            String recorded = valueOf(text, "app-sha256");
            String builtFrom = valueOf(text, "built-from");
            if (recorded.isEmpty() || builtFrom.isEmpty()) return;
            Path jar = Path.of(builtFrom);
            String actual = Files.isRegularFile(jar) ? Hashing.sha256Hex(jar) : "";
            // The lock is the dependency closure's identity: a dep-only bump rebuilds nothing
            // in the thin main jar, but run.sh would keep executing stale lib/ copies. A
            // manifest without the lock key cannot be validated.
            boolean lockFresh = valueOf(text, "lock-sha256").equals(currentLockSha(projectDir));
            if (recorded.equals(actual) && lockFresh) return;
            PathUtil.deleteRecursively(outDir);
            CliOutput.err("jk: the AOT cache no longer matches this build — removed " + PathDisplay.styledRaw(outDir)
                    + " (re-run with --aot-cache)");
        } catch (IOException | RuntimeException ignored) {
            // Best effort: never fail a build over a cache that was only ever an optimisation.
        }
    }

    /** sha256 of the module's lockfile, or empty when there is none. */
    private static String currentLockSha(Path projectDir) throws IOException {
        Path lock = LockPaths.lockFile(projectDir);
        return Files.isRegularFile(lock) ? Hashing.sha256Hex(lock) : "";
    }

    /** {@code <target>/aot-cache} for a module, or null when there is none. */
    private static @Nullable Path findCacheDir(Path projectDir) {
        Path dir = projectDir.resolve(BuildLayout.TARGET).resolve("aot-cache");
        return Files.isDirectory(dir) ? dir : null;
    }

    /** The value of a {@code key = "value"} line, or empty. */
    private static String valueOf(String toml, String key) {
        for (String line : toml.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(key)) continue;
            int q = t.indexOf('"');
            int end = t.lastIndexOf('"');
            if (q > 0 && end > q) return t.substring(q + 1, end);
        }
        return "";
    }

    static final String MANIFEST = "aot-cache.toml";

    /** What the cache is pinned to, for anyone (or anything) that needs to check later. */
    private static void writeManifest(
            Path outDir,
            Path sourceJar,
            Path projectDir,
            String java,
            String jvmIdent,
            String cacheFile,
            String runFlag,
            String appJarName)
            throws IOException {
        // The jar the layout was derived from, not the extracted copy inside outDir — the copy
        // never changes on its own, so comparing it to itself would always look fresh. The lock
        // pins the dependency closure the lib/ copies came from for the same reason.
        String appSha = Files.isRegularFile(sourceJar) ? Hashing.sha256Hex(sourceJar) : "";
        Files.writeString(outDir.resolve(MANIFEST), """
                # Written by `jk build --aot-cache`. The cache is void if this directory moves, the
                # jars change, or a JVM other than the one below runs it — the JVM reports none of
                # that at default log level, so check here rather than trusting a fast start.
                cache        = "%s"
                built-from   = "%s"
                app-sha256   = "%s"
                lock-sha256  = "%s"
                java-home    = "%s"
                jvm-identity = "%s"
                run          = "%s %s -jar %s"
                """.formatted(
                        cacheFile,
                        sourceJar.toAbsolutePath(),
                        appSha,
                        currentLockSha(projectDir),
                        java,
                        jvmIdent,
                        java,
                        runFlag,
                        appJarName));
    }

    /** A launcher that pins the JVM and the working directory, since both are part of the key. */
    private static Path writeLauncher(Path outDir, String java, String runFlag, String appJarName) throws IOException {
        Path launcher = outDir.resolve(Os.isWindows() ? "run.cmd" : "run.sh");
        // JVM options ride JK_JAVA_OPTS rather than "$@", which lands after -jar and would reach
        // the application as arguments. Flags that change heap shape or the collector can cost the
        // cache; the JVM falls back to a cold start rather than misbehaving.
        String body = Os.isWindows()
                ? "@echo off\r\ncd /d \"%~dp0\"\r\n\"" + java + "\" %JK_JAVA_OPTS% " + runFlag + " -jar " + appJarName
                        + " %*\r\n"
                : "#!/bin/sh\n"
                        + "# The cache is keyed to this JVM and this directory; both are pinned here.\n"
                        + "# JVM options: JK_JAVA_OPTS=... ./run.sh   application arguments: ./run.sh a b c\n"
                        + "cd \"$(dirname \"$0\")\" || exit 1\n"
                        + "exec \"" + java + "\" ${JK_JAVA_OPTS} " + runFlag + " -jar " + appJarName + " \"$@\"\n";
        Files.writeString(launcher, body);
        launcher.toFile().setExecutable(true, false);
        return launcher;
    }

    /**
     * Run Boot's {@code -Djarmode=tools extract} against the boot jar — emits the thin launcher
     * jar + {@code lib/}. Returns the thin jar's file name inside {@code outDir}.
     */
    private static String extractBootLayout(String java, Path bootJar, Path outDir, Path projectDir)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(bootJar)) {
            throw new IOException("boot jar not found at " + bootJar + " — build before --aot-cache");
        }
        // extract refuses a non-empty destination; outDir was recreated empty, but the
        // tool also wants to create it itself — point it at outDir and allow force.
        List<String> command = List.of(
                java,
                "-Djarmode=tools",
                "-jar",
                bootJar.toAbsolutePath().toString(),
                "extract",
                "--force",
                "--destination",
                outDir.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder captured = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(l -> captured.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        // Bounded: an unresponsive extract must not hang the build forever.
        if (!process.waitFor(TRAINING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Spring Boot extract did not finish within " + TRAINING_TIMEOUT_SECONDS + "s");
        }
        reader.join(5_000);
        String out = captured.toString();
        if (process.exitValue() != 0) {
            throw new IOException("jarmode extract failed:\n" + tail(out));
        }
        try (var stream = Files.list(outDir)) {
            // Sorted, like BootLayout.launcherJarIn: Files.list order is filesystem-dependent,
            // and an unordered pick is nondeterministic if extract ever emits two jars.
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IOException("jarmode extract produced no app jar in " + outDir));
        }
    }

    /**
     * Non-Boot layout: the app jar with a {@code Class-Path} pointing at {@code lib/}, deps copied
     * under their original {@code artifact-version.jar} names (both straight from the engine's
     * plan). The Class-Path manifest entry lets training and runtime both use plain {@code -jar}.
     */
    private static String assemblePlainLayout(ExecPlan plan, Path outDir) throws IOException {
        Path mainJar = Path.of(plan.mainJar());
        Path libDir = Files.createDirectories(outDir.resolve("lib"));
        for (int i = 0; i < plan.libNames().size(); i++) {
            Files.copy(
                    Path.of(plan.libPaths().get(i)),
                    libDir.resolve(plan.libNames().get(i)),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        String appJarName = mainJar.getFileName().toString();
        rewriteAppJar(mainJar, outDir.resolve(appJarName), plan.libNames(), plan.mainClass());
        return appJarName;
    }

    /**
     * Copy {@code from} to {@code to}, adding a {@code Class-Path} manifest entry with relative
     * {@code lib/} refs (a jar's Class-Path resolves against the jar's own location, so the layout
     * is relocatable) and {@code Main-Class} when the source jar has none.
     *
     * <p>Every entry — the manifest included — is pinned by {@link DeterministicZip}, so
     * re-running {@code --aot-cache} over an unchanged app yields a byte-identical jar. That is
     * why the manifest is written by hand instead of through
     * {@code new JarOutputStream(out, manifest)}: the convenience constructor stamps it with
     * {@code System.currentTimeMillis()}.
     */
    static void rewriteAppJar(Path from, Path to, List<String> libNames, String mainClass) throws IOException {
        try (var jarIn = new JarInputStream(Files.newInputStream(from))) {
            Manifest manifest = jarIn.getManifest();
            if (manifest == null) manifest = new Manifest();
            manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS) == null && !mainClass.isEmpty()) {
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
            }
            if (!libNames.isEmpty()) {
                StringBuilder cp = new StringBuilder();
                for (String name : libNames) {
                    if (cp.length() > 0) cp.append(' ');
                    cp.append("lib/").append(name);
                }
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, cp.toString());
            }
            try (var jarOut = new JarOutputStream(DeterministicZip.archiveStream(to))) {
                ZIP.writeManifest(jarOut, manifest);
                JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    if (entry.getName().equals(JarFile.MANIFEST_NAME)) continue;
                    // jarIn stays open for the next entry, so this must not be writeEntryStreaming.
                    jarOut.putNextEntry(ZIP.entry(entry.getName()));
                    jarIn.transferTo(jarOut);
                    jarOut.closeEntry();
                }
            }
        }
    }

    /** The last ~25 lines — JVM/App startup logs are long; the failure is at the bottom. */
    private static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 25);
        return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
    }
}
