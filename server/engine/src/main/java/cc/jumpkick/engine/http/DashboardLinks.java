// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import org.jspecify.annotations.Nullable;

/**
 * The dashboard URLs the engine hands out. The bearer token rides the fragment as {@code t=} — the
 * SPA stores it and scrubs it on load — so a link pasted from an MCP result opens authenticated.
 * On a project route the token is the fragment's query, {@code #project/<id>?t=<token>}; the bare
 * dashboard keeps {@code #t=<token>}, the shape {@code jk web} prints.
 */
public final class DashboardLinks {

    private DashboardLinks() {}

    /**
     * The project page that follows its newest run, authenticated. Falls back to the dashboard
     * root when the checkout has no durable project id; {@code null} when HTTP is not serving.
     */
    public static @Nullable String project(
            @Nullable String baseUrl, @Nullable String token, @Nullable String projectId) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String auth = token == null || token.isBlank() ? "" : token.trim();
        if (projectId == null || projectId.isBlank()) return auth.isEmpty() ? base : base + "#t=" + auth;
        String route = base + "#project/" + projectId;
        return auth.isEmpty() ? route : route + "?t=" + auth;
    }
}
