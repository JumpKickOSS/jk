// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.AotTraining;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

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
    record Result(Path stagingRoot, List<String> runArgs, Path cache, Set<String> stagedFiles) {}

    /** How long a training run may take to settle, and to stop once asked. */
    private static final Duration TRAIN_TIMEOUT = Duration.ofSeconds(300);

    private AotCacheTrainer() {}

    /** Why an AOT cache cannot be trained for this image, or null when it can. */
    static @Nullable String unsupportedReason(ImageBuilder.Plan plan) {
        // Layout refusals first — they are intrinsic and more actionable than "no docker".
        if (!plan.hasAppTree() && plan.classesDir() != null && !BootLayout.isBootJar(plan.mainJar())) {
            return "needs a Spring Boot jar, not exploded classes";
        }
        // An app tree (and jar layouts) still need a matching host JVM or a container runtime.
        if (containerRuntime(plan.config().dockerExecutable()) == null
                && !BaseJre.hostCanExecute(plan.config().platforms())) {
            return "needs docker, podman or nerdctl, or a "
                    + (plan.config().platforms().isEmpty()
                            ? "linux/amd64"
                            : String.join(",", plan.config().platforms()))
                    + " host";
        }
        return null;
    }

    static boolean isAvailable(ImageBuilder.Plan plan) {
        return unsupportedReason(plan) == null;
    }

    /** The one platform a cache is trained for: the image's, linux/amd64 when it declares none. */
    static String platform(ImageConfig config) {
        return BaseJre.target(config.platforms());
    }

    /**
     * What the platform check found: why the image ships without an AOT cache though one was asked
     * for ({@code noCache}, null when one can be trained), and the flags that assemble a cache with
     * no AOT code ({@code noCode}, empty unless the training runs are emulated).
     */
    record PlatformCheck(@Nullable String noCache, List<String> noCode) {
        static final PlatformCheck NATIVE = new PlatformCheck(null, List.of());
    }

    /**
     * Whether a cache can be trained for the image's platform here, and how. A cache only loads in a
     * JVM of the platform that trained it: an image built for several platforms shares one cache
     * layer among them, and a host whose container runtime cannot run the image's platform has no
     * JVM to train it with. A host that runs it emulated trains it emulated, without AOT code: the
     * code would be compiled for the emulator's CPU, whose features (the hypervisor bit among them)
     * a real one lacks, and a JVM told to insist on the cache would refuse it.
     */
    static PlatformCheck platformCheck(ImageBuilder.Plan plan) throws IOException {
        List<String> platforms = plan.config().platforms();
        if (platforms.size() > 1) {
            return new PlatformCheck(
                    "an AOT cache loads only on the platform that trained it, and this image is built for "
                            + String.join(", ", platforms),
                    List.of());
        }
        if (BaseJre.hostCanExecute(platforms)) return PlatformCheck.NATIVE;
        String runtime = containerRuntime(plan.config().dockerExecutable());
        if (runtime == null) return PlatformCheck.NATIVE; // refused by unsupportedReason, as before
        String platform = platform(plan.config());
        String base = qualify(ImageBuilder.baseOf(plan.config()));
        List<String> probe = List.of(
                runtime,
                "run",
                "--rm",
                "--platform",
                platform,
                base,
                "java",
                "-XshowSettings:properties",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+PrintFlagsFinal",
                "-version");
        Processes.Captured run = Processes.capture(probe, PROBE_TIMEOUT);
        if (run.timedOut()) throw new IOException("[image] aot-cache: " + String.join(" ", probe) + " did not finish");
        String refusal = platformRefusal(runtime, platform, run.exit(), run.output());
        if (refusal != null) return new PlatformCheck(refusal, List.of());
        return new PlatformCheck(null, emulatedOn(platform) == null ? List.of() : noCodeFlags(run.output()));
    }

    /**
     * The flags that keep AOT code out of a cache, from a {@code -XX:+PrintFlagsFinal} listing: every
     * {@code AOT…Caching} switch the JVM has (adapters and stubs in JDK 27), turned off. Empty for a
     * JVM with none, which stores no code to begin with.
     */
    static List<String> noCodeFlags(String flagsFinal) {
        List<String> off = new ArrayList<>();
        for (String line : flagsFinal.split("\n")) {
            String[] f = line.strip().split("\\s+");
            if (f.length >= 3 && f[0].equals("bool") && f[1].matches("AOT\\w*Caching") && f[2].equals("=")) {
                off.add("-XX:-" + f[1]);
            }
        }
        if (off.isEmpty()) return List.of();
        off.addFirst("-XX:+UnlockDiagnosticVMOptions");
        return List.copyOf(off);
    }

    /** How long the platform probe (a pull, then {@code java -version}) may take. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(300);

    /**
     * Read the platform probe: null when the base's JVM ran as {@code platform}, the reason when the
     * runtime cannot run that platform here (no emulation for it) or ran another platform's JVM. A
     * probe that failed for any other reason (a pull, a credential) is an error, not a skip.
     */
    static @Nullable String platformRefusal(String runtime, String platform, int exit, String output)
            throws IOException {
        String want = BaseJre.normalizeArch(platform.substring(platform.indexOf('/') + 1));
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (!t.startsWith("os.arch = ")) continue;
            String ran =
                    BaseJre.normalizeArch(t.substring("os.arch = ".length()).strip());
            if (ran.equals(want)) return null;
            return runtime + " ran the " + ran + " JVM when asked for " + platform
                    + ", so the cache would be trained for another platform than the image's";
        }
        if (output.toLowerCase(Locale.ROOT).contains("exec format error")) {
            return runtime + " on this host cannot run " + platform + " programs (exec format error)";
        }
        throw new IOException("[image] aot-cache: could not start the base image's java for " + platform + " (exit "
                + exit + "):\n" + AotTraining.tail(output));
    }

    /** The host's architecture when it is not {@code platform}'s: the training runs are emulated. */
    static @Nullable String emulatedOn(String platform) {
        String host = BaseJre.normalizeArch(System.getProperty("os.arch", ""));
        String want = BaseJre.normalizeArch(platform.substring(platform.indexOf('/') + 1));
        return host.equals(want) ? null : host;
    }

    /**
     * Stage the image's layout, train inside the base image, and verify the result loads. Returns
     * the cache file, or throws with the reason it could not be produced.
     *
     * @param workDir the module's staging root — {@code aot-train/} lives here and {@code jk clean}
     *     is welcome to it
     * @param cacheRoot jk's cache root: the extracted base JRE is 50–200 MB and is shared by every
     *     module that builds on the same base, so it belongs under the bound {@code
     *     CacheTier} declares for CacheTree.BASE_JRE, not in module build output nothing reclaims
     * @param auth the credential for the base image's registry — the JRE this trains with is
     *     extracted from that image, so a private base has to authenticate here too
     */
    static Result train(
            ImageBuilder.Plan plan,
            PlatformCheck check,
            Path workDir,
            Path cacheRoot,
            RegistryAuth auth,
            Consumer<String> log)
            throws IOException, InterruptedException {
        String blocked = unsupportedReason(plan);
        if (blocked != null) throw new IOException(blocked);

        String base = qualify(ImageBuilder.baseOf(plan.config()));
        Path localJre = localBaseJre(plan, base, cacheRoot, auth, log);
        Path staging = workDir.resolve("aot-train");

        // Boot nests its jars under BOOT-INF and loads them itself, so nothing useful reaches the
        // JVM classpath. Its own `jarmode extract` produces the shape that can be trained: a thin
        // launcher jar whose manifest Class-Path names lib/*.jar, relative.
        List<String> runArgs;
        if (plan.hasAppTree()) {
            // The packager already produced the runnable tree; train against a copy of exactly what
            // ships. Quarkus's fast-jar also keeps lib/main off the JVM classpath — its own loader
            // reads those — so the archive only has to agree about quarkus-run.jar and lib/boot.
            copyTree(plan.appTree(), staging);
            runArgs = List.of("-jar", plan.appTreeJar());
            log.accept("training against the packager's " + plan.appTree().getFileName() + " tree");
        } else if (BootLayout.isBootJar(plan.mainJar())) {
            Path extractTool = localJre != null ? localJre : hostJava();
            BootLayout.Extracted boot = BootLayout.extract(plan.mainJar(), staging, extractTool);
            runArgs = List.of("-jar", boot.launcherJar());
            log.accept("unpacked the Spring Boot jar for training: " + boot.launcherJar() + " + lib/");
        } else {
            stageLayout(plan, staging);
            runArgs = List.of("-cp", relativeClasspath(plan), plan.mainClass());
        }
        stamp(staging);
        // Snapshot what was staged before any training process runs: whatever the app writes
        // during record/assemble (logs, embedded-DB files) is not application content and must
        // not become image bytes.
        Set<String> stagedFiles = snapshotRelative(staging);

        // A container has to be addressable to be stopped; the local path signals the process
        // directly. Each run gets its own name so the training and verifying containers cannot
        // collide.
        String runtime = localJre == null ? containerRuntime(plan.config().dockerExecutable()) : null;
        Map<String, String> env = trainingEnvironment(plan.config());
        AotTraining.Launcher launcher;
        if (localJre != null) {
            String java = localJre.toString();
            launcher = new AotTraining.Launcher() {
                @Override
                public List<String> java(String runName) {
                    return List.of(java);
                }

                @Override
                public Path cwd() {
                    return staging;
                }

                @Override
                public Map<String, String> env() {
                    return env;
                }
            };
        } else {
            if (runtime == null) {
                throw new IOException(
                        "no docker, podman or nerdctl to train in, and " + base + "'s JVM does not run here");
            }
            launcher = containerLauncher(runtime, staging, base, platform(plan.config()), env);
        }
        String emulated = localJre == null ? emulatedOn(platform(plan.config())) : null;
        log.accept(
                localJre != null
                        ? "training the AOT cache with " + base + "'s JVM, on this host"
                        : "training the AOT cache in " + base + " for " + platform(plan.config())
                                + (emulated == null
                                        ? ""
                                        : ", emulated on this " + emulated + " host"
                                                + (check.noCode().isEmpty() ? "" : ", without AOT code")));

        Path cache = staging.resolve(CACHE_FILE);
        AotTraining.trainAotCache(
                launcher,
                // The same flags the entrypoint runs with.
                ImageBuilder.javaOpts(plan.config()),
                runArgs,
                new AotTraining.Target(CACHE_FILE, cache),
                new AotTraining.Target(CONFIG_FILE, staging.resolve(CONFIG_FILE)),
                localJre == null ? check.noCode() : List.of(),
                TRAIN_TIMEOUT,
                log);
        Files.setLastModifiedTime(cache, LAYER_TIME);
        log.accept("AOT cache verified (" + Files.size(cache) / (1024 * 1024) + " MiB)");
        return new Result(staging, runArgs, cache, stagedFiles);
    }

    private static Set<String> snapshotRelative(Path root) throws IOException {
        Set<String> out = new TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.toList()) {
                if (Files.isRegularFile(f)) {
                    out.add(root.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }

    /** The JVM running this worker — good enough to rewrite a jar with Boot's jarmode tool. */
    private static Path hostJava() {
        return JdkFingerprint.java(Path.of(System.getProperty("java.home")));
    }

    /**
     * The image's JVM, on this host, or null to fall back to running the image. Never fatal: the
     * container path produces the same cache, so a base image jk cannot unpack is a slower build
     * rather than a failed one.
     */
    static @Nullable Path localBaseJre(
            ImageBuilder.Plan plan, String base, Path cacheRoot, RegistryAuth auth, Consumer<String> log) {
        if (!BaseJre.hostCanExecute(plan.config().platforms())) return null;
        try {
            Path java = BaseJre.javaBinary(base, BaseJre.target(plan.config().platforms()), cacheRoot, auth);
            return java != null && PathUtil.isRunnable(java) ? java : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.accept("could not read " + base + "'s JVM, training in a container: " + e.getMessage());
            return null;
        }
    }

    /** Copy a tree verbatim — the staged copy is what gets trained and what ships. */
    private static void copyTree(Path from, Path to) throws IOException {
        PathUtil.copyTree(from, to, PathUtil.Copy.CLEAN_TARGET, PathUtil.Copy.OVERWRITE_ALWAYS);
    }

    /** Lay out exactly what the image will contain, at the paths the image will use. */
    private static void stageLayout(ImageBuilder.Plan plan, Path staging) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(staging);
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
        Collections.sort(libs);
        entries.addAll(libs);
        return String.join(":", entries);
    }

    /**
     * Add the implicit Docker Hub registry to a bare name. Jib resolves {@code bellsoft/x} as Docker
     * Hub by convention; podman refuses to guess and, with no TTY to prompt on, simply fails.
     */
    static String qualify(String image) {
        if (image.isBlank()) return image;
        int slash = image.indexOf('/');
        if (slash < 0) return "docker.io/library/" + image;
        String first = image.substring(0, slash);
        boolean isRegistry = first.contains(".") || first.contains(":") || first.equals("localhost");
        return isRegistry ? image : "docker.io/" + image;
    }

    /**
     * What every training run sees in its environment: the image's {@code env}, then
     * {@code [image.training-env]} over it — the settings that let the app start and settle without
     * its production dependencies (a database, a broker). The cache depends on the JVM's flags, which
     * stay the entrypoint's, never on these.
     */
    static Map<String, String> trainingEnvironment(ImageConfig config) {
        Map<String, String> env = new LinkedHashMap<>(config.env());
        env.putAll(config.trainingEnv());
        return env;
    }

    /**
     * {@code <runtime> run --rm --platform <p> -e … -v <staging>:/app -w /app <base>} — everything
     * before the java command. The platform is the image's: without it the runtime runs the host's
     * variant of a multi-platform base, whose JVM trains a cache the image's JVM refuses.
     */
    static List<String> containerPrefix(
            String runtime, Path staging, String base, String platform, String name, Map<String, String> env) {
        List<String> cmd = new ArrayList<>(List.of(runtime, "run", "--rm", "--platform", platform, "--name", name));
        // Rootful docker writes app.aot/app.aotconf into the bind mount as root:root — the
        // follow-up setLastModifiedTime/delete then fails AFTER a successful training run, and
        // the root-owned staging dir breaks the next build's cleanup. Rootless podman
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
        env.forEach((k, v) -> cmd.addAll(List.of("-e", k + "=" + v)));
        cmd.add(base);
        return cmd;
    }

    /** True for a docker CLI fronting a rootful daemon (a probe that fails or times out → rootful). */
    private static boolean rootfulDocker(String runtime) {
        return rootfulDocker(runtime, Duration.ofSeconds(5));
    }

    static boolean rootfulDocker(String runtime, Duration timeout) {
        String name = Path.of(runtime).getFileName().toString();
        if (!name.startsWith("docker")) return false;
        try {
            // The runtime is a PATH name; the daemon behind it may be stuck, so the answer is
            // read beside a deadline rather than to EOF.
            Process p = new ProcessBuilder(runtime, "info", "--format", "{{.SecurityOptions}}")
                    .redirectErrorStream(true)
                    .start();
            return BoundedRun.await(p, timeout)
                    .map(run -> !run.output().contains("rootless"))
                    .orElse(true);
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** {@code uid:gid} of the invoking user (owner of {@code probe}), or null off POSIX. */
    private static @Nullable String unixUserGroup(Path probe) {
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

    private static boolean isSelinux() {
        return Files.isDirectory(Path.of("/sys/fs/selinux"));
    }

    /** docker, podman or nerdctl — the configured one when set, else the first on PATH. */
    static @Nullable String containerRuntime(@Nullable String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        for (String candidate : List.of("docker", "podman", "nerdctl")) {
            if (onPath(candidate)) return candidate;
        }
        return null;
    }

    private static boolean onPath(String exe) {
        String path = ProcessEnv.get("PATH");
        if (path == null) return false;
        boolean windows = Os.isWindows();
        for (String dir : SearchPath.entries(path)) {
            Path dirPath = SearchPath.path(dir);
            if (dirPath == null) continue;
            Path base = dirPath.resolve(exe);
            if (PathUtil.isRunnable(base)) return true;
            // Windows PATHEXT: docker.exe / docker.cmd / docker.bat (chocolatey shims and corp
            // wrappers ship .bat), not a bare "docker" file — same launcher set as
            // JkLayoutPaths.isRunnableClient.
            if (windows
                    && (PathUtil.isRunnable(dirPath.resolve(exe + ".exe"))
                            || PathUtil.isRunnable(dirPath.resolve(exe + ".cmd"))
                            || PathUtil.isRunnable(dirPath.resolve(exe + ".bat")))) {
                return true;
            }
        }
        return false;
    }

    /** Runs in a fresh container of {@code base}; stopped and killed through the runtime, by name. */
    private static AotTraining.Launcher containerLauncher(
            String runtime, Path staging, String base, String platform, Map<String, String> env) {
        return new AotTraining.Launcher() {
            @Override
            public List<String> java(String runName) {
                List<String> cmd = new ArrayList<>(containerPrefix(runtime, staging, base, platform, runName, env));
                cmd.add("java");
                return cmd;
            }

            @Override
            public void requestStop(Process process, String runName, Duration grace) {
                AotCacheTrainer.requestStop(process, runtime, runName, grace);
            }

            @Override
            public void forceStop(String runName) {
                AotCacheTrainer.forceStop(runtime, runName);
            }
        };
    }

    /**
     * SIGTERM through the runtime: signalling the client that streams the container's output does
     * not reliably reach PID 1 inside.
     */
    private static void requestStop(Process process, String runtime, String containerName, Duration grace) {
        Process stopper = null;
        try {
            stopper = new ProcessBuilder(runtime, "stop", "--time", String.valueOf(grace.toSeconds()), containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            stopper.waitFor(grace.toSeconds(), TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            process.destroy(); // best effort: the forcible kill after it still bounds the wait
        } finally {
            if (stopper != null && stopper.isAlive()) stopper.destroyForcibly();
        }
    }

    /** An abandoned run: the container is killed, not given time to write its cache. */
    private static void forceStop(String runtime, String containerName) {
        Process killer = null;
        try {
            killer = new ProcessBuilder(runtime, "kill", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            killer.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (killer != null && killer.isAlive()) killer.destroyForcibly();
        }
    }
}
