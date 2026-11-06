// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldLine;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.McpFlattening;
import cc.jumpkick.form.Step;
import cc.jumpkick.mcp.McpRevision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * How MCP {@code new} settles a template's form before it scaffolds. A field the call answered
 * keeps its answer and a field with a default takes it; only a required field left with neither is
 * asked. A client that declared form elicitation is asked for exactly those fields, one step at a
 * time; otherwise the call fails with each missing key and the values it takes, so the agent can
 * fill them and call again rather than guess.
 */
final class McpNewForm {

    /** How long the call waits for the human; under the tool-call timeouts hosts use. */
    static final Duration WAIT = Duration.ofMinutes(5);

    private static final String NEEDS = " needs a value";

    private McpNewForm() {}

    /**
     * The settled answers to {@code form}, starting from {@code known} (typed, keys of the form
     * only), which gains whatever the human answers.
     *
     * @throws McpError {@code -32602} for an answer that does not fit, or for required fields still
     *     missing; {@code -32000} when the human declined
     */
    static FormCheck settle(McpCall in, Form form, Map<String, Object> known) {
        FormCheck check = form.check(known, DerivedDefaults.NONE);
        if (check.ok()) return check;
        refuseMisfits(form, check);
        McpConnection c = in.connection();
        if (c != null && c.elicitation().form()) {
            ask(in, form, known, c.revision());
            check = form.check(known, DerivedDefaults.NONE);
            if (check.ok()) return check;
            refuseMisfits(form, check);
        }
        // TODO: offer the web wizard, pre-filled with `known`, by URL elicitation once it serves a
        // new-project page a link can open.
        throw new McpError(-32602, missing(form, check));
    }

    /** Elicits the missing fields step by step; stops at the first round that gets no answers. */
    private static void ask(McpCall in, Form form, Map<String, Object> known, McpRevision revision) {
        for (Step step : form.steps()) {
            // Each round asks at least one field, so a step takes at most one round per field.
            for (int rounds = step.fields().size(); rounds > 0; rounds--) {
                McpFlattening.Elicitation next = McpFlattening.flatten(
                        form, step, known, revision, DerivedDefaults.NONE, (field, endpoint) -> List.of());
                if (next instanceof McpFlattening.Complete) break;
                if (!(next instanceof McpFlattening.Ask question) || !answer(in, form, question, known)) return;
            }
        }
    }

    /** One elicitation; false when it brought no answers. */
    private static boolean answer(McpCall in, Form form, McpFlattening.Ask question, Map<String, Object> known) {
        McpElicitResult result = in.elicit(
                new McpElicitation.Form(form.title() + ": " + question.message(), question.requestedSchema()), WAIT);
        switch (result) {
            case McpElicitResult.Accepted accepted -> {
                if (!(question.answers(accepted.content()) instanceof McpFlattening.Answered answered)
                        || answered.answers().isEmpty()) {
                    return false;
                }
                known.putAll(NewProjectForm.typed(form, answered.answers()));
                return true;
            }
            case McpElicitResult.Declined d -> throw declined(question);
            case McpElicitResult.Cancelled c -> throw declined(question);
            default -> {
                return false;
            }
        }
    }

    private static McpError declined(McpFlattening.Ask question) {
        return new McpError(
                -32000,
                "the user declined to answer "
                        + question.fields().stream().map(Field::key).collect(Collectors.joining(", "))
                        + "; nothing was created");
    }

    private static void refuseMisfits(Form form, FormCheck check) {
        List<String> wrong = new ArrayList<>();
        for (FormCheck.Problem p : check.problems()) {
            if (p.message().endsWith(NEEDS)) continue;
            Field f = form.field(p.key());
            wrong.add(f == null ? p.message() : p.message() + " (" + FieldLine.values(f) + ")");
        }
        if (!wrong.isEmpty()) throw new McpError(-32602, String.join("; ", wrong));
    }

    /** Each missing field as one line of what it takes, then what to do about it. */
    static String missing(Form form, FormCheck check) {
        StringBuilder out = new StringBuilder("new needs answers the call did not give:");
        for (FormCheck.Problem p : check.problems()) {
            Field f = form.field(p.key());
            out.append("\n  ").append(f == null ? p.message() : FieldLine.of(f));
        }
        return out.append("\nPass the template's keys in params and the others as arguments, then call again.")
                .toString();
    }
}
