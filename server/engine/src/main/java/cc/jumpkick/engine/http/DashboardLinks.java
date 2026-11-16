// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.wire.DashboardSigning;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard URLs the engine hands out. A link the engine answers with — an MCP result, a
 * decision's link in {@code jk decide} or {@code jk doctor --fix}, the {@code /api/decisions} list,
 * the new-project wizard an agent sends a person to — carries no token: it lands in agent
 * transcripts, CI logs and terminals that are recorded, and the token opens every engine this
 * machine pairs. A browser that has opened a signed link once keeps the token and opens these
 * signed in; one that has not shows the authorization dialog, which says to run {@code jk web}.
 *
 * <p>Only what the engine opens or shows itself — the browser {@code [ui] ask = open} starts, the
 * notification a click on which opens the decision — is {@link #signed}: {@code t=} in the
 * fragment, which the SPA stores and scrubs on load and which never leaves the browser. The CLI
 * signs the links it prints the same way, at an interactive terminal only ({@code
 * DashboardToken}).
 */
public final class DashboardLinks {

    private DashboardLinks() {}

    /**
     * The project page that follows the newest run of {@code dir}: {@code
     * #project/<id>?dir=<checkout>}. The checkout rides along because every worktree of a
     * repository shares the id. Falls back to the dashboard root when the checkout has no durable
     * project id; {@code null} when HTTP is not serving.
     */
    public static @Nullable String project(@Nullable String baseUrl, @Nullable String projectId, @Nullable String dir) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        if (projectId == null || projectId.isBlank()) return base;
        StringBuilder route = new StringBuilder(base).append("#project/").append(projectId);
        if (dir != null && !dir.isBlank()) {
            route.append("?dir=")
                    .append(URLEncoder.encode(dir, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return route.toString();
    }

    /** The decision's dialog {@code #decision/<id>}; {@code null} when HTTP is not serving. */
    public static @Nullable String decision(@Nullable String baseUrl, String id) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "#decision/" + id;
    }

    /**
     * {@code link} carrying {@code token} for a browser to adopt: {@code #t=<token>} on the bare
     * dashboard, {@code t=<token>} in a route's fragment query ({@code #decision/<id>?t=…}). {@code
     * link} itself when there is no token. For what the engine opens or shows itself, never for an
     * answer.
     */
    public static @Nullable String signed(@Nullable String link, @Nullable String token) {
        return DashboardSigning.signed(link, token);
    }
}
