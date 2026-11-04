// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which repositories a mirror stands in for, in Maven's {@code mirrorOf} grammar: {@code *} every
 * repository; {@code external:*} every repository not on this machine (neither a loopback host nor
 * a {@code file:} URL); {@code external:http:*} every plaintext-http repository not on this
 * machine; a bare id that repository; {@code !id} every match but that one. One matcher for both
 * spellings — a {@code settings.xml} {@code <mirrorOf>} string and a {@code mirror-of} list in
 * {@code ~/.jk/config.toml} — so the two cannot read the same tokens differently.
 *
 * @param tokens the tokens in the order written, each stripped and non-empty
 */
public record MirrorOf(List<String> tokens) {

    public MirrorOf {
        List<String> clean = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String t = Objects.requireNonNull(token, "token").strip();
            if (!t.isEmpty()) clean.add(t);
        }
        tokens = List.copyOf(clean);
    }

    /** Maven's spelling: tokens separated by commas, {@code *,!jumpkick}. */
    public static MirrorOf parse(String commaSeparated) {
        return new MirrorOf(List.of(commaSeparated.split(",")));
    }

    /**
     * Whether the repository {@code repoId} at {@code repoUrl} is mirrored. An exact id match or an
     * exclusion decides outright, in the order written; a wildcard keeps reading so a later
     * exclusion can veto it.
     */
    public boolean matches(String repoId, URI repoUrl) {
        boolean result = false;
        for (String t : tokens) {
            if (t.length() > 1 && t.startsWith("!")) {
                if (t.substring(1).equals(repoId)) return false;
            } else if (t.equals(repoId)) {
                return true;
            } else if (t.equals("external:*") && external(repoUrl)) {
                result = true;
            } else if (t.equals("external:http:*") && externalHttp(repoUrl)) {
                result = true;
            } else if (t.equals("*")) {
                result = true;
            }
        }
        return result;
    }

    /** The tokens in Maven's spelling, for a diagnostic: {@code *,!jumpkick}. */
    public String spelled() {
        return String.join(",", tokens);
    }

    /** Not on this machine: neither a loopback host nor a {@code file:} URL. */
    public static boolean external(URI url) {
        return !"file".equalsIgnoreCase(url.getScheme()) && !RepositorySpec.loopback(url.getHost());
    }

    private static boolean externalHttp(URI url) {
        return "http".equalsIgnoreCase(url.getScheme()) && external(url);
    }
}
