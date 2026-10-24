// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Which repositories keep their artifacts in the Maven local repository.
 *
 * <p>Maven Central, and every repository the user's {@code settings.xml} declares in an active
 * profile, are Maven's to share: their jars live once, in {@link M2Dirs#localRepository()}, and jk
 * reads them there. Every other origin — the first-party shelf, the repositories jk ships with
 * beyond Central, a project's own — keeps a tree under {@code <store>/repos/<id>/}. A repository
 * is never in both, so no artifact is stored twice.
 *
 * <p>The decision turns on the repository's origin alone, so the store a resolve writes through and
 * the store a later build reads from agree without either having to ask the project. The one
 * switch is {@code [m2] integration}: off, every origin is store-backed.
 */
public final class M2Backing {

    private M2Backing() {}

    /** The local repository {@code origin}'s artifacts live in, or empty when its own store tree holds them. */
    public static Optional<Path> rootFor(URI origin, boolean m2integration) {
        if (!m2integration || !JkM2Config.resolve().integration()) return Optional.empty();
        return shared(origin, MavenSettings.current()) ? Optional.of(M2Dirs.localRepository()) : Optional.empty();
    }

    /** True when Maven would keep {@code origin}'s artifacts in the local repository: Central, or one {@code settings} declares. */
    static boolean shared(URI origin, MavenSettings settings) {
        String canonical = RepoIdentity.canonicalOrigin(origin);
        if (canonical.equals(RepoIdentity.canonicalOrigin(RepositorySpec.MAVEN_CENTRAL.url()))) return true;
        for (RepositorySpec declared : settings.profileRepositories()) {
            if (canonical.equals(RepoIdentity.canonicalOrigin(declared.url()))) return true;
        }
        return false;
    }
}
