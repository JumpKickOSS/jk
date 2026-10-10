// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.Log;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Whether the running jk may rewrite a lock another jk wrote. A lock records its writer —
 * {@code generated-by} names the version, {@code generated-by-build} and its time name the build.
 * A newer jk and the same build always may. A jk older than the writer may only when it restates
 * every key the lock carries: a key it does not know would be dropped, and the newer tree that
 * carries the lock could not boot from the result. A change in what a known key means is the
 * {@code jk-min} floor's to refuse, not this guard's. {@code jk lock --force} overrides the refusal.
 */
public final class LockRewriteGuard {

    private LockRewriteGuard() {}

    /** The running jk as a lock records its writer, or null when the code runs from no archive. */
    public static @Nullable WriterBuild runningBuild() {
        String id = BuildIdentity.buildId();
        if (id.isEmpty()) return null;
        return new WriterBuild(id, BuildIdentity.builtAt());
    }

    /**
     * Refuse to rewrite the lock at {@code lockFile} when a newer jk wrote it, unless {@code
     * force}. A missing or unreadable lock is no opinion: the relock that follows restates it.
     */
    public static void refuseUnlessForced(Path lockFile, boolean force) {
        if (force) return;
        String refusal = refusal(lockFile);
        if (refusal != null) throw new LockRewriteRefused(refusal);
    }

    /**
     * Why the running jk must not rewrite the lock at {@code lockFile}, or null when it may. A
     * missing or unreadable lock is no opinion: the relock that follows restates it.
     */
    public static @Nullable String refusal(Path lockFile) {
        if (!Files.isRegularFile(lockFile)) return null;
        String text;
        Lockfile existing;
        try {
            text = Files.readString(lockFile);
            existing = LockfileReader.read(lockFile);
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("refusal: unreadable lock is restated by the relock", unreadable);
            return null;
        }
        return refusal(text, existing, JkVersion.VERSION, runningBuild());
    }

    /** The rule with the lock's text and the running identity explicit, for tests. */
    static @Nullable String refusal(
            String text, Lockfile existing, String runningVersion, @Nullable WriterBuild running) {
        String relation = olderThanWriter(existing, runningVersion, running);
        if (relation == null) return null;
        Set<String> dropped = droppedKeys(text, existing);
        if (dropped.isEmpty()) return null;
        return message(
                Objects.requireNonNull(writerVersion(existing.generatedBy())),
                existing.writerBuild(),
                runningVersion,
                running,
                relation,
                dropped);
    }

    /**
     * How the running jk is older than the lock's writer ({@code "an older jk"}, {@code "an older
     * build of the same version"}), or null when it is not.
     */
    public static @Nullable String olderThanWriter(Lockfile existing) {
        return olderThanWriter(existing, JkVersion.VERSION, runningBuild());
    }

    /** {@link #olderThanWriter(Lockfile)} with the running identity explicit, for tests. */
    static @Nullable String olderThanWriter(Lockfile existing, String runningVersion, @Nullable WriterBuild running) {
        String writerVersion = writerVersion(existing.generatedBy());
        if (writerVersion == null) return null;
        int order;
        try {
            order = Versions.compare(writerVersion, runningVersion);
        } catch (RuntimeException notAVersion) {
            return null;
        }
        if (order < 0) return null;
        WriterBuild writer = existing.writerBuild();
        if (order > 0) return "an older jk";
        if (writer == null || running == null || writer.id().equals(running.id())) return null;
        Instant wrote = writer.time();
        Instant runs = running.time();
        if (wrote == null || runs == null || !wrote.isAfter(runs)) return null;
        return "an older build of the same version";
    }

    /**
     * The key paths in {@code text} that this jk's writer does not restate from {@code existing},
     * sorted: {@code module[].foo} for a key inside a row of {@code [[module]]}. Values are not
     * compared — a relock changes them — only which keys survive.
     */
    static Set<String> droppedKeys(String text, Lockfile existing) {
        Set<String> dropped = keyPaths(Toml.parse(text));
        dropped.removeAll(keyPaths(Toml.parse(LockfileWriter.render(existing))));
        return dropped;
    }

    private static Set<String> keyPaths(TomlParseResult toml) {
        Set<String> out = new TreeSet<>();
        collect("", toml, out);
        return out;
    }

    private static void collect(String prefix, TomlTable table, Set<String> out) {
        for (String key : table.keySet()) {
            String path = prefix + key;
            out.add(path);
            Object value = table.get(List.of(key));
            if (value instanceof TomlTable nested) collect(path + ".", nested, out);
            if (value instanceof TomlArray array) {
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i) instanceof TomlTable row) collect(path + "[].", row, out);
                }
            }
        }
    }

    /** The version {@code generated-by} names ({@code "jk 0.13.7"} → {@code "0.13.7"}), or null. */
    static @Nullable String writerVersion(@Nullable String generatedBy) {
        if (generatedBy == null) return null;
        String version = generatedBy.trim();
        if (version.startsWith("jk ")) version = version.substring(3).trim();
        return version.isEmpty() ? null : version;
    }

    private static String message(
            String writerVersion,
            @Nullable WriterBuild writer,
            String runningVersion,
            @Nullable WriterBuild running,
            String relation,
            Set<String> dropped) {
        return "jk-lock.toml was written by jk " + writerVersion + describe(writer) + " and this engine is jk "
                + runningVersion + describe(running) + ", " + relation + " that does not know its key(s) "
                + String.join(", ", dropped)
                + " — a relock with it would drop them, and the tree that carries the lock may not build"
                + " from the result. Install the jk that wrote it (`jk self update`, or"
                + " `jk install --skip-tests` from its checkout) and run `jk engine stop`, or pass"
                + " `jk lock --force` to rewrite the lock with this engine anyway";
    }

    /** {@code " (build 4ee07400a592 of 2026-09-17T15:57:16Z)"}, or {@code ""} for no build. */
    static String describe(@Nullable WriterBuild build) {
        if (build == null) return "";
        String time = build.time() == null ? "" : " of " + build.time().truncatedTo(ChronoUnit.SECONDS);
        return " (build " + build.id() + time + ")";
    }

    /** A lock rewrite the running jk refused because a newer jk wrote the lock. */
    public static final class LockRewriteRefused extends IllegalStateException {
        LockRewriteRefused(String message) {
            super(message);
        }
    }
}
