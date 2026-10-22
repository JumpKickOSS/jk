// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.deny;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.DenyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Applies a {@link DenyPolicy} to a {@link Lockfile}: source-host denylists and the licences each row records. */
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
            String licence = licenceViolation(pkg.licenses());
            if (licence != null) out.add(new Violation(pkg.name(), pkg.version(), licence));
        }
        return out;
    }

    /**
     * Why {@code licences} breaks the policy, or null. With an allow list a row needs one allowed
     * licence (a POM that declares none has none); a row is denied when every licence it offers is.
     * A row that records no licences (no POM, or a lock written before they were recorded) is not
     * judged.
     */
    private @Nullable String licenceViolation(@Nullable List<String> licences) {
        if (licences == null) return null;
        String offered = licences.isEmpty() ? "no licence" : String.join(" / ", licences);
        if (!policy.allowedLicenses().isEmpty()
                && licences.stream().noneMatch(l -> matchesAny(policy.allowedLicenses(), l))) {
            return "licensed " + offered + ", none of which [deny.licenses] allow";
        }
        if (!policy.deniedLicenses().isEmpty()
                && !licences.isEmpty()
                && licences.stream().allMatch(l -> matchesAny(policy.deniedLicenses(), l))) {
            return "licensed " + offered + ", which [deny.licenses] deny";
        }
        return null;
    }

    private static boolean matchesAny(List<String> globs, String licence) {
        return globs.stream().anyMatch(g -> licenceMatches(g, licence));
    }

    /** {@code glob} ({@code *} for any run of characters) against a licence id, ignoring case. */
    public static boolean licenceMatches(String glob, String licence) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) re.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(licence)
                .matches();
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
