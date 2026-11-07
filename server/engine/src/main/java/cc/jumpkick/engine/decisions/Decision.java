// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.Form;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One question an operation puts to a person, as the queue holds it at one moment: what asks
 * ({@code kind}), the question and the lines that explain it, the command a person runs to answer
 * it in a terminal, the form every surface renders, whether it changes what the project trusts, who
 * raised it, its deadline, and how it was settled.
 *
 * @param kind the operation that asks ({@code update}, {@code disk-clean}, …)
 * @param command the terminal command that answers it, {@code jk decide <id>}
 * @param trust true when the answer changes what the project trusts: the dashboard may show it and
 *     decline it, never accept it
 * @param session the session id of the agent that raised it, or null for none
 * @param label that session's label ({@code claude-code 3f9a}), or null
 * @param episode the session's episode when it was raised, or null
 * @param project the checkout it concerns, or null
 * @param createdAt epoch millis
 * @param deadline epoch millis after which an open decision expires as declined
 * @param answers the settled form values; empty while open, declined or expired
 * @param answeredBy where the settling answer came from; null while open and when it expired
 * @param answeredAt epoch millis of the settling answer or the expiry; 0 while open
 */
public record Decision(
        String id,
        String kind,
        String question,
        List<String> detail,
        String command,
        Form form,
        boolean trust,
        @Nullable String session,
        @Nullable String label,
        @Nullable String episode,
        @Nullable String project,
        long createdAt,
        long deadline,
        State state,
        Map<String, Object> answers,
        @Nullable DecisionSurface answeredBy,
        long answeredAt) {

    /** How a decision stands. Only {@link #OPEN} takes an answer. */
    public enum State {
        OPEN,
        ACCEPTED,
        DECLINED,
        EXPIRED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Decision {
        detail = List.copyOf(detail);
        answers = Map.copyOf(answers);
    }

    public boolean open() {
        return state == State.OPEN;
    }

    public boolean accepted() {
        return state == State.ACCEPTED;
    }

    /** The dashboard route that shows it. */
    public String route() {
        return "#decision/" + id;
    }

    /** This decision settled as {@code state} by {@code surface} at {@code at}. */
    Decision settled(State state, Map<String, Object> answers, @Nullable DecisionSurface surface, long at) {
        return new Decision(
                id, kind, question, detail, command, form, trust, session, label, episode, project, createdAt, deadline,
                state, answers, surface, at);
    }

    /** The decision as {@code GET /api/decisions}, the SSE {@code decision} event and {@code jk decide} carry it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("question", question);
        m.put("detail", detail);
        m.put("command", command);
        m.put("trust", trust);
        m.put("form", MiniJson.parse(form.toJson()));
        if (session != null) m.put("session", session);
        if (label != null) m.put("label", label);
        if (episode != null) m.put("episode", episode);
        if (project != null) m.put("project", project);
        m.put("createdAt", createdAt);
        m.put("deadline", deadline);
        m.put("state", state.wire());
        if (!answers.isEmpty()) m.put("answers", answers);
        if (answeredBy != null) m.put("answeredBy", answeredBy.wire());
        if (answeredAt > 0) m.put("answeredAt", answeredAt);
        m.put("route", route());
        return m;
    }
}
