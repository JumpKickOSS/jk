// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code [schema-registry]} tables under a lock ask for, and the {@code [[schema]]} rows
 * that answer them: each declared subject pinned at its version (any version for {@code latest}),
 * and every subject version a pinned schema references.
 */
public final class LockSchemas {

    /** The table every module declares its registry subjects in. */
    public static final String TABLE = "schema-registry";

    /** The version a subject follows until {@code jk update} or {@code jk lock -F} moves it. */
    public static final String LATEST = "latest";

    private LockSchemas() {}

    /**
     * One module's table: {@code subjects} maps a subject to a version number or {@code latest}.
     *
     * @param module the module path under the lock; null for the lock's own project
     * @param repository the id the registry's credentials are read under; null for its host
     */
    public record Declared(
            @Nullable String module,
            String registry,
            Map<String, String> subjects,
            @Nullable String repository) {

        public Declared {
            registry = registry.endsWith("/") ? registry.substring(0, registry.length() - 1) : registry;
            subjects = Map.copyOf(new LinkedHashMap<>(subjects));
        }
    }

    /** The declared table of each manifest under {@code owner} that has one, by module path. */
    public static List<Declared> declared(Path owner, Map<Path, JkBuild> modules) {
        Path root = owner.toAbsolutePath().normalize();
        List<Declared> out = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            String rel = root.relativize(e.getKey().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            of(rel.isEmpty() ? null : rel, e.getValue()).ifPresent(out::add);
        }
        return out;
    }

    /** {@code build}'s table, or empty when it declares none. */
    public static Optional<Declared> of(@Nullable String module, JkBuild build) {
        return build.pluginConfig(TABLE).map(config -> declared(module, config));
    }

    private static Declared declared(@Nullable String module, PluginConfig config) {
        return new Declared(
                module,
                config.string("url"),
                config.stringMap("subjects"),
                config.stringOpt("repository").orElse(null));
    }

    /**
     * The row pinning {@code subject} as {@code declared} asks for it, the newest pinned one for
     * {@code latest}, or null when none does.
     */
    public static @Nullable SchemaEntry row(List<SchemaEntry> rows, Declared declared, String subject) {
        String version = declared.subjects().get(subject);
        String exact = version == null || LATEST.equals(version) ? null : version;
        SchemaEntry found = null;
        for (SchemaEntry row : rows) {
            if (!row.answersFor(declared.module())
                    || !row.registry().equals(declared.registry())
                    || !row.subject().equals(subject)) continue;
            boolean match = exact == null
                    ? found == null || row.version() > found.version()
                    : exact.equals(Integer.toString(row.version()));
            if (match) found = row;
        }
        return found;
    }

    /**
     * The rows a module reads: each declared subject's and, transitively, every version they
     * reference. Throws naming the first subject the lock does not pin, or a subject two
     * versions of which meet.
     */
    public static List<SchemaEntry> closure(List<SchemaEntry> rows, Declared declared) {
        Map<String, SchemaEntry> byKey = new LinkedHashMap<>();
        for (SchemaEntry row : rows) {
            if (row.answersFor(declared.module()) && row.registry().equals(declared.registry()))
                byKey.put(row.key(), row);
        }
        Set<String> seen = new LinkedHashSet<>();
        Deque<SchemaEntry> work = new ArrayDeque<>();
        for (String subject : declared.subjects().keySet()) {
            SchemaEntry row = row(rows, declared, subject);
            if (row == null) throw new IllegalStateException(subject + ": not in jk-lock.toml; run jk lock");
            work.add(row);
        }
        List<SchemaEntry> out = new ArrayList<>();
        while (!work.isEmpty()) {
            SchemaEntry row = work.poll();
            if (!seen.add(row.key())) continue;
            out.add(row);
            for (String ref : row.references()) {
                SchemaEntry referenced = byKey.get(ref);
                if (referenced == null) throw new IllegalStateException(ref + ": not in jk-lock.toml; run jk lock");
                work.add(referenced);
            }
        }
        oneVersionEach(out);
        return out;
    }

    /**
     * Throws when two versions of one subject meet in {@code rows}: both would be laid out at the
     * subject's file.
     */
    public static void oneVersionEach(List<SchemaEntry> rows) {
        Map<String, SchemaEntry> bySubject = new LinkedHashMap<>();
        for (SchemaEntry row : rows) {
            SchemaEntry other = bySubject.putIfAbsent(row.subject(), row);
            if (other != null && other.version() != row.version()) {
                int low = Math.min(other.version(), row.version());
                int high = Math.max(other.version(), row.version());
                throw new IllegalArgumentException(
                        row.subject() + ": v" + low + " and v" + high + " both reach one module; keep one");
            }
        }
    }

    /**
     * True when some declared subject, or a version a pinned schema references, has no row, or a
     * module's rows split one subject across versions.
     */
    static boolean missing(Path owner, Map<Path, JkBuild> modules, Lockfile lock) {
        for (Declared declared : declared(owner, modules)) {
            try {
                closure(lock.schemas(), declared);
            } catch (IllegalStateException | IllegalArgumentException unpinnedOrSplit) {
                return true;
            }
        }
        return false;
    }
}
