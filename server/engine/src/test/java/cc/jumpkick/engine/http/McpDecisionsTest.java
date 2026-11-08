// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionAlerts;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.http.mcp.tools.RunTool;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Decisions reach the person behind a fake client by the first rung its session supports: a
 * client that declared form elicitation is asked in the call, one that declared URL elicitation is
 * sent to the decision's page, and a call whose rungs run out of time gets {@code NEEDS-YOU} with
 * the continuation {@code run(decision=<id>)} and, under {@code [ui] ask = notify}, a desktop
 * notification carrying the link. Any surface answers, the first answer wins, and a question still
 * in flight to the host is withdrawn when another surface answers first.
 */
class McpDecisionsTest {

    private static final String BASE = "http://127.0.0.1:8910/";

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final DecisionQueue queue = new DecisionQueue();
    private final List<List<String>> launched = Collections.synchronizedList(new ArrayList<>());
    private final Decisions decisions = new Decisions(queue, id -> BASE + "#decision/" + id + "?t=T")
            .alerts(new DecisionAlerts(argv -> launched.add(argv), "Linux", name -> null))
            .ask(() -> UiAsk.NOTIFY);
    private final AtomicInteger done = new AtomicInteger();
    private final McpHandler mcp = handler();

    /** Asks before it does anything. */
    private final class Risky implements McpTool {
        private final String name;

        Risky(String name) {
            this.name = name;
        }

        @Override
        public Spec spec() {
            return new Spec(name, "Does a risky thing.", Map.of("type", "object"));
        }

        @Override
        public Map<String, Object> call(McpCall in) {
            McpDecisions.Ask ask = new McpDecisions.Ask(name, "Do the risky thing?", List.of("a → b"), "Yes, do it");
            if (McpDecisions.require(in, this, ask) instanceof McpDecisions.Outcome.Stop stop) return stop.result();
            return in.text("DONE " + done.incrementAndGet() + "\n");
        }
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void a_form_client_is_asked_in_the_call_and_its_yes_lets_the_operation_proceed() throws Exception {
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);

        Map<String, Object> request = next(sent);
        Map<String, Object> params = object(request, "params");
        assertThat(params.get("mode")).isEqualTo("form");
        assertThat(String.valueOf(params.get("message"))).contains("Do the risky thing?", "a → b");
        assertThat(object(object(params, "requestedSchema"), "properties")).containsKey("answer");
        String props = MiniJson.write(object(object(params, "requestedSchema"), "properties"));
        assertThat(props).as("no dashboard escalation without URL mode").doesNotContain("show");
        answer(session, request, "{\"action\":\"accept\",\"content\":{\"answer\":\"yes\"}}");

        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("DONE 1\n");
        Decision d = queue.list().getFirst();
        assertThat(d.state()).isEqualTo(Decision.State.ACCEPTED);
        assertThat(d.answeredBy()).isEqualTo(DecisionSurface.HOST_FORM);
        assertThat(launched).as("an answered decision notifies nobody").isEmpty();
    }

    @Test
    void a_form_client_that_declines_stops_the_operation() throws Exception {
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);
        answer(session, next(sent), "{\"action\":\"decline\"}");

        assertThat(text(call.get(10, TimeUnit.SECONDS))).startsWith("DECLINED Do the risky thing?");
        assertThat(done).hasValue(0);
    }

    @Test
    void a_form_client_that_never_answers_gets_the_continuation_before_its_timeout() throws Exception {
        mcp.waitSecondMs(2);
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);
        assertThat(next(sent).get("method")).isEqualTo("elicitation/create");

        String reply = text(call.get(10, TimeUnit.SECONDS));
        Decision d = queue.list().getFirst();
        assertThat(reply).startsWith("NEEDS-YOU ").contains("run(decision=" + d.id() + ")");
        assertThat(d.open()).as("the decision stays open").isTrue();
        assertThat(requireNonNull(sent.poll(5, TimeUnit.SECONDS)).get("method")).isEqualTo("notifications/cancelled");
    }

    @Test
    void a_form_question_in_flight_is_withdrawn_when_the_dashboard_answers_first() throws Exception {
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);
        Map<String, Object> request = next(sent);
        Decision open = queue.list().getFirst();

        assertThat(queue.accept(open.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD))
                .isInstanceOf(DecisionQueue.Result.Settled.class);

        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("DONE 1\n");
        Map<String, Object> withdrawn = requireNonNull(sent.poll(5, TimeUnit.SECONDS));
        assertThat(withdrawn.get("method")).isEqualTo("notifications/cancelled");
        assertThat(object(withdrawn, "params").get("requestId")).isEqualTo(request.get("id"));
        assertThat(queue.find(open.id()).orElseThrow().answeredBy()).isEqualTo(DecisionSurface.DASHBOARD);
    }

    @Test
    void a_url_client_is_sent_to_the_decision_page_where_the_dashboard_answers() throws Exception {
        String session = initialize(URL);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);

        Map<String, Object> request = next(sent);
        Map<String, Object> params = object(request, "params");
        Decision open = queue.list().getFirst();
        assertThat(params.get("mode")).isEqualTo("url");
        assertThat(params.get("elicitationId")).isEqualTo(open.id());
        assertThat(String.valueOf(params.get("url"))).isEqualTo(BASE + "#decision/" + open.id() + "?t=T");
        answer(session, request, "{\"action\":\"accept\"}");

        assertThat(queue.accept(open.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD))
                .isInstanceOf(DecisionQueue.Result.Settled.class);

        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("DONE 1\n");
        assertThat(queue.find(open.id()).orElseThrow().answeredBy()).isEqualTo(DecisionSurface.DASHBOARD);
        Map<String, Object> complete = requireNonNull(sent.poll(5, TimeUnit.SECONDS));
        assertThat(complete.get("method")).isEqualTo("notifications/elicitation/complete");
        assertThat(object(complete, "params").get("elicitationId")).isEqualTo(open.id());
    }

    @Test
    void a_link_the_host_has_not_opened_is_withdrawn_and_completed_when_jk_decide_answers_first() throws Exception {
        String session = initialize(URL);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = async(session, "risky", Map.of(), sent);
        Map<String, Object> request = next(sent);
        Decision open = queue.list().getFirst();

        assertThat(queue.accept(open.id(), Map.of("answer", "yes"), DecisionSurface.CLI))
                .isInstanceOf(DecisionQueue.Result.Settled.class);

        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("DONE 1\n");
        Map<String, Object> withdrawn = requireNonNull(sent.poll(5, TimeUnit.SECONDS));
        assertThat(withdrawn.get("method")).isEqualTo("notifications/cancelled");
        assertThat(object(withdrawn, "params").get("requestId")).isEqualTo(request.get("id"));
        Map<String, Object> complete = requireNonNull(sent.poll(5, TimeUnit.SECONDS));
        assertThat(complete.get("method")).isEqualTo("notifications/elicitation/complete");
        assertThat(object(complete, "params").get("elicitationId")).isEqualTo(open.id());
    }

    @Test
    void a_call_out_of_time_gets_needs_you_a_notification_and_a_continuation_the_dashboard_answers() {
        mcp.waitSecondMs(1);
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();

        String reply = text(mcp.handle(call("risky", Map.of()), session, null, null, sent::add));
        Decision d = queue.list().getFirst();
        String link = BASE + "#decision/" + d.id() + "?t=T";
        assertThat(reply)
                .startsWith("NEEDS-YOU Do the risky thing?\n")
                .contains("  a → b\n")
                .contains("the user answers it: " + link + "\n")
                .doesNotContain("terminal")
                .endsWith("run(decision=" + d.id() + ")\n");
        assertThat(launched).singleElement().satisfies(argv -> {
            assertThat(argv.getFirst()).isEqualTo("notify-send");
            assertThat(argv.getLast()).contains("Do the risky thing?", "Answer it in the dashboard.", link);
        });

        // Calling the tool again while it is open opens nothing new and notifies nobody again.
        assertThat(text(mcp.handle(call("risky", Map.of()), session, null, null, sent::add)))
                .contains("run(decision=" + d.id() + ")");
        assertThat(queue.list()).hasSize(1);
        assertThat(launched).hasSize(1);

        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD))
                .isInstanceOf(DecisionQueue.Result.Settled.class);
        assertThat(text(mcp.handle(call("run", Map.of("decision", d.id())), session, null, null, sent::add)))
                .isEqualTo("DONE 1\n");
        assertThat(text(mcp.handle(call("run", Map.of("decision", d.id())), session, null, null, sent::add)))
                .as("a decision proceeds one operation once")
                .contains("no call waits on decision " + d.id());
    }

    @Test
    void an_accepted_decision_proceeds_only_the_call_it_was_asked_about() {
        mcp.waitSecondMs(1);
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        mcp.handle(call("risky", Map.of("deps", List.of("jackson"))), session, null, null, sent::add);
        Decision d = queue.list().getFirst();
        queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI);

        assertThat(text(mcp.handle(call("risky", Map.of("deps", List.of("everything"), "decision", d.id())), session)))
                .contains("answers another call");
        assertThat(text(mcp.handle(call("other", Map.of("decision", d.id())), session)))
                .contains("answers another call");
        assertThat(done).hasValue(0);
        assertThat(text(mcp.handle(call("risky", Map.of("deps", List.of("jackson"), "decision", d.id())), session)))
                .isEqualTo("DONE 1\n");
    }

    @Test
    void quiet_leaves_the_link_in_the_result_alone() {
        decisions.ask(() -> UiAsk.QUIET);
        mcp.waitSecondMs(1);
        String session = initialize(FORM);
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        assertThat(text(mcp.handle(call("risky", Map.of()), session, null, null, sent::add)))
                .startsWith("NEEDS-YOU");
        assertThat(launched).isEmpty();
    }

    @Test
    void an_operation_on_a_host_that_cannot_ask_proceeds_on_its_own_confirm() {
        String session = initialize("{}");
        assertThat(text(mcp.handle(call("risky", Map.of()), session))).isEqualTo("DONE 1\n");
        assertThat(queue.list()).isEmpty();
    }

    private static final String FORM = "{\"elicitation\":{\"form\":{}}}";

    private static final String URL = "{\"elicitation\":{\"url\":{}}}";

    private CompletableFuture<McpHandler.Reply> async(
            String session, String tool, Map<String, Object> args, BlockingQueue<Map<String, Object>> sent) {
        return CompletableFuture.supplyAsync(
                () -> mcp.handle(call(tool, args), session, null, null, sent::add), threads);
    }

    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> sent) throws Exception {
        Map<String, Object> request = requireNonNull(sent.poll(10, TimeUnit.SECONDS), "nothing was sent");
        assertThat(request.get("method")).isEqualTo("elicitation/create");
        return request;
    }

    private void answer(String session, Map<String, Object> request, String result) {
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":" + Jsonl.quote(String.valueOf(request.get("id"))) + ",\"result\":"
                        + result + "}",
                session);
    }

    private static String call(String tool, Map<String, Object> args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":" + Jsonl.quote(tool)
                + ",\"arguments\":" + MiniJson.write(args) + "}}";
    }

    private static String text(McpHandler.Reply reply) {
        Map<String, Object> body = parseObject(reply.body());
        assertThat(body).as(reply.body()).containsKey("result");
        return String.valueOf(
                objects(object(body, "result"), "content").getFirst().get("text"));
    }

    private String initialize(String capabilities) {
        return requireNonNull(mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                                + "\"2025-11-25\",\"capabilities\":" + capabilities
                                + ",\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}",
                        null)
                .openedSessionId());
    }

    private McpHandler handler() {
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
        h.tools(new McpTools(List.of(new Risky("risky"), new Risky("other"), new RunTool())));
        h.decisions(decisions);
        h.waitSecondMs(50);
        return h;
    }
}
