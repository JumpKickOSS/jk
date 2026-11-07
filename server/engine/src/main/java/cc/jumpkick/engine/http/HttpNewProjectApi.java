// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.http.mcp.NewProjectWizard;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.runtime.NewProjectAgents;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.engine.runtime.NewProjectPost;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.scaffold.NewGroupGuess;
import cc.jumpkick.scaffold.NewParentDirGuess;
import cc.jumpkick.scaffold.NewProjectStep;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The new-project form over HTTP, for the dashboard's wizard: the engine's form (jk's step and the
 * chosen template's), a preview of what its answers scaffold, and the create. A posted
 * {@code rev} that no longer names the form's content answers {@code 409} with the form as it is
 * now, so the wizard re-asks what changed.
 *
 * <pre>
 * GET  /api/forms/new-project?template=&amp;lang=  {form, template, derived}
 * POST /api/projects/preview                      {rev, answers} → {path, template, files, manifest}
 * POST /api/projects                              {rev, answers, elicitation?} → {path, dir, projectId, agents}
 * </pre>
 *
 * {@code agents} is the agent wiring the create did for the engine's agents setting: {@code files}
 * written, by path under the project, and the {@code notes} left to the user.
 *
 * {@code derived} holds the values of the form's derived defaults ({@code git-email},
 * {@code parent-dir}) on this machine; an answer left out takes the same value here.
 */
final class HttpNewProjectApi {

    private final BuildJournal journal;
    private final NewProjectWizard wizard;

    HttpNewProjectApi(BuildJournal journal, NewProjectWizard wizard) {
        this.journal = journal;
        this.wizard = wizard;
    }

    /** {@code GET /api/forms/new-project?template=<ref>&lang=<lang>}; no template is the default one. */
    void handleForm(HttpExchange exchange) throws IOException {
        String template;
        String lang;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            template = HttpQuery.queryParam(q, "template");
            lang = HttpQuery.queryParam(q, "lang");
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
            return;
        }
        Map<String, String> derived = derived();
        NewProjectOps.TemplateForm t;
        try {
            t = NewProjectPost.templateForm(template, lang, derived);
        } catch (IllegalArgumentException | IOException e) {
            error(exchange, 400, Errors.text(e));
            return;
        }
        HttpResponses.sendJson(exchange, 200, formBody(NewProjectForm.of(t), t.id(), derived, null));
    }

    /** {@code POST /api/projects/preview}: the files and {@code jk.toml} the answers would scaffold. */
    void handlePreview(HttpExchange exchange) throws IOException {
        Settled s = settle(exchange);
        if (s == null) return;
        try {
            NewProjectOps.Preview p = NewProjectOps.preview(s.request());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("path", p.path());
            body.put("template", s.template());
            body.put("files", p.files());
            body.put("manifest", p.manifest());
            HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (IllegalStateException e) {
            error(exchange, 409, e.getMessage());
        } catch (IOException e) {
            error(exchange, 500, Errors.text(e));
        }
    }

    /**
     * {@code POST /api/projects}: scaffold the answers. An {@code elicitation} id from a wizard link
     * hands the result to the MCP call waiting on it.
     */
    void handleCreate(HttpExchange exchange) throws IOException {
        Settled s = settle(exchange);
        if (s == null) return;
        try {
            // The SPA routes #project/<id> immediately, so identity materializes with creation.
            NewProjectOps.Created created = NewProjectOps.createWithIdentity(s.request());
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("path", created.path().toString());
            if (created.projectId() != null) project.put("projectId", created.projectId());
            project.put("agents", NewProjectAgents.json(created.agents(), created.path()));
            if (s.elicitation() != null) wizard.complete(s.elicitation(), project);
            Map<String, Object> body = new LinkedHashMap<>(project);
            body.put("dir", created.path().toString());
            HttpResponses.sendJson(exchange, 201, MiniJson.write(body));
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (IllegalStateException e) {
            error(exchange, 409, e.getMessage());
        } catch (IOException e) {
            error(exchange, 500, e.getMessage() == null ? "scaffold failed" : e.getMessage());
        }
    }

    /** Posted answers checked against the form their template names, as a scaffold request. */
    private record Settled(
            NewProjectOps.Request request,
            String template,
            @Nullable String elicitation) {}

    /**
     * Reads {@code {rev, answers, elicitation}}, builds the form the answers' template and language
     * name, and settles the answers against it; null after answering 400 (a malformed body, an
     * unknown template, answers that do not fit) or 409 (a stale {@code rev}).
     */
    private @Nullable Settled settle(HttpExchange exchange) throws IOException {
        Object body;
        try {
            body = MiniJson.parse(HttpRequests.body(exchange));
        } catch (IllegalArgumentException e) {
            error(exchange, 400, "body is not JSON: " + e.getMessage());
            return null;
        }
        if (!(MiniJson.get(body, "answers") instanceof Map<?, ?> raw)) {
            error(exchange, 400, "missing \"answers\"");
            return null;
        }
        Map<String, Object> answers = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null) answers.put(String.valueOf(k), v);
        });
        Map<String, String> derived = derived();
        NewProjectPost.Outcome outcome;
        try {
            outcome = NewProjectPost.settle(answers, MiniJson.str(body, "rev"), derived);
        } catch (IllegalArgumentException | IOException e) {
            error(exchange, 400, Errors.text(e));
            return null;
        }
        switch (outcome) {
            case NewProjectPost.Outcome.Stale stale -> {
                HttpResponses.sendJson(
                        exchange, 409, formBody(stale.form(), stale.template(), derived, stale.message()));
                return null;
            }
            case NewProjectPost.Outcome.Refused refused -> {
                List<Map<String, Object>> problems = new ArrayList<>();
                for (FormCheck.Problem p : refused.problems()) problems.add(problem(p.key(), p.message()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", refused.problems().getFirst().message());
                out.put("problems", problems);
                HttpResponses.sendJson(exchange, 400, MiniJson.write(out));
                return null;
            }
            case NewProjectPost.Outcome.Ready ready -> {
                return new Settled(
                        ready.request(),
                        ready.template(),
                        blankToNull(MiniJson.str(body, NewProjectWizard.ELICITATION)));
            }
        }
    }

    private static String formBody(Form form, String template, Map<String, String> derived, @Nullable String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (error != null) out.put("error", error);
        out.put("form", MiniJson.parse(form.toJson()));
        out.put("template", template);
        out.put("derived", derived);
        return MiniJson.write(out);
    }

    /**
     * This machine's values for the form's derived defaults: the group from the git email, as
     * {@code jk new} guesses it, and the parent directory from build history and well-known roots.
     */
    private Map<String, String> derived() {
        List<Path> historyDirs = new ArrayList<>();
        try {
            for (var rec : journal.list()) {
                if (rec != null && rec.dir() != null && !rec.dir().isBlank()) historyDirs.add(Path.of(rec.dir()));
            }
        } catch (RuntimeException e) {
            // An empty or unreadable journal: the guess works without history.
            Log.debug("new-project form: journal unreadable", e);
        }
        String home = System.getProperty("user.home");
        Map<String, String> out = new LinkedHashMap<>();
        out.put(NewProjectStep.GIT_EMAIL, NewGroupGuess.guess());
        out.put(
                NewProjectStep.PARENT_DIR,
                NewParentDirGuess.guess(home == null ? null : Path.of(home), historyDirs)
                        .toString());
        return out;
    }

    private static Map<String, Object> problem(String key, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("message", message);
        return p;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void error(HttpExchange exchange, int status, @Nullable String message) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message == null ? "request failed" : message);
        HttpResponses.sendJson(exchange, status, MiniJson.write(out));
    }
}
