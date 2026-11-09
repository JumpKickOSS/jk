// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.decisions.Dashboards;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.decisions.JournaledDecisions;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.http.mcp.tools.RunTool;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two engines over one state dir, as an upgrade leaves them: an agent's call raises a decision on
 * the first, the second displaces it, and the person's answer and the agent's {@code
 * run(decision=<id>)} both reach the second. Each engine is its {@link SessionLog}, {@link
 * DecisionQueue} and {@link McpHandler}; the operation counts how often it ran.
 */
class McpDecisionTakeoverTest {

    @TempDir
    Path state;

    @TempDir
    Path project;

    private final AtomicInteger ran = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    /** Asks a question, then counts one run. */
    private final class Update implements McpTool {
        @Override
        public Spec spec() {
            return new Spec("risky", "Asks before it runs.", Map.of("type", "object"));
        }

        @Override
        public Map<String, Object> call(McpCall in) {
            McpDecisions.Ask ask = new McpDecisions.Ask("risky", "Apply 1 move?", List.of("a 1 → 2"), "Yes, apply it");
            if (McpDecisions.require(in, this, ask) instanceof McpDecisions.Outcome.Stop stop) return stop.result();
            return in.text("RAN " + ran.incrementAndGet() + "\n");
        }
    }

    /** One engine. */
    private record Engine(McpHandler mcp, DecisionQueue queue) {}

    @Test
    void a_decision_opened_on_one_engine_is_answered_on_its_successor_and_the_operation_runs_once() {
        AtomicBoolean firstIsPrimary = new AtomicBoolean(true);
        Engine first = engine(firstIsPrimary);
        String session = initialize(first);
        String asked = text(first.mcp()
                .handle(call("risky", Map.of("goal", "move a")), session, project.toString(), null, m -> {}));
        assertThat(asked).startsWith("NEEDS-YOU Apply 1 move?");
        String id = first.queue().list().getFirst().id();
        assertThat(asked).endsWith("run(decision=" + id + ")\n");

        // The upgrade: a new engine takes the endpoint, and the first one no longer writes.
        firstIsPrimary.set(false);
        Engine next = engine(new AtomicBoolean(true));

        assertThat(first.queue().accept(id, Map.of("answer", "yes"), DecisionSurface.CLI))
                .as("the displaced engine takes no answer")
                .isInstanceOf(DecisionQueue.Result.Missing.class);
        assertThat(next.queue().accept(id, Map.of("answer", "yes"), DecisionSurface.CLI))
                .isInstanceOf(DecisionQueue.Result.Settled.class);

        assertThat(text(next.mcp()
                        .handle(call("run", Map.of("decision", id)), session, project.toString(), null, null)))
                .isEqualTo("RAN 1\n");
        assertThat(text(next.mcp()
                        .handle(call("run", Map.of("decision", id)), session, project.toString(), null, null)))
                .as("the continuation is used up")
                .contains("no call waits on decision " + id);
        first.mcp().waitSecondMs(1);
        assertThat(text(first.mcp()
                        .handle(
                                call("risky", Map.of("goal", "move a", "decision", id)),
                                session,
                                project.toString(),
                                null,
                                null)))
                .as("the displaced engine never runs it")
                .doesNotContain("RAN");
        assertThat(ran).hasValue(1);
        assertThat(next.queue().find(id).map(Decision::accepted)).hasValue(true);
    }

    @Test
    void a_continuation_whose_decision_is_gone_asks_again_and_is_no_error() {
        Engine engine = engine(new AtomicBoolean(true));
        String session = initialize(engine);
        Map<String, Object> result = result(engine.mcp()
                .handle(
                        call("run", Map.of("decision", "0123456789abcdef01234567")),
                        session,
                        project.toString(),
                        null,
                        null));
        assertThat(result.get("isError")).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result))
                .startsWith("ASK-AGAIN decision 0123456789abcdef01234567 is gone")
                .contains("repeat the call that asked it, without decision");
    }

    @Test
    void a_decision_whose_deadline_passed_across_the_hand_off_expires_and_asks_again() {
        AtomicBoolean firstIsPrimary = new AtomicBoolean(true);
        Engine first = engine(firstIsPrimary);
        String session = initialize(first);
        first.mcp().handle(call("risky", Map.of()), session, project.toString(), null, m -> {});
        String id = first.queue().list().getFirst().id();

        firstIsPrimary.set(false);
        now.addAndGet(DecisionQueue.DEADLINE.toMillis() + 1);
        Engine next = engine(new AtomicBoolean(true));
        assertThat(text(next.mcp()
                        .handle(call("run", Map.of("decision", id)), session, project.toString(), null, null)))
                .startsWith("EXPIRED Apply 1 move?")
                .contains("repeat the call without decision to ask anew");
        assertThat(ran).hasValue(0);
    }

    private Engine engine(AtomicBoolean primary) {
        SessionLog log = new SessionLog(
                new SessionJournal(state.resolve(SessionJournal.DIR)),
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis());
        log.writeWhile(primary::get, run -> {});
        DecisionQueue queue = new DecisionQueue(new Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public long nanos() {
                return System.nanoTime();
            }
        });
        queue.store(new JournaledDecisions(log));
        McpHandler h = new McpHandler(
                () -> new StatusSnapshot("0.13.7", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        return 1L;
                    }

                    @Override
                    public boolean cancel(long requestId) {
                        return false;
                    }

                    @Override
                    public int cancelDir(String dir) {
                        return 0;
                    }
                },
                dir -> Map.of(),
                List::of,
                "0.13.7");
        h.sessions(log);
        h.tools(new McpTools(List.of(new Update(), new RunTool())));
        h.decisions(new Decisions(queue, id -> null, new Dashboards()));
        h.waitSecondMs(1);
        return new Engine(h, queue);
    }

    private String initialize(Engine engine) {
        return requireNonNull(engine.mcp()
                .handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                                + "\"2025-11-25\",\"capabilities\":{\"elicitation\":{\"form\":{}}},"
                                + "\"clientInfo\":{\"name\":\"fake\","
                                + "\"version\":\"1\"}}}",
                        null,
                        project.toString(),
                        null,
                        null)
                .openedSessionId());
    }

    private static String call(String tool, Map<String, Object> args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":" + Jsonl.quote(tool)
                + ",\"arguments\":" + MiniJson.write(args) + "}}";
    }

    private static Map<String, Object> result(McpHandler.Reply reply) {
        Map<String, Object> body = parseObject(reply.body());
        assertThat(body).as(reply.body()).containsKey("result");
        return object(body, "result");
    }

    private static String text(McpHandler.Reply reply) {
        return text(result(reply));
    }

    private static String text(Map<String, Object> result) {
        return String.valueOf(objects(result, "content").getFirst().get("text"));
    }
}
