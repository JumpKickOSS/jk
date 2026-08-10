// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.surface.DynamicSurface;
import cc.jumpkick.surface.DynamicSurfaceIo;
import cc.jumpkick.surface.KeepRuleEmitter;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@code jk train}: observe one or more profiles under the Graal tracing agent, merge into a
 * {@link DynamicSurface}, write {@code target/train/}, optionally record a JVM AOT cache and
 * promote to {@code commit-to}.
 */
public final class TrainRunner {

    private static final long QUIET_MILLIS = 4_000;
    private static final long MIN_RUN_MILLIS = 3_000;
    private static final long TRAIN_TIMEOUT_SECONDS = 300;

    private TrainRunner() {}

    public record Result(DynamicSurface surface, Path trainRoot, boolean cacheHit, boolean aotWritten) {}

    /**
     * @param graalHome GraalVM home that provides {@code native-image-agent}, or null to try the
     *     process JDK
     * @param force ignore fingerprint cache
     * @param profileFilter optional single profile name
     */
    public static Result run(
            Path moduleDir,
            JkBuild project,
            BuildLayout layout,
            Path lockFile,
            Path graalHome,
            Path javaHome,
            TrainConfig config,
            String profileFilter,
            boolean force,
            Consumer<String> log)
            throws IOException, InterruptedException {
        List<TrainConfig.Profile> profiles = config.select(profileFilter);
        Path target = layout.moduleTargetDir();
        Path mainJar = layout.mainJar();
        if (!Files.isRegularFile(mainJar)) {
            throw new IOException("train needs a packaged main jar at " + mainJar + " — build first");
        }

        String fingerprint = fingerprint(moduleDir, project, lockFile, mainJar, config, profiles);
        Path fpFile = TrainLayout.fingerprint(target);
        if (!force
                && Files.isRegularFile(fpFile)
                && fingerprint.equals(
                        Files.readString(fpFile, StandardCharsets.UTF_8).trim())
                && Files.isRegularFile(TrainLayout.surfaceJson(target))) {
            log.accept("train outputs up-to-date (fingerprint match)");
            return new Result(
                    DynamicSurfaceIo.readJson(TrainLayout.surfaceJson(target)),
                    TrainLayout.root(target),
                    true,
                    Files.isRegularFile(TrainLayout.aotCache(target)));
        }

        Path agentJavaHome = resolveAgentJavaHome(graalHome, javaHome);
        if (agentJavaHome == null) {
            throw new IOException("train needs a GraalVM JDK with the tracing agent (native-image-agent).\n"
                    + "  Install GraalVM (jk native will prompt) and re-run, or point train\n"
                    + "  at a Graal home that provides the agent.");
        }

        DynamicSurface merged = DynamicSurface.empty();
        for (TrainConfig.Profile profile : profiles) {
            log.accept("train profile `" + profile.name() + "`");
            Path agentOut = TrainLayout.agentDir(target, profile.name());
            deleteRecursively(agentOut);
            Files.createDirectories(agentOut);

            List<String> cmd = new ArrayList<>();
            if (config.hasCommand()) {
                // User-owned launcher; they attach the agent if needed.
                cmd.addAll(shell(config.command()));
            } else {
                cmd.add(javaBinary(agentJavaHome).toString());
                cmd.add("-agentlib:native-image-agent=config-output-dir=" + agentOut.toAbsolutePath());
                for (var e : profile.properties().entrySet()) {
                    cmd.add("-D" + e.getKey() + "=" + e.getValue());
                }
                cmd.add("-jar");
                cmd.add(mainJar.toAbsolutePath().toString());
                cmd.addAll(profile.args());
            }

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.directory(moduleDir.toFile());
            for (var e : profile.env().entrySet()) {
                pb.environment().put(e.getKey(), e.getValue());
            }
            Output out = runUntilSettled(pb, log);
            DynamicSurface observed = DynamicSurfaceIo.importAgentDir(agentOut, "train:" + profile.name());
            if (observed.entries().isEmpty()) {
                log.accept("profile `" + profile.name() + "` produced no agent metadata"
                        + (out.exit() != 0 ? " (exit " + out.exit() + ")" : ""));
            }
            merged = merged.merge(observed);
        }

        if (merged.entries().isEmpty()) {
            throw new IOException("train produced an empty dynamic surface.\n"
                    + "  The suite did not exercise reflective / resource / proxy usage the\n"
                    + "  agent can see. Add a full-app exercise ([train] command or a main\n"
                    + "  that loads by name) — unit tests in isolation are not enough.");
        }

        // Write merged outputs
        Path mergedDir = TrainLayout.merged(target);
        Files.createDirectories(mergedDir);
        DynamicSurfaceIo.writeJson(TrainLayout.surfaceJson(target), merged);
        Files.writeString(TrainLayout.keepsPro(target), KeepRuleEmitter.emit(merged), StandardCharsets.UTF_8);
        DynamicSurfaceIo.writeReachabilityDir(TrainLayout.reachabilityDir(target), merged);
        Files.writeString(fpFile, fingerprint + "\n", StandardCharsets.UTF_8);

        boolean aotWritten = false;
        if (config.aotCache()) {
            aotWritten = writeAotCache(mainJar, javaHome, moduleDir, target, log);
        }

        if (config.hasCommitTo()) {
            Path dest = moduleDir.resolve(config.commitTo()).normalize();
            if (!dest.startsWith(moduleDir.normalize())) {
                throw new IOException("train commit-to must stay under the module: " + config.commitTo());
            }
            copyTree(mergedDir, dest);
            log.accept("promoted train metadata to " + dest);
        }

        log.accept("train wrote "
                + merged.entries().size()
                + " surface "
                + (merged.entries().size() == 1 ? "entry" : "entries")
                + " → "
                + TrainLayout.root(target));
        return new Result(merged, TrainLayout.root(target), false, aotWritten);
    }

    /**
     * When {@code [train] require-fresh = true}, refuse if train outputs are missing or the
     * fingerprint no longer matches. Returns null when fresh (or require-fresh is off).
     */
    public static String staleReason(
            Path moduleDir, JkBuild project, BuildLayout layout, Path lockFile, TrainConfig config) throws IOException {
        if (!config.requireFresh()) return null;
        Path target = layout.moduleTargetDir();
        Path fpFile = TrainLayout.fingerprint(target);
        if (!Files.isRegularFile(fpFile) || !Files.isRegularFile(TrainLayout.surfaceJson(target))) {
            return "train outputs are missing and [train] require-fresh = true — run `jk train`";
        }
        List<TrainConfig.Profile> profiles = config.effectiveProfiles();
        String expected = fingerprint(moduleDir, project, lockFile, layout.mainJar(), config, profiles);
        String actual = Files.readString(fpFile, StandardCharsets.UTF_8).trim();
        if (!expected.equals(actual)) {
            return "train outputs are stale and [train] require-fresh = true — re-run `jk train`";
        }
        return null;
    }

    private static String fingerprint(
            Path moduleDir,
            JkBuild project,
            Path lockFile,
            Path mainJar,
            TrainConfig config,
            List<TrainConfig.Profile> profiles)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("jk=").append(JkVersion.VERSION).append('\n');
        sb.append("agent=native-image-agent\n");
        if (Files.isRegularFile(lockFile)) {
            sb.append("lock=").append(Hashing.sha256Hex(lockFile)).append('\n');
        }
        if (Files.isRegularFile(mainJar)) {
            sb.append("jar=").append(Hashing.sha256Hex(mainJar)).append('\n');
        }
        sb.append("profiles=")
                .append(Hashing.sha256Hex(config.profilesToken().getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        // Classes dir when present
        Path classes = moduleDir.resolve("target/classes");
        // Prefer layout-relative if exists under module target from BuildLayout convention
        sb.append("name=").append(project.project().name()).append('\n');
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A JDK home that can load {@code -agentlib:native-image-agent}. Graal ships the agent next to
     * {@code native-image}; a stock Temurin JDK does not.
     */
    static Path resolveAgentJavaHome(Path graalHome, Path javaHome) {
        for (Path home : new Path[] {graalHome, javaHome}) {
            if (home == null) continue;
            if (looksLikeGraal(home)) return home;
        }
        // Last resort: the running process may already be a Graal JVM.
        Path self = Path.of(System.getProperty("java.home"));
        if (looksLikeGraal(self)) return self;
        return null;
    }

    private static boolean looksLikeGraal(Path home) {
        if (Files.isExecutable(home.resolve("bin/native-image"))
                || Files.isRegularFile(home.resolve("bin/native-image.cmd"))) {
            return true;
        }
        for (String rel : List.of(
                "lib/libnative-image-agent.so",
                "lib/server/libnative-image-agent.so",
                "Contents/Home/lib/libnative-image-agent.so")) {
            if (Files.isRegularFile(home.resolve(rel))) return true;
        }
        // vm.vendor often says GraalVM
        return false;
    }

    private static Path javaBinary(Path javaHome) {
        if (javaHome != null) {
            Path j = javaHome.resolve("bin/java");
            if (Files.isExecutable(j)) return j;
        }
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static List<String> shell(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return List.of("cmd", "/c", command);
        }
        return List.of("sh", "-c", command);
    }

    private static boolean writeAotCache(Path mainJar, Path javaHome, Path moduleDir, Path target, Consumer<String> log)
            throws IOException, InterruptedException {
        Path cache = TrainLayout.aotCache(target);
        Files.deleteIfExists(cache);
        Path conf = target.resolve("train/app.aotconf");
        Files.deleteIfExists(conf);
        Path java = javaBinary(javaHome);
        // Two-step record/create so SIGTERM still yields a cache (same as image trainer).
        List<String> record = List.of(
                java.toString(),
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=" + conf.toAbsolutePath(),
                "-jar",
                mainJar.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(record).redirectErrorStream(true).directory(moduleDir.toFile());
        runUntilSettled(pb, log);
        if (!Files.isRegularFile(conf) || Files.size(conf) == 0) {
            log.accept("AOT record produced no configuration — skipping AOT cache");
            return false;
        }
        List<String> create = List.of(
                java.toString(),
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=" + conf.toAbsolutePath(),
                "-XX:AOTCache=" + cache.toAbsolutePath(),
                "-jar",
                mainJar.toAbsolutePath().toString());
        ProcessBuilder pb2 =
                new ProcessBuilder(create).redirectErrorStream(true).directory(moduleDir.toFile());
        Process p = pb2.start();
        p.waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (p.isAlive()) p.destroyForcibly();
        Files.deleteIfExists(conf);
        if (Files.isRegularFile(cache) && Files.size(cache) > 0) {
            log.accept("AOT cache → " + cache.getFileName() + " (" + Files.size(cache) / (1024 * 1024) + " MiB)");
            return true;
        }
        log.accept("AOT create did not produce a cache");
        return false;
    }

    private record Output(String text, int exit) {}

    private static Output runUntilSettled(ProcessBuilder pb, Consumer<String> log)
            throws IOException, InterruptedException {
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        java.util.concurrent.atomic.AtomicLong lastOutput =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(line -> {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                    lastOutput.set(System.nanoTime());
                });
            } catch (IOException ignored) {
            }
        });
        long started = System.nanoTime();
        boolean asked = false;
        while (process.isAlive()) {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            long quietMs = (System.nanoTime() - lastOutput.get()) / 1_000_000L;
            if (elapsedMs > TRAIN_TIMEOUT_SECONDS * 1000) {
                log.accept("train run did not settle within " + TRAIN_TIMEOUT_SECONDS + "s — stopping");
                asked = true;
            } else if (!asked && elapsedMs >= MIN_RUN_MILLIS && quietMs >= QUIET_MILLIS) {
                asked = true;
            }
            if (asked) {
                process.destroy(); // SIGTERM
                process.waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                break;
            }
        }
        if (process.isAlive()) process.destroyForcibly();
        reader.join(5_000);
        int exit = process.isAlive() ? -1 : process.exitValue();
        synchronized (out) {
            return new Output(out.toString(), exit);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        deleteRecursively(to);
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
