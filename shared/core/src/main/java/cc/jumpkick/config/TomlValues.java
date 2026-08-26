// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Lenient TOML coercion for config views: wrong type / blank / absent → empty {@link Optional},
 * never throws. Works on root {@link TomlParseResult} and nested {@link TomlTable}s alike.
 */
public final class TomlValues {

    private TomlValues() {}

    /**
     * Parse a TOML file, swallowing IO and syntax errors. Empty when the path is {@code null},
     * absent, unreadable, or fails to parse — callers then fall back to their defaults instead of
     * failing the command.
     */
    public static Optional<TomlParseResult> parse(Path file) {
        if (file == null) return Optional.empty();
        Path key = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(key);
        if (stamp == null) return Optional.empty(); // absent or unreadable
        if (System.currentTimeMillis() - stamp.modified().toMillis() < SETTLE_MS) return read(key);
        Optional<TomlParseResult> hit = PARSED.get(key, stamp, () -> read(key));
        return hit == null ? Optional.empty() : hit;
    }

    /**
     * Memo of the parse, stamped on {@code (size, mtime)}.
     *
     * <p>Three user-config readers reach here — {@code JkTemplatesConfig}, {@code JkHistoryConfig},
     * {@code JkHttpConfig} — and none of them memoized, so each re-read and re-parsed
     * {@code ~/.config/jk/config.toml} on every {@code resolve()}. Memoizing here rather than at the
     * three callers is the same choice {@code TomlScan} made for its twenty-eight: the read is the
     * cost and one owner ends it for everyone (JK-1033).
     *
     * <p>An unsettled file bypasses the memo, for the reason {@code TomlScan} and
     * {@code JkBuildParser} both give: size+mtime cannot see a same-length edit inside one coarse
     * mtime tick, and a config file being edited is exactly when that matters.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, Optional<TomlParseResult>> PARSED =
            StampedMemo.create();

    /** Distrust {@code (size, mtime)} for a file modified within this window. */
    private static final long SETTLE_MS = 2_000;

    private static Optional<TomlParseResult> read(Path file) {
        try {
            TomlParseResult toml = Toml.parse(file);
            return toml.hasErrors() ? Optional.empty() : Optional.of(toml);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Test seam: drop every memoized parse. */
    public static void clearCache() {
        PARSED.clear();
    }

    /** A boolean value; absent table/key or wrong type → empty. */
    public static Optional<Boolean> optBoolean(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Boolean b) ? Optional.of(b) : Optional.empty();
    }

    /** A non-blank string value; absent table/key, blank, or wrong type → empty. */
    public static Optional<String> optString(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    /**
     * A TOML integer coerced to {@code int}; absent, non-integer, or out of {@code int} range →
     * empty. Callers needing a narrower range (e.g. only non-negative) should {@link Optional#filter}
     * the result.
     */
    public static Optional<Integer> optInt(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        if (table.get(key) instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return Optional.of(l.intValue());
        }
        return Optional.empty();
    }

    /** A TOML integer as {@code long}; absent or non-integer → empty. */
    public static Optional<Long> optLong(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Long l) ? Optional.of(l) : Optional.empty();
    }

    /** A TOML number (integer or float) as {@code double}; absent or non-number → empty. */
    public static Optional<Double> optDouble(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Number n) ? Optional.of(n.doubleValue()) : Optional.empty();
    }

    /**
     * A TOML array of strings; absent table/key or non-array → empty list. Non-string elements are
     * skipped rather than failing the whole list.
     */
    public static List<String> stringList(TomlTable table, String key) {
        if (table == null || !(table.getArray(key) instanceof TomlArray arr)) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) instanceof String s) out.add(s);
        }
        return out;
    }
}
