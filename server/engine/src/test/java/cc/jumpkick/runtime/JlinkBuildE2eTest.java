// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: {@code [jlink]} builds {@code target/<name>-jri/} with the modules {@code jdeps}
 * finds, the application under {@code app/}, {@code bin/start}, and an AOT cache trained by
 * starting a server that never exits on its own; an unchanged rebuild relinks nothing, and an image
 * restored from the action cache still maps its cache.
 */
@Tag("integration")
class JlinkBuildE2eTest {

    @Test
    void the_runtime_image_links_trains_runs_and_restores_from_cache(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "the cache is trained by asking the application to stop");
        Path cache = TestCaches.dir("jlink-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);

        Labels first = new Labels();
        WorkspaceResult built = build(ws, cache, first);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();

        Path image = ws.resolve("app/target/app-jri");
        assertThat(Files.readString(image.resolve("release")))
                .as("jdeps found java.logging in the sibling jar")
                .contains("java.logging");
        assertThat(image.resolve("app/app.jar")).isRegularFile();
        assertThat(image.resolve("app/libs/lib-1.0.0.jar")).isRegularFile();
        assertThat(image.resolve("app/app.aot")).isRegularFile();
        assertThat(first.of("app", TaskNames.PACKAGE_JLINK))
                .startsWith("app-jri: ")
                .contains("AOT cache");
        assertThat(run(image.resolve("bin/start"), "once")).contains("hello from lib");
        assertThat(cacheRefusal(image)).isNull();

        Labels second = new Labels();
        assertThat(build(ws, cache, second).success()).isTrue();
        assertThat(second.of("app", TaskNames.PACKAGE_JLINK))
                .as("an unchanged rebuild does not link again")
                .doesNotStartWith("link")
                .doesNotStartWith("app-jri: ");

        PathUtil.deleteRecursivelyOrThrow(image);
        Labels third = new Labels();
        assertThat(build(ws, cache, third).success()).isTrue();
        assertThat(third.of("app", TaskNames.PACKAGE_JLINK))
                .as("a deleted image is restored from the action cache, not linked again")
                .isEqualTo("app-jri up-to-date");
        assertThat(image.resolve("bin/start")).isExecutable();
        assertThat(run(image.resolve("bin/start"), "once")).contains("hello from lib");
        assertThat(cacheRefusal(image))
                .as("a restored image still matches the cache it was trained with")
                .isNull();
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib/src/com/example"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(lib.resolve("Lib.java"), """
                package com.example;

                import java.util.logging.Logger;

                public final class Lib {
                    private Lib() {}

                    public static String text() {
                        Logger.getLogger("lib").fine("text");
                        return "hello from lib";
                    }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app/src/com/example/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [application]
                main = "com.example.app.Main"

                [jlink]
                args          = ["serve"]
                jvm-args      = ["-XX:+UseSerialGC"]
                start-timeout = 60

                [dependencies]
                lib = { workspace = true }
                """);
        Files.writeString(app.resolve("Main.java"), """
                package com.example.app;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) throws Exception {
                        System.out.println(com.example.Lib.text());
                        if (args.length > 0 && args[0].equals("serve")) {
                            System.out.println("serving");
                            Thread.sleep(Long.MAX_VALUE);
                        }
                    }
                }
                """);
        return ws;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String member : List.of("lib", "app")) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(member).resolve("jk-lock.toml"));
        }
    }

    private static WorkspaceResult build(Path ws, Path cache, Labels labels) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), labels);
    }

    /** Why the image's JVM refuses its cache, run the way bin/start runs it; null when it maps. */
    private static @Nullable String cacheRefusal(Path image) throws Exception {
        Path real = image.toRealPath();
        String log = run(
                real.resolve("bin/java"),
                "-XX:+UseSerialGC",
                "-Xlog:aot=info",
                "-XX:AOTCache=" + real.resolve("app/app.aot"),
                "-jar",
                real.resolve("app/app.jar").toString(),
                "once");
        assertThat(log).contains("Using AOT-linked classes");
        return AotCacheFiles.refusal(log);
    }

    private static String run(Path exe, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(exe.toString()));
        cmd.addAll(List.of(args));
        Processes.Captured run = Processes.capture(cmd, Duration.ofSeconds(60));
        String out = run.output().trim();
        assertThat(run.exit()).as(out).isZero();
        return out;
    }

    /** The last label each module's steps carried. */
    private static final class Labels implements WorkspaceBuildListener {
        private final Map<String, String> last = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    last.put(name + "/" + step, label);
                }
            };
        }

        String of(String module, String step) {
            return last.getOrDefault(module + "/" + step, "");
        }
    }
}
