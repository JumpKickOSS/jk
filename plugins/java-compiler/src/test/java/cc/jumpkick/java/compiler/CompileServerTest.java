// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The resident worker's side of the channel, with a compiler the test controls: compiles run at
 * once on the pool's threads, every frame names its compile, a cancel ends one compile and not its
 * neighbour, a failing compile answers an error and the worker goes on, and heap and trim answers go
 * out.
 */
class CompileServerTest {

    @Test
    void compiles_run_at_once_and_every_frame_names_its_compile() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        try (Harness h = new Harness((plan, spec, emit) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            bothRunning.countDown();
            bothRunning.await(10, TimeUnit.SECONDS);
            emit.accept(PluginReply.diagnostic("WARNING", spec.toString(), 1, 1, "about " + spec.getFileName()));
            emit.accept(PluginReply.result(Map.of("status", "OK", "compiled", List.of())));
            running.decrementAndGet();
            return 0;
        })) {
            h.send("{\"op\":\"hello\",\"threads\":2}");
            String hello = h.await(PluginProtocol.HELLO);
            assertThat(Jsonl.intValue(hello, "threads", 0)).isEqualTo(2);
            assertThat(Jsonl.longValue(hello, "pid", -1))
                    .isEqualTo(ProcessHandle.current().pid());
            assertThat(Jsonl.str(hello, "jdk")).isEqualTo(Runtime.version().toString());

            h.send("{\"op\":\"compile\",\"id\":11,\"spec\":\"/s/a.spec\"}");
            h.send("{\"op\":\"compile\",\"id\":12,\"spec\":\"/s/b.spec\"}");
            h.awaitCount(PluginProtocol.RESULT, 2);
            assertThat(peak.get()).as("two compiles ran at once on two threads").isEqualTo(2);
            for (String frame : h.frames(PluginProtocol.DIAGNOSTIC)) {
                long id = Jsonl.longValue(frame, PluginProtocol.ID, -1);
                assertThat(Jsonl.str(frame, "msg")).isEqualTo(id == 11 ? "about a.spec" : "about b.spec");
            }
            assertThat(h.frames(PluginProtocol.RESULT))
                    .extracting(f -> Jsonl.longValue(f, PluginProtocol.ID, -1))
                    .containsExactlyInAnyOrder(11L, 12L);
            h.send("{\"op\":\"done\"}");
            assertThat(h.exit.get(10, TimeUnit.SECONDS)).isZero();
        }
    }

    @Test
    void a_cancel_ends_one_compile_and_its_neighbour_finishes() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (Harness h = new Harness((plan, spec, emit) -> {
            started.countDown();
            if (spec.toString().contains("slow")) {
                // Stands in for javac's task events, each of which looks at the flag.
                while (true) {
                    CompileCancel.checkpoint();
                    Thread.sleep(5);
                }
            }
            release.await(10, TimeUnit.SECONDS);
            emit.accept(PluginReply.result(Map.of("status", "OK")));
            return 0;
        })) {
            h.send("{\"op\":\"hello\",\"threads\":2}");
            h.send("{\"op\":\"compile\",\"id\":1,\"spec\":\"/s/slow.spec\"}");
            h.send("{\"op\":\"compile\",\"id\":2,\"spec\":\"/s/fast.spec\"}");
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            h.send("{\"op\":\"cancel\",\"id\":1}");
            String cancelled = h.await(PluginProtocol.CANCELLED);
            assertThat(Jsonl.longValue(cancelled, PluginProtocol.ID, -1)).isEqualTo(1);
            release.countDown();
            String result = h.await(PluginProtocol.RESULT);
            assertThat(Jsonl.longValue(result, PluginProtocol.ID, -1)).isEqualTo(2);
            assertThat(h.frames(PluginProtocol.ERROR)).isEmpty();
        }
    }

    @Test
    void a_cancel_that_overtakes_its_compile_still_wins() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        try (Harness h = new Harness((plan, spec, emit) -> {
            // Where the real compile looks first, before it reads its spec.
            CompileCancel.checkpoint();
            ran.incrementAndGet();
            emit.accept(PluginReply.result(Map.of("status", "OK")));
            return 0;
        })) {
            h.send("{\"op\":\"hello\",\"threads\":1}");
            h.send("{\"op\":\"cancel\",\"id\":9}");
            h.send("{\"op\":\"compile\",\"id\":9,\"spec\":\"/s/late.spec\"}");
            String cancelled = h.await(PluginProtocol.CANCELLED);
            assertThat(Jsonl.longValue(cancelled, PluginProtocol.ID, -1)).isEqualTo(9);
            h.send("{\"op\":\"compile\",\"id\":10,\"spec\":\"/s/next.spec\"}");
            assertThat(Jsonl.longValue(h.await(PluginProtocol.RESULT), PluginProtocol.ID, -1))
                    .isEqualTo(10);
            assertThat(ran.get())
                    .as("the cancelled compile never got past its first checkpoint")
                    .isEqualTo(1);
            assertThat(h.frames(PluginProtocol.ERROR)).isEmpty();
        }
    }

    @Test
    void a_compile_that_throws_answers_an_error_and_the_worker_goes_on() throws Exception {
        try (Harness h = new Harness((plan, spec, emit) -> {
            if (spec.toString().contains("bad")) throw new IllegalStateException("spec is unreadable");
            emit.accept(PluginReply.result(Map.of("status", "OK", "outcome", plan ? "full" : "")));
            return 0;
        })) {
            h.send("{\"op\":\"compile\",\"id\":5,\"spec\":\"/s/bad.spec\"}");
            String error = h.await(PluginProtocol.ERROR);
            assertThat(Jsonl.longValue(error, PluginProtocol.ID, -1)).isEqualTo(5);
            assertThat(Jsonl.str(error, "message")).contains("spec is unreadable");
            h.send("{\"op\":\"plan\",\"id\":6,\"spec\":\"/s/good.spec\"}");
            String result = h.await(PluginProtocol.RESULT);
            assertThat(Jsonl.longValue(result, PluginProtocol.ID, -1)).isEqualTo(6);
            assertThat(Jsonl.str(result, "outcome")).isEqualTo("full");
        }
    }

    @Test
    void heap_readings_and_a_trim_go_out() throws Exception {
        try (Harness h = new Harness((plan, spec, emit) -> 0)) {
            h.send("{\"op\":\"hello\"}");
            String hello = h.await(PluginProtocol.HELLO);
            assertThat(Jsonl.intValue(hello, "threads", 0))
                    .as("one thread per core the JVM sees")
                    .isEqualTo(Runtime.getRuntime().availableProcessors());
            String heap = h.await(PluginProtocol.HEAP);
            assertThat(Jsonl.longValue(heap, "committed", -1)).isPositive();
            assertThat(Jsonl.longValue(heap, "max", -1)).isPositive();
            assertThat(Jsonl.doubleValue(heap, "gc_frac", -1)).isBetween(0.0, 1.0);
            assertThat(Jsonl.intValue(heap, "inflight", -1)).isZero();
            h.send("{\"op\":\"trim\"}");
            String trimmed = h.await(PluginProtocol.TRIMMED);
            assertThat(Jsonl.longValue(trimmed, PluginProtocol.USED_AFTER_GC, -1))
                    .isNotNegative();
        }
    }

    @Test
    void an_id_goes_first_so_a_value_can_never_be_taken_for_it() {
        String framed = CompileServer.withId(PluginReply.diagnostic("ERROR", null, 0, 0, "\"id\":99"), 7);
        assertThat(framed).startsWith("{\"id\":7,\"t\":\"diagnostic\"");
        assertThat(Jsonl.longValue(framed, PluginProtocol.ID, -1)).isEqualTo(7);
    }

    /** A server on a pipe, its frames collected, serving on a thread of its own. */
    private static final class Harness implements AutoCloseable {
        private final PrintWriter toServer;
        private final List<String> frames = new CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> exit = new CompletableFuture<>();

        Harness(CompileServer.Compiler compiler) throws Exception {
            PipedWriter writer = new PipedWriter();
            BufferedReader in = new BufferedReader(new PipedReader(writer, 1 << 16));
            toServer = new PrintWriter(writer, true);
            CompileServer server = new CompileServer(in, frames::add, compiler);
            Thread serving = new Thread(() -> {
                try {
                    exit.complete(server.serve());
                } catch (Exception e) {
                    exit.completeExceptionally(e);
                }
            });
            serving.setDaemon(true);
            serving.start();
        }

        void send(String line) {
            toServer.println(line);
        }

        List<String> frames(String type) {
            return frames.stream()
                    .filter(f -> type.equals(Jsonl.str(f, PluginProtocol.T)))
                    .toList();
        }

        String await(String type) throws InterruptedException {
            awaitTrue(() -> !frames(type).isEmpty(), "a " + type + " frame");
            return frames(type).get(0);
        }

        void awaitCount(String type, int n) throws InterruptedException {
            awaitTrue(() -> frames(type).size() >= n, n + " " + type + " frames");
        }

        @Override
        public void close() {
            toServer.close();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }
}
