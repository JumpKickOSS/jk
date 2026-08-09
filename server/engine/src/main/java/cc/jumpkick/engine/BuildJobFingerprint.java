// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable exclusivity key for concurrent build-like jobs.
 *
 * <p>Two admissions with the same fingerprint must not run at once on one engine. For every
 * {@linkplain BuildHistoryKinds build-history kind}, the key is <strong>project directory +
 * kind only</strong> (JK-1291 extended): overlapping work that shares a {@code target/} tree must
 * not interleave, even when flags differ ({@code --rebuild}, {@code -m}, …). Non-build kinds
 * ({@code lock}, {@code format}, …) never take a slot.
 *
 * <p>Different worktrees (different real paths) yield different fingerprints and may run
 * concurrently. Different kinds on the same dir (e.g. {@code build} vs {@code test}) are separate
 * slots.
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
        if (BuildHistoryKinds.isBuildLike(kind)) {
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

    /** Fingerprint for HTTP/MCP workspace jobs (absolute dir + kind + test-only shape). */
    public static String ofHttp(String kind, Path dir, boolean skipTests, boolean testOnly) {
        if (BuildHistoryKinds.isBuildLike(kind)) {
            return ofProject(kind, dir != null ? dir.toString() : "");
        }
        return of(kind, dir != null ? dir.toString() : "", false, false, skipTests, testOnly, null, null, null);
    }

    /**
     * Project-scoped exclusivity for build-like kinds: same canonical dir + kind cannot run two
     * jobs at once (JK-1291).
     */
    public static String ofProject(String kind, String dir) {
        String canon = canonicalDir(dir);
        StringBuilder sb = new StringBuilder(128);
        sb.append("kind=").append(kind == null ? "" : kind).append('\n');
        sb.append("dir=").append(canon).append('\n');
        sb.append("scope=project\n");
        return sha256Hex(sb.toString());
    }

    public static String of(
            String kind,
            String dir,
            boolean rebuild,
            boolean offline,
            boolean skipTests,
            boolean testOnly,
            String modules,
            String variant,
            String assemblyOverride) {
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
        return sha256Hex(sb.toString());
    }

    /** Real path when the tree exists; otherwise absolute normalized path (worktrees stay distinct). */
    public static String canonicalDir(String dir) {
        if (dir == null || dir.isBlank()) return "";
        try {
            Path p = Path.of(dir);
            if (!p.isAbsolute()) p = p.toAbsolutePath();
            p = p.normalize();
            if (Files.exists(p)) {
                try {
                    return p.toRealPath().toString();
                } catch (Exception ignored) {
                    // fall through to absolute form
                }
            }
            return p.toString();
        } catch (RuntimeException e) {
            return dir.trim();
        }
    }

    private static String normalizeModules(String modules) {
        if (modules == null || modules.isBlank()) return "";
        TreeSet<String> parts = new TreeSet<>();
        for (String p : modules.split("[,\\s]+")) {
            if (!p.isBlank()) parts.add(p.trim());
        }
        return String.join(",", parts);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required on every JDK we run; fall back so admission still works.
            return Integer.toHexString(s.hashCode());
        }
    }
}
