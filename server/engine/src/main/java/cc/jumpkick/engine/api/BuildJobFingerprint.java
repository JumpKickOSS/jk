// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Stable exclusivity key for concurrent build-like jobs.
 *
 * <p>Two admissions with the same fingerprint must not run at once on one engine. For every
 * {@linkplain BuildHistoryKinds build-history kind}, the key is the <strong>project directory
 * alone</strong>: overlapping work that shares a {@code target/} tree must not interleave, whatever
 * the flags ({@code --rebuild}, {@code -m}, …) and whatever the kind — a {@code jk build} and a
 * {@code jk test} on one workspace write the same compile outputs and the same test sandboxes, and
 * the root {@code after-build} scripts reclaim disk under them. Non-build kinds ({@code lock},
 * {@code format}, …) never take a slot.
 *
 * <p>Different worktrees (different real paths) yield different fingerprints and may run
 * concurrently.
 */
public final class BuildJobFingerprint {

    /**
     * Kinds that take an exclusive fingerprint slot — same set as durable build history ({@link
     * BuildHistoryKinds#ALL}).
     */
    public static final Set<String> EXCLUSIVE_KINDS = BuildHistoryKinds.ALL;

    private BuildJobFingerprint() {}

    public static boolean isExclusiveKind(String kind) {
        return BuildHistoryKinds.isBuildLike(kind);
    }

    /**
     * Fingerprint for a socket request line ({@code dir} + session-ish flags) and dispatch
     * {@code kind}. Build-like kinds use project-scoped exclusivity; others are non-exclusive.
     */
    public static String ofRequest(String kind, String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
        // Build-like: dir+kind exclusivity so concurrent rebuild/modules cannot race target/.
        if (dir != null && BuildHistoryKinds.isBuildLike(kind)) {
            return ofProject(kind, dir);
        }
        return of(
                kind,
                dir,
                Jsonl.bool(requestLine, "rebuild", false) || Jsonl.bool(requestLine, "force", false),
                Jsonl.bool(requestLine, "offline", false),
                Jsonl.bool(requestLine, "skipTests", false),
                Jsonl.bool(requestLine, "testOnly", false),
                Jsonl.str(requestLine, "modules"),
                Jsonl.str(requestLine, "variant"),
                Jsonl.str(requestLine, "assemblyOverride"));
    }

    /**
     * Project-scoped exclusivity for build-like kinds: one canonical dir, one job at a time, whatever
     * the kind. {@code kind} is accepted so call sites read as what they are and is deliberately not
     * part of the key.
     */
    public static String ofProject(String kind, String dir) {
        String canon = canonicalDir(dir);
        StringBuilder sb = new StringBuilder(128);
        sb.append("kind=build-like\n");
        sb.append("dir=").append(canon).append('\n');
        sb.append("scope=project\n");
        return Hashing.sha256Hex(sb.toString());
    }

    public static String of(
            @Nullable String kind,
            @Nullable String dir,
            boolean rebuild,
            boolean offline,
            boolean skipTests,
            boolean testOnly,
            @Nullable String modules,
            @Nullable String variant,
            @Nullable String assemblyOverride) {
        String canon = canonicalDir(dir);
        StringBuilder sb = new StringBuilder(256);
        sb.append("kind=").append(kind == null ? "" : kind).append('\n');
        sb.append("dir=").append(canon).append('\n');
        sb.append("rebuild=").append(rebuild).append('\n');
        sb.append("offline=").append(offline).append('\n');
        sb.append("skipTests=").append(skipTests).append('\n');
        sb.append("testOnly=").append(testOnly).append('\n');
        sb.append("modules=").append(normalizeModules(modules)).append('\n');
        sb.append("variant=").append(nullToEmpty(variant)).append('\n');
        sb.append("assembly=").append(nullToEmpty(assemblyOverride)).append('\n');
        return Hashing.sha256Hex(sb.toString());
    }

    /** Real path when the tree exists; otherwise absolute normalized path (worktrees stay distinct). */
    public static String canonicalDir(@Nullable String dir) {
        if (dir == null || dir.isBlank()) return "";
        try {
            Path p = Path.of(dir);
            if (!p.isAbsolute()) p = p.toAbsolutePath();
            p = p.normalize();
            if (Files.exists(p)) {
                try {
                    return p.toRealPath().toString();
                } catch (Exception e) {
                    // fall through to absolute form
                    Log.debug("canonicalDir: fall through to absolute form", e);
                }
            }
            return p.toString();
        } catch (RuntimeException e) {
            return dir.trim();
        }
    }

    private static String normalizeModules(@Nullable String modules) {
        if (modules == null || modules.isBlank()) return "";
        TreeSet<String> parts = new TreeSet<>();
        for (String p : modules.split("[,\\s]+")) {
            if (!p.isBlank()) parts.add(p.trim());
        }
        return String.join(",", parts);
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
