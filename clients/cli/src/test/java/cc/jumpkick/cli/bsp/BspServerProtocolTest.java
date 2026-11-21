// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the server answers, held to the Build Server Protocol: source and output items that are
 * directories say so, a target names the workspace modules it depends on, every diagnostics
 * notification carries {@code reset} and a fixed file is cleared, and a slow model fetch on the
 * reader never holds up a frame the job worker has to write.
 */
class BspServerProtocolTest {

    /** An initialized server declares this process a BSP session; later tests in this JVM are not one. */
    @AfterEach
    void forgetTheDeclaredSession() {
        System.clearProperty("jk.build.trigger");
        System.clearProperty("jk.build.session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void source_and_output_items_of_a_module_are_directories(@TempDir Path dir) throws Exception {
        Path app = module(dir, "app");
        Files.createDirectories(app.resolve("src/main/java"));
        Files.createDirectories(app.resolve("src/test/java"));
        IdeEngineClient ide = fake(dir, model(dir, List.of("app"), List.of()));

        List<Map<String, Object>> frames = serve(
                ide,
                request(2, "buildTarget/sources", target(dir, "app")),
                request(3, "buildTarget/outputPaths", target(dir, "app")));

        Map<String, Object> sources = itemOf(result(frames, 2));
        List<Map<String, Object>> items = (List<Map<String, Object>>) sources.get("sources");
        assertThat(items).hasSizeGreaterThanOrEqualTo(2);
        assertThat(items)
                .as("SourceItemKind 2 is a directory; 1 would be a file")
                .allSatisfy(item -> assertThat(item).containsEntry("kind", 2L));
        Map<String, Object> outputs = itemOf(result(frames, 3));
        List<Map<String, Object>> paths = (List<Map<String, Object>>) outputs.get("outputPaths");
        assertThat(paths).hasSize(2);
        assertThat(paths).as("OutputPathItemKind 2 is a directory").allSatisfy(item -> assertThat(item)
                .containsEntry("kind", 2L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_module_target_names_the_workspace_modules_it_depends_on(@TempDir Path dir) throws Exception {
        module(dir, "app");
        module(dir, "core");
        module(dir, "testkit");
        IdeEngineClient ide = fake(
                dir,
                model(
                        dir,
                        List.of("app", "core", "testkit"),
                        List.of("0|core|COMPILE", "0|testkit|TEST", "1|testkit|TEST_KIND")));

        List<Map<String, Object>> frames = serve(ide, request(2, "workspace/buildTargets", "{}"));

        List<Map<String, Object>> targets =
                (List<Map<String, Object>>) requireNonNull(result(frames, 2).get("targets"));
        String root = BspServer.pathUri(dir);
        assertThat(dependencies(targets, root + "#app")).containsExactly(root + "#core", root + "#testkit");
        assertThat(dependencies(targets, root + "#core")).containsExactly(root + "#testkit");
        assertThat(dependencies(targets, root + "#testkit")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_target_s_dependency_modules_and_sources_are_its_own_libraries(@TempDir Path dir) throws Exception {
        module(dir, "app");
        module(dir, "core");
        String model = "{"
                + array(
                        "moduleDirs",
                        List.of(
                                dir.resolve("app").toString(),
                                dir.resolve("core").toString()))
                + "," + array("names", List.of("app", "core"))
                + "," + array("libNames", List.of("g:a:jar::1", "g:b:jar::1"))
                + "," + array("libJars", List.of("/cache/a.jar", "/cache/b.jar"))
                + "," + array("libSources", List.of("/cache/a-sources.jar", "/cache/b-sources.jar"))
                + "," + array("libEntries", List.of("0|g:a:jar::1|MAIN", "1|g:b:jar::1|MAIN")) + "}";
        IdeEngineClient ide = fake(dir, IdeWireModel.decode(model));

        List<Map<String, Object>> frames = serve(
                ide,
                request(2, "buildTarget/dependencyModules", target(dir, "core")),
                request(3, "buildTarget/dependencySources", target(dir, "core")));

        List<Map<String, Object>> modules = (List<Map<String, Object>>)
                requireNonNull(itemOf(result(frames, 2)).get("modules"));
        assertThat(modules).extracting(m -> m.get("name")).containsExactly("g:b:jar::1");
        assertThat((List<Object>) itemOf(result(frames, 3)).get("sources"))
                .containsExactly(BspServer.pathUri(Path.of("/cache/b-sources.jar")));
    }

    @Test
    void a_workspace_s_build_targets_come_from_the_model_alone(@TempDir Path dir) throws Exception {
        module(dir, "app");
        IdeWireModel model = model(dir, List.of("app"), List.of());
        AtomicInteger summaries = new AtomicInteger();
        IdeEngineClient ide = new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                return model;
            }

            @Override
            public ProjectInfo projectInfo() {
                summaries.incrementAndGet();
                return ProjectInfo.decode("{}");
            }
        };

        List<Map<String, Object>> frames = serve(ide, request(2, "workspace/buildTargets", "{}"));

        assertThat(result(frames, 2).get("targets")).asList().hasSize(1);
        assertThat(summaries)
                .as("the model names every module; no project summary is asked for")
                .hasValue(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_diagnostics_notification_resets_and_a_fixed_file_is_cleared(@TempDir Path dir) throws Exception {
        module(dir, "app");
        Path source = Files.createDirectories(dir.resolve("app/src/main/java")).resolve("App.java");
        Files.writeString(source, "class App {}\n");
        AtomicInteger compiles = new AtomicInteger();
        IdeEngineClient ide = new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                return model(dir, List.of("app"), List.of());
            }

            @Override
            public BuildOutcome buildModule(@Nullable Path moduleDir, @Nullable BuildListener listener) {
                if (compiles.incrementAndGet() == 1) {
                    return new BuildOutcome(false, 1, 1, List.of(source + ":1: error: cannot find symbol"), List.of());
                }
                return new BuildOutcome(true, 1, 0, List.of(), List.of());
            }
        };

        List<Map<String, Object>> frames = serve(
                ide,
                request(2, "buildTarget/compile", target(dir, "app")),
                request(3, "buildTarget/compile", target(dir, "app")));

        List<Map<String, Object>> published = frames.stream()
                .filter(f -> "build/publishDiagnostics".equals(f.get("method")))
                .map(f -> (Map<String, Object>) requireNonNull(f.get("params")))
                .toList();
        assertThat(published).hasSize(2);
        assertThat(published).allSatisfy(p -> assertThat(p).containsEntry("reset", true));
        String file = BspServer.pathUri(source);
        assertThat((Map<String, Object>) published.get(0).get("textDocument")).containsEntry("uri", file);
        assertThat((List<Object>) published.get(0).get("diagnostics")).hasSize(1);
        assertThat((Map<String, Object>) published.get(1).get("textDocument"))
                .as("the green compile clears the file the red one marked")
                .containsEntry("uri", file);
        assertThat((Map<String, Object>) published.get(1).get("buildTarget"))
                .containsEntry("uri", BspServer.pathUri(dir) + "#app");
        assertThat((List<Object>) published.get(1).get("diagnostics")).isEmpty();
    }

    /**
     * A compile of two targets builds both, and each file's diagnostics go to the target whose
     * module holds it; a later compile of one of them clears that one's file under that target.
     */
    @Test
    @SuppressWarnings("unchecked")
    void each_file_s_diagnostics_go_to_the_target_that_holds_it(@TempDir Path dir) throws Exception {
        module(dir, "app");
        module(dir, "core");
        Path app = Files.createDirectories(dir.resolve("app/src/main/java")).resolve("App.java");
        Path core = Files.createDirectories(dir.resolve("core/src/main/java")).resolve("Core.java");
        Files.writeString(app, "class App {}\n");
        Files.writeString(core, "class Core {}\n");
        AtomicInteger compiles = new AtomicInteger();
        IdeEngineClient ide = new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                return model(dir, List.of("app", "core"), List.of());
            }

            @Override
            public BuildOutcome buildModule(@Nullable Path moduleDir, @Nullable BuildListener listener) {
                if (compiles.incrementAndGet() == 1) {
                    return new BuildOutcome(
                            false,
                            2,
                            2,
                            List.of(app + ":1: error: cannot find symbol", core + ":1: error: cannot find symbol"),
                            List.of());
                }
                return new BuildOutcome(true, 1, 0, List.of(), List.of());
            }
        };
        String root = BspServer.pathUri(dir);
        String both = "{\"targets\":[{\"uri\":\"" + root + "#app\"},{\"uri\":\"" + root + "#core\"}]}";

        List<Map<String, Object>> frames = serve(
                ide, request(2, "buildTarget/compile", both), request(3, "buildTarget/compile", target(dir, "core")));

        List<Map<String, Object>> published = frames.stream()
                .filter(f -> "build/publishDiagnostics".equals(f.get("method")))
                .map(f -> (Map<String, Object>) requireNonNull(f.get("params")))
                .toList();
        assertThat(published).hasSize(3);
        assertThat(published.subList(0, 2))
                .extracting(p -> ((Map<String, Object>) requireNonNull(p.get("textDocument"))).get("uri") + " "
                        + ((Map<String, Object>) requireNonNull(p.get("buildTarget"))).get("uri"))
                .containsExactlyInAnyOrder(
                        BspServer.pathUri(app) + " " + root + "#app", BspServer.pathUri(core) + " " + root + "#core");
        Map<String, Object> cleared = published.get(2);
        assertThat((Map<String, Object>) cleared.get("textDocument"))
                .as("compiling core alone clears the file core diagnosed")
                .containsEntry("uri", BspServer.pathUri(core));
        assertThat((Map<String, Object>) cleared.get("buildTarget")).containsEntry("uri", root + "#core");
        assertThat((List<Object>) cleared.get("diagnostics")).isEmpty();
    }

    /**
     * A module's compile builds the modules it depends on: an error in one is that module's, and
     * the module's next green compile clears it there.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_dependency_s_diagnostics_are_its_own_and_its_dependent_s_compile_clears_them(@TempDir Path dir)
            throws Exception {
        module(dir, "app");
        module(dir, "core");
        Path core = Files.createDirectories(dir.resolve("core/src/main/java")).resolve("Core.java");
        Files.writeString(core, "class Core {}\n");
        AtomicInteger compiles = new AtomicInteger();
        IdeEngineClient ide = new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                return model(dir, List.of("app", "core"), List.of("0|core|COMPILE"));
            }

            @Override
            public BuildOutcome buildModule(@Nullable Path moduleDir, @Nullable BuildListener listener) {
                if (compiles.incrementAndGet() == 1) {
                    return new BuildOutcome(false, 2, 1, List.of(core + ":1: error: cannot find symbol"), List.of());
                }
                return new BuildOutcome(true, 2, 0, List.of(), List.of());
            }
        };

        List<Map<String, Object>> frames = serve(
                ide,
                request(2, "buildTarget/compile", target(dir, "app")),
                request(3, "buildTarget/compile", target(dir, "app")));

        List<Map<String, Object>> published = frames.stream()
                .filter(f -> "build/publishDiagnostics".equals(f.get("method")))
                .map(f -> (Map<String, Object>) requireNonNull(f.get("params")))
                .toList();
        assertThat(published).hasSize(2);
        String coreTarget = BspServer.pathUri(dir) + "#core";
        assertThat(published).allSatisfy(p -> {
            assertThat((Map<String, Object>) p.get("textDocument")).containsEntry("uri", BspServer.pathUri(core));
            assertThat((Map<String, Object>) p.get("buildTarget")).containsEntry("uri", coreTarget);
        });
        assertThat((List<Object>) published.get(0).get("diagnostics")).hasSize(1);
        assertThat((List<Object>) published.get(1).get("diagnostics")).isEmpty();
    }

    /**
     * A compile finishing on the worker writes its reply while the reader is still inside a slow
     * model fetch for another request: the frame writer and the model cache do not share a lock.
     */
    @Test
    void a_slow_model_fetch_does_not_hold_up_a_finished_jobs_reply(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "group = \"t\"\nname = \"t\"\nversion = \"0.0.1\"\njava = 25\n");
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch finishCompile = new CountDownLatch(1);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch finishFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        IdeEngineClient ide = new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                if (fetches.incrementAndGet() > 1) {
                    fetching.countDown();
                    await(finishFetch);
                }
                return IdeWireModel.decode("{}");
            }

            @Override
            public BuildOutcome buildModule(@Nullable Path moduleDir, @Nullable BuildListener listener) {
                compiling.countDown();
                await(finishCompile);
                return new BuildOutcome(true, 1, 0, List.of());
            }
        };
        PipedOutputStream client = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(client, 1 << 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread server = Thread.ofPlatform().daemon().start(() -> {
            try {
                new BspServer(ide, serverIn, out, Optional::empty).serve();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        try (client) {
            send(
                    client,
                    frame(request(1, "build/initialize", "{}"))
                            + frame(request(2, "buildTarget/compile", "{\"targets\":[{\"uri\":\"file:///x#root\"}]}")));
            assertThat(compiling.await(10, TimeUnit.SECONDS)).isTrue();
            send(
                    client,
                    frame(request(3, "workspace/reload", "null")) + frame(request(4, "buildTarget/sources", "{}")));
            assertThat(fetching.await(10, TimeUnit.SECONDS))
                    .as("the reader is inside the model fetch")
                    .isTrue();
            finishCompile.countDown();
            assertThat(awaitFrame(out, "\"id\":2", 5))
                    .as("the compile's reply is written while the fetch is still running")
                    .isTrue();
            finishFetch.countDown();
            assertThat(awaitFrame(out, "\"id\":4", 10)).isTrue();
            send(
                    client,
                    frame(request(5, "build/shutdown", "null"))
                            + frame("{\"jsonrpc\":\"2.0\",\"method\":\"build/exit\"}"));
        } finally {
            finishCompile.countDown();
            finishFetch.countDown();
        }
        server.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(server.isAlive()).isFalse();
    }

    private static Path module(Path ws, String name) throws IOException {
        Path dir = Files.createDirectories(ws.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), "name = \"" + name + "\"\nversion = \"0.0.1\"\njava = 25\n");
        return dir;
    }

    /** A workspace model of {@code names}, one module directory each under {@code ws}. */
    private static IdeWireModel model(Path ws, List<String> names, List<String> siblingRefs) {
        List<String> dirs = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        List<String> testClasses = new ArrayList<>();
        for (String name : names) {
            dirs.add(ws.resolve(name).toString());
            classes.add(ws.resolve(name + "/target/classes").toString());
            testClasses.add(ws.resolve(name + "/target/test-classes").toString());
        }
        return IdeWireModel.decode("{" + array("moduleDirs", dirs) + "," + array("names", names) + ","
                + array("classesDirs", classes) + "," + array("testClassesDirs", testClasses) + ","
                + array("siblingRefs", siblingRefs) + "}");
    }

    private static String array(String key, List<String> values) {
        return "\"" + key + "\":" + MiniJson.write(values);
    }

    private static IdeEngineClient fake(Path dir, IdeWireModel model) {
        return new IdeEngineClient(dir, dir.resolve("cache"), null) {
            @Override
            public IdeWireModel ideModel() {
                return model;
            }

            @Override
            public ProjectInfo projectInfo() {
                return ProjectInfo.decode("{}");
            }
        };
    }

    /** One session: initialize, {@code requests}, shutdown and exit; every frame the server wrote, parsed. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> serve(IdeEngineClient ide, String... requests) throws IOException {
        StringBuilder session = new StringBuilder(frame(request(1, "build/initialize", "{}")));
        for (String r : requests) session.append(frame(r));
        session.append(frame(request(99, "build/shutdown", "null")));
        session.append(frame("{\"jsonrpc\":\"2.0\",\"method\":\"build/exit\"}"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(
                        ide,
                        new ByteArrayInputStream(session.toString().getBytes(StandardCharsets.UTF_8)),
                        out,
                        Optional::empty)
                .serve();
        List<Map<String, Object>> frames = new ArrayList<>();
        for (String body : out.toString(StandardCharsets.UTF_8).split("Content-Length: \\d+\r\n\r\n")) {
            if (!body.isEmpty()) frames.add((Map<String, Object>) requireNonNull(MiniJson.parse(body)));
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(List<Map<String, Object>> frames, int id) {
        return frames.stream()
                .filter(f -> f.get("id") instanceof Number n && n.intValue() == id)
                .map(f -> (Map<String, Object>) requireNonNull(f.get("result"), () -> "no result: " + f))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemOf(Map<String, Object> result) {
        return ((List<Map<String, Object>>) requireNonNull(result.get("items"))).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dependencies(List<Map<String, Object>> targets, String id) {
        Map<String, Object> target = targets.stream()
                .filter(t -> id.equals(((Map<String, Object>) requireNonNull(t.get("id"))).get("uri")))
                .findFirst()
                .orElseThrow();
        return ((List<Map<String, Object>>) requireNonNull(target.get("dependencies")))
                .stream().map(d -> d.get("uri")).toList();
    }

    private static String target(Path ws, String module) {
        return "{\"targets\":[{\"uri\":\"" + BspServer.pathUri(ws) + "#" + module + "\"}]}";
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static String frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }

    private static void send(PipedOutputStream client, String frames) throws IOException {
        client.write(frames.getBytes(StandardCharsets.UTF_8));
        client.flush();
    }

    private static boolean awaitFrame(ByteArrayOutputStream out, String needle, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (out.toString(StandardCharsets.UTF_8).contains(needle)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
