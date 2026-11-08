// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.schema;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockSchemas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.SchemaEntry;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk lock}'s {@code [[schema]]} rows: each module's declared subjects and every version they
 * reference, fetched from the registry with their text put in the store. A module whose subjects
 * the previous lock already answers keeps those rows, so {@code latest} moves only when the lock is
 * written without them ({@code jk update}, {@code jk lock -F}).
 */
public final class SchemaPins {

    private SchemaPins() {}

    /**
     * {@code lock} with the rows the tables under {@code lockDir} ask for. {@code previous} is the
     * lock whose rows may be kept, or null to fetch every subject again.
     */
    public static Lockfile pin(
            Lockfile lock,
            @Nullable Lockfile previous,
            Path lockDir,
            Map<Path, JkBuild> modules,
            Cas cas,
            Consumer<String> label)
            throws IOException, InterruptedException {
        List<SchemaEntry> rows = new ArrayList<>();
        for (LockSchemas.Declared declared : LockSchemas.declared(lockDir, modules)) {
            List<SchemaEntry> kept = previous == null ? null : kept(previous, declared);
            if (kept != null) {
                rows.addAll(kept);
                continue;
            }
            label.accept("fetching " + declared.subjects().size() + " schema subjects");
            rows.addAll(fetch(declared, cas));
        }
        return lock.withSchemas(rows);
    }

    private static @Nullable List<SchemaEntry> kept(Lockfile previous, LockSchemas.Declared declared) {
        try {
            return LockSchemas.closure(previous.schemas(), declared);
        } catch (IllegalStateException | IllegalArgumentException unpinnedOrSplit) {
            return null;
        }
    }

    /** Every declared subject of {@code declared} and each version it references, from the registry. */
    static List<SchemaEntry> fetch(LockSchemas.Declared declared, Cas cas) throws IOException, InterruptedException {
        RegistryClient client = RegistryClient.of(declared);
        Map<String, SchemaEntry> byKey = new LinkedHashMap<>();
        Deque<String[]> work = new ArrayDeque<>();
        declared.subjects().forEach((subject, version) -> work.add(new String[] {subject, version}));
        while (!work.isEmpty()) {
            String[] next = work.poll();
            if (byKey.containsKey(next[0] + "@" + next[1])) continue;
            RegistryClient.Version served = client.version(next[0], next[1]);
            byte[] text = served.schema().getBytes(StandardCharsets.UTF_8);
            String sha = Hashing.sha256Hex(text);
            cas.put(text, sha);
            List<String> refs = new ArrayList<>();
            for (RegistryClient.Reference ref : served.references()) {
                refs.add(ref.key());
                work.add(new String[] {ref.subject(), Integer.toString(ref.version())});
            }
            SchemaEntry row = new SchemaEntry(
                    declared.module(),
                    declared.registry(),
                    served.subject(),
                    served.version(),
                    served.id(),
                    served.type(),
                    "sha256:" + sha,
                    refs);
            byKey.put(row.key(), row);
        }
        List<SchemaEntry> rows = List.copyOf(byKey.values());
        LockSchemas.oneVersionEach(rows);
        return rows;
    }
}
