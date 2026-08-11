// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Trains a JVM AOT cache (JEP 514) for an image by running the application inside the image's own
 * base image.
 *
 * <p>The cache is only usable by a JVM whose identity matches the one that trained it, down to the
 * OS, architecture, build number and the compiler HotSpot was built with. Running the base image is
 * the only way to be certain of that on a host of any platform, which is what Paketo and Quarkus
 * both do. It costs a container runtime at build time; {@link #isAvailable} reports whether one is
 * there so the caller can say so rather than producing a layer that does nothing.
 *
 * <p>What the JVM checks when it loads the cache is, per classpath entry, the path suffix after the
 * longest common prefix, the file size, and the modification time. It does <em>not</em> checksum
 * anything. Both sides are therefore pinned deliberately: the staged tree is stamped with {@link
 * #LAYER_TIME}, which is the same stamp the layers carry into the image.
 */
final class AotCacheTrainer {

    /**
     * Modification time stamped on every trained and shipped file. Jib's own default happens to be
     * epoch + 1s, but relying on that would make the cache depend on a Jib implementation detail —
     * the two sides are set explicitly from this constant instead.
     */
    static final FileTime LAYER_TIME = FileTime.fromMillis(1000);

    /** Where the application tree lives in the image; also the working directory. */
    static final String APP_DIR = "/app";

    /** The cache file, named relative to {@link #APP_DIR}. */
    static final String CACHE_FILE = "app.aot";

    /** The recording the assembling step consumes; never shipped. */
    static final String CONFIG_FILE = "app.aotconf";

    /**
     * A trained layout: the tree to ship at {@link #APP_DIR}, the java arguments that run it, and
     * the cache. Paths are relative and the image sets {@code WORKDIR}, which is how Spring, Paketo
     * and Quarkus all do it — the archive records each entry as given, so a relative classpath run
     * from a fixed directory matches wherever the tree ends up.
     */
    /** {@code stagedFiles} = staging-relative paths present BEFORE the record run. */
    record Result(Path stagingRoot, List<String> runArgs, Path cache, java.util.Set<String> stagedFiles) {}

    private static final long TRAIN_TIMEOUT_SECONDS = 300;

    /** No output for this long means startup has finished talking. */
    private static final long QUIET_MILLIS = 4_000;

    /** Never stop an application before it has had a chance to get going. */
    private static final long MIN_RUN_MILLIS = 3_000;

    /** How long to wait for the child JVM that assembles the cache after the app has exited. */
    private static final long ASSEMBLE_TIMEOUT_SECONDS = 180;

    private AotCacheTrainer() {}

    /** Why an AOT cache cannot be trained for this image, or null when it can. */
    static String unsupportedReason(ImageBuilder.Plan plan) {
        // The runtime probe applies to EVERY layout — an app tree trains in a container exactly
        // like a jar layout when the host cannot execute the image's JVM, and skipping the probe
        // used to surface as a raw `Cannot run program "docker"` mid-train (JK-1759).
        if (containerRuntime(plan.config().dockerExecutable()) == null
                && !BaseJre.hostCanExecute(plan.config().platforms())) {
            return "this host can neither run the image's JVM directly (it builds for "
                    + (plan.config().platforms().isEmpty()
                            ? "linux/amd64"
                            : String.join(",", plan.config().platforms()))
                    + ") nor find a container runtime (docker, podman, nerdctl). The cache is only"
                    + " valid for the exact JVM build that produced it, so training needs one or the"
                    + " other";
        }
        if (plan.hasAppTree()) return null;
        if (plan.classesDir() != null && !BootLayout.isBootJar(plan.mainJar())) {
            return "this module's image is an exploded-classes layout and its main artifact is not a"
                    + " Spring Boot jar, so there is nothing to unpack into a trainable shape. A CDS"
                    + " dump refuses any classpath entry that is a directory (JDK-8329980, Won't"
                    + " Fix)";
        }
        return null;
    }

    static boolean isAvailable(ImageBuilder.Plan plan) {
        return unsupportedReason(plan) == null;
    }

    /**
     * Stage the image's layout, train inside the base image, and verify the result loads. Returns
     * the cache file, or throws with the reason it could not be produced.
     *
     * @param classpath the classpath string the image entrypoint will use — the training run must
     *     be given the identical string, in the identical order, or the JVM rejects the cache
     */
    static Result train(ImageBuilder.Plan plan, Path workDir, Consumer<String> log)
            throws IOException, InterruptedException {
        String blocked = unsupportedReason(plan);
        if (blocked != null) throw new IOException(blocked);

        String base = qualify(plan.config().base());
        Path localJre = localBaseJre(plan, base, workDir, log);
        Path staging = workDir.resolve("aot-train");

        // Boot nests its jars under BOOT-INF and loads them itself, so nothing useful reaches the
        // JVM classpath. Its own `jarmode extract` produces the shape that can be trained: a thin
        // launcher jar whose manifest Class-Path names lib/*.jar, relative.
        List<String> runArgs;
        if (plan.hasAppTree()) {
            // The packager already produced the runnable tree; train against a copy of exactly what
            // ships. Quarkus's fast-jar also keeps lib/main off the JVM classpath — its own loader
            // reads those — so the archive only has to agree about quarkus-run.jar and lib/boot.
            copyTree(plan.appDir(), staging);
            runArgs = List.of("-jar", plan.appJar());
            log.accept("training against the packager's " + plan.appDir().getFileName() + " tree");
        } else if (BootLayout.isBootJar(plan.mainJar())) {
            Path extractTool = localJre != null ? localJre : hostJava();
            BootLayout.Extracted boot = BootLayout.extract(plan.mainJar(), staging, extractTool);
            runArgs = List.of("-jar", boot.launcherJar());
            log.accept("unpacked the Spring Boot jar for training (" + boot.launcherJar() + " + lib/)");
        } else {
            stageLayout(plan, staging);
            runArgs = List.of("-cp", relativeClasspath(plan), plan.mainClass());
        }
        stamp(staging);
        // Snapshot what was staged before any training process runs: whatever the app writes
        // during record/assemble (logs, embedded-DB files) is not application content and must
        // not become image bytes (JK-1758).
        java.util.Set<String> stagedFiles = snapshotRelative(staging);

        // A container has to be addressable to be stopped; the local path signals the process
        // directly. Each run gets its own name so the training and verifying containers cannot
        // collide.
        String runtime = localJre == null ? containerRuntime(plan.config().dockerExecutable()) : null;
        java.util.function.Function<String, List<String>> prefixFor = name -> {
            if (localJre != null) return new ArrayList<>(List.of(localJre.toString()));
            List<String> cmd = new ArrayList<>(containerPrefix(runtime, staging, base, name));
            cmd.add("java");
            return cmd;
        };
        log.accept(
                localJre != null
                        ? "training the AOT cache with " + base + "'s JVM, on this host"
                        : "training the AOT cache in " + base);

        String trainName = "jk-aot-train-" + java.util.UUID.randomUUID();
        String assembleName = "jk-aot-create-" + java.util.UUID.randomUUID();
        String verifyName = "jk-aot-verify-" + java.util.UUID.randomUUID();
        List<String> prefix = prefixFor.apply(trainName);

        // Two steps, deliberately. The one-step -XX:AOTCacheOutput assembles the cache from a child
        // JVM that the application spawns as it exits normally — and a server does not exit
        // normally, it is signalled. Under SIGTERM the recording is written and the child never
        // runs, leaving a 33 MiB .aotconf and no cache. Recording and assembling separately puts
        // the assembly in jk's hands, which is also how Quarkus drives its own Leyden path.
        List<String> record = new ArrayList<>(prefix);
        record.add("-XX:AOTMode=record");
        record.add("-XX:AOTConfiguration=" + CONFIG_FILE);
        record.addAll(runArgs);

        Output recorded = runUntilSettled(record, localJre != null ? staging : null, runtime, trainName, log);
        Path config = staging.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config) || sizeOrZero(config) == 0) {
            throw new IOException("the training run recorded nothing.\n"
                    + "  command: " + String.join(" ", record) + "\n"
                    + tail(recorded.text()));
        }

        List<String> assemble = new ArrayList<>(prefixFor.apply(assembleName));
        assemble.add("-XX:AOTMode=create");
        assemble.add("-XX:AOTConfiguration=" + CONFIG_FILE);
        assemble.add("-XX:AOTCache=" + CACHE_FILE);
        assemble.addAll(runArgs);
        Output assembled = runUntilSettled(assemble, localJre != null ? staging : null, runtime, assembleName, log);
        Path cache = staging.resolve(CACHE_FILE);
        if (!Files.isRegularFile(cache) || sizeOrZero(cache) == 0) {
            throw new IOException("the recording could not be assembled into a cache.\n"
                    + "  command: " + String.join(" ", assemble) + "\n"
                    + tail(assembled.text()));
        }
        Files.deleteIfExists(config);
        Files.setLastModifiedTime(cache, LAYER_TIME);

        // Prove it loads before it becomes a layer. A rejected cache is silent at default log
        // level, so an unverified one is indistinguishable from a working one.
        List<String> verify = new ArrayList<>(prefixFor.apply(verifyName));
        verify.add("-Xlog:aot=info");
        verify.add("-XX:AOTCache=" + CACHE_FILE);
        verify.addAll(runArgs);

        String refusal = refusal(runUntilSettled(verify, localJre != null ? staging : null, runtime, verifyName, log)
                .text());
        if (refusal != null) {
            throw new IOException("the AOT cache was trained but the JVM refused it:\n  " + refusal);
        }
        log.accept("AOT cache verified (" + Files.size(cache) / (1024 * 1024) + " MiB)");
        return new Result(staging, runArgs, cache, stagedFiles);
    }

    private static java.util.Set<String> snapshotRelative(Path root) throws IOException {
        java.util.Set<String> out = new java.util.TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.toList()) {
                if (Files.isRegularFile(f)) {
                    out.add(root.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The JVM running this worker — good enough to rewrite a jar with Boot's jarmode tool. */
    private static Path hostJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /**
     * The image's JVM, on this host, or null to fall back to running the image. Never fatal: the
     * container path produces the same cache, so a base image jk cannot unpack is a slower build
     * rather than a failed one.
     */
    private static Path localBaseJre(ImageBuilder.Plan plan, String base, Path workDir, Consumer<String> log) {
        if (!BaseJre.hostCanExecute(plan.config().platforms())) return null;
        try {
            Path java = BaseJre.javaBinary(base, workDir.resolve("jk-image"));
            return java != null && Files.isExecutable(java) ? java : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.accept("could not read " + base + "'s JVM (" + e.getMessage() + ") — training in a container");
            return null;
        }
    }

    /** Copy a tree verbatim — the staged copy is what gets trained and what ships. */
    private static void copyTree(Path from, Path to) throws IOException {
        deleteRecursively(to);
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Lay out exactly what the image will contain, at the paths the image will use. */
    private static void stageLayout(ImageBuilder.Plan plan, Path staging) throws IOException {
        deleteRecursively(staging);
        Path classpathDir = Files.createDirectories(staging.resolve("classpath"));
        Path libs = Files.createDirectories(staging.resolve("libs"));
        Files.copy(
                plan.mainJar(),
                classpathDir.resolve(plan.mainJar().getFileName().toString()));
        for (Path jar : allDependencyJars(plan)) {
            Files.copy(jar, libs.resolve(plan.nameOf(jar)));
        }
    }

    /** Release then snapshot jars, matching the order the image layers add them. */
    static List<Path> allDependencyJars(ImageBuilder.Plan plan) {
        List<Path> jars = new ArrayList<>(plan.dependencyJars());
        jars.addAll(plan.snapshotJars());
        return jars;
    }

    /**
     * The classpath for both the training run and the entrypoint: every jar named explicitly, in
     * one fixed order. A {@code *} wildcard is expanded by the launcher in directory order, and the
     * training run reads a bind mount while the real run reads an overlay — nothing guarantees
     * those enumerate alike, and a different order is a rejected cache.
     */
    static String relativeClasspath(ImageBuilder.Plan plan) {
        List<String> entries = new ArrayList<>();
        entries.add("classpath/" + plan.mainJar().getFileName());
        List<String> libs = new ArrayList<>();
        for (Path jar : allDependencyJars(plan)) libs.add("libs/" + plan.nameOf(jar));
        java.util.Collections.sort(libs);
        entries.addAll(libs);
        return String.join(":", entries);
    }

    /**
     * Add the implicit Docker Hub registry to a bare name. Jib resolves {@code bellsoft/x} as Docker
     * Hub by convention; podman refuses to guess and, with no TTY to prompt on, simply fails.
     */
    static String qualify(String image) {
        if (image == null || image.isBlank()) return image;
        int slash = image.indexOf('/');
        if (slash < 0) return "docker.io/library/" + image;
        String first = image.substring(0, slash);
        boolean isRegistry = first.contains(".") || first.contains(":") || first.equals("localhost");
        return isRegistry ? image : "docker.io/" + image;
    }

    /** {@code <runtime> run --rm -v <staging>:/app -w /app <base>} — everything before the java command. */
    private static List<String> containerPrefix(String runtime, Path staging, String base, String name) {
        List<String> cmd = new ArrayList<>(List.of(runtime, "run", "--rm", "--name", name));
        // Rootful docker writes app.aot/app.aotconf into the bind mount as root:root — the
        // follow-up setLastModifiedTime/delete then fails AFTER a successful training run, and
        // the root-owned staging dir breaks the next build's cleanup (JK-1760). Rootless podman
        // and rootless docker map container-root to the invoking user, so --user there would
        // remap through subuids and break instead — only rootful docker gets the flag.
        if (rootfulDocker(runtime)) {
            String user = unixUserGroup(staging);
            if (user != null) cmd.addAll(List.of("--user", user));
        }
        // :z relabels for SELinux and is meaningless (and rejected) elsewhere.
        String mount = staging.toAbsolutePath() + ":/app";
        cmd.addAll(List.of("-v", isSelinux() ? mount + ":z" : mount));
        cmd.addAll(List.of("-w", "/app"));
        cmd.add(base);
        return cmd;
    }

    /** True for a docker CLI fronting a rootful daemon (probe fails → assume rootful). */
    private static boolean rootfulDocker(String runtime) {
        String name = Path.of(runtime).getFileName().toString();
        if (!name.startsWith("docker")) return false;
        try {
            Process p = new ProcessBuilder(runtime, "info", "--format", "{{.SecurityOptions}}")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return !out.contains("rootless");
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** {@code uid:gid} of the invoking user (owner of {@code probe}), or null off POSIX. */
    private static String unixUserGroup(Path probe) {
        try {
            Object uid = Files.getAttribute(probe, "unix:uid");
            Object gid = Files.getAttribute(probe, "unix:gid");
            return uid + ":" + gid;
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** Stamp every staged file so the training run records the times the image will carry. */
    private static void stamp(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.setLastModifiedTime(file, LAYER_TIME);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** The line explaining why the JVM would not use the cache, or null when it mapped. */
    /**
     * A line proving the JVM refused the cache, or null. Matches the specific refusal shapes
     * {@code -Xlog:aot} emits (cache not loaded/used/mapped, identity mismatches) rather than any
     * line containing "failed" — AOT logging also narrates non-fatal per-item failures ("failed to
     * load class ...") on runs where the cache itself mapped fine (JK-1783).
     */
    static String refusal(String log) {
        for (String line : log.split("\n")) {
            if (!line.contains("[aot")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            // The refusal shapes -Xlog:aot emits — but not per-item noise like "failed to
            // load class X", which appears on runs where the cache mapped fine (JK-1783).
            if (lower.contains("mismatch")
                    || lower.contains("different version")
                    || lower.contains("unable to map")
                    || lower.contains("unable to use")
                    || lower.contains("cannot be used")
                    || lower.contains("disabled")
                    || ((lower.contains("archive") || lower.contains("cache")) && lower.contains("failed"))) {
                return line.trim();
            }
        }
        return null;
    }

    private static boolean isSelinux() {
        return Files.isDirectory(Path.of("/sys/fs/selinux"));
    }

    /** docker, podman or nerdctl — the configured one when set, else the first on PATH. */
    static String containerRuntime(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        for (String candidate : List.of("docker", "podman", "nerdctl")) {
            if (onPath(candidate)) return candidate;
        }
        return null;
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }

    private record Output(String text, int exit) {}

    /**
     * Start the application, wait for it to settle, then ask it to stop, and return everything it
     * said.
     *
     * <p>A server never exits on its own, and JEP 514 assembles the cache when the JVM exits. It
     * does so on SIGTERM as well, which is what makes this framework-agnostic — no
     * {@code spring.context.exit}, no {@code quarkus.appcds.generate}, nothing to know per
     * framework. "Settled" is {@value #QUIET_MILLIS} ms with no output after at least
     * {@value #MIN_RUN_MILLIS} ms, which is a startup that has stopped logging. An application that
     * exits by itself is simply finished and its output is returned as-is.
     *
     * @param runtime the container CLI when running in a container, null when running locally
     * @param containerName the container to stop; ignored when {@code runtime} is null
     */
    private static Output runUntilSettled(
            List<String> command, Path cwd, String runtime, String containerName, Consumer<String> log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd.toFile());
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
                log.accept("the training run did not settle within " + TRAIN_TIMEOUT_SECONDS + "s — stopping it");
                asked = true;
            } else if (!asked && elapsedMs >= MIN_RUN_MILLIS && quietMs >= QUIET_MILLIS) {
                asked = true;
            }
            if (asked) {
                synchronized (out) {
                    out.append("[jk] settled after ").append(elapsedMs).append("ms — asking it to stop\n");
                }
                requestStop(process, runtime, containerName);
                // The JVM writes the cache from a shutdown hook; give it room to finish.
                process.waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                break;
            }
        }
        boolean killed = process.isAlive();
        if (killed) process.destroyForcibly();
        reader.join(5_000);
        synchronized (out) {
            out.append("[jk] ")
                    .append(killed ? "did not stop when asked; killed" : "exited " + process.exitValue())
                    .append('\n');
        }
        synchronized (out) {
            return new Output(out.toString(), process.isAlive() ? -1 : process.exitValue());
        }
    }

    /**
     * SIGTERM, and nothing harsher. {@code destroy()} signals the local JVM; a container needs its
     * runtime asked, because signalling the client that is streaming its output does not reliably
     * reach PID 1 inside.
     */
    private static void requestStop(Process process, String runtime, String containerName) {
        if (runtime == null) {
            process.destroy();
            return;
        }
        try {
            new ProcessBuilder(runtime, "stop", "--time", String.valueOf(TRAIN_TIMEOUT_SECONDS), containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            process.destroy(); // best effort: the forcible kill below still bounds the wait
        }
    }

    private static String tail(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 20);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
