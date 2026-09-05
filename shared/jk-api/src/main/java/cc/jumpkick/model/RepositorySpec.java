// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Declared repository: name, URL, optional inline credential, object-store config, and optional
 * exclusive Maven group bindings dependency-confusion defense).
 */
public record RepositorySpec(
        String name,
        URI url,
        @Nullable RepoCredential credential,
        @Nullable ObjectStoreConfig objectStore,
        /** When non-empty, matching {@code groupId}s resolve only from this repo (and other repos
         * that also bind the same group). Patterns: exact, {@code prefix.*} (group or subpackages). */
        List<String> groups) {

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
            new RepositorySpec(CENTRAL, URI.create("https://repo.maven.apache.org/maven2/"));

    /**
     * Synthetic first-party install store under {@code repos/jk-local/} (workers, {@code jk
     * install}). Not a remote — reserved in {@code [repositories]}; lockfile file-deps use the bare
     * source {@code jk-local}.
     */
    public static final String JK_LOCAL = "jk-local";

    /**
     * Groups that live on Google's Android Maven (not Maven Central). Applied as exclusive
     * bindings when the Google remote is present so warm multi-repo locks do not probe Central
     * for every {@code androidx.*} GAV.
     */
    public static final List<String> GOOGLE_ANDROID_EXCLUSIVE_GROUPS = List.of(
            "androidx",
            "androidx.*",
            "com.android",
            "com.android.*",
            // NOT the bare "com.google.android" group: its artifacts (com.google.android:annotations,
            // a grpc-core runtime dep) are hosted only on Central — claiming the bare group made
            // grpc-netty-shaded's closure unresolvable and the b1a1e7d9 re-lock silently dropped
            // it. Subgroups below stay exclusive.
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
     * Exclusive for {@link #GOOGLE_ANDROID_EXCLUSIVE_GROUPS} by default.
     */
    public static final RepositorySpec GOOGLE_MAVEN = new RepositorySpec(
            GOOGLE,
            URI.create("https://dl.google.com/dl/android/maven2/"),
            null,
            null,
            GOOGLE_ANDROID_EXCLUSIVE_GROUPS);

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
    public static final RepositorySpec JUMPKICK = new RepositorySpec(
            JUMPKICK_NAME,
            URI.create("https://jumpkick.build/repo/"),
            null,
            null,
            List.of("cc.jumpkick", "cc.jumpkick.*", "build.jumpkick", "build.jumpkick.*"));

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
        return new RepositorySpec(JUMPKICK.name(), url, null, null, JUMPKICK.groups());
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
}
