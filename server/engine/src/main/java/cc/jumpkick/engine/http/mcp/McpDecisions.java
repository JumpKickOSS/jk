// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.McpFlattening;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.mcp.McpRevision;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How a tool puts a decision to the person behind its client before it proceeds. The engine climbs
 * three rungs and stops at the first the session supports: form elicitation, which the host renders
 * and answers in the call; URL elicitation to the decision's dashboard page; and a {@code
 * NEEDS-YOU} result naming the decision and the continuation {@code run(decision=<id>)}, after which
 * {@code [ui] ask} notifies, opens the browser or stays quiet. Every rung answers before the
 * client's tool timeout ({@link McpJobRuns#clientWaitS}), leaving the decision open when nobody
 * has answered yet.
 *
 * <p>A decision asks only a host that can ask: on one that cannot, the call's own {@code
 * confirm=true} is the answer. Any surface may answer it and the first answer wins: a question still
 * in flight to the host when another surface answers is withdrawn.
 *
 * <p>The call a decision continues is stored with it in the queue, so a successor engine that
 * adopted the decision continues it. A continuation whose decision is gone answers {@code
 * ASK-AGAIN}: the agent repeats the original call and is asked anew.
 */
public final class McpDecisions {

    /** The argument naming the decision a call continues. */
    public static final String DECISION = "decision";

    /** The {@code -32042} error: the call needs the user to open a link before it can go on. */
    static final int URL_ELICITATION_REQUIRED = -32042;

    private static final int DETAIL_LINES = 12;

    /** The session log's origin for MCP connections. */
    private static final String ORIGIN = "mcp";

    private McpDecisions() {}

    /**
     * What an operation asks.
     *
     * @param kind names the operation in the queue ({@code disk-clean}, {@code jdk-uninstall}, …)
     * @param question one line
     * @param detail the lines that explain it (the paths, the versions)
     * @param yes what accepting does, one line
     */
    public record Ask(String kind, String question, List<String> detail, String yes) {
        public Ask {
            detail = List.copyOf(detail);
        }
    }

    /** Whether the tool goes on, or answers {@link Stop#result} instead. */
    public sealed interface Outcome {
        /** Go on. {@code decision} is the accepted one, or null when the host could not ask and none was needed. */
        record Proceed(@Nullable Decision decision) implements Outcome {}

        record Stop(Map<String, Object> result) implements Outcome {}
    }

    /** The argument that names the task; masked before a decision stores it. */
    private static final String GOAL = "goal";

    /**
     * Ask {@code ask} of the person behind {@code in}'s client, or read the answer to the decision
     * {@code in} names in {@value #DECISION}. {@code tool} is called again with the same arguments
     * when {@code run(decision=<id>)} continues the decision.
     */
    public static Outcome require(McpCall in, McpTool tool, Ask ask) {
        Decisions decisions = in.ctx().decisions();
        DecisionQueue queue = decisions.queue();
        String named = in.str(DECISION);
        if (named != null && !named.isBlank()) {
            Decision d = queue.find(named.strip()).orElse(null);
            if (d == null) return stop(askAgain(in, named.strip()));
            Map<String, Object> asked = askedArgs(queue, d.id());
            if (!d.kind().equals(ask.kind()) || (asked != null && !asked.equals(callArgs(in)))) {
                return stop(in.error("decision " + d.id() + " answers another call; call again without decision"
                        + " to ask about this one\n"));
            }
            return answered(in, decisions, d, false);
        }
        if (!canAsk(in)) return new Outcome.Proceed(null);
        McpConnection c = in.connection();
        String label = c == null ? null : c.label();
        String project = in.dir();
        boolean fresh = false;
        Decision d = queue.openFor(ask.kind(), label, project)
                .filter(open -> sameCall(in, open))
                .orElse(null);
        if (d == null) {
            d = queue.open(raise(in, ask, label, project));
            fresh = true;
        }
        remember(in, d, tool);
        return climb(in, decisions, d, fresh);
    }

    /**
     * A destructive operation the call confirmed: ask a host that can ask, and go on otherwise.
     * Null to go on; else the tool's answer (declined, expired, or {@code NEEDS-YOU}).
     */
    public static @Nullable Map<String, Object> confirm(
            McpCall in, McpTool tool, String kind, String question, String yes) {
        return require(in, tool, new Ask(kind, question, List.of(), yes)) instanceof Outcome.Stop s ? s.result() : null;
    }

    /**
     * {@code run(decision=<id>)}: call the tool the decision continues again, with its arguments
     * and the decision, which answers it once the decision is settled.
     */
    public static Map<String, Object> resume(McpCall in, String id) {
        String named = id.strip();
        DecisionQueue queue = in.ctx().decisions().queue();
        Decision d = queue.find(named).orElse(null);
        if (d == null) return askAgain(in, named);
        Map<String, Object> call = queue.call(named).orElse(null);
        Map<String, Object> asked = askedArgs(queue, named);
        McpTool tool = call == null ? null : in.ctx().toolNamed().apply(String.valueOf(call.get("tool")));
        if (call == null || asked == null || tool == null) {
            return in.error("no call waits on decision " + named + ": the call it answers was already continued\n");
        }
        Map<String, Object> args = new LinkedHashMap<>(asked);
        args.put(DECISION, named);
        Map<String, Object> result = tool.call(in.withArgs(args));
        if (queue.find(named).map(x -> !x.open()).orElse(true)) queue.call(named, null);
        return result;
    }

    /** {@code ASK-AGAIN}: decision {@code id} is gone, so the agent repeats the call that asked it. */
    private static Map<String, Object> askAgain(McpCall in, String id) {
        return in.text("ASK-AGAIN decision " + id + " is gone: it expired or was lost, and nothing changed\n"
                + "repeat the call that asked it, without decision, to ask anew\n");
    }

    private static DecisionQueue.Ask raise(McpCall in, Ask ask, @Nullable String label, @Nullable String project) {
        String session = null;
        String episode = null;
        SessionLog log = in.ctx().sessions();
        if (log != null && label != null) {
            SessionLog.Place place = log.where(ORIGIN, label).orElse(null);
            if (place != null) {
                session = place.session();
                episode = place.episode();
            }
        }
        String question = EventRedaction.redactText(project, ask.question());
        List<String> detail = new ArrayList<>();
        for (String line : ask.detail()) detail.add(EventRedaction.redactText(project, line));
        Form form = DecisionForms.confirm(ask.kind(), question, ask.yes());
        return new DecisionQueue.Ask(ask.kind(), question, detail, form, session, label, episode, project);
    }

    /**
     * The call's arguments without {@value #DECISION}, its goal masked as a session record masks it:
     * what a decision was asked about, as the queue stores it.
     */
    private static Map<String, Object> callArgs(McpCall in) {
        Map<String, Object> args = new LinkedHashMap<>();
        in.args().forEach((k, v) -> {
            if (v == null || DECISION.equals(k)) return;
            args.put(k, GOAL.equals(k) && v instanceof String goal ? EventRedaction.redactText(in.dir(), goal) : v);
        });
        return args;
    }

    /** The arguments of the call {@code id} continues; null when it stores none. */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> askedArgs(DecisionQueue queue, String id) {
        return queue.call(id)
                .map(c -> c.get("args") instanceof Map<?, ?> a ? (Map<String, Object>) a : null)
                .orElse(null);
    }

    /** True when {@code open} was asked about this very call, so asking again would be a duplicate. */
    private static boolean sameCall(McpCall in, Decision open) {
        Map<String, Object> asked = askedArgs(in.ctx().decisions().queue(), open.id());
        return asked != null && asked.equals(callArgs(in));
    }

    /** Store the call {@code d} continues with it, so {@code run(decision=<id>)} can make it again on any engine. */
    private static void remember(McpCall in, Decision d, McpTool tool) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool.spec().name());
        call.put("args", Map.copyOf(callArgs(in)));
        in.ctx().decisions().queue().call(d.id(), call);
    }

    /** Whether the session can be asked at all: a mode declared and a way back to the client. */
    private static boolean canAsk(McpCall in) {
        McpConnection c = in.connection();
        if (c == null || c.elicitation().none()) return false;
        return in.outbound() != null || c.elicitation().url();
    }

    private static Outcome climb(McpCall in, Decisions decisions, Decision d, boolean fresh) {
        McpConnection c = in.connection();
        McpElicitModes modes = c == null ? McpElicitModes.NONE : c.elicitation();
        long until = deadline(in);
        String hostLink = decisions.link(d.id());
        if (modes.form() && in.outbound() != null) {
            switch (byForm(in, decisions, d, modes.url() && hostLink != null, until)) {
                case Rung.Settled s -> {
                    return answered(in, decisions, s.decision(), true);
                }
                case Rung.TimedOut t -> {
                    return needsYou(in, decisions, d, fresh);
                }
                case Rung.Next n -> {}
            }
        }
        if (modes.url() && hostLink != null) {
            switch (byLink(in, decisions, d, hostLink, until)) {
                case Rung.Settled s -> {
                    return answered(in, decisions, s.decision(), true);
                }
                case Rung.TimedOut t -> {
                    return needsYou(in, decisions, d, false);
                }
                case Rung.Next n -> {}
            }
        }
        return needsYou(in, decisions, d, fresh);
    }

    /** How one rung ended: settled, out of time with the decision still open, or not this rung. */
    private sealed interface Rung {
        record Settled(Decision decision) implements Rung {}

        record TimedOut() implements Rung {}

        record Next() implements Rung {}
    }

    private static Rung byForm(McpCall in, Decisions decisions, Decision d, boolean show, long until) {
        McpConnection c = in.connection();
        if (c == null) return new Rung.Next();
        Form form = show ? DecisionForms.confirmOrShow(d.form()) : d.form();
        McpFlattening.Elicitation next = McpFlattening.flatten(
                form,
                form.steps().getFirst(),
                new HashMap<>(),
                c.revision(),
                DerivedDefaults.NONE,
                (field, endpoint) -> List.of());
        if (!(next instanceof McpFlattening.Ask question)) return new Rung.Next();
        DecisionQueue queue = decisions.queue();
        McpElicitResult result = in.elicit(
                new McpElicitation.Form(message(d), question.requestedSchema()),
                left(until),
                queue.whenSettled(d.id()));
        return switch (result) {
            case McpElicitResult.Accepted accepted -> {
                if (!(question.answers(accepted.content()) instanceof McpFlattening.Answered answered)
                        || answered.answers().isEmpty()) {
                    yield new Rung.Next();
                }
                if (DecisionForms.shows(answered.answers())) yield new Rung.Next();
                yield settled(queue, queue.accept(d.id(), answered.answers(), DecisionSurface.HOST_FORM));
            }
            case McpElicitResult.Declined x -> settled(queue, queue.decline(d.id(), DecisionSurface.HOST_FORM));
            case McpElicitResult.Cancelled x -> settled(queue, queue.decline(d.id(), DecisionSurface.HOST_FORM));
            case McpElicitResult.Closed x -> new Rung.Settled(queue.find(d.id()).orElse(d));
            case McpElicitResult.TimedOut x -> new Rung.TimedOut();
            default -> new Rung.Next();
        };
    }

    private static Rung byLink(McpCall in, Decisions decisions, Decision d, String link, long until) {
        McpOutbound out = in.outbound();
        if (out == null) {
            Map<String, Object> elicitation =
                    new McpElicitation.Url(message(d), link, d.id()).params(McpRevision.LATEST);
            throw new McpError(
                    URL_ELICITATION_REQUIRED,
                    "this call needs a decision: open the link, then call again",
                    Map.of("elicitations", List.of(elicitation)));
        }
        String message = d.question() + ": answer it in the dashboard";
        DecisionQueue queue = decisions.queue();
        return switch (in.elicit(
                new McpElicitation.Url(message, link, d.id()), left(until), queue.whenSettled(d.id()))) {
            case McpElicitResult.Accepted accepted -> {
                Decision settled = in.ctx().admissionYield().yielding(() -> queue.await(d.id(), left(until))
                        .orElse(d));
                if (settled.open()) yield new Rung.TimedOut();
                completed(out, d.id());
                yield new Rung.Settled(settled);
            }
            case McpElicitResult.Declined x -> settled(queue, queue.decline(d.id(), DecisionSurface.HOST_URL));
            case McpElicitResult.Cancelled x -> settled(queue, queue.decline(d.id(), DecisionSurface.HOST_URL));
            case McpElicitResult.Closed x -> {
                completed(out, d.id());
                yield new Rung.Settled(queue.find(d.id()).orElse(d));
            }
            case McpElicitResult.TimedOut x -> new Rung.TimedOut();
            default -> new Rung.Next();
        };
    }

    /** The decision as an answer left it: settled by it, or by an earlier answer from another surface. */
    private static Rung settled(DecisionQueue queue, DecisionQueue.Result result) {
        return switch (result) {
            case DecisionQueue.Result.Settled s -> new Rung.Settled(s.decision());
            case DecisionQueue.Result.Already a -> new Rung.Settled(a.decision());
            default -> new Rung.Next();
        };
    }

    /**
     * The tool's answer for {@code d} as it stands: go on when accepted (once), stop when declined
     * or expired, and for an open one wait out the call's budget, then {@code NEEDS-YOU} again.
     */
    private static Outcome answered(McpCall in, Decisions decisions, Decision d, boolean justAsked) {
        DecisionQueue queue = decisions.queue();
        Decision now = d;
        if (now.open() && !justAsked) {
            Duration wait = left(deadline(in));
            now = in.ctx().admissionYield().yielding(() -> queue.await(d.id(), wait)
                    .orElse(d));
        }
        return switch (now.state()) {
            case OPEN -> needsYou(in, decisions, now, false);
            case ACCEPTED ->
                queue.spend(now.id())
                        ? new Outcome.Proceed(now)
                        : stop(in.error("decision " + now.id() + " was already used; call again without decision"
                                + " to ask anew\n"));
            case DECLINED -> stop(in.text("DECLINED " + now.question() + "\n" + by(now) + "nothing changed\n"));
            case EXPIRED ->
                stop(in.text("EXPIRED " + now.question() + "\nnobody answered by the deadline; nothing changed\n"
                        + "repeat the call without decision to ask anew\n"));
        };
    }

    private static String by(Decision d) {
        return d.answeredBy() == null ? "" : "by " + d.answeredBy().wire() + "\n";
    }

    /**
     * {@code NEEDS-YOU}: the question, how a person answers it, and the continuation. A fresh
     * decision also gets what {@code [ui] ask} says: a notification, the browser, or nothing.
     */
    private static Outcome needsYou(McpCall in, Decisions decisions, Decision d, boolean alert) {
        String link = decisions.link(d.id());
        StringBuilder out = new StringBuilder("NEEDS-YOU ").append(d.question()).append('\n');
        for (String line : clipped(d.detail())) out.append("  ").append(line).append('\n');
        out.append("the user answers it: ")
                .append(link != null ? link : d.command())
                .append('\n');
        if (alert && link != null) decisions.alerts().raise(decisions.ask(), d, link);
        out.append("run(decision=").append(d.id()).append(")\n");
        return stop(in.text(out.toString()));
    }

    private static String message(Decision d) {
        StringBuilder m = new StringBuilder(d.question());
        for (String line : clipped(d.detail())) m.append('\n').append(line);
        return m.toString();
    }

    private static List<String> clipped(List<String> detail) {
        if (detail.size() <= DETAIL_LINES) return detail;
        List<String> head = new ArrayList<>(detail.subList(0, DETAIL_LINES - 1));
        head.add("+" + (detail.size() - DETAIL_LINES + 1) + " more");
        return head;
    }

    /** Tells the client the URL interaction {@code id} named is over. */
    private static void completed(McpOutbound out, String id) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/elicitation/complete");
        note.put("params", Map.of("elicitationId", id));
        try {
            out.send(note);
        } catch (IOException | RuntimeException ignored) {
            // The client is gone; the decision is settled all the same.
        }
    }

    /** When this call must answer, in {@link Clock#nanos} millis. */
    private static long deadline(McpCall in) {
        return Clock.SYSTEM.nanos() / 1_000_000
                + McpJobRuns.clientWaitS(in) * in.ctx().waitSecondMs();
    }

    private static Duration left(long until) {
        return Duration.ofMillis(Math.max(1, until - Clock.SYSTEM.nanos() / 1_000_000));
    }

    private static Outcome stop(Map<String, Object> result) {
        return new Outcome.Stop(result);
    }
}
