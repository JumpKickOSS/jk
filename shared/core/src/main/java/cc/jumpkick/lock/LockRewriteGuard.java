// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.Log;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Whether the running jk may rewrite a lock another jk wrote. A lock records its writer —
 * {@code generated-by} names the version, {@code generated-by-build} and its time name the build
 * — and a jk older than that writer does not relock: it would restate the lock in its own,
 * older format, and the newer tree that carries the lock could not boot from the result. A newer
 * jk always may (newest wins), the same build always may, and {@code jk lock --force} overrides
 * the refusal.
 */
public final class LockRewriteGuard {

    private LockRewriteGuard() {}

    /** The running jk as a lock records its writer, or null when the code runs from no archive. */
    public static @Nullable WriterBuild runningBuild() {
        String id = BuildIdentity.buildId();
        if (id.isEmpty()) return null;
        return new WriterBuild(id, BuildIdentity.codeModifiedAt());
    }

    /**
     * Refuse to rewrite the lock at {@code lockFile} when a newer jk wrote it, unless {@code
     * force}. A missing or unreadable lock is no opinion: the relock that follows restates it.
     */
    public static void refuseUnlessForced(Path lockFile, boolean force) {
        if (force || !Files.isRegularFile(lockFile)) return;
        Lockfile existing;
        try {
            existing = LockfileReader.read(lockFile);
        } catch (Exception unreadable) {
            Log.debug("refuseUnlessForced: unreadable lock is restated by the relock", unreadable);
            return;
        }
        String refusal = refusal(existing);
        if (refusal != null) throw new LockRewriteRefused(refusal);
    }

    /** Why the running jk must not rewrite {@code existing}, or null when it may. */
    public static @Nullable String refusal(Lockfile existing) {
        return refusal(existing, JkVersion.VERSION, runningBuild());
    }

    /** The rule with the running identity explicit, for tests. */
    static @Nullable String refusal(Lockfile existing, String runningVersion, @Nullable WriterBuild running) {
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
        if (order > 0) return message(writerVersion, writer, runningVersion, running, "an older jk");
        if (writer == null || running == null || writer.id().equals(running.id())) return null;
        Instant wrote = writer.time();
        Instant runs = running.time();
        if (wrote == null || runs == null || !wrote.isAfter(runs)) return null;
        return message(writerVersion, writer, runningVersion, running, "an older build of the same version");
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
            String relation) {
        return "jk-lock.toml was written by jk " + writerVersion + describe(writer) + " and this engine is jk "
                + runningVersion + describe(running) + ", " + relation
                + " — a relock with it would restate the lock in the older jk's format, which the tree that"
                + " carries the lock may not build from. Install the jk that wrote it (`jk self update`, or"
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
