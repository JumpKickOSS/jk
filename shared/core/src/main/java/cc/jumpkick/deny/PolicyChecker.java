// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.deny;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.DenyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Applies a {@link DenyPolicy} to a {@link Lockfile} (currently source-URL host denylists). */
public final class PolicyChecker {

    private final DenyPolicy policy;

    public PolicyChecker(DenyPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public record Violation(String module, String version, String reason) {}

    public List<Violation> check(Lockfile lock) {
        List<Violation> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Source format: `<name>+<url>`; we test the host portion. RepoSource.url() applies the
            // lenient split (url after the first '+', else the whole string) this check has always used.
            String host = hostOf(RepoSource.parse(pkg.source()).url());
            for (String denied : policy.deniedSources()) {
                if (hostMatches(host, denied)) {
                    out.add(new Violation(
                            pkg.name(),
                            pkg.version(),
                            "source `" + host + "` matches denylisted host `" + denied + "`"));
                    break;
                }
            }
        }
        return out;
    }

    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = hostPort.indexOf(':');
        return (colon >= 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase(Locale.ROOT);
    }

    /**
     * Exact host match, or a DNS-label suffix match ({@code evil.com} matches {@code a.evil.com}
     * but not {@code notevil.com}). Patterns may be written with a leading {@code .}.
     */
    static boolean hostMatches(String host, String pattern) {
        if (host == null || host.isBlank() || pattern == null || pattern.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.startsWith(".")) p = p.substring(1);
        if (h.equals(p)) return true;
        return h.endsWith("." + p);
    }
}
