// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.AotTraining;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.Jlink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [jlink]} runtime image of an application: a runtime linked from the project JDK with
 * the modules {@code jdeps} finds in the application and its runtime jars, the application jar
 * with a {@code Class-Path} manifest and those jars under {@code app/}, a {@code bin/start}
 * launcher, and a startup cache trained by starting the image.
 *
 * <pre>
 * target/&lt;name&gt;-jri/
 *   bin/java, lib/, conf/, legal/, release    the linked runtime
 *   bin/start (bin/start.bat on Windows)      the launcher
 *   app/&lt;name&gt;.jar                          Main-Class and Class-Path: libs/…
 *   app/libs/                                 the runtime jars
 *   app/&lt;name&gt;.aot (or .jsa)                the startup cache
 * </pre>
 *
 * <p>A cache is only valid for the jars it was trained against, judged by size and modification
 * time, so every file of the image carries {@link #STAMP}: an image restored from the action cache,
 * or copied by a tool that keeps times, still matches its cache.
 */
public final class JlinkImage {

    /** The modification time of every file in an image. */
    public static final FileTime STAMP = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

    /** Where the application lives inside the image. */
    public static final String APP_DIR = "app";

    /** The module {@code bin/start --debug} needs: the JDWP agent, a few hundred KiB. */
    static final String DEBUG_AGENT = "jdk.jdwp.agent";

    private static final long TOOL_TIMEOUT_MINUTES = 10;

    private JlinkImage() {}

    /** The startup cache an image carries, as decided for its JDK. */
    public enum Cache {
        AOT(".aot"),
        CDS(".jsa"),
        NONE("");

        private final String suffix;

        Cache(String suffix) {
            this.suffix = suffix;
        }

        /** The JVM flag that loads {@code file}. */
        String flag(String file) {
            return this == AOT ? "-XX:AOTCache=" + file : "-XX:SharedArchiveFile=" + file;
        }
    }

    /**
     * What the image is built from.
     *
     * @param javaHome the project JDK: the runtime is linked from it, and {@code jdeps} reads the
     *     jars at its {@code release}
     * @param release the project's {@code java} level, {@code jdeps --multi-release}; 0 for the
     *     JDK's own
     * @param thinJar the module jar, copied in as {@code app/<appName>.jar}
     * @param libs the runtime jars and the names they ship under in {@code app/libs/}
     * @param mainClass written as {@code Main-Class} when the jar names none
     * @param image {@code target/<name>-jri/}, replaced whole
     * @param workDir scratch space: the training runs' working directory
     * @param env the whole environment of the image's starts at build time
     */
    public record Plan(
            Path javaHome,
            int release,
            Path thinJar,
            String appName,
            List<LibDirLayout.Lib> libs,
            String mainClass,
            Jlink config,
            Path image,
            Path workDir,
            Map<String, String> env) {}

    /** A built image: its JDK modules, its cache, and every file in it. */
    public record Result(List<String> modules, Cache cache, List<Path> files, long bytes) {}

    /**
     * The cache {@code config} asks for on a JDK of {@code feature}: an AOT cache on 25 and newer,
     * a CDS archive below; nothing on Windows, where a process cannot be asked to stop and write
     * its recording.
     */
    public static Cache cache(Jlink config, int feature) {
        if (config.aotCache() == Jlink.AotCache.OFF || Os.isWindows()) return Cache.NONE;
        if (config.aotCache() == Jlink.AotCache.CDS || feature < 25) return Cache.CDS;
        return Cache.AOT;
    }

    /** Link, lay out, write the launcher, stamp, and start the image to train it or prove it runs. */
    public static Result build(Plan plan, Consumer<String> log) throws IOException, InterruptedException {
        if (plan.mainClass().isEmpty()) {
            throw new IOException("[jlink] needs a main class to start the image: set [application] main");
        }
        Path jlink = JdkFingerprint.tool(plan.javaHome(), "jlink");
        Path jdeps = JdkFingerprint.tool(plan.javaHome(), "jdeps");
        if (!Files.isRegularFile(jlink) || !Files.isRegularFile(jdeps)) {
            throw new IOException("[jlink] links from the project JDK, and " + plan.javaHome()
                    + " has no bin/jlink or bin/jdeps (a JRE?); point the module at a full JDK");
        }
        int feature = JvmOptions.hostFeature(plan.javaHome());
        Cache cache = cache(plan.config(), feature);
        if (cache == Cache.NONE && plan.config().aotCache() != Jlink.AotCache.OFF) {
            log.accept("no startup cache: training asks the application to stop, which Windows cannot do politely");
        }

        PathUtil.deleteRecursivelyOrThrow(plan.image());
        Path parent = plan.image().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createDirectories(plan.workDir());

        // jdeps reads the jars where they already are; the layout copies them after the link.
        List<Path> classPath = plan.libs().stream().map(LibDirLayout.Lib::jar).toList();
        List<String> modules = modules(
                jdeps, plan.release(), plan.thinJar(), classPath, plan.config().modules(), plan.workDir());
        log.accept("linking " + String.join(",", modules));
        link(jlink, plan.javaHome(), modules, plan.config().stripDebug(), cache == Cache.CDS, plan.image());

        Path app = plan.image().resolve(APP_DIR);
        Path appJar = app.resolve(plan.appName() + ".jar");
        LibDirLayout.write(plan.thinJar(), appJar, app.resolve("libs"), "libs", plan.libs(), plan.mainClass());
        writeLauncher(plan, cache);
        stamp(plan.image());

        if (plan.config().testStart()) start(plan, cache, log);
        stamp(plan.image());
        List<Path> files = files(plan.image());
        long bytes = 0;
        for (Path f : files) bytes += Files.size(f);
        return new Result(modules, cache, files, bytes);
    }

    /**
     * The JDK modules the image links: what {@code jdeps --print-module-deps} finds in the jar and
     * its classpath, then {@code extra} ({@link #mergeModules}).
     */
    static List<String> modules(
            Path jdeps, int release, Path jar, List<Path> classPath, List<String> extra, Path workDir)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(jdeps.toString(), "--print-module-deps", "--ignore-missing-deps"));
        if (release > 0) cmd.addAll(List.of("--multi-release", Integer.toString(release)));
        if (!classPath.isEmpty()) {
            cmd.add("--class-path");
            cmd.add(Classpaths.join(classPath));
        }
        cmd.add(jar.toString());
        Tool out = runTool(cmd, workDir);
        if (out.exit() != 0) {
            throw new IOException("jdeps could not work out the JDK modules the application needs (exit " + out.exit()
                    + "):\n" + tail(out.text()) + "\n  jdeps reads every jar of app/libs at release " + release
                    + " with the project JDK; a jar it cannot parse fails the image");
        }
        return mergeModules(parseModuleDeps(out.text()), extra);
    }

    /** The last line of {@code jdeps --print-module-deps}: a comma-separated module list. */
    static List<String> parseModuleDeps(String output) {
        String last = "";
        for (String line : output.strip().split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("Warning") && !t.contains(" ")) last = t;
        }
        return Arrays.stream(last.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * {@code found}, then {@code extra}, then the JDWP agent {@code bin/start --debug} loads, without
     * repeats; {@code java.base} leads when jdeps found nothing.
     */
    static List<String> mergeModules(List<String> found, List<String> extra) {
        Set<String> all = new LinkedHashSet<>(found);
        all.addAll(extra);
        if (all.isEmpty()) all.add("java.base");
        all.add(DEBUG_AGENT);
        return List.copyOf(all);
    }

    private static void link(
            Path jlink, Path javaHome, List<String> modules, boolean stripDebug, boolean cdsBase, Path image)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                jlink.toString(), "--add-modules", String.join(",", modules), "--no-header-files", "--no-man-pages"));
        if (stripDebug) cmd.add("--strip-debug");
        // A dynamic CDS archive is layered on the runtime's own base archive, which jlink only writes when asked.
        if (cdsBase) cmd.add("--generate-cds-archive");
        cmd.addAll(List.of("--output", image.toString()));
        Tool out = runTool(cmd, null);
        if (out.exit() == 0) return;
        String text = out.text();
        String hint = "";
        if (!Files.isDirectory(javaHome.resolve("jmods"))) {
            hint = "\n  " + javaHome + " has no jmods/ directory, so jlink links from the run-time image itself,"
                    + " which works only for a JDK built with that capability (most JDK 24+ builds) and never"
                    + " links jdk.jlink. Point the module at a JDK that ships jmods/, or drop the module jlink"
                    + " refused from [jlink] modules";
        }
        throw new IOException("jlink failed (exit " + out.exit() + "):\n" + tail(text) + hint);
    }

    /** {@code bin/start} (or {@code bin/start.bat} on Windows), executable. */
    private static void writeLauncher(Plan plan, Cache cache) throws IOException {
        Path bin = plan.image().resolve("bin");
        Files.createDirectories(bin);
        String jar = plan.appName() + ".jar";
        String cacheFile = cache == Cache.NONE ? "" : plan.appName() + cache.suffix;
        if (Os.isWindows()) {
            Path bat = bin.resolve("start.bat");
            Files.writeString(bat, windowsLauncher(plan.config(), jar), StandardCharsets.UTF_8);
            return;
        }
        Path sh = bin.resolve("start");
        Files.writeString(sh, posixLauncher(plan.config(), jar, cache, cacheFile), StandardCharsets.UTF_8);
        sh.toFile().setExecutable(true, false);
    }

    /**
     * The POSIX launcher. It runs the image's own {@code java} on {@code app/<jar>} by absolute
     * path, which is how the cache was trained: the JVM accepts the whole image moved elsewhere, but
     * not the same jar named another way. {@code --debug} adds a JDWP agent and leaves the cache
     * out; {@code JAVA_OPTS} adds flags after the configured ones.
     */
    static String posixLauncher(Jlink config, String jar, Cache cache, String cacheFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("# Starts the application on the runtime image this script lives in.\n");
        sb.append("#   bin/start [--debug] [args...]\n");
        sb.append("# --debug listens for a debugger on JDWP_PORT (5005); JDWP_SUSPEND=y waits for it.\n");
        sb.append("# JAVA_OPTS adds JVM flags; a cache is only used under the flags it was trained with.\n");
        sb.append("DIR=$(cd \"$(dirname \"$0\")/..\" && pwd -P)\n");
        sb.append("DEBUG=\n");
        sb.append("if [ \"$1\" = \"--debug\" ]; then DEBUG=1; shift; fi\n");
        if (!config.args().isEmpty()) {
            sb.append("if [ $# -eq 0 ]; then set -- ")
                    .append(shellWords(config.args()))
                    .append("; fi\n");
        }
        sb.append("CACHE=\n");
        sb.append("JDWP=\n");
        sb.append("if [ -n \"$DEBUG\" ]; then\n");
        sb.append(
                "  JDWP=\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=${JDWP_SUSPEND:-n},address=*:${JDWP_PORT:-5005}\"\n");
        if (cache != Cache.NONE) {
            sb.append("elif [ -f \"$DIR/" + APP_DIR + "/" + cacheFile + "\" ]; then\n");
            sb.append("  CACHE=\"" + cache.flag("$DIR/" + APP_DIR + "/" + cacheFile) + "\"\n");
        }
        sb.append("fi\n");
        sb.append("# shellcheck disable=SC2086\n");
        sb.append("exec \"$DIR/bin/java\"");
        if (!config.jvmArgs().isEmpty()) sb.append(' ').append(shellWords(config.jvmArgs()));
        sb.append(" $JAVA_OPTS ${CACHE:+\"$CACHE\"} ${JDWP:+\"$JDWP\"} -jar \"$DIR/" + APP_DIR + "/" + jar
                + "\" \"$@\"\n");
        return sb.toString();
    }

    /** The Windows launcher: the same shape without a cache, which Windows does not train. */
    static String windowsLauncher(Jlink config, String jar) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("rem Starts the application on the runtime image this script lives in.\r\n");
        sb.append("rem   bin\\start.bat [--debug] [args...]\r\n");
        sb.append("setlocal\r\n");
        sb.append("set \"DIR=%~dp0..\"\r\n");
        sb.append("set \"JDWP=\"\r\n");
        sb.append("if \"%~1\"==\"--debug\" (\r\n");
        sb.append("  set \"JDWP=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005\"\r\n");
        sb.append("  shift\r\n");
        sb.append(")\r\n");
        sb.append("set \"ARGS=\"\r\n");
        sb.append(":collect\r\n");
        sb.append("if \"%~1\"==\"\" goto run\r\n");
        sb.append("set ARGS=%ARGS% %1\r\n");
        sb.append("shift\r\n");
        sb.append("goto collect\r\n");
        sb.append(":run\r\n");
        if (!config.args().isEmpty()) {
            sb.append("if not defined ARGS set ARGS=")
                    .append(batchWords(config.args()))
                    .append("\r\n");
        }
        sb.append("\"%DIR%\\bin\\java.exe\"");
        if (!config.jvmArgs().isEmpty()) sb.append(' ').append(batchWords(config.jvmArgs()));
        sb.append(" %JAVA_OPTS% %JDWP% -jar \"%DIR%\\" + APP_DIR + "\\" + jar + "\" %ARGS%\r\n");
        return sb.toString();
    }

    /** Each word single-quoted for {@code sh}. */
    static String shellWords(List<String> words) {
        List<String> quoted = new ArrayList<>();
        for (String w : words) quoted.add("'" + w.replace("'", "'\\''") + "'");
        return String.join(" ", quoted);
    }

    private static String batchWords(List<String> words) {
        List<String> quoted = new ArrayList<>();
        for (String w : words) quoted.add(w.contains(" ") ? "\"" + w + "\"" : w);
        return String.join(" ", quoted);
    }

    /**
     * Start the image as {@code bin/start} would. With a cache this is its training; without one it
     * is a start that proves the image runs. Either way a start that dies on its own fails the build.
     */
    private static void start(Plan plan, Cache cache, Consumer<String> log) throws IOException, InterruptedException {
        // The real path: the launcher resolves its directory with `pwd -P`, and the cache records
        // the jars by the path they were opened under.
        Path image = plan.image().toRealPath();
        Path app = image.resolve(APP_DIR);
        String java = JdkFingerprint.java(image).toString();
        List<String> runArgs = new ArrayList<>(
                List.of("-jar", app.resolve(plan.appName() + ".jar").toString()));
        runArgs.addAll(plan.config().args());
        Map<String, String> env = plan.env();
        Path cwd = plan.workDir();
        AotTraining.Launcher launcher = new AotTraining.Launcher() {
            @Override
            public List<String> java(String runName) {
                return List.of(java);
            }

            @Override
            public Path cwd() {
                return cwd;
            }

            @Override
            public Map<String, String> env() {
                return env;
            }

            @Override
            public boolean wholeEnv() {
                return true;
            }

            @Override
            public Process start(ProcessBuilder pb) throws IOException {
                Process process = pb.start();
                JobWorkers.register(process);
                return process;
            }
        };
        Duration timeout = Duration.ofSeconds(plan.config().startTimeoutSeconds());
        List<String> jvmArgs = plan.config().jvmArgs();
        switch (cache) {
            case AOT -> {
                log.accept("training the AOT cache: starting the image");
                Path file = app.resolve(plan.appName() + Cache.AOT.suffix);
                AotTraining.trainAotCache(
                        launcher,
                        jvmArgs,
                        runArgs,
                        AotTraining.Target.local(file),
                        AotTraining.Target.local(plan.workDir().resolve(plan.appName() + ".aotconf")),
                        timeout,
                        log);
            }
            case CDS -> {
                log.accept("training the CDS archive: starting the image");
                Path file = app.resolve(plan.appName() + Cache.CDS.suffix);
                AotTraining.trainCdsArchive(launcher, jvmArgs, runArgs, AotTraining.Target.local(file), timeout, log);
            }
            case NONE -> {
                log.accept("starting the image");
                List<String> cmd = new ArrayList<>(List.of(java));
                cmd.addAll(jvmArgs);
                cmd.addAll(runArgs);
                AotTraining.Run run = AotTraining.runUntilSettled(launcher, cmd, "jk-jlink-start", timeout, log);
                if (run.diedOnItsOwn()) {
                    throw new IOException("the image did not start (exit " + run.exit() + ").\n  command: "
                            + String.join(" ", cmd) + "\n" + AotTraining.tail(run.text())
                            + "\n  set [jlink] test-start = false (with aot-cache = false) to build it without"
                            + " starting it");
                }
            }
        }
    }

    /** Give every file under {@code image} the time {@link #STAMP}; see the class doc for why. */
    public static void stamp(Path image) throws IOException {
        for (Path f : files(image)) Files.setLastModifiedTime(f, STAMP);
    }

    /** Every regular file under {@code image}, links followed (the runtime links its legal notices). */
    public static List<Path> files(Path image) throws IOException {
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(image, (file, attrs) -> out.add(file));
        out.sort(null);
        return out;
    }

    private record Tool(int exit, String text) {}

    private static Tool runTool(List<String> cmd, @Nullable Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd.toFile());
        Process process = pb.start();
        JobWorkers.register(process);
        try {
            Processes.Captured c = Processes.capture(process, Duration.ofMinutes(TOOL_TIMEOUT_MINUTES));
            if (c.timedOut()) {
                return new Tool(-1, "timed out after " + TOOL_TIMEOUT_MINUTES + " minutes: " + String.join(" ", cmd));
            }
            return new Tool(c.exit(), c.output());
        } finally {
            JobWorkers.unregister(process);
        }
    }

    private static String tail(String text) {
        String[] lines = text.strip().split("\n");
        return "  " + String.join("\n  ", Arrays.asList(lines).subList(Math.max(0, lines.length - 15), lines.length));
    }
}
