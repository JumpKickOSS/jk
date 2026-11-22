// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.updates;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the engine's update check last did, in {@code <store>/updates/state.json}: when it last
 * asked, what came of it, the validators of the manifest it holds, the version of each kind it
 * applied and the ones still waiting to be. {@code jk doctor} reads it. A store wipe forgets it with
 * the data it describes.
 *
 * @param checked when the manifest was last asked for; null before the first check
 * @param outcome one plain phrase: what the last check or apply came to
 * @param pending kind to the manifest version that waits to be applied
 */
public record UpdateState(
        @Nullable Instant checked,
        String outcome,
        String etag,
        String lastModified,
        Map<String, String> applied,
        Map<String, String> pending) {

    /** Before any check. */
    public static final UpdateState NONE = new UpdateState(null, "", "", "", Map.of(), Map.of());

    /** The state file's name under {@link #dir()}. */
    public static final String FILE = "state.json";

    public UpdateState {
        applied = Map.copyOf(applied);
        pending = Map.copyOf(pending);
    }

    /** Where the check keeps the manifest it trusts and this state: {@code <store>/updates}. */
    public static Path dir() {
        return JkDirs.store().resolve("updates");
    }

    /** The state under {@code dir}; {@link #NONE} when there is none or it does not read. */
    public static UpdateState read(Path dir) {
        Path file = dir.resolve(FILE);
        try {
            if (!Files.isRegularFile(file)) return NONE;
            Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            return new UpdateState(
                    instant(MiniJson.str(root, "checked")),
                    text(root, "outcome"),
                    text(root, "etag"),
                    text(root, "lastModified"),
                    strings(MiniJson.get(root, "applied")),
                    strings(MiniJson.get(root, "pending")));
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    /** Write this state under {@code dir}, replacing the file whole. */
    public void write(Path dir) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        if (checked != null) root.put("checked", checked.toString());
        root.put("outcome", outcome);
        root.put("etag", etag);
        root.put("lastModified", lastModified);
        root.put("applied", new LinkedHashMap<>(applied));
        root.put("pending", new LinkedHashMap<>(pending));
        Files.createDirectories(dir);
        AtomicWrites.replace(dir.resolve(FILE), MiniJson.writePretty(root) + "\n");
    }

    public UpdateState checked(Instant at, String outcome) {
        return new UpdateState(at, outcome, etag, lastModified, applied, pending);
    }

    public UpdateState withOutcome(String outcome) {
        return new UpdateState(checked, outcome, etag, lastModified, applied, pending);
    }

    public UpdateState withValidators(String etag, String lastModified) {
        return new UpdateState(checked, outcome, etag, lastModified, applied, pending);
    }

    public UpdateState withPending(Map<String, String> pending) {
        return new UpdateState(checked, outcome, etag, lastModified, applied, pending);
    }

    /** {@code kind} applied at {@code version}: recorded, and no longer pending. */
    public UpdateState withApplied(String kind, String version) {
        Map<String, String> nowApplied = new LinkedHashMap<>(applied);
        nowApplied.put(kind, version);
        Map<String, String> stillPending = new LinkedHashMap<>(pending);
        stillPending.remove(kind);
        return new UpdateState(checked, outcome, etag, lastModified, nowApplied, stillPending);
    }

    private static @Nullable Instant instant(@Nullable String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(@Nullable Object root, String key) {
        String value = MiniJson.str(root, key);
        return value == null ? "" : value;
    }

    private static Map<String, String> strings(@Nullable Object node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof String s) out.put(String.valueOf(k), s);
            });
        }
        return out;
    }
}
