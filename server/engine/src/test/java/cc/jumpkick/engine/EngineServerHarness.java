// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The real-socket fixture the {@code EngineServer*Test} contract tests share: short-path temp
 * dirs and their cleanup, a hand-rolled client that speaks whichever transport the engine bound
 * ({@link EngineTransport}), the mock-repo seeder, and the drivers that run one request to its
 * terminal line. Nothing here stands in for the engine — every test starts a real
 * {@link EngineServer} and talks to it over a real socket.
 */
abstract class EngineServerHarness {

    // @TempDir nests deep enough under Gradle's build dir to overrun what the JDK will bind as a
    // Unix domain socket. ShortTempDirs owns the short root, the creation, and the teardown-safe
    // cleanup (an engine under test may still be deleting its own socket/pid/lock mid-walk).
    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkd-");

    Path shortTempDir() throws IOException {
        return tempDirs.create();
    }

    @AfterEach
    void resetSharedHeapPlan() {
        // Every test that gets a real EngineServer to run triggers planSharedWorkerMemoryOnce,
        // which mutates JvmOptions' process-wide static heap plan — reset it so it doesn't leak into
        // unrelated tests (e.g. JvmOptionsTest) sharing this test JVM.
        JvmOptions.resetSharedPlanForTests();
    }

    static EnginePaths.Paths paths(Path stateDir) {
        return EnginePaths.resolve(stateDir);
    }

    /** Poll {@code condition} until true or {@code timeout} elapses (fails the test on timeout). */
    static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Await.until(timeout, condition);
    }

    /** A minimal hand-rolled client: connect, send lines, read one reply line per line sent. */
    static final class Client implements AutoCloseable {
        private final SocketChannel channel;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        Client(Path socket) throws IOException {
            channel = EngineSockets.connect(socket);
            reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
        }

        String send(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
            return reader.readLine();
        }

        /** Fire-and-forget send, for request types that reply with an event stream (not one line). */
        void sendLine(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }

        /** Read the next event line off the stream ({@code null} on EOF). */
        String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    static Thread runInBackground(EngineServer server) {
        List<Throwable> failures = new ArrayList<>();
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (Throwable e) {
                        e.printStackTrace();
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                },
                "test-engine-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Drive one request to its plan-finish and return that terminal line. */
    static String runToBuildPlanFinish(EnginePaths.Paths p, String request) throws IOException {
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(request);
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.BUILDPLAN_FINISH.equals(type)) return line;
                if (EngineProtocol.ERROR.equals(type)) throw new IOException("request failed: " + line);
            }
        }
        throw new IOException("disconnected before plan-finish");
    }

    /** Minimal metadata + dependency-free POM + stub jar for one coordinate on the mock repo. */
    static void seedArtifact(Map<String, byte[]> served, String group, String artifact, String version) {
        new MavenStub(served).leaf(group, artifact, version);
    }

    static JkHttpConfig httpOnEphemeralPort(Path webRoot) {
        return new JkHttpConfig("127.0.0.1", 0, 16, 16, webRoot.toString(), JkHttpConfig.Mcp.DEFAULTS);
    }

    /** The value of a flat {@code "key":"value"} pair — enough for one field of a status body. */
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /** Read SSE lines until an {@code event: <type>} frame, returning its {@code data:} payload. */
    static String awaitSseData(Iterator<String> lines, String type) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
                    while (lines.hasNext()) {
                        if (lines.next().equals("event: " + type)) {
                            String data = lines.next();
                            return data.startsWith("data: ") ? data.substring("data: ".length()) : data;
                        }
                    }
                    throw new AssertionError("stream ended without an 'event: " + type + "' frame");
                })
                .get(10, TimeUnit.SECONDS);
    }
}
