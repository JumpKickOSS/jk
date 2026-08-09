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

    /** The cache's path inside the image, and the name the entrypoint refers to. */
    static final String CACHE_PATH = "/app/app.aot";

    private static final long TRAIN_TIMEOUT_SECONDS = 300;

    private AotCacheTrainer() {}

    /** Why an AOT cache cannot be trained for this image, or null when it can. */
    static String unsupportedReason(ImageBuilder.Plan plan) {
        if (plan.classesDir() != null) {
            return "the exploded-classes image layout cannot be trained — a CDS dump refuses any"
                    + " classpath entry that is a directory (JDK-8329980, Won't Fix). Package this"
                    + " module as a jar image layout to use [image] aot-cache";
        }
        if (containerRuntime(plan.config().dockerExecutable()) == null) {
            return "no container runtime found (docker, podman or nerdctl). The training run has to"
                    + " execute inside the base image, because the cache is only valid for the exact"
                    + " JVM build that produced it";
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
    static Path train(ImageBuilder.Plan plan, String classpath, Path workDir, Consumer<String> log)
            throws IOException, InterruptedException {
        String blocked = unsupportedReason(plan);
        if (blocked != null) throw new IOException(blocked);

        Path staging = workDir.resolve("aot-train");
        stageLayout(plan, staging);
        stamp(staging);

        String runtime = containerRuntime(plan.config().dockerExecutable());
        String base = qualify(plan.config().base());
        log.accept("training the AOT cache in " + base);

        List<String> train = new ArrayList<>(containerPrefix(runtime, staging, base));
        train.add("java");
        train.add("-XX:AOTCacheOutput=" + CACHE_PATH);
        // Boot exits once the context is up. Anything else has to terminate on its own; JEP 514
        // assembles the cache at exit, so a run that never ends produces nothing.
        if (isSpringBoot(plan)) train.add("-Dspring.context.exit=onRefresh");
        train.addAll(List.of("-cp", classpath, plan.mainClass()));

        Output trained = exec(train);
        Path cache = staging.resolve("app.aot");
        if (!Files.isRegularFile(cache)) {
            throw new IOException("the training run produced no cache. It has to be a run that exits —"
                    + " Spring Boot exits at context refresh, other applications must do so themselves.\n"
                    + "  command: " + String.join(" ", train) + "\n"
                    + tail(trained.text()));
        }
        Files.setLastModifiedTime(cache, LAYER_TIME);

        // Prove it loads before it becomes a layer. A rejected cache is silent at default log
        // level, so an unverified one is indistinguishable from a working one.
        List<String> verify = new ArrayList<>(containerPrefix(runtime, staging, base));
        verify.add("java");
        verify.add("-Xlog:aot=info");
        verify.add("-XX:AOTCache=" + CACHE_PATH);
        if (isSpringBoot(plan)) verify.add("-Dspring.context.exit=onRefresh");
        verify.addAll(List.of("-cp", classpath, plan.mainClass()));

        String refusal = refusal(exec(verify).text());
        if (refusal != null) {
            throw new IOException("the AOT cache was trained but the JVM refused it:\n  " + refusal);
        }
        log.accept("AOT cache verified (" + Files.size(cache) / (1024 * 1024) + " MiB)");
        return cache;
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
            Files.copy(jar, libs.resolve(jar.getFileName().toString()));
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
    static String explicitClasspath(ImageBuilder.Plan plan) {
        List<String> entries = new ArrayList<>();
        entries.add("/app/classpath/" + plan.mainJar().getFileName());
        List<String> libs = new ArrayList<>();
        for (Path jar : allDependencyJars(plan)) libs.add("/app/libs/" + jar.getFileName());
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
    private static List<String> containerPrefix(String runtime, Path staging, String base) {
        List<String> cmd = new ArrayList<>(List.of(runtime, "run", "--rm"));
        // :z relabels for SELinux and is meaningless (and rejected) elsewhere.
        String mount = staging.toAbsolutePath() + ":/app";
        cmd.addAll(List.of("-v", isSelinux() ? mount + ":z" : mount));
        cmd.addAll(List.of("-w", "/app"));
        cmd.add(base);
        return cmd;
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
    static String refusal(String log) {
        for (String line : log.split("\n")) {
            if (!line.contains("[aot")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("mismatch")
                    || lower.contains("failed")
                    || lower.contains("unable to")
                    || lower.contains("different version")) {
                return line.trim();
            }
        }
        return null;
    }

    private static boolean isSpringBoot(ImageBuilder.Plan plan) {
        return plan.mainClass() != null && plan.mainClass().startsWith("org.springframework.boot.loader.");
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

    private static Output exec(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(l -> out.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        if (!process.waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        reader.join(5_000);
        return new Output(out.toString(), process.exitValue());
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
