// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.RepoStoreDirs;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The identity of a repository inside the artifact store: derived from where its bytes come
 * from, never from the name a manifest gives it.
 *
 * <p>A {@code [repositories]} name is a project-local alias. Two projects on one machine may both
 * call a repository {@code private} and point it at different origins, and one origin may go by
 * two names; keyed by name, the store would hand one project the other's bytes under the right
 * coordinate and let a poisoned mirror reach every project that reuses the word. So the store
 * directory is {@link #storeId}: a reserved word for the three public origins jk ships with
 * (their directories predate this rule and their origin is unambiguous), else the host of the
 * origin followed by a digest of its canonical form, so a human reading {@code repos/} still
 * sees where each tree came from.
 */
public final class RepoIdentity {

    private RepoIdentity() {}

    /** Store ids that name a public origin jk ships with, by the origin's canonical form. */
    private static final Map<String, String> RESERVED = Map.of(
            canonicalOrigin(RepositorySpec.MAVEN_CENTRAL.url()), RepositorySpec.CENTRAL,
            canonicalOrigin(RepositorySpec.GOOGLE_MAVEN.url()), RepositorySpec.GOOGLE,
            canonicalOrigin(RepositorySpec.JUMPKICK.url()), RepositorySpec.JUMPKICK_NAME);

    private static final Pattern DERIVED_ID = Pattern.compile(".+-[0-9a-f]{12}");

    /** Hex digits of the canonical origin's SHA-256 that follow the host in a derived id. */
    private static final int DIGEST_CHARS = 12;

    /**
     * The origin with every detail that does not change which bytes are served removed: scheme
     * and host lower-cased, an explicit default port dropped, user-info, query and fragment
     * gone, trailing slashes trimmed. {@code https://User:tok@Repo.Acme.com:443/maven/} and
     * {@code https://repo.acme.com/maven} are one origin.
     */
    public static String canonicalOrigin(URI origin) {
        String scheme = origin.getScheme() == null ? "" : origin.getScheme().toLowerCase(Locale.ROOT);
        String host = origin.getHost() == null ? "" : origin.getHost().toLowerCase(Locale.ROOT);
        int port = origin.getPort();
        if (port == defaultPort(scheme)) port = -1;
        String path = origin.getRawPath() == null ? "" : origin.getRawPath();
        if (origin.getHost() == null && origin.getRawSchemeSpecificPart() != null && path.isEmpty()) {
            // An opaque URI (s3:bucket/path, or a file: without slashes): the whole specific part is the path.
            path = origin.getRawSchemeSpecificPart();
        }
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (port >= 0) out.append(':').append(port);
        return out.append(path).toString();
    }

    /** As {@link #canonicalOrigin(URI)} for a string that may not parse; an unparseable origin canonicalizes to itself. */
    public static String canonicalOrigin(String origin) {
        try {
            return canonicalOrigin(new URI(origin.strip()));
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return origin.strip();
        }
    }

    /**
     * The {@code repos/<id>} directory for {@code origin}: a reserved word for a public origin jk
     * ships with, else {@code <host>-<12 hex of sha256(canonical origin)>}. The name a project
     * uses for the repository plays no part.
     */
    public static String storeId(URI origin) {
        return storeId(canonicalOrigin(origin), origin.getHost());
    }

    /** As {@link #storeId(URI)} for a string that may not parse. */
    public static String storeId(String origin) {
        URI parsed;
        try {
            parsed = new URI(origin.strip());
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return storeId(origin.strip(), null);
        }
        return storeId(parsed);
    }

    private static String storeId(String canonical, @Nullable String host) {
        String reserved = RESERVED.get(canonical);
        if (reserved != null) return reserved;
        String digest =
                Hashing.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)).substring(0, DIGEST_CHARS);
        String scheme = canonical.indexOf("://") > 0 ? canonical.substring(0, canonical.indexOf("://")) : "";
        String head = host == null || host.isBlank() ? (scheme.isBlank() ? "origin" : scheme) : host;
        return slug(head) + "-" + digest;
    }

    /** {@code name} reduced to the characters a directory name is safe with anywhere. */
    static String slug(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            out.append(keep ? c : '-');
        }
        String s = out.toString();
        while (s.startsWith(".") || s.startsWith("-")) s = s.substring(1);
        return s.isEmpty() ? "origin" : s;
    }

    /** True when {@code id} is a reserved public-origin id ({@link RepoStoreDirs#isReservedName}). */
    public static boolean isReserved(String id) {
        return RepoStoreDirs.isReservedName(id);
    }

    /** The canonical origin a reserved id stands for, or {@code null} when {@code id} is not reserved. */
    public static @Nullable String reservedOrigin(String id) {
        for (Map.Entry<String, String> e : RESERVED.entrySet()) {
            if (e.getValue().equals(id)) return e.getKey();
        }
        return null;
    }

    /**
     * True when a directory name under {@code repos/} has the shape this class produces — a
     * reserved id, the first-party shelf, or {@code <host>-<12 hex>} — so a path walker can tell
     * the store root from a group segment without knowing every store on disk.
     */
    public static boolean looksLikeStoreId(String name) {
        return isReserved(name)
                || RepositorySpec.JK_LOCAL.equals(name)
                || DERIVED_ID.matcher(name).matches();
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }
}
