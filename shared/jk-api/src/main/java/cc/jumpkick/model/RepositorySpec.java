// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Declared repository: name, URL, optional inline credential, object-store config, optional
 * exclusive Maven group bindings (dependency-confusion defense), the two trust opt-ins a
 * repository table may carry, and the release/snapshot policy Maven gives a {@code
 * <repository>}: which kind of version it is asked for.
 */
public record RepositorySpec(
        String name,
        URI url,
        @Nullable RepoCredential credential,
        @Nullable ObjectStoreConfig objectStore,
        /** When non-empty, matching {@code groupId}s resolve only from this repo (and other repos
         * that also bind the same group). Patterns: exact, {@code prefix.*} (group or subpackages). */
        List<String> groups,
        /** {@code allow-insecure = true}: a plaintext {@code http://} URL is accepted for this repository. */
        boolean allowInsecure,
        /**
         * {@code allow-unverified = true}: an artifact this repository publishes no checksum sidecar
         * for may still be pinned at lock time.
         */
        boolean allowUnverified,
        /** {@code releases = true} (the default): release versions are asked of this repository. */
        boolean releases,
        /**
         * {@code snapshots = true} (the default for a declared repository): {@code -SNAPSHOT} versions
         * are asked of this repository. Off for every built-in: Maven Central hosts no snapshots.
         */
        boolean snapshots,
        /**
         * {@code blocked = true}: the repository is known and never asked, as Maven 3.9 blocks a
         * plaintext {@code http://} repository. A package no other repository serves fails naming
         * it; {@code jk import} writes a POM's plaintext repository this way.
         */
        boolean blocked) {

    /**
     * The one name Maven Central answers to inside jk — the {@code repos/<name>/} store directory,
     * the lockfile {@code source} prefix, and the repo-group entry are all this string. Spelled
     * once for the same reason {@link #JK_LOCAL} is: a store written under one spelling and read
     * under another is a cache that silently never hits.
     */
    public static final String CENTRAL = "central";

    /**
     * Maven Central. {@code repo1.maven.org} is a CNAME for the same service and must not be used:
     * {@code CentralMirror} and {@code HostCooldown} both key on the canonical host, so traffic
     * addressed to the alias is invisible to the rate-limit window and to the failover mirror.
     */
    public static final RepositorySpec MAVEN_CENTRAL =
            releasesOnly(new RepositorySpec(CENTRAL, URI.create("https://repo.maven.apache.org/maven2/")));

    /**
     * Synthetic first-party install store under {@code repos/jk-local/} (workers, {@code jk
     * install}). Not a remote — reserved in {@code [repositories]}; lockfile file-deps use the bare
     * source {@code jk-local}.
     */
    public static final String JK_LOCAL = "jk-local";

    /**
     * Groups Google's Android Maven serves. Applied as <em>routed</em> bindings when the Google
     * remote is present: Google is asked first and alone when it answers, so warm multi-repo locks
     * do not probe Central for every {@code androidx.*} GAV, and every other repository is asked
     * when Google misses, because these namespaces are shared: {@code com.google.firebase} holds
     * the Firebase Android SDK on Google and {@code firebase-admin} on Central, and
     * {@code com.google.android:annotations} (a grpc-core runtime dep) is Central-only.
     */
    public static final List<String> GOOGLE_ANDROID_GROUPS = List.of(
            "androidx",
            "androidx.*",
            "com.android",
            "com.android.*",
            "com.google.android.*",
            "com.google.android.gms",
            "com.google.android.gms.*",
            "com.google.android.material",
            "com.google.android.material.*",
            "com.google.firebase",
            "com.google.firebase.*",
            "com.google.mlkit",
            "com.google.mlkit.*",
            "com.google.testing.platform",
            "com.google.testing.platform.*");

    /**
     * The one name Google's Android Maven answers to inside jk — store directory, lockfile
     * {@code source} prefix, repo-group entry. The formatter style of the same spelling
     * ({@code FormatStyles}) is a different vocabulary and does not borrow this constant.
     */
    public static final String GOOGLE = "google";

    /**
     * Google's Android / Play services Maven repository (after Central in the built-in list).
     * Routed for {@link #GOOGLE_ANDROID_GROUPS}; {@link #groups()} carries only what a user binds
     * exclusively on top.
     */
    public static final RepositorySpec GOOGLE_MAVEN =
            releasesOnly(new RepositorySpec(GOOGLE, URI.create("https://dl.google.com/dl/android/maven2/")));

    /**
     * The one name the official first-party repository answers to inside jk — store directory,
     * lockfile {@code source} prefix, repo-group entry, and the plugin registry's notion of the
     * official repo. Named {@code JUMPKICK_NAME} because {@link #JUMPKICK} is the spec itself.
     */
    public static final String JUMPKICK_NAME = "jumpkick";

    /**
     * JumpKick's first-party Maven repository. Exclusive for {@code cc.jumpkick.*} and
     * {@code build.jumpkick.*}. The product URL is {@code https://jumpkick.build/repo/};
     * Hosting redirects that prefix to whatever object store is current (GCS today).
     */
    public static final RepositorySpec JUMPKICK = releasesOnly(new RepositorySpec(
            JUMPKICK_NAME,
            URI.create("https://jumpkick.build/repo/"),
            null,
            null,
            List.of("cc.jumpkick", "cc.jumpkick.*", "build.jumpkick", "build.jumpkick.*")));

    /**
     * System property override for the official repo URL ({@code JK_OFFICIAL_REPO_URL} env as a
     * fallback) — hermetic tests and mirror deployments point it at their own base. Every code
     * path that contacts the official repo must resolve through {@link #officialUrl()} /
     * {@link #officialJumpKick()}; a raw {@code JUMPKICK.url()} lets a redirected deployment (or a
     * test that thinks it is offline) silently reach the public repo.
     */
    public static final String OFFICIAL_REPO_URL_PROPERTY = "jk.official.repo.url";

    /** Base URL ending in {@code /} for the official first-party Maven repo, override applied. */
    public static URI officialUrl() {
        String prop = System.getProperty(OFFICIAL_REPO_URL_PROPERTY);
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("JK_OFFICIAL_REPO_URL");
        }
        if (prop == null || prop.isBlank()) {
            return JUMPKICK.url();
        }
        return URI.create(prop.endsWith("/") ? prop : prop + "/");
    }

    /** {@link #JUMPKICK} with {@link #officialUrl()} applied (same name, groups, and defaults). */
    public static RepositorySpec officialJumpKick() {
        URI url = officialUrl();
        if (url.equals(JUMPKICK.url())) return JUMPKICK;
        return releasesOnly(new RepositorySpec(JUMPKICK.name(), url, null, null, JUMPKICK.groups()));
    }

    /** {@code spec} with snapshots off — the policy of every built-in remote. */
    private static RepositorySpec releasesOnly(RepositorySpec spec) {
        return spec.withPolicy(true, false);
    }

    /** This repository with the given release/snapshot policy. */
    public RepositorySpec withPolicy(boolean releases, boolean snapshots) {
        return new RepositorySpec(
                name,
                url,
                credential,
                objectStore,
                groups,
                allowInsecure,
                allowUnverified,
                releases,
                snapshots,
                blocked);
    }

    /** This repository blocked: kept in the manifest, never asked, named when nothing else serves a package. */
    public RepositorySpec withBlocked() {
        return new RepositorySpec(
                name, url, credential, objectStore, groups, allowInsecure, allowUnverified, releases, snapshots, true);
    }

    /** Convenience: a repository that is asked — the ten-component form with {@code blocked} off. */
    public RepositorySpec(
            String name,
            URI url,
            @Nullable RepoCredential credential,
            @Nullable ObjectStoreConfig objectStore,
            List<String> groups,
            boolean allowInsecure,
            boolean allowUnverified,
            boolean releases,
            boolean snapshots) {
        this(name, url, credential, objectStore, groups, allowInsecure, allowUnverified, releases, snapshots, false);
    }

    /** True when a version of the given kind is asked of this repository. */
    public boolean serves(boolean snapshot) {
        return snapshot ? snapshots : releases;
    }

    /** Convenience: a repository with no inline credential, object-store, or exclusive groups. */
    public RepositorySpec(String name, URI url) {
        this(name, url, null, null, List.of());
    }

    /** Convenience: a repository with a credential but no object-store or exclusive groups. */
    public RepositorySpec(String name, URI url, @Nullable RepoCredential credential) {
        this(name, url, credential, null, List.of());
    }

    /** Convenience: full auth/object-store without exclusive groups. */
    public RepositorySpec(
            String name, URI url, @Nullable RepoCredential credential, @Nullable ObjectStoreConfig objectStore) {
        this(name, url, credential, objectStore, List.of());
    }

    /** Convenience: neither trust opt-in — the default for every built-in and imported repository. */
    public RepositorySpec(
            String name,
            URI url,
            @Nullable RepoCredential credential,
            @Nullable ObjectStoreConfig objectStore,
            List<String> groups) {
        this(name, url, credential, objectStore, groups, false, false);
    }

    /** Convenience: Maven's default policy — releases and snapshots both asked of the repository. */
    public RepositorySpec(
            String name,
            URI url,
            @Nullable RepoCredential credential,
            @Nullable ObjectStoreConfig objectStore,
            List<String> groups,
            boolean allowInsecure,
            boolean allowUnverified) {
        this(name, url, credential, objectStore, groups, allowInsecure, allowUnverified, true, true);
    }

    public Optional<RepoCredential> credentialOpt() {
        return Optional.ofNullable(credential);
    }

    public Optional<ObjectStoreConfig> objectStoreOpt() {
        return Optional.ofNullable(objectStore);
    }

    public RepositorySpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        if (name.isBlank()) throw new IllegalArgumentException("repo name must not be blank");
        groups = groups == null || groups.isEmpty() ? List.of() : List.copyOf(groups);
    }

    /** {@code true} when this repo claims exclusive ownership of at least one group pattern. */
    public boolean hasExclusiveGroups() {
        return !groups.isEmpty();
    }
    /**
     * The one written form of a repository URL, so two manifests naming one origin agree by text:
     * scheme and host lower-cased, an explicit default port ({@code :80} for http, {@code :443} for
     * https) dropped, and the path ending in exactly one slash — a Maven layout is asked with the
     * slash either way. An opaque URL ({@code s3:bucket/path}) is left as declared. The same
     * instance comes back when nothing changes.
     */
    public static URI normalizedUrl(URI url) {
        if (url.isOpaque()) return url;
        String scheme = url.getScheme() == null ? null : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
        int port = url.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
        String rawPath = url.getRawPath() == null ? "" : url.getRawPath();
        String path = rawPath.replaceAll("/+$", "") + "/";
        StringBuilder out = new StringBuilder();
        if (scheme != null) out.append(scheme).append(':');
        if (host != null || url.getRawAuthority() != null || "file".equals(scheme)) {
            out.append("//");
            if (url.getRawUserInfo() != null) out.append(url.getRawUserInfo()).append('@');
            if (host != null) out.append(host);
            if (port >= 0) out.append(':').append(port);
        }
        out.append(path);
        if (url.getRawQuery() != null) out.append('?').append(url.getRawQuery());
        if (url.getRawFragment() != null) out.append('#').append(url.getRawFragment());
        String normalized = out.toString();
        return normalized.equals(url.toString()) ? url : URI.create(normalized);
    }

    /** True when this repository and {@code other} are one repository: their {@link #normalizedUrl} forms agree, origin and path. */
    public boolean sameRepository(RepositorySpec other) {
        return normalizedUrl(url).equals(normalizedUrl(other.url()));
    }

    /**
     * True when {@code a} and {@code b} name one origin — the scheme, host and port a credential is
     * scoped to — compared as {@link #normalizedUrl} spells them: scheme and host case-folded, an
     * explicit default port dropped. The path is the repository within the origin, not the origin,
     * so two repositories on one server are one origin. A URL with no host (an opaque {@code
     * s3:bucket/path}, a {@code file:} tree) names no server to be scoped to and is one origin only
     * with its own normalized spelling.
     */
    public static boolean sameOrigin(URI a, URI b) {
        URI na = normalizedUrl(a);
        URI nb = normalizedUrl(b);
        if (na.getHost() == null || nb.getHost() == null) return na.equals(nb);
        return Objects.equals(na.getScheme(), nb.getScheme())
                && na.getHost().equals(nb.getHost())
                && na.getPort() == nb.getPort();
    }

    /**
     * A host on this machine's loopback interface. Plaintext to it is not the threat the
     * insecure-repository refusal names — there is no network path for anyone to sit on — so a
     * local mirror, an ssh-tunnelled Nexus or a test stub needs no opt-in and is not reported as
     * insecure.
     */
    public static boolean loopback(@Nullable String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("::1") || h.startsWith("127.");
    }
}
