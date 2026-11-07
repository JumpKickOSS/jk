// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk decide}: lists the decision queue, and answers one decision. Only an answer typed at a
 * terminal is taken: the CLI asks only on one, and an accept that says otherwise is refused.
 */
public final class DecideVerb implements HostedVerb {

    private final VerbHost host;
    private final DecisionQueue queue;

    public DecideVerb(VerbHost host, DecisionQueue queue) {
        this.host = host;
        this.queue = queue;
    }

    @Override
    public String wireType() {
        return EngineProtocol.DECISIONS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("decide");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-decide-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            DecisionsAck ack;
            try {
                ack = answer(queue, DecisionsRequest.decode(requestLine));
            } catch (RuntimeException e) {
                ack = DecisionsAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    static DecisionsAck answer(DecisionQueue queue, DecisionsRequest req) {
        String list = MiniJson.write(queue.list().stream().map(Decision::toMap).toList());
        if (DecisionsRequest.LIST.equals(req.action())) return new DecisionsAck(null, list, null);
        String id = req.id();
        if (id == null || id.isBlank()) return DecisionsAck.error("name the decision to answer");
        if (!req.tty()) return DecisionsAck.error("jk decide answers only at a terminal");
        DecisionQueue.Result result =
                switch (req.action()) {
                    case DecisionsRequest.ACCEPT -> queue.accept(id, answers(req.answers()), DecisionSurface.CLI, null);
                    case DecisionsRequest.DECLINE -> queue.decline(id, DecisionSurface.CLI, null);
                    default -> throw new IllegalArgumentException("unknown decisions action: " + req.action());
                };
        return switch (result) {
            case DecisionQueue.Result.Settled s ->
                new DecisionsAck(null, list, MiniJson.write(s.decision().toMap()));
            case DecisionQueue.Result.Already a ->
                new DecisionsAck(
                        "decision " + id + " is already " + a.decision().state().wire(),
                        list,
                        MiniJson.write(a.decision().toMap()));
            case DecisionQueue.Result.Missing m -> DecisionsAck.error("no decision " + id);
            case DecisionQueue.Result.Refused r -> DecisionsAck.error(r.reason());
            case DecisionQueue.Result.Invalid i -> DecisionsAck.error(i.problems());
        };
    }

    private static Map<String, Object> answers(@Nullable String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json != null && MiniJson.parse(json) instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getValue() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }
}
