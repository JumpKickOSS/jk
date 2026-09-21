// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard URLs the engine hands out. The bearer token rides the fragment as {@code t=} — the
 * SPA stores it and scrubs it on load — so a link pasted from an MCP result opens authenticated.
 * On a project route the token is the fragment's query, {@code #project/<id>?dir=…&t=<token>};
 * the bare dashboard keeps {@code #t=<token>}, the shape {@code jk web} prints.
 */
public final class DashboardLinks {

    private DashboardLinks() {}

    /**
     * The project page that follows the newest run of {@code dir}, authenticated: {@code
     * #project/<id>?dir=<checkout>&t=<token>}. The checkout rides along because every worktree of
     * a repository shares the id. Falls back to the dashboard root when the checkout has no
     * durable project id; {@code null} when HTTP is not serving.
     */
    public static @Nullable String project(
            @Nullable String baseUrl, @Nullable String token, @Nullable String projectId, @Nullable String dir) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String auth = token == null || token.isBlank() ? "" : token.trim();
        if (projectId == null || projectId.isBlank()) return auth.isEmpty() ? base : base + "#t=" + auth;
        StringBuilder route = new StringBuilder(base).append("#project/").append(projectId);
        char sep = '?';
        if (dir != null && !dir.isBlank()) {
            route.append(sep)
                    .append("dir=")
                    .append(URLEncoder.encode(dir, StandardCharsets.UTF_8).replace("+", "%20"));
            sep = '&';
        }
        if (!auth.isEmpty()) route.append(sep).append("t=").append(auth);
        return route.toString();
    }
}
