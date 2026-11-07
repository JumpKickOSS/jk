// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.runtime.NewProjectAgents;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldLine;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.McpFlattening;
import cc.jumpkick.form.Step;
import cc.jumpkick.mcp.McpRevision;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * How MCP {@code new} settles a template's form before it scaffolds. A field the call answered
 * keeps its answer and a field with a default takes it; only a required field left with neither is
 * asked. A client that declared form elicitation is asked for exactly those fields, one step at a
 * time. One that declared URL elicitation is sent to the dashboard's wizard, pre-filled with what
 * the call gave, and the call returns what the human created there. Otherwise the call fails with
 * each missing key and the values it takes, so the agent can fill them and call again rather than
 * guess.
 */
final class McpNewForm {

    /** How long the call waits for the human, at each step; under the tool-call timeouts hosts use. */
    static final Duration WAIT = Duration.ofMinutes(5);

    private static final String NEEDS = " needs a value";

    private McpNewForm() {}

    /** How the form settled: answers to scaffold, or a project the human created in the wizard. */
    sealed interface Settled {
        record Answers(FormCheck check) implements Settled {}

        /** {@code project} holds the created project's {@code path} and {@code projectId}. */
        record Created(Map<String, Object> project) implements Settled {}
    }

    /**
     * The settled answers to {@code form}, starting from {@code known} (typed, keys of the form
     * only), which gains whatever the human answers. With {@code wizard}, a client that declared URL
     * elicitation may be sent to the dashboard's wizard instead, and what it created is the answer.
     *
     * @throws McpError {@code -32602} for an answer that does not fit, or for required fields still
     *     missing; {@code -32000} when the human declined or left the wizard unfinished
     */
    static Settled settle(McpCall in, Form form, Map<String, Object> known, boolean wizard) {
        FormCheck check = form.check(known, DerivedDefaults.NONE);
        if (check.ok()) return new Settled.Answers(check);
        refuseMisfits(form, check);
        McpConnection c = in.connection();
        if (c != null && c.elicitation().form()) {
            ask(in, form, known, c.revision());
            check = form.check(known, DerivedDefaults.NONE);
            if (check.ok()) return new Settled.Answers(check);
            refuseMisfits(form, check);
        }
        NewProjectWizard site = in.ctx().wizard();
        if (wizard && c != null && c.elicitation().url() && site != null) {
            Map<String, Object> created = viaWizard(in, form, known, site);
            if (created != null) return new Settled.Created(created);
        }
        throw new McpError(-32602, missing(form, check));
    }

    /**
     * Sends the human to the wizard, pre-filled with {@code known}, and waits for its create; null
     * when the link could not be offered, so the caller falls back to naming what is missing.
     */
    private static @Nullable Map<String, Object> viaWizard(
            McpCall in, Form form, Map<String, Object> known, NewProjectWizard site) {
        String id = site.open(McpClientAgent.choice(in.connection(), NewProjectAgents.current()));
        try {
            String link = site.link(known, id);
            if (link == null) return null;
            String message = form.title() + ": finish it in the dashboard, which holds what the agent gave";
            switch (in.elicit(new McpElicitation.Url(message, link, id), WAIT)) {
                case McpElicitResult.Accepted accepted -> {}
                case McpElicitResult.Declined d ->
                    throw new McpError(-32000, "the user declined to open the wizard; nothing was created");
                case McpElicitResult.Cancelled c ->
                    throw new McpError(-32000, "the user declined to open the wizard; nothing was created");
                default -> {
                    return null;
                }
            }
            Map<String, Object> project = in.ctx().admissionYield().yielding(() -> awaitCreate(site, id));
            completed(in, id);
            return project;
        } finally {
            site.close(id);
        }
    }

    private static Map<String, Object> awaitCreate(NewProjectWizard site, String id) {
        try {
            return site.created(id).get(site.waiting().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpError(
                    -32000,
                    "the wizard was not finished within " + span(site.waiting()) + "; nothing was created"
                            + " yet, and a project made through the link later is not reported to this call");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpError(-32000, "interrupted while waiting for the wizard");
        } catch (ExecutionException e) {
            throw new McpError(-32000, "the wizard failed: " + e.getCause());
        }
    }

    /** {@code d} in whole minutes, or seconds under one. */
    private static String span(Duration d) {
        return d.toMinutes() > 0 ? d.toMinutes() + " minutes" : d.toSeconds() + " seconds";
    }

    /** Tells the client the interaction {@code id} named is over. */
    private static void completed(McpCall in, String id) {
        McpOutbound out = in.outbound();
        if (out == null) return;
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/elicitation/complete");
        note.put("params", Map.of("elicitationId", id));
        try {
            out.send(note);
        } catch (IOException | RuntimeException ignored) {
            // The client is gone; the project exists all the same.
        }
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
