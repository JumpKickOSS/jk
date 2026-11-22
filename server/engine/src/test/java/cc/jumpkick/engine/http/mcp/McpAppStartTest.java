// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run(action=start)}'s whole start: the build {@code jk run} runs (tests skipped), the run plan's command
 * started with its output in {@code target/jk-app.log}, the wait for the port the app logs, then
 * that log read back, a restart, and a stop.
 */
class McpAppStartTest {

    private static final long JID = 45L;

    private static final String APP = """
            package com.example;
            import java.net.*;
            public class App {
                public static void main(String[] a) throws Exception {
                    try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                        System.out.println("Netty started on port " + s.getLocalPort());
                        System.out.println("GET /orders/7 req=a1 served");
                        System.out.flush();
                        while (true) s.accept().close();
                    }
                }
            }
            """;

    @TempDir
    Path dir;

    private final List<JobSpec> builds = new ArrayList<>();

    private @Nullable McpContext ctx;

    @AfterEach
    void stopEverything() {
        McpContext c = ctx;
        if (c != null) c.apps().stopAll();
    }

    @Test
    void start_builds_runs_and_waits_for_the_port_then_logs_restart_and_stop() throws Exception {
        Path project = project();
        McpContext ctx = context(project);
        this.ctx = ctx;
        Map<String, Object> dirArg = Map.of("dir", project.toString());

        String started = text(McpAppRuns.start(new McpCall(ctx, dirArg, null)));

        assertThat(builds).singleElement().satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo("build");
            assertThat(spec.skipTests()).isTrue();
        });
        assertThat(started).startsWith("READY app shop pid ").contains(" · http://localhost:");
        McpApp first = requireNonNull(ctx.apps().in(McpHistoryViews.dirKey(project.toString())));
        assertThat(first.alive()).isTrue();
        assertThat(project.resolve(McpAppRuns.LOG)).content().contains("Netty started on port");

        String logs =
                text(McpAppRuns.logs(new McpCall(ctx, Map.of("dir", project.toString(), "request", "req=a1"), null)));
        assertThat(logs)
                .startsWith("LOG app shop · running pid " + first.process().pid() + "\n"
                        + "== stdout and stderr · 1 line for req=a1\n"
                        + "GET /orders/7 req=a1 served\n")
                .endsWith("== logs/app.log · 1 line for req=a1\n" + "INFO order 7 req=a1 from the file\n")
                .doesNotContain("Netty", "req=b2");
        String regex = text(
                McpAppRuns.logs(new McpCall(ctx, Map.of("dir", project.toString(), "request", "/req=a\\d s/"), null)));
        assertThat(regex).contains("== stdout and stderr · 1 line for /req=a\\d s/\nGET /orders/7 req=a1 served\n");
        String named = text(
                McpAppRuns.logs(new McpCall(ctx, Map.of("dir", project.toString(), "log", "logs/other.log"), null)));
        assertThat(named).contains("== logs/other.log · no file yet\n").doesNotContain("logs/app.log");

        String again = text(McpAppRuns.start(new McpCall(ctx, dirArg, null)));
        assertThat(again).startsWith("READY app shop");
        assertThat(first.alive()).as("a second start restarts the app").isFalse();

        McpApp second = requireNonNull(ctx.apps().in(McpHistoryViews.dirKey(project.toString())));
        String stopped = text(McpAppRuns.stop(new McpCall(ctx, dirArg, null)));
        assertThat(stopped)
                .startsWith("STOPPED app shop pid " + second.process().pid() + " · ran ");
        assertThat(second.alive()).isFalse();
        assertThat(text(McpAppRuns.stop(new McpCall(ctx, dirArg, null)))).isEqualTo("no app running in shop\n");
        assertThat(ctx.apps().rows()).isEmpty();
    }

    /** A project whose packaged jar is the app above. */
    private Path project() throws IOException {
        Path project = Files.createDirectories(dir.resolve("shop"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "shop"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.App"

                [run]
                log = "logs/app.log"
                """);
        Files.createDirectories(project.resolve("logs"));
        Files.writeString(project.resolve("logs/app.log"), "INFO order 7 req=a1 from the file\nINFO health req=b2\n");
        Path src = Files.createDirectories(project.resolve("src/main/java/com/example"))
                .resolve("App.java");
        Files.writeString(src, APP);
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "-d", classes.toString(), src.toString()))
                .isZero();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.App");
        Path jar = project.resolve("target/shop-0.1.0-SNAPSHOT.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("com/example/App.class"));
            out.write(Files.readAllBytes(classes.resolve("com/example/App.class")));
            out.closeEntry();
        }
        return project;
    }

    private McpContext context(Path project) {
        String finished = "{\"id\":\"r9\",\"buildNumber\":9,\"kind\":\"build\",\"dir\":\""
                + project.toString().replace("\\", "\\\\")
                + "\",\"projectId\":\"p\",\"success\":true,\"exitCode\":0,\"millis\":10,\"coord\":\"com.example:shop\","
                + "\"requestId\":45,\"startedAt\":" + System.currentTimeMillis()
                + ",\"modules\":[],\"diagnostics\":[]}";
        McpContext c = new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        builds.add(spec);
                        return JID;
                    }

                    @Override
                    public boolean cancel(long requestId) {
                        return false;
                    }

                    @Override
                    public int cancelDir(String d) {
                        return 0;
                    }
                },
                d -> Map.of(),
                List::of,
                "0",
                null,
                List::of,
                null,
                jid -> jid == JID ? finished : null);
        c.journalSettleMs(50);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return String.valueOf(requireNonNull(content).getFirst().get("text"));
    }
}
