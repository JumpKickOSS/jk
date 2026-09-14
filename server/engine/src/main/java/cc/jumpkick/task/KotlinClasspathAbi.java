// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Whole-entry Kotlin ABI token for a compile-classpath entry: {@code kt-abi:<sha256>} of the
 * Build Tools API classpath-entry snapshot's bytes — the same snapshot the incremental compile
 * consults to decide what a dependency change invalidates. That snapshot is the right notion of
 * ABI for kotlinc, where a JVM-signature ABI is unsound: inline function bodies, {@code const val}
 * values, {@code @PublishedApi} and the {@code kotlin.Metadata} annotation all decide what a
 * consumer compiles to, and the snapshot covers them.
 *
 * <p>The snapshot is computed by the Kotlin worker, never in the engine (the compiler stays out of
 * the engine's classpath), through the {@link Snapshotter} the caller supplies. Tokens are memoized
 * on {@link ClasspathFingerprint#entry content identity}, so a jar seen once — under any path — is a
 * lookup, and a sibling jar rewritten with the same ABI costs one snapshot and then keys the same.
 * An entry the snapshotter does not report (absent snapshot dir, a worker that could not read it)
 * keys on its full content identity instead: strictly finer, so never a false hit. A missing entry
 * is {@code missing:<module-relative path>}, as everywhere else.
 */
public final class KotlinClasspathAbi {

    public static final String PREFIX = "kt-abi:";

    /** Memo namespace: the JVM ABI memo keys the same content identities to different tokens. */
    private static final String MEMO_NAMESPACE = "kotlin:";

    private KotlinClasspathAbi() {}

    /**
     * Writes the ABI snapshots of {@code entries} and returns each entry's snapshot digest (SHA-256
     * hex of the snapshot bytes), keyed by the absolute normalized entry path. Entries left out
     * could not be snapshotted; the caller keys them on content.
     */
    @FunctionalInterface
    public interface Snapshotter {
        Map<Path, String> snapshot(List<Path> entries) throws IOException;

        /**
         * The content identity an entry's token is memoized under. On disk by default; a forecast
         * answers for a wiped sibling tree with the identity of the tree the build restores, so
         * the snapshot digest the build memoized against that tree is the token here too.
         */
        default String identity(Path entry) throws IOException {
            return ClasspathFingerprint.entry(entry);
        }
    }

    /**
     * Never snapshots: a token is either already memoized or the entry's full content identity.
     * For read-only callers (the forecast) that must not fork a worker.
     */
    public static final Snapshotter MEMOIZED_ONLY = entries -> Map.of();

    /**
     * {@link #MEMOIZED_ONLY} reading each entry's identity through {@code identity}: a wiped
     * entry the forecast knows the build restores keys as the restored bytes, and hits the memo
     * when the build has snapshotted those bytes before.
     */
    public static Snapshotter memoizedOnly(ClasspathFingerprint.EntryIdentity identity) {
        return new Snapshotter() {
            @Override
            public Map<Path, String> snapshot(List<Path> entries) {
                return Map.of();
            }

            @Override
            public String identity(Path entry) throws IOException {
                return identity.of(entry);
            }
        };
    }

    /** The token of one entry; see {@link #tokens}. */
    public static String token(Path entry, Snapshotter snapshotter) throws IOException {
        return tokens(List.of(entry), snapshotter).getFirst();
    }

    /**
     * One token per entry, in the entries' order. Memo hits and missing entries are answered
     * without the snapshotter; the rest go to it in one call, deduplicated by content identity so
     * the same bytes at two paths are snapshotted once.
     */
    public static List<String> tokens(List<Path> entries, Snapshotter snapshotter) throws IOException {
        int n = entries.size();
        Path[] abs = new Path[n];
        String[] identities = new String[n];
        // Pending entries hold the empty string until the snapshotter has answered.
        String[] tokens = new String[n];
        Arrays.fill(tokens, "");
        // Content identity → the one path asked for it; the token answers every path sharing it.
        Map<String, Path> toSnapshot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            abs[i] = entries.get(i).toAbsolutePath().normalize();
            String identity = snapshotter.identity(abs[i]);
            identities[i] = identity;
            if (identity.startsWith("missing:")) {
                tokens[i] = identity;
                continue;
            }
            String hit = AbiMemo.get(MEMO_NAMESPACE + identity);
            if (hit != null) {
                tokens[i] = hit;
                continue;
            }
            toSnapshot.putIfAbsent(identity, abs[i]);
        }
        Map<Path, String> digests =
                toSnapshot.isEmpty() ? Map.of() : snapshotter.snapshot(new ArrayList<>(toSnapshot.values()));
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                @Nullable Path asked = toSnapshot.get(identities[i]);
                @Nullable String digest = asked == null ? null : digests.get(asked);
                if (digest != null && Hashing.isHex(digest, 64)) {
                    token = PREFIX + digest;
                    AbiMemo.put(MEMO_NAMESPACE + identities[i], token);
                } else {
                    // No snapshot: the full content identity keys this entry — a finer token, so
                    // a body-only rewrite misses but nothing stale is ever served. Not memoized:
                    // the next sighting asks again.
                    token = identities[i];
                }
            }
            out.add(token);
        }
        return List.copyOf(out);
    }
}
