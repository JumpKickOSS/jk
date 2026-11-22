// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The tokens an agent used in an episode, as the agent itself counted them in its session files:
 * real figures, not jk's estimate. Only numbers and a model id of a model id's shape ever come from
 * those files.
 *
 * @param agent who counted them, as the receipt names it ({@code Claude Code}, {@code Codex})
 * @param byModel the counts per model id, {@link #UNKNOWN} for one that has no model id's shape
 * @param wholeSession the agent counts a whole session at its end (Copilot CLI), so the figure is
 *     its session's, not only this episode's
 */
public record AgentTokens(String agent, Map<String, Counts> byModel, boolean wholeSession) {

    /** The model of a count whose model id was missing or did not look like one. */
    public static final String UNKNOWN = "unknown";

    /** What a model id looks like: lowercase letters, digits and {@code . - _ : /}, at most 64 characters. */
    private static final Pattern MODEL = Pattern.compile("[a-z0-9._:/-]{1,64}");

    public AgentTokens {
        byModel = Map.copyOf(byModel);
    }

    /**
     * One model's counts; every one is tokens.
     *
     * @param input input tokens read fresh, neither written to nor read from the prompt cache
     * @param cacheWrite input tokens written to the prompt cache
     * @param cacheRead input tokens read from the prompt cache
     * @param output output tokens, reasoning included
     */
    public record Counts(long input, long cacheWrite, long cacheRead, long output) {

        public static final Counts ZERO = new Counts(0, 0, 0, 0);

        public Counts plus(Counts o) {
            return new Counts(input + o.input, cacheWrite + o.cacheWrite, cacheRead + o.cacheRead, output + o.output);
        }

        public long total() {
            return input + cacheWrite + cacheRead + output;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("input", input);
            m.put("cacheWrite", cacheWrite);
            m.put("cacheRead", cacheRead);
            m.put("output", output);
            m.put("total", total());
            return m;
        }
    }

    /** {@code raw} when it has a model id's shape, else {@link #UNKNOWN}. */
    static String model(@Nullable Object raw) {
        return raw instanceof String s && MODEL.matcher(s).matches() ? s : UNKNOWN;
    }

    /** Every model's counts added up. */
    public Counts sum() {
        Counts all = Counts.ZERO;
        for (Counts c : byModel.values()) all = all.plus(c);
        return all;
    }

    /** As {@code GET /api/sessions/<id>} serves it under an episode's {@code saved.tokens}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent);
        m.put("wholeSession", wholeSession);
        m.putAll(sum().toMap());
        List<Map<String, Object>> models = new ArrayList<>();
        byModel.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", e.getKey());
            row.putAll(e.getValue().toMap());
            models.add(row);
        });
        m.put("models", models);
        return m;
    }

    /**
     * The receipt's line: {@code Tokens the agent used: 1,234 (Claude Code's own count, 1,000 read from
     * its cache)}, with {@code whole session} after the count's owner when it is a whole session's.
     */
    public String line() {
        Counts s = sum();
        String session = wholeSession ? ", whole session" : "";
        String cached = s.cacheRead() > 0 ? ", " + grouped(s.cacheRead()) + " read from its cache" : "";
        return "Tokens the agent used: " + grouped(s.total()) + " (" + agent + "'s own count" + session + cached + ")";
    }

    private static String grouped(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
