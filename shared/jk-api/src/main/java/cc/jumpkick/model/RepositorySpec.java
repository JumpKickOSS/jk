// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Declared repository: name, URL, optional inline credential, object-store config, and optional
 * exclusive Maven group bindings dependency-confusion defense).
 */
public record RepositorySpec(
        String name,
        URI url,
        Optional<RepoCredential> credential,
        Optional<ObjectStoreConfig> objectStore,
        /** When non-empty, matching {@code groupId}s resolve only from this repo (and other repos
         * that also bind the same group). Patterns: exact, {@code prefix.*} (group or subpackages). */
        List<String> groups) {

    public static final RepositorySpec MAVEN_CENTRAL =
            new RepositorySpec("central", URI.create("https://repo.maven.apache.org/maven2/"));

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
            "com.google.android",
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
     * Google's Android / Play services Maven repository (after Central in the built-in list).
     * Exclusive for {@link #GOOGLE_ANDROID_EXCLUSIVE_GROUPS} by default.
     */
    public static final RepositorySpec GOOGLE_MAVEN = new RepositorySpec(
            "google",
            URI.create("https://dl.google.com/dl/android/maven2/"),
            Optional.empty(),
            Optional.empty(),
            GOOGLE_ANDROID_EXCLUSIVE_GROUPS);

    /**
     * JumpKick's first-party Maven repository (GCS-backed). Exclusive for {@code cc.jumpkick.*}
     * and {@code build.jumpkick.*}. Public URL is {@code https://jumpkick.build/repo/} (Hosting
     * redirect); the transport URL is the GCS HTTPS origin so resolves work before custom DNS.
     */
    public static final RepositorySpec JUMPKICK = new RepositorySpec(
            "jumpkick",
            URI.create("https://storage.googleapis.com/jkbuild-releases/repo/"),
            Optional.empty(),
            Optional.empty(),
            List.of("cc.jumpkick", "cc.jumpkick.*", "build.jumpkick", "build.jumpkick.*"));

    /** Convenience: a repository with no inline credential, object-store, or exclusive groups. */
    public RepositorySpec(String name, URI url) {
        this(name, url, Optional.empty(), Optional.empty(), List.of());
    }

    /** Convenience: a repository with a credential but no object-store or exclusive groups. */
    public RepositorySpec(String name, URI url, Optional<RepoCredential> credential) {
        this(name, url, credential, Optional.empty(), List.of());
    }

    /** Convenience: full auth/object-store without exclusive groups. */
    public RepositorySpec(
            String name, URI url, Optional<RepoCredential> credential, Optional<ObjectStoreConfig> objectStore) {
        this(name, url, credential, objectStore, List.of());
    }

    public RepositorySpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(objectStore, "objectStore");
        if (name.isBlank()) throw new IllegalArgumentException("repo name must not be blank");
        groups = groups == null || groups.isEmpty() ? List.of() : List.copyOf(groups);
    }

    /** {@code true} when this repo claims exclusive ownership of at least one group pattern. */
    public boolean hasExclusiveGroups() {
        return !groups.isEmpty();
    }
}
