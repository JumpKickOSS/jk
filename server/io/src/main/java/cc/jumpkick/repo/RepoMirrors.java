// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Routes a repository through the {@code <mirror>} Maven's {@code settings.xml} names for it.
 *
 * <p>The mirror is a transport rewrite and nothing else: the {@link MavenRepo} keeps its name and
 * its logical URL — what the lock's {@code source} and the store's origin key are built from — and
 * only the URL every request opens changes, sidecars and metadata included. The mirror's
 * credential is the one resolved for the mirror's own id, the way Maven reads the {@code <server>}
 * with the mirror's id.
 *
 * <p>A mirror is held to the transport rule every repository meets: a plaintext {@code http://}
 * mirror on a network path is refused with a warning naming the entry, and the repository is
 * asked at its own URL. A loopback mirror is plaintext to nobody but this machine and is used.
 */
public final class RepoMirrors {

    private RepoMirrors() {}

    /**
     * {@code repo} routed through the mirror {@code settings} names for its id and URL, with the
     * credential {@code creds} resolves for the mirror; {@code repo} itself when no mirror matches
     * or the matching one is refused.
     */
    public static MavenRepo apply(MavenRepo repo, MavenSettings settings, RepoCredentialResolver creds) {
        Optional<MavenSettings.Mirror> match = settings.mirrorFor(repo.name(), repo.baseUrl());
        if (match.isEmpty()) return repo;
        MavenSettings.Mirror mirror = match.get();
        String refusal = refusal(mirror);
        if (refusal != null) {
            RunNotices.warnOnce(
                    "m2-mirror-refused:" + mirror.id() + " " + repo.name(),
                    () -> "jk: warning: " + mirror.label() + " is not used for repository `" + repo.name() + "`: "
                            + refusal + ". The repository is asked at " + SafeUri.forMessage(repo.baseUrl()) + ".");
            return repo;
        }
        RepoCredential credential = creds.resolve(mirror.id(), mirror.url(), Optional.empty());
        return repo.mirroredThrough(new MavenRepo.Mirror(mirror.id(), mirror.url(), credential, mirror.label()));
    }

    /**
     * {@link #apply(MavenRepo, MavenSettings, RepoCredentialResolver)} over the settings this
     * machine has and the request's environment — for a repository a POM declares, which has no
     * builder in hand.
     */
    public static MavenRepo apply(MavenRepo repo) {
        MavenSettings settings = MavenSettings.current();
        if (settings.mirrors().isEmpty()) return repo;
        return apply(repo, settings, RepoCredentialResolver.withEnv(BuildEnv.ambient(), settings));
    }

    /** Why {@code mirror} may not be used, or null when it may. */
    public static @Nullable String refusal(MavenSettings.Mirror mirror) {
        String scheme = mirror.url().getScheme();
        if (scheme == null) return "its URL names no scheme";
        if ("http".equalsIgnoreCase(scheme)
                && !RepositorySpec.loopback(mirror.url().getHost())) {
            return "it is plaintext http on a network path, and a settings.xml mirror has no table to say"
                    + " allow-insecure in";
        }
        return null;
    }
}
