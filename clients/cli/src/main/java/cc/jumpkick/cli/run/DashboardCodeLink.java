// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.engine.EnginePaths;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Deep links from CLI failure snippets into the dashboard Monaco files pane
 * ({@code #project/<id>/files/<rel>?line=N} — same route as the web fail-report path).
 *
 * <p>HTTP base and project id are best-effort: when the engine HTTP surface is off or the checkout
 * has no durable id, callers paint an unlinked path. Token bootstrap stays on {@code #t=} from
 * {@code jk web} / {@code jk engine status}; file links reuse a stored session token.
 */
public final class DashboardCodeLink {

    private static final long HTTP_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private static volatile HttpCache httpCache;

    /** Test-only project-id override; null means resolve via {@link ProjectBuilds#key}. */
    private static volatile String projectIdOverride;

    private DashboardCodeLink() {}

    /**
     * Checkout (and optional module) in effect while painting a failure block. Nested scopes
     * restore the previous binding.
     */
    public static final class Scope implements AutoCloseable {
        private final Path checkoutDir;
        private final Path moduleDir;
        private final Scope previous;

        private Scope(Path checkoutDir, Path moduleDir, Scope previous) {
            this.checkoutDir = checkoutDir;
            this.moduleDir = moduleDir;
            this.previous = previous;
        }

        public Path checkoutDir() {
            return checkoutDir;
        }

        public Path moduleDir() {
            return moduleDir;
        }

        @Override
        public void close() {
            if (previous == null) SCOPE.remove();
            else SCOPE.set(previous);
        }
    }

    /** Bind checkout/module for the duration of a try-with-resources around {@code paintLines}. */
    public static Scope open(Path checkoutDir, Path moduleDir) {
        Scope scope = new Scope(normalize(checkoutDir), normalize(moduleDir), SCOPE.get());
        SCOPE.set(scope);
        return scope;
    }

    static Scope current() {
        return SCOPE.get();
    }

    /** Test seam — drop memoized HTTP base and project-id override. */
    public static void clearHttpCache() {
        httpCache = null;
        projectIdOverride = null;
    }

    /** Test seam — force a durable project id without a real checkout. */
    static void putProjectId(String projectId) {
        projectIdOverride = projectId;
    }

    /**
     * Workspace-relative path for the files pane. Mirrors web {@code codePathForFailure}: module-
     * relative snippet paths join under {@code rel(checkout, module)}; empty/null module dir leaves
     * the file as already checkout-relative. Basename-only and {@code ..} segments yield null.
     */
    public static String codePath(Path checkoutDir, Path moduleDir, String file) {
        if (file == null || file.isBlank()) return null;
        String f = file.replace('\\', '/').strip();
        if (f.isEmpty()) return null;
        boolean abs = f.startsWith("/") || (f.length() > 1 && f.charAt(1) == ':' && Character.isLetter(f.charAt(0)));
        if (abs) {
            if (checkoutDir == null) return null;
            String rel = relativizeUnder(checkoutDir, Path.of(f));
            if (rel == null || hasDotDot(rel)) return null;
            return rel;
        }
        if (!f.contains("/")) return null;
        if (hasDotDot(f)) return null;
        if (moduleDir == null) return f;
        if (checkoutDir == null) return null;
        String moduleRel = relativizeUnder(checkoutDir, moduleDir);
        if (moduleRel == null) return null;
        String joined = posixJoin(moduleRel, f);
        if (joined == null || joined.isEmpty() || hasDotDot(joined)) return null;
        return joined;
    }

    /**
     * Full dashboard URL for a workspace-relative file and 1-based line, or null when any piece is
     * missing. Line {@code 0} omits {@code ?line=}.
     */
    public static String fileUrl(String httpBase, String projectId, String workspaceRelPath, int line) {
        if (httpBase == null || httpBase.isBlank()) return null;
        if (projectId == null || projectId.isBlank()) return null;
        if (workspaceRelPath == null || workspaceRelPath.isBlank()) return null;
        if (hasDotDot(workspaceRelPath)) return null;
        StringBuilder hash = new StringBuilder();
        hash.append("#project/").append(encodeSeg(projectId)).append("/files");
        for (String seg : workspaceRelPath.replace('\\', '/').split("/")) {
            if (seg.isEmpty()) continue;
            hash.append('/').append(encodeSeg(seg));
        }
        if (line > 0) hash.append("?line=").append(line);
        String base = httpBase.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + hash;
    }

    /**
     * Resolve a clickable URL for a failure snippet path using the current {@link Scope} (or cwd as
     * checkout when unbound). Null when HTTP is unavailable or the path cannot be linked.
     */
    public static String urlForSnippet(String moduleRelativePath, int line) {
        Scope scope = SCOPE.get();
        Path checkout = scope != null && scope.checkoutDir != null
                ? scope.checkoutDir
                : Path.of("").toAbsolutePath().normalize();
        Path module = scope != null ? scope.moduleDir : null;
        String rel = codePath(checkout, module, moduleRelativePath);
        if (rel == null) return null;
        String http = resolveHttpBase();
        if (http == null) return null;
        String projectId = resolveProjectId(checkout);
        if (projectId == null) return null;
        return fileUrl(http, projectId, rel, line);
    }

    /** Package-visible for tests — force a known HTTP base without talking to the engine. */
    static void putHttpCache(String httpUrl) {
        httpCache = new HttpCache(httpUrl, System.nanoTime() + HTTP_TTL_NANOS);
    }

    static String resolveHttpBase() {
        HttpCache c = httpCache;
        long now = System.nanoTime();
        if (c != null && now - c.expiresAtNanos < 0) return c.url;
        String url = null;
        try {
            EnginePaths.Paths paths = EnginePaths.current();
            url = EngineClient.status(EnginePaths.activeSocket(paths))
                    .map(EngineClient.Status::httpUrl)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
        } catch (RuntimeException ignored) {
            // no engine / no paths — leave unlinked
        }
        httpCache = new HttpCache(url, now + HTTP_TTL_NANOS);
        return url;
    }

    static String resolveProjectId(Path checkoutDir) {
        if (projectIdOverride != null) return projectIdOverride.isBlank() ? null : projectIdOverride;
        if (checkoutDir == null) return null;
        try {
            String id = ProjectBuilds.key(checkoutDir);
            return id == null || id.isBlank() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path normalize(Path p) {
        return p == null ? null : p.toAbsolutePath().normalize();
    }

    private static boolean hasDotDot(String rel) {
        for (String seg : rel.replace('\\', '/').split("/")) {
            if ("..".equals(seg)) return true;
        }
        return false;
    }

    private static String relativizeUnder(Path root, Path abs) {
        if (root == null || abs == null) return null;
        Path r = root.toAbsolutePath().normalize();
        Path a = abs.toAbsolutePath().normalize();
        if (a.equals(r)) return "";
        if (!a.startsWith(r)) return null;
        Path rel = r.relativize(a);
        String s = rel.toString().replace('\\', '/');
        return s;
    }

    private static String posixJoin(String a, String b) {
        List<String> parts = new ArrayList<>();
        if (a != null && !a.isEmpty() && !".".equals(a)) {
            for (String s : a.replace('\\', '/').split("/")) {
                if (!s.isEmpty()) parts.add(s);
            }
        }
        if (b != null && !b.isEmpty() && !".".equals(b)) {
            for (String s : b.replace('\\', '/').split("/")) {
                if (!s.isEmpty()) parts.add(s);
            }
        }
        return String.join("/", parts);
    }

    /** encodeURIComponent-ish (spaces as %20, not +). */
    static String encodeSeg(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record HttpCache(String url, long expiresAtNanos) {}
}
