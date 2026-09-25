// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Forks;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkInstallListener;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.resolver.StallWatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Reads a Gradle build's project model by running Gradle itself in a fork: the distribution the
 * wrapper names (else jk's default) is provisioned the way {@code jk gradle} provisions it — a
 * verified download, or a healthy install already on the machine — and launched on a JDK it can
 * run on, with the init script {@code jk-import-model.init.gradle} that writes the evaluated model
 * as JSON — a JDK provisioned first when none installed falls in the distribution's range. The
 * engine's heap holds none of Gradle; the fork's lines are the import's progress, and a fork whose
 * output stands still for the resolve stall window is stopped and refused with what it was doing,
 * as a lock's resolve is.
 */
public final class GradleModelQuery implements GradleBuildImport.ModelSource {

    /** The launcher command, the JDK it runs on, and the words the progress line uses for it. */
    public record Launch(List<String> command, @Nullable Path javaHome, String label) {}

    /** Provisions Gradle for {@code buildRoot} and says how to run it; {@code progress} hears what was provisioned. */
    public interface Launcher {
        Launch launch(Path buildRoot, Consumer<String> progress) throws IOException, InterruptedException;
    }

    /** The init script, on the engine's classpath. */
    static final String INIT_SCRIPT = "/cc/jumpkick/gradle/jk-import-model.init.gradle";

    /** The task the init script registers on the root project. */
    static final String MODEL_TASK = ":jkImportModel";

    /** How many of the fork's last lines the failure message may quote. */
    private static final int TAIL_LINES = 40;

    private final Launcher launcher;
    private final Path tmpDir;
    private final Clock clock;
    private final long stallWindowMs;

    GradleModelQuery(Launcher launcher, Path tmpDir, Clock clock, long stallWindowMs) {
        this.launcher = launcher;
        this.tmpDir = tmpDir;
        this.clock = clock;
        this.stallWindowMs = stallWindowMs;
    }

    /**
     * The query the engine runs: Gradle provisioned under {@code toolsRoot} through {@code http},
     * scratch files under {@code tmpDir}, the stall window from {@code JK_RESOLVE_TIMEOUT_MS}.
     */
    public static GradleModelQuery provisioning(
            Path toolsRoot, Http http, ToolProvisioning.Policy policy, Path tmpDir) {
        return new GradleModelQuery(
                provisioningLauncher(toolsRoot, http, policy), tmpDir, Clock.SYSTEM, StallWatch.envWindowMs());
    }

    private static Launcher provisioningLauncher(Path toolsRoot, Http http, ToolProvisioning.Policy policy) {
        return (buildRoot, progress) -> {
            ToolDistribution dist = new GradleResolver().resolve(buildRoot);
            ToolProvisioning.Result result =
                    ToolProvisioning.provision(dist, new ToolRegistry(toolsRoot.toAbsolutePath()), http, policy);
            if (result.source() != ToolProvisioning.Result.Source.CACHED) {
                progress.accept("Gradle " + dist.version() + " "
                        + result.source().name().toLowerCase(Locale.ROOT)
                        + (result.verification().isEmpty() ? "" : " · " + result.verification()));
            }
            Path runningHome = JavaHomes.runningJavaHome();
            GradleJvmCompatibility.Pick jdk = GradleJvmCompatibility.pick(
                    dist.version(),
                    runningHome,
                    Runtime.version().feature(),
                    () -> new JdkRegistry().listHits(),
                    major -> installJdk(major, dist.version(), progress));
            return new Launch(
                    List.of(result.tool().binary().toString()),
                    jdk.javaHome(),
                    "Gradle " + dist.version() + " on JDK " + jdk.major());
        };
    }

    /**
     * Provision JDK {@code major} for Gradle {@code gradleVersion} into the managed JDK root, as a
     * manifest's {@code jdk} pin is provisioned; the download and extract are progress lines.
     */
    private static Path installJdk(int major, String gradleVersion, Consumer<String> progress)
            throws IOException, InterruptedException {
        progress.accept("Gradle " + gradleVersion + " runs on no installed JDK · provisioning JDK " + major);
        InstalledJdk installed = JdkEnsure.install(String.valueOf(major), progress, new JdkInstallListener() {
            @Override
            public void onDownloadStart(String label, long totalBytes) {
                progress.accept("downloading " + label + (totalBytes > 0 ? " (" + (totalBytes >> 20) + " MiB)" : ""));
            }

            @Override
            public void onExtractStart(String label) {
                progress.accept("installing " + label);
            }

            @Override
            public void onInstalled(InstalledJdk jdk) {
                progress.accept("JDK " + major + " installed at " + jdk.home());
            }
        });
        return installed.home();
    }

    @Override
    public String read(Path buildRoot, List<String> pluginIds, Consumer<String> progress) throws IOException {
        Launch launch;
        try {
            launch = launcher.launch(buildRoot, progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while provisioning Gradle", e);
        }
        Files.createDirectories(tmpDir);
        Path work = Files.createTempDirectory(tmpDir, "jk-gradle-import-");
        try {
            Path init = work.resolve("jk-import-model.init.gradle");
            try (InputStream in = GradleModelQuery.class.getResourceAsStream(INIT_SCRIPT)) {
                if (in == null) throw new IOException("init script " + INIT_SCRIPT + " is not on the classpath");
                Files.copy(in, init, StandardCopyOption.REPLACE_EXISTING);
            }
            Path out = work.resolve("model.json");
            List<String> command = new ArrayList<>(launch.command());
            command.addAll(List.of(
                    "--init-script",
                    init.toString(),
                    "--no-daemon",
                    "--console=plain",
                    "-Dorg.gradle.configuration-cache=false",
                    "-Dorg.gradle.configureondemand=false",
                    "-Dorg.gradle.welcome=never",
                    "-Pjk.import.out=" + out,
                    "-Pjk.import.plugins=" + String.join(",", pluginIds),
                    MODEL_TASK));
            progress.accept(launch.label() + " · evaluating " + buildRoot.getFileName());
            ProcessBuilder pb =
                    new ProcessBuilder(command).directory(buildRoot.toFile()).redirectErrorStream(true);
            PassthroughEnv.apply(pb.environment(), launch.javaHome());
            Run run = run(pb, buildRoot, launch.label());
            if (run.exit() != 0) {
                throw new IOException("Gradle exited " + run.exit() + " evaluating " + buildRoot + " (" + launch.label()
                        + "): " + run.failure());
            }
            if (!Files.isRegularFile(out)) {
                throw new IOException(
                        "Gradle finished without writing the project model of " + buildRoot + ": " + run.failure());
            }
            return Files.readString(out, StandardCharsets.UTF_8);
        } finally {
            PathUtil.deleteRecursively(work);
        }
    }

    /** The fork's exit status and the tail of what it printed. */
    private record Run(int exit, List<String> tail) {
        /** Gradle's {@code * What went wrong:} section when it printed one, else the last lines. */
        String failure() {
            int from = -1;
            int to = tail.size();
            for (int i = 0; i < tail.size(); i++) {
                if (tail.get(i).startsWith("* What went wrong")) from = i + 1;
                else if (from >= 0 && tail.get(i).startsWith("* Try:")) {
                    to = i;
                    break;
                }
            }
            List<String> lines =
                    from >= 0 ? tail.subList(from, to) : tail.subList(Math.max(0, tail.size() - 8), tail.size());
            return String.join(
                    " ",
                    lines.stream().map(String::trim).filter(l -> !l.isEmpty()).toList());
        }
    }

    /** Output pump state: the fork's line count and the moment it last advanced, read by the wait loop. */
    private static final class Output {
        private final Deque<String> tail = new ArrayDeque<>();
        private volatile long lastAdvanceNanos;
        private volatile String lastLine = "";

        synchronized void line(String line, long nowNanos) {
            if (tail.size() == TAIL_LINES) tail.removeFirst();
            tail.addLast(line);
            lastLine = line;
            lastAdvanceNanos = nowNanos;
        }

        synchronized List<String> lines() {
            return new ArrayList<>(tail);
        }
    }

    /**
     * Run the fork to its end, or destroy it when the request is cancelled or its output has stood
     * still for the stall window — that refusal opens with {@link PomImporter#BUDGET_EXCEEDED} and
     * names the build and the last line the fork printed.
     */
    private Run run(ProcessBuilder pb, Path buildRoot, String label) throws IOException {
        Process process = Forks.start(pb);
        Output output = new Output();
        output.lastAdvanceNanos = clock.nanos();
        Thread pump = SessionContext.startPlatform("jk-gradle-import-pump", () -> pump(process, output));
        long windowNanos = stallWindowMs <= 0 ? 0L : TimeUnit.MILLISECONDS.toNanos(stallWindowMs);
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (SessionContext.current().cancelled()) {
                    process.destroyForcibly();
                    throw new IOException("import cancelled while Gradle was evaluating " + buildRoot);
                }
                long still = clock.nanos() - output.lastAdvanceNanos;
                if (windowNanos > 0 && still > windowNanos) {
                    process.destroyForcibly();
                    String last = output.lastLine;
                    throw new IOException(PomImporter.BUDGET_EXCEEDED + "no Gradle output for "
                            + TimeUnit.NANOSECONDS.toSeconds(still) + " s while evaluating " + buildRoot + " (" + label
                            + ")" + (last.isEmpty() ? "" : "; the last line was `" + last.trim() + "`")
                            + ". JK_RESOLVE_TIMEOUT_MS widens the window.");
                }
            }
            pump.join(TimeUnit.SECONDS.toMillis(5));
            return new Run(process.exitValue(), output.lines());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while Gradle was evaluating " + buildRoot, e);
        }
    }

    private void pump(Process process, Output output) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.line(line, clock.nanos());
        } catch (IOException ignored) {
            // The fork closed its end; the wait loop has the exit status.
        }
    }
}
