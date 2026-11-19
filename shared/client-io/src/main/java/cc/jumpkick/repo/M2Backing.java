// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Which repositories' artifacts may be in the Maven local repository, and which are downloaded
 * into it.
 *
 * <p>Maven Central, and every repository the user's {@code settings.xml} declares in an active
 * profile, are the ones Maven keeps in {@link M2Dirs#localRepository()}. For those, a verified
 * file already there is used where it is ({@link #rootFor}), so nothing Maven fetched is fetched or
 * stored again. Where jk's own download of one lands is the machine's {@code [m2] integration}
 * ({@link JkM2Config.Mode}): its tree under {@code <store>/repos/<id>/} by default, so the store
 * alone holds everything jk fetched, or the local repository under {@code "share"} ({@link
 * #writesThrough}). Every other origin — the first-party shelf, the repositories jk ships with
 * beyond Central, a project's own — only ever has its store tree.
 *
 * <p>A reader does not need to know which mode a file was fetched under: it looks in the store
 * tree, then in the local repository ({@link RepoArtifactStore}).
 */
public final class M2Backing {

    private M2Backing() {}

    /** The local repository {@code origin}'s artifacts may be read from, or empty when only its store tree holds them. */
    public static Optional<Path> rootFor(URI origin, boolean m2integration) {
        if (!m2integration || !JkM2Config.resolve().integration()) return Optional.empty();
        return shared(origin, MavenSettings.current()) ? Optional.of(M2Dirs.localRepository()) : Optional.empty();
    }

    /** True when a download from {@code origin} is written into the local repository, not the store tree. */
    public static boolean writesThrough(URI origin, boolean m2integration) {
        return JkM2Config.resolve().share() && rootFor(origin, m2integration).isPresent();
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
