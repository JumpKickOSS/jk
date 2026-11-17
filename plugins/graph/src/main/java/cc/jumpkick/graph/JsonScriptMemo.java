// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.CodeText;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What a file says for JSON property anchors whatever route it requests: a script's type members
 * and whether it is headed as generated, asked of every script in the workspace; and what a file
 * that requests a route reads (its function blocks, the names it reads or sends, its jq fields).
 * Every relink asks them again, so they are kept by the file's content hash while the graph worker
 * is served: a refresh reads and scans again only the files whose content changed.
 */
final class JsonScriptMemo {

    /** One content's facts: headed as generated (then no types), else its types' members. */
    record Facts(boolean generated, List<JsonReaders.Member> types) {}

    /** What a requesting file reads: its text, function blocks, line starts, uses and jq fields. */
    record Reads(
            String body,
            JsonReaders.@Nullable Blocks blocks,
            int[] starts,
            List<JsonReaders.Use> uses,
            List<JsonReaders.Use> jq) {}

    /** One workspace's memo: facts and reads by content hash. */
    private record Store(Map<String, Facts> facts, Map<String, Reads> reads) {
        Store() {
            this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    /** Per workspace root, facts by content hash, across the refreshes of a served worker; the last few workspaces only. */
    private static final Map<String, Store> BY_WORKSPACE =
            Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Store> eldest) {
                    return size() > WORKSPACES;
                }
            });

    private static final int WORKSPACES = 4;

    /** No content hashes: every file is read (a caller without the refresh's hashes). */
    static final JsonScriptMemo NONE = new JsonScriptMemo("", Map.of());

    private final Map<String, String> shas;
    private final Store store;

    /** {@code shas}: each file of the workspace at {@code root}, by its content hash as this refresh computed it. */
    JsonScriptMemo(String root, Map<String, String> shas) {
        this.shas = shas;
        this.store = shas.isEmpty() ? new Store() : BY_WORKSPACE.computeIfAbsent(root, k -> new Store());
    }

    /** {@code rel}'s facts, from its content hash when known and seen before; null when it does not read. */
    @Nullable
    Facts of(String rel, Function<String, @Nullable String> text) {
        String sha = shas.get(rel);
        Facts had = sha == null ? null : store.facts().get(sha);
        if (had != null) return had;
        String body = text.apply(rel);
        if (body == null) return null;
        boolean generated = JsonReaders.generatedHeader(body);
        Facts f = new Facts(generated, generated ? List.of() : JsonReaders.scriptTypes(body));
        if (sha != null) store.facts().put(sha, f);
        return f;
    }

    /** What {@code rel} reads, {@code read} of its text, from its content hash when known and seen before; null when it does not read. */
    @Nullable
    Reads reads(String rel, Function<String, @Nullable String> text, BiFunction<String, String, Reads> read) {
        String sha = shas.get(rel);
        Reads had = sha == null ? null : store.reads().get(sha + ':' + rel);
        if (had != null) return had;
        String body = text.apply(rel);
        if (body == null) return null;
        Reads r = read.apply(rel, body);
        if (sha != null) store.reads().put(sha + ':' + rel, r);
        return r;
    }

    /** What a requesting file reads, by its kind: a script's or a JVM test's uses in their functions, a script's or shell's jq fields. */
    static Reads read(String rel, String body) {
        boolean script = JsonAnchors.SCRIPT.matcher(rel).matches();
        boolean jvm = !script && JsonAnchors.JVM.matcher(rel).matches();
        JsonReaders.@Nullable Blocks blocks = script || jvm
                ? JsonReaders.functions(CodeText.blank(body, CodeText.Blank.COMMENTS_AND_STRINGS, script))
                : null;
        List<JsonReaders.Use> uses =
                script ? JsonReaders.scriptUses(body) : jvm ? JsonReaders.jsonPathUses(body) : List.of();
        List<JsonReaders.Use> jq =
                script || JsonAnchors.SHELL.matcher(rel).matches() ? JsonReaders.jqUses(body) : List.of();
        return new Reads(body, blocks, JsonReaders.lineStarts(body), uses, jq);
    }

    /** Drops the facts of contents no file of this refresh has, so the memo holds the workspace as it is. */
    void retain() {
        if (shas.isEmpty()) return;
        store.facts().keySet().retainAll(new HashSet<>(shas.values()));
        Set<String> now = new HashSet<>();
        shas.forEach((rel, sha) -> now.add(sha + ':' + rel));
        store.reads().keySet().retainAll(now);
    }
}
