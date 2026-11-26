// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where {@code [publish] release} sends a release's deliverables: an object-store prefix ({@code
 * s3://bucket/prefix/}, {@code gs://bucket/prefix/}) or a forge's releases ({@code github}, {@code
 * gitlab}, {@code forgejo}).
 */
public sealed interface ReleaseTarget {

    /** {@code github}: the forge jk releases to. */
    String GITHUB = "github";

    /** The forges {@code [publish] release} may name. */
    List<String> FORGES = List.of(GITHUB, "gitlab", "forgejo");

    /**
     * An object-store prefix, always ending in {@code /}. A release is {@code {prefix}{version}/},
     * and {@code {prefix}latest.json} names the published one.
     */
    record Bucket(URI base) implements ReleaseTarget {

        /** {@code {prefix}{version}/}: the release's own directory. */
        public URI version(String version) {
            return URI.create(base + version + "/");
        }

        /** {@code {prefix}{version}/{name}}. */
        public URI file(String version, String name) {
            return URI.create(base + version + "/" + name);
        }

        /** {@code {prefix}latest.json}. */
        public URI latest() {
            return URI.create(base + "latest.json");
        }

        @Override
        public String toString() {
            return base.toString();
        }
    }

    /** A forge's releases of the repository the git remote {@code origin} names. */
    record Forge(String kind) implements ReleaseTarget {
        @Override
        public String toString() {
            return kind;
        }
    }

    /**
     * {@code value} as a target.
     *
     * @throws IllegalArgumentException with {@link #refusal}'s line when it names none
     */
    static ReleaseTarget parse(String value) {
        String refused = refusal(value);
        if (refused != null) throw new IllegalArgumentException(refused);
        if (FORGES.contains(value)) return new Forge(value);
        return new Bucket(URI.create(value.endsWith("/") ? value : value + "/"));
    }

    /** Why {@code value} names no release target, or {@code null} when it names one. */
    static @Nullable String refusal(String value) {
        if (FORGES.contains(value)) return null;
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            uri = null;
        }
        String scheme =
                uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri == null || !(scheme.equals("s3") || scheme.equals("gs"))) {
            return "`" + value + "` is not github, gitlab, forgejo or an s3:// or gs:// URL";
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) return "`" + value + "` names no bucket";
        if (uri.getQuery() != null || uri.getFragment() != null) {
            return "`" + value + "` has a query or fragment; name a bucket and prefix";
        }
        return null;
    }
}
