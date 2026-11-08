// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.form.Asker;
import cc.jumpkick.cli.tui.form.FormRenderer;
import cc.jumpkick.cli.tui.form.FormSources;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Step;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk decide [<id>]} — the decisions waiting for a person, and an answer to one. At a terminal
 * the decision's form is asked with the same widgets {@code jk new} uses; anywhere, {@code -y}
 * accepts and {@code -d} declines without asking.
 */
public final class DecideCommand implements CliCommand {

    /** A decision's confirmation field, and the value that accepts it. */
    private static final String ANSWER = "answer";

    private static final String YES = "yes";

    @Override
    public String name() {
        return "decide";
    }

    @Override
    public String description() {
        return "List the decisions waiting for you, and answer one";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("id", Arity.ZERO_OR_ONE, "The decision to answer (default: pick one)"));
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Decline the decision instead of answering its form (-y accepts it)", "-d", "--decline"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        EnginePaths.Paths paths = EnginePaths.current();
        DecisionsAck listed = EngineClient.decisions(paths, new DecisionsRequest(DecisionsRequest.LIST, null, null));
        if (listed.error() != null) {
            CommandWedge.printFail("Decide", listed.error());
            return Exit.SOFTWARE;
        }
        List<Map<?, ?>> all = DecideView.parse(listed.decisions());
        List<Map<?, ?>> open = DecideView.open(all);
        boolean terminal = Interactivity.canPrompt() && !global.outputIsJson();
        String id =
                in.positionals().isEmpty() ? null : in.positionals().getFirst().strip();
        if (id == null) {
            DecideView.table(open).forEach(CliOutput::out);
            if (open.isEmpty() || !terminal) return Exit.SUCCESS;
            id = open.size() == 1 ? MiniJson.str(open.getFirst(), "id") : pick(open);
            if (id == null) return Exit.SUCCESS;
        }
        Map<?, ?> d = DecideView.find(all, id);
        if (d == null) {
            CommandWedge.printFail("Decide", "no decision " + id + " (jk decide lists them)");
            return Exit.USAGE;
        }
        if (!"open".equals(MiniJson.str(d, "state"))) {
            CommandWedge.printFail(
                    "Decide", "decision " + MiniJson.str(d, "id") + " is already " + MiniJson.str(d, "state"));
            return Exit.FAILURE;
        }
        boolean decline = in.isSet("decline");
        if (!terminal && !decline && !global.yes) {
            CommandWedge.printFail("Decide", "without a terminal, -y accepts it and -d declines it");
            return Exit.USAGE;
        }
        return answer(paths, d, decline, global.yes);
    }

    private static int answer(EnginePaths.Paths paths, Map<?, ?> d, boolean decline, boolean yes) throws IOException {
        String id = String.valueOf(MiniJson.str(d, "id"));
        DecideView.card(d).forEach(CliOutput::out);
        DecisionsRequest req = flagged(id, decline, yes);
        if (req == null) {
            Asker asker = Asker.interactive();
            if (asker == null) {
                CommandWedge.printFail("Decide", "without a terminal, -y accepts it and -d declines it");
                return Exit.USAGE;
            }
            Form form = Form.fromJson(MiniJson.write(d.get("form")));
            FormRenderer renderer = new FormRenderer(asker, FormSources.local(Path.of(".")));
            Optional<Map<String, Object>> values = renderer.run(form, Map.of());
            if (values.isEmpty()) {
                CliOutput.out("Left unanswered; it is still open.");
                return Exit.SUCCESS;
            }
            req = new DecisionsRequest(DecisionsRequest.ACCEPT, id, MiniJson.write(values.get()));
        }
        DecisionsAck ack = EngineClient.decisions(paths, req);
        if (ack.error() != null) {
            CommandWedge.printFail("Decide", ack.error());
            return Exit.FAILURE;
        }
        Object settled = ack.decision() == null ? null : MiniJson.parse(ack.decision());
        CommandWedge.printOk("Decide", id + " " + MiniJson.str(settled, "state"));
        return Exit.SUCCESS;
    }

    /**
     * The answer {@code -d} ({@code decline}) or {@code -y} ({@code yes}) gives decision {@code id},
     * no question asked; null when neither was given and the form is asked.
     */
    static @Nullable DecisionsRequest flagged(String id, boolean decline, boolean yes) {
        if (decline) return new DecisionsRequest(DecisionsRequest.DECLINE, id, null);
        if (yes) return new DecisionsRequest(DecisionsRequest.ACCEPT, id, MiniJson.write(Map.of(ANSWER, YES)));
        return null;
    }

    /** Asks which of {@code open} to answer; null when the user backed out. */
    private static @Nullable String pick(List<Map<?, ?>> open) {
        Asker asker = Asker.interactive();
        if (asker == null) return null;
        Field.Builder which = Field.of("decision", "Which decision", FieldKind.CHOICE);
        for (Map<?, ?> d : open) which.option(String.valueOf(MiniJson.str(d, "id")), DecideView.oneLine(d), null);
        Form form = Form.of("decide", "Decide", Step.of("pick", "Pick a decision", which.build()));
        Optional<Map<String, Object>> picked =
                new FormRenderer(asker, FormSources.local(Path.of("."))).run(form, Map.of());
        return picked.map(v -> String.valueOf(v.get("decision"))).orElse(null);
    }
}
