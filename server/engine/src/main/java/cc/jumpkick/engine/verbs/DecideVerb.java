// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.decisions.MachineSetupDecisions;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.form.Form;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk decide}: lists the decision queue, and answers one decision; a command that asks a
 * person ({@code jk doctor --fix}) raises its decision and spends the accepted one here too, and a
 * person's build offers the machine-setup decision here.
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
        if (DecisionsRequest.RAISE.equals(req.action())) return raise(queue, req.ask());
        if (DecisionsRequest.OFFER.equals(req.action())) {
            String project = req.ask() != null && MiniJson.parse(req.ask()) instanceof Map<?, ?> ask
                    ? MiniJson.str(ask, "project")
                    : null;
            MachineSetupDecisions.offer(queue, MachineSetupDecisions.project(project));
            return new DecisionsAck(null, "[]", null);
        }
        String list = MiniJson.write(queue.list().stream().map(Decision::toMap).toList());
        if (DecisionsRequest.LIST.equals(req.action())) return new DecisionsAck(null, list, null);
        String id = req.id();
        if (id == null || id.isBlank()) return DecisionsAck.error("name the decision to answer");
        if (DecisionsRequest.SPEND.equals(req.action())) {
            boolean spent = queue.spend(id);
            String d = queue.find(id).map(x -> MiniJson.write(x.toMap())).orElse(null);
            return new DecisionsAck(null, "[]", d, queue.link(id), spent);
        }
        DecisionQueue.Result result =
                switch (req.action()) {
                    case DecisionsRequest.ACCEPT -> queue.accept(id, answers(req.answers()), DecisionSurface.CLI);
                    case DecisionsRequest.DECLINE -> queue.decline(id, DecisionSurface.CLI);
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
            case DecisionQueue.Result.Invalid i -> DecisionsAck.error(i.problems());
        };
    }

    /**
     * Opens the decision {@code askJson} describes, or answers the one of the same kind about the same
     * project that still waits to be used (open, or accepted and unspent), so asking twice shows one
     * question and asking after a late answer proceeds with it. A batch's {@code items} become its checklist.
     */
    static DecisionsAck raise(DecisionQueue queue, @Nullable String askJson) {
        if (askJson == null || !(MiniJson.parse(askJson) instanceof Map<?, ?> ask)) {
            return DecisionsAck.error("raise needs the question to ask");
        }
        String kind = MiniJson.str(ask, "kind");
        String question = MiniJson.str(ask, "question");
        if (kind == null || question == null) return DecisionsAck.error("raise needs a kind and a question");
        String project = MiniJson.str(ask, "project");
        String yes = MiniJson.str(ask, "yes");
        List<String> detail = new ArrayList<>();
        for (Object line : MiniJson.list(ask, "detail")) detail.add(String.valueOf(line));
        List<DecisionForms.Item> items = new ArrayList<>();
        for (Object o : MiniJson.list(ask, "items")) {
            if (!(o instanceof Map<?, ?> item)) continue;
            String id = MiniJson.str(item, "id");
            String label = MiniJson.str(item, "label");
            if (id == null || label == null) continue;
            String help = MiniJson.str(item, "help");
            items.add(new DecisionForms.Item(id, label, help == null ? "" : help));
        }
        Decision d = queue.unspentFor(kind, project).orElse(null);
        if (d == null) {
            Form form = DecisionForms.batch(kind, question, items, yes == null ? "Yes" : yes);
            d = queue.open(new DecisionQueue.Ask(kind, question, detail, form, null, null, null, project));
            if (Boolean.TRUE.equals(ask.get("alert"))) queue.alert(d);
        }
        return new DecisionsAck(null, "[]", MiniJson.write(d.toMap()), queue.link(d.id()), false);
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
