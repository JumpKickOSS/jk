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
 * <p>Two admissions with the same fingerprint must not run at once on one engine. Different
 * worktrees (different real paths), kinds, or request flags that change work yield different
 * fingerprints and may run concurrently.
 *
 * <p><strong>Inputs</strong> (order-independent hash of a canonical form):
 *
 * <ul>
 * <li>canonical project directory (real path when resolvable)
 * <li>kind ({@code build}, {@code test}, …)
 * <li>flags that change the job: rebuild/force, offline, modules selection, variant,
 * skipTests/testOnly, assembly override
 * </ul>
 */
public final class BuildJobFingerprint {

    /** Kinds that take an exclusive fingerprint slot (journaled build-like work). */
    public static final Set<String> EXCLUSIVE_KINDS = Set.of("build", "test");

    private BuildJobFingerprint() {}

    public static boolean isExclusiveKind(String kind) {
        return kind != null && EXCLUSIVE_KINDS.contains(kind);
    }

    /**
     * Fingerprint for a socket request line ({@code dir} + session-ish flags) and dispatch
     * {@code kind}.
     */
    public static String ofRequest(String kind, String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
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
        return of(kind, dir != null ? dir.toString() : "", false, false, skipTests, testOnly, null, null, null);
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
