// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.Form;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One question an operation puts to a person, as the queue holds it at one moment: what asks
 * ({@code kind}), the question and the lines that explain it, the command a person runs to answer
 * it in a terminal, the form every surface renders, who raised it, its deadline, and how it was
 * settled. Any surface may answer it; the first answer wins.
 *
 * @param kind the operation that asks ({@code disk-clean}, {@code jdk-uninstall}, …)
 * @param command the terminal command that answers it, {@code jk decide <id>}
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

        static Optional<State> parse(@Nullable String wire) {
            for (State s : values()) {
                if (s.wire().equals(wire)) return Optional.of(s);
            }
            return Optional.empty();
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
                id, kind, question, detail, command, form, session, label, episode, project, createdAt, deadline, state,
                answers, surface, at);
    }

    /** The decision as {@code GET /api/decisions}, the SSE {@code decision} event and {@code jk decide} carry it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("question", question);
        m.put("detail", detail);
        m.put("command", command);
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

    /** Reads {@link #toMap}'s shape back; empty when {@code m} is not a whole decision. */
    static Optional<Decision> of(Map<?, ?> m) {
        String id = MiniJson.str(m, "id");
        String kind = MiniJson.str(m, "kind");
        String question = MiniJson.str(m, "question");
        String command = MiniJson.str(m, "command");
        State state = State.parse(MiniJson.str(m, "state")).orElse(null);
        if (id == null || kind == null || question == null || command == null || state == null) return Optional.empty();
        if (!(m.get("form") instanceof Map<?, ?> form)) return Optional.empty();
        List<String> detail = new ArrayList<>();
        for (Object line : MiniJson.list(m, "detail")) detail.add(String.valueOf(line));
        Map<String, Object> answers = new LinkedHashMap<>();
        if (m.get("answers") instanceof Map<?, ?> a) a.forEach((k, v) -> answers.put(String.valueOf(k), v));
        try {
            return Optional.of(new Decision(
                    id,
                    kind,
                    question,
                    detail,
                    command,
                    Form.fromJson(MiniJson.write(form)),
                    MiniJson.str(m, "session"),
                    MiniJson.str(m, "label"),
                    MiniJson.str(m, "episode"),
                    MiniJson.str(m, "project"),
                    millis(m, "createdAt"),
                    millis(m, "deadline"),
                    state,
                    answers,
                    DecisionSurface.parse(MiniJson.str(m, "answeredBy")).orElse(null),
                    millis(m, "answeredAt")));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static long millis(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0;
    }
}
