// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.prices;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Model API list prices, USD per million tokens by kind, as of {@link #published}. A table prices
 * tokens when asked and is never stored beside them: keep tokens by model id and price them at render
 * time, so a model the table does not know yet is priced once a table that knows it arrives.
 *
 * <p>The file is TOML: a top-level {@code published} offset date-time, then one {@code
 * [models."<id>"]} table per model with {@code input} and {@code output}, optional {@code
 * cached_input}, {@code cache_write} and {@code cache_write_1h}, and a {@code retrieved} date. Keys a
 * table does not know are skipped, so an older JumpKick reads a newer table. A published copy ends with
 * one {@link #SIGNATURE_PREFIX signature line} ({@link #signed}).
 */
public record PriceTable(Instant published, Map<String, ModelPrice> models) {

    /** The comment line a published table ends with: this prefix, the base64 signature, LF. */
    public static final String SIGNATURE_PREFIX = "# signature ";

    /** A model id: lowercase letters, digits and {@code . - _ : /}, at most 64 characters. */
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9._:/-]{1,64}");

    private static final PriceTable BUILT_IN = loadBuiltIn();

    public PriceTable {
        models = Map.copyOf(models);
    }

    /** What one model's tokens cost, USD per million, and the date the prices were read. */
    public record ModelPrice(
            double input,
            double output,
            double cachedInput,
            double cacheWrite,
            double cacheWrite1h,
            LocalDate retrieved) {

        /**
         * The list price of these tokens, in USD. A cache write is priced at the 5-minute or the
         * 1-hour rate as {@code window} says.
         */
        public double usd(long input, long output, long cacheRead, long cacheWrite, CacheWindow window) {
            double write = window == CacheWindow.ONE_HOUR ? cacheWrite1h : this.cacheWrite;
            return (input * this.input + output * this.output + cacheRead * cachedInput + cacheWrite * write)
                    / 1_000_000d;
        }
    }

    /** How long the agent's prompt cache is written for; the vendors price the two apart. */
    public enum CacheWindow {
        FIVE_MINUTES,
        ONE_HOUR
    }

    /**
     * The price row for {@code modelId}: the longest key that is the id or that the id starts with
     * followed by {@code -} ({@code grok-4.7-high} is priced as {@code grok-4.7}); empty when none is.
     */
    public Optional<ModelPrice> price(String modelId) {
        return models.keySet().stream()
                .filter(key -> modelId.equals(key) || modelId.startsWith(key + "-"))
                .max(Comparator.comparingInt(String::length))
                .map(models::get);
    }

    /**
     * Parse a table; the signature line, if any, is a comment and is not checked here.
     *
     * @throws IOException naming what is wrong when the bytes are not a price table
     */
    public static PriceTable parse(byte[] bytes) throws IOException {
        TomlParseResult toml = Toml.parse(utf8(bytes));
        if (toml.hasErrors())
            throw new IOException(
                    "price table is not valid TOML: " + toml.errors().getFirst());
        OffsetDateTime published = toml.isOffsetDateTime("published") ? toml.getOffsetDateTime("published") : null;
        if (published == null) throw new IOException("price table has no published date-time");
        TomlTable rows = toml.isTable("models") ? toml.getTable("models") : null;
        if (rows == null || rows.isEmpty()) throw new IOException("price table has no models");
        Map<String, ModelPrice> models = new LinkedHashMap<>();
        for (String id : rows.keySet()) {
            if (!MODEL_ID.matcher(id).matches()) throw new IOException("price table has a malformed model id");
            TomlTable row = rows.isTable(List.of(id)) ? rows.getTable(List.of(id)) : null;
            if (row == null) throw new IOException("price table row " + id + " is not a table");
            models.put(id, row(id, row));
        }
        return new PriceTable(published.toInstant(), models);
    }

    private static ModelPrice row(String id, TomlTable row) throws IOException {
        double input = rate(id, row, "input", null);
        double write = rate(id, row, "cache_write", input);
        LocalDate retrieved;
        try {
            retrieved = LocalDate.parse(String.valueOf(row.get("retrieved")));
        } catch (DateTimeParseException e) {
            throw new IOException("price table row " + id + " has no retrieved date");
        }
        return new ModelPrice(
                input,
                rate(id, row, "output", null),
                rate(id, row, "cached_input", input),
                write,
                rate(id, row, "cache_write_1h", write),
                retrieved);
    }

    /** A finite, non-negative rate; {@code fallback} when the key is absent, an error when that is null too. */
    private static double rate(String id, TomlTable row, String key, @Nullable Double fallback) throws IOException {
        Object value = row.get(key);
        if (value == null && fallback != null) return fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) {
            throw new IOException("price table row " + id + " has no valid " + key);
        }
        return n.doubleValue();
    }

    /** The table built into this JumpKick, {@code prices.toml} beside this class. */
    public static PriceTable builtIn() {
        return BUILT_IN;
    }

    private static PriceTable loadBuiltIn() {
        try (InputStream in =
                Objects.requireNonNull(PriceTable.class.getResourceAsStream("prices.toml"), "built-in price table")) {
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("the built-in price table does not parse", e);
        }
    }

    /** A published table split into the bytes its signature covers and the base64 signature. */
    public record Signed(byte[] content, String signature) {}

    /**
     * Split a published table: its last line must be {@link #SIGNATURE_PREFIX} and the base64
     * signature, LF-terminated, and the signature covers every byte before that line.
     *
     * @throws IOException when the file does not end with a signature line
     */
    public static Signed signed(byte[] bytes) throws IOException {
        String text = utf8(bytes);
        if (!text.endsWith("\n")) throw new IOException("price table has no signature line");
        int start = text.lastIndexOf('\n', text.length() - 2) + 1;
        String last = text.substring(start, text.length() - 1);
        if (!last.startsWith(SIGNATURE_PREFIX)) throw new IOException("price table has no signature line");
        int contentBytes = text.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
        return new Signed(
                Arrays.copyOf(bytes, contentBytes),
                last.substring(SIGNATURE_PREFIX.length()).strip());
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("price table is not UTF-8", e);
        }
    }
}
