// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.tool.TrustedSources;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * JBang catalog resolution for {@code alias@catalog} targets ({@code @user}, {@code @user/repo},
 * or host URL). Every candidate catalog origin is trust-gated before it is fetched.
 */
final class JBangCatalog {

    /** A located catalog: where it came from (for trust + relative refs) and the alias entry. */
    record Resolved(
            String pageOrigin,
            URI rawBase,
            String scriptRef,
            List<String> arguments,
            List<String> dependencies,
            List<String> javaOptions) {}

    private JBangCatalog() {}

    /** One catalog GET: the body of a 200, {@code null} for any other status. */
    @FunctionalInterface
    interface Fetch {
        byte @Nullable [] get(URI uri) throws IOException, InterruptedException;
    }

    /** {@link Fetch} over {@code http}. */
    static Fetch over(Http http) {
        return uri -> {
            var response = http.documents().get(uri);
            return response.statusCode() == 200 ? response.body() : null;
        };
    }

    /** Asks about one untrusted origin before it is contacted: {@code null} allows it, else the exit code. */
    @FunctionalInterface
    interface Prompt {
        @Nullable
        Integer ask(String origin) throws IOException;
    }

    /** What {@link #resolve} came to: the alias, or the exit code of the prompt that refused an origin. */
    sealed interface Outcome {
        record Found(Resolved resolved) implements Outcome {}

        record Refused(int exit) implements Outcome {}
    }

    /**
     * Resolve {@code alias@rest}, deciding each candidate origin just before it would be fetched. When
     * {@code trust} already covers one of the candidates only trusted ones are contacted and nobody is
     * asked; otherwise each origin goes to {@code prompt} first, in fetch order, and the first refusal
     * ends the resolution. A forge fallback therefore never contacts an origin the user did not allow.
     * A failed fetch or a catalog without the alias throws {@link IOException} with a user-ready message.
     */
    static Outcome resolve(String target, Fetch fetch, TrustedSources trust, Prompt prompt)
            throws IOException, InterruptedException {
        int at = target.indexOf('@');
        String alias = target.substring(0, at);

        List<Candidate> candidates = candidatesOf(target);
        boolean anyTrusted = candidates.stream().anyMatch(c -> trust.isTrusted(c.pageOrigin()));
        List<String> tried = new ArrayList<>();
        IOException lastFailure = null;
        for (Candidate c : candidates) {
            if (!trust.isTrusted(c.pageOrigin())) {
                if (anyTrusted) continue;
                Integer refused = prompt.ask(c.pageOrigin());
                if (refused != null) return new Outcome.Refused(refused);
            }
            tried.add(c.catalogUrl());
            try {
                byte[] body = fetch.get(URI.create(c.catalogUrl()));
                if (body == null) continue;
                // Jsonl's lookups expect the wire's compact form; catalogs are
                // pretty-printed by humans — normalize before parsing.
                String json = compact(new String(body, StandardCharsets.UTF_8));
                return new Outcome.Found(toResolved(target, alias, c, json));
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IOException(
                "no jbang-catalog.json found for `" + target.substring(at + 1) + "` (tried " + tried + ")");
    }

    private static Resolved toResolved(String target, String alias, Candidate c, String json) throws IOException {
        String aliases = Jsonl.nested(json, "aliases");
        String entry = aliases != null ? Jsonl.nested(aliases, alias) : null;
        if (entry == null) {
            throw new IOException("catalog " + c.catalogUrl() + " has no alias `" + alias + "`");
        }
        String scriptRef = Jsonl.str(entry, "script-ref");
        if (scriptRef == null || scriptRef.isBlank()) {
            throw new IOException("alias `" + alias + "` in " + c.catalogUrl() + " has no script-ref");
        }
        String base = c.catalogUrl().substring(0, c.catalogUrl().lastIndexOf('/') + 1);
        return new Resolved(
                c.pageOrigin(),
                URI.create(base),
                scriptRef,
                Jsonl.strArray(entry, "arguments"),
                Jsonl.strArray(entry, "dependencies"),
                Jsonl.strArray(entry, "java-options"));
    }

    /** Strip whitespace outside string literals so Jsonl's compact-form lookups match. */
    static String compact(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                sb.append(ch);
                if (ch == '\\' && i + 1 < json.length()) sb.append(json.charAt(++i));
                else if (ch == '"') inString = false;
            } else if (ch == '"') {
                inString = true;
                sb.append(ch);
            } else if (!Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private record Candidate(String catalogUrl, String pageOrigin) {}

    /** Parse {@code alias@rest[~path]} and expand the forge candidates. */
    private static List<Candidate> candidatesOf(String target) {
        String rest = target.substring(target.indexOf('@') + 1);
        String path = "";
        int tilde = rest.indexOf('~');
        if (tilde >= 0) {
            path = rest.substring(tilde + 1);
            rest = rest.substring(0, tilde);
        }
        return candidatesFor(rest, path);
    }

    private static List<Candidate> candidatesFor(String rest, String path) {
        String sub = path.isEmpty() ? "" : path + "/";
        String[] parts = rest.split("/");
        // A dot in the first segment = a hostname catalog, not a forge username.
        if (parts[0].contains(".")) {
            String tail = rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
            String scheme = RepositorySpec.loopbackAuthority(parts[0]) ? "http" : "https";
            return List.of(new Candidate(
                    scheme + "://" + tail + "/" + sub + "jbang-catalog.json",
                    scheme + "://" + parts[0] + "/" + (parts.length > 1 ? parts[1] + "/" : "")));
        }
        String user = parts[0];
        String repo = parts.length > 1 ? parts[1] : "jbang-catalog";
        String branch = parts.length > 2 ? parts[2] : "HEAD";
        return List.of(
                new Candidate(
                        "https://raw.githubusercontent.com/" + user + "/" + repo + "/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://github.com/" + user + "/"),
                new Candidate(
                        "https://gitlab.com/" + user + "/" + repo + "/-/raw/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://gitlab.com/" + user + "/"),
                new Candidate(
                        "https://bitbucket.org/" + user + "/" + repo + "/raw/" + branch + "/" + sub
                                + "jbang-catalog.json",
                        "https://bitbucket.org/" + user + "/"));
    }
}
