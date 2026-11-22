// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.prices.PriceTable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Tokens agents did not spend, by the model they would have been spent on and the kind each is priced
 * as. An estimate from the bench's measured savings per graph call ({@link SavingsRates}), never a
 * count of this machine's tokens. Kept by model id and priced only when shown ({@link #priced}), so a
 * model the price table does not know yet is priced once a table that knows it arrives.
 *
 * @param byModel the tokens per model id
 */
public record TokensSaved(Map<String, Counts> byModel) {

    public static final TokensSaved NONE = new TokensSaved(Map.of());

    public TokensSaved {
        byModel = Map.copyOf(byModel);
    }

    /**
     * One model's tokens by the kind each is priced as.
     *
     * @param cacheWrite input written to the prompt cache for five minutes
     * @param cacheWrite1h input written to the prompt cache for an hour, priced apart
     */
    public record Counts(long input, long output, long cacheRead, long cacheWrite, long cacheWrite1h) {

        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0);

        public long total() {
            return input + output + cacheRead + cacheWrite + cacheWrite1h;
        }

        Counts plus(Counts o) {
            return new Counts(
                    input + o.input,
                    output + o.output,
                    cacheRead + o.cacheRead,
                    cacheWrite + o.cacheWrite,
                    cacheWrite1h + o.cacheWrite1h);
        }
    }

    /** {@code counts} on {@code model}. */
    static TokensSaved of(String model, Counts counts) {
        return counts.equals(Counts.ZERO) ? NONE : new TokensSaved(Map.of(model, counts));
    }

    public long total() {
        long n = 0;
        for (Counts c : byModel.values()) n += c.total();
        return n;
    }

    TokensSaved plus(TokensSaved o) {
        if (o.byModel.isEmpty()) return this;
        if (byModel.isEmpty()) return o;
        Map<String, Counts> sum = new TreeMap<>(byModel);
        o.byModel.forEach((model, c) -> sum.merge(model, c, Counts::plus));
        return new TokensSaved(sum);
    }

    /**
     * What the tokens would have cost at {@code table}'s API list prices.
     *
     * @param usdMicros the dollars, in millionths, so rows from many projects or machines add up exactly
     * @param unpriced the tokens on a model the table has no row for, left out of {@code usdMicros}
     * @param retrieved the newest date a price behind {@code usdMicros} was read from its vendor's page,
     *     or null when none was
     */
    public record Priced(
            long usdMicros, long unpriced, @Nullable LocalDate retrieved) {}

    public Priced priced(PriceTable table) {
        double micros = 0;
        long unpriced = 0;
        LocalDate retrieved = null;
        for (Map.Entry<String, Counts> e : byModel.entrySet()) {
            Counts c = e.getValue();
            PriceTable.ModelPrice p = table.price(e.getKey()).orElse(null);
            if (p == null) {
                unpriced += c.total();
                continue;
            }
            micros += p.usd(c.input(), c.output(), c.cacheRead(), c.cacheWrite(), PriceTable.CacheWindow.FIVE_MINUTES)
                    + p.usd(0, 0, 0, c.cacheWrite1h(), PriceTable.CacheWindow.ONE_HOUR);
            if (retrieved == null || p.retrieved().isAfter(retrieved)) retrieved = p.retrieved();
        }
        return new Priced(Math.round(micros * 1e6), unpriced, retrieved);
    }

    /**
     * The tokens and dollars as {@code jk stats} prints them, marked as the estimate they are: {@code
     * ~1.2M, ~$0.840 (estimate, API list prices of 2026-11-17)}, and how many of the calls behind them
     * the bench has not measured, {@code notMeasured}, so left out.
     */
    public String text(PriceTable table, long notMeasured) {
        Priced p = priced(table);
        return "~" + compact(total()) + ", ~" + dollars(p.usdMicros()) + " (estimate, API list prices"
                + (p.retrieved() == null ? "" : " of " + p.retrieved())
                + (p.unpriced() == 0 ? "" : "; " + compact(p.unpriced()) + " on models with no price")
                + notMeasured(notMeasured) + ")";
    }

    /** The clause that says {@code n} calls were left out because the bench has not measured them. */
    static String notMeasured(long n) {
        return n == 0 ? "" : "; " + n + (n == 1 ? " call" : " calls") + " not measured by the bench, not counted";
    }

    /** {@code n} in a few characters: {@code 950}, {@code 12k}, {@code 1.2M}. */
    static String compact(long n) {
        if (Math.abs(n) >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (Math.abs(n) >= 1_000) return String.format(Locale.ROOT, "%.0fk", n / 1e3);
        return Long.toString(n);
    }

    /** Millionths of a dollar as dollars: three decimals under one dollar, cents above. */
    static String dollars(long micros) {
        double usd = micros / 1e6;
        return Math.abs(usd) < 1 ? String.format(Locale.ROOT, "$%.3f", usd) : String.format(Locale.ROOT, "$%,.2f", usd);
    }

    /**
     * The tokens per model, as the rows of {@code jk stats -O jsonl} and {@code /api/stats} carry them:
     * one object per model id, sorted, so rows from many projects or machines can be added up and
     * priced again.
     */
    List<Map<String, Object>> rows() {
        List<Map<String, Object>> out = new ArrayList<>();
        new TreeMap<>(byModel).forEach((model, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model);
            m.put("input", c.input());
            m.put("output", c.output());
            m.put("cacheRead", c.cacheRead());
            m.put("cacheWrite", c.cacheWrite());
            m.put("cacheWrite1h", c.cacheWrite1h());
            out.add(m);
        });
        return out;
    }
}
