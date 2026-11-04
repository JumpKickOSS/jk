// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.RepositoryMirror;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Routes a repository through the mirror that stands in for it: the first {@code mirror-of} table
 * of {@code ~/.jk/config.toml} that matches, in file order, else the first {@code <mirror>} of
 * Maven's {@code settings.xml} that does.
 *
 * <p>The mirror is a transport rewrite and nothing else: the {@link MavenRepo} keeps its name and
 * its logical URL — what the lock's {@code source} and the store's origin key are built from — and
 * only the URL every request opens changes, sidecars and metadata included. The mirror's
 * credential is the one resolved for the mirror's own id, the way Maven reads the {@code <server>}
 * with the mirror's id.
 *
 * <p>A mirror never goes direct. One that is unreachable or refuses access fails the resolve
 * naming it ({@link MavenRepo}), and so does one jk refuses outright: a plaintext {@code http://}
 * {@code settings.xml} mirror on a network path, which has no table to say {@code allow-insecure}
 * in. The repository is never asked at its own URL in the mirror's place. A loopback mirror is
 * plaintext to nobody but this machine and is used.
 */
public final class RepoMirrors {

    private RepoMirrors() {}

    /** The mirror that stands in for a repository, whichever file declared it. */
    private record Chosen(
            String id,
            URI url,
            Optional<RepoCredential> inline,
            String label,
            @Nullable String refusal) {}

    /**
     * {@code repo} routed through the mirror {@code natives} or, failing those, {@code settings}
     * names for its id and URL, with the credential {@code creds} resolves for the mirror; {@code
     * repo} itself when no mirror matches.
     */
    public static MavenRepo apply(
            MavenRepo repo, List<RepositoryMirror> natives, MavenSettings settings, RepoCredentialResolver creds) {
        Optional<Chosen> match = choose(repo, natives, settings);
        if (match.isEmpty()) return repo;
        Chosen mirror = match.get();
        RepoCredential credential = mirror.refusal() != null
                ? RepoCredential.ANONYMOUS
                : creds.resolve(mirror.id(), mirror.url(), mirror.inline());
        MavenRepo mirrored = repo.mirroredThrough(
                new MavenRepo.Mirror(mirror.id(), mirror.url(), credential, mirror.label(), mirror.refusal()));
        // The mirror's credential is the one every request carries; a ${cmd:} helper's may have
        // expired, so a refusal re-runs it once, as it would for the repository itself.
        var refresh = creds.refresher(mirror.id(), mirror.url(), mirror.inline(), credential);
        return refresh == null ? mirrored : mirrored.refreshingCredentialWith(refresh);
    }

    /**
     * {@link #apply(MavenRepo, List, MavenSettings, RepoCredentialResolver)} over the mirrors this
     * machine declares and the request's environment — for a repository a POM declares, which has
     * no builder in hand.
     */
    public static MavenRepo apply(MavenRepo repo) {
        List<RepositoryMirror> natives = GlobalConfig.repositoryMirrors();
        MavenSettings settings = MavenSettings.current();
        if (natives.isEmpty() && settings.mirrors().isEmpty()) return repo;
        return apply(repo, natives, settings, RepoCredentialResolver.withEnv(BuildEnv.ambient(), settings));
    }

    private static Optional<Chosen> choose(MavenRepo repo, List<RepositoryMirror> natives, MavenSettings settings) {
        for (RepositoryMirror mirror : natives) {
            if (mirror.matches(repo.name(), repo.baseUrl())) {
                return Optional.of(new Chosen(
                        mirror.id(), mirror.url(), Optional.ofNullable(mirror.credential()), mirror.label(), null));
            }
        }
        return settings.mirrorFor(repo.name(), repo.baseUrl())
                .map(m -> new Chosen(m.id(), m.url(), Optional.empty(), m.label(), refusal(m)));
    }

    /**
     * Why jk will not use {@code mirror}, or null when it will: a scheme-less URL, or plaintext
     * {@code http://} on a network path. Every repository it matches then fails to resolve, naming
     * it and the two ways out.
     */
    public static @Nullable String refusal(MavenSettings.Mirror mirror) {
        String scheme = mirror.url().getScheme();
        if (scheme == null) return "its URL names no scheme";
        if ("http".equalsIgnoreCase(scheme)
                && !RepositorySpec.loopback(mirror.url().getHost())) {
            return "it is plaintext http on a network path, and a settings.xml mirror has no table to say"
                    + " allow-insecure in — switch it to https, or declare it in ~/.jk/config.toml as"
                    + " [repositories." + mirror.id() + "] with url, mirror-of = ["
                    + String.join(
                            ", ",
                            MirrorOf.parse(mirror.mirrorOf()).tokens().stream()
                                    .map(t -> "\"" + t + "\"")
                                    .toList())
                    + "] and allow-insecure = true";
        }
        return null;
    }
}
