// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where a publish goes and what the client holds for it: the repository (or the Central Portal),
 * the PGP key and the credential. {@link #resolve} reads {@code [publish]} and the optional
 * {@code --repository} override; {@code jk release} runs it before it tags so a release this
 * machine cannot publish is refused with nothing changed.
 */
record PublishTarget(
        @Nullable URI repoUrl, boolean central, @Nullable Path keyFile, RepoCredential credential) {

    /** The production Portal; {@link #PORTAL_ENV} points at another (a stub, a mirror). */
    static final URI CENTRAL_PORTAL = URI.create("https://central.sonatype.com/");

    /** The environment variable that names another Portal for the {@code central} repository. */
    static final String PORTAL_ENV = "JK_CENTRAL_PORTAL_URL";

    /** The credential id the Portal token lives under, in the store and the {@code JK_REPO_<ID>_*} variables. */
    static final String CENTRAL_CREDENTIAL = RepositorySpec.CENTRAL;

    static final String CENTRAL_SIGNING_REQUIRED =
            "Maven Central needs every file signed; set [publish] key-file = \"<secret-key.asc>\""
                    + "\n  give the passphrase with JK_GPG_PASSPHRASE";

    /** The outcome of {@link #resolve}: a target, or the refusal to print. */
    record Resolution(
            @Nullable PublishTarget target, @Nullable String refusal) {
        static Resolution of(PublishTarget target) {
            return new Resolution(target, null);
        }

        static Resolution refused(String message) {
            return new Resolution(null, message);
        }
    }

    /** Whether a real publish would reach a networked HTTP repository with no credential; a loopback one needs none. */
    boolean lacksCredential() {
        if (!credential.isAnonymous()) return false;
        if (central) return true;
        return repoUrl != null
                && repoUrl.getScheme() != null
                && repoUrl.getScheme().startsWith("http")
                && !RepositorySpec.loopback(repoUrl.getHost());
    }

    /** A short name for the target in a refusal: {@code central}, or the repository's URL. */
    String describe() {
        return central ? RepositorySpec.CENTRAL : String.valueOf(repoUrl);
    }

    /**
     * Resolve the target of {@code info}'s publish. {@code override} is {@code --repository}: a
     * declared repository's name, {@code central}, or a URL; it replaces {@code [publish]
     * repository}. A snapshot goes to {@code [publish] snapshot-repository} when set; a release never
     * does. A dry run may name no repository and needs no credential. A run without the library
     * lane ({@code library} false) resolves only the signing key, which the release lane signs with
     * too.
     */
    static Resolution resolve(
            ProjectInfo info,
            Path projectDir,
            Path manifest,
            @Nullable String override,
            boolean snapshot,
            boolean dryRun,
            boolean library,
            Function<String, @Nullable String> env) {
        boolean central = false;
        URI repoUrl = null;
        String repository = !library ? "" : override != null ? override : info.publishRepositoryFor(snapshot);
        boolean snapshotTarget = library
                && override == null
                && snapshot
                && !info.publishSnapshotRepository().isEmpty();
        String source = override != null
                ? "--repository"
                : snapshotTarget ? "[publish] snapshot-repository" : "[publish] repository";
        if (!repository.isEmpty()) {
            if (RepositorySpec.CENTRAL.equals(repository)) {
                if (snapshot) return Resolution.refused("Maven Central takes no -SNAPSHOT; name another repository");
                central = true;
                String portal = env.apply(PORTAL_ENV);
                repoUrl = portal == null || portal.isBlank() ? CENTRAL_PORTAL : URI.create(portal);
            } else {
                repoUrl = locate(manifest, repository);
                if (repoUrl == null) return Resolution.refused(undeclared(source, repository));
            }
        } else if (library && !dryRun) {
            return Resolution.refused("no repository to publish to; set [publish] repository in jk.toml"
                    + " or pass --repository <name|url>");
        }

        Path keyFile = null;
        if (info.publishSign() && !info.publishKeyFile().isEmpty()) {
            String configured = info.publishKeyFile();
            Path home = Path.of(System.getProperty("user.home", "."));
            keyFile = configured.startsWith("~/")
                    ? home.resolve(configured.substring(2))
                    : projectDir.resolve(configured).normalize();
            if (!Files.isRegularFile(keyFile)) {
                return Resolution.refused("[publish] key-file " + configured + " is not a file");
            }
        }
        if (central) {
            if (!info.publishSign()) {
                return Resolution.refused("Maven Central needs every file signed; remove `sign = false` from [publish]"
                        + " and set key-file");
            }
            if (keyFile == null) return Resolution.refused(CENTRAL_SIGNING_REQUIRED);
        }

        RepoCredential credential;
        try {
            credential = repoUrl == null || (central && dryRun)
                    ? RepoCredential.ANONYMOUS
                    : central ? centralCredential(repoUrl) : repositoryCredential(manifest, repoUrl);
        } catch (RuntimeException e) {
            return Resolution.refused(String.valueOf(e.getMessage()));
        }
        return Resolution.of(new PublishTarget(repoUrl, central, keyFile, credential));
    }

    /** The refusal a jk yank of anything on Maven Central gets. */
    static final String CENTRAL_YANK = "Maven Central never delists or deletes a published version";

    /**
     * The repository a yank edits, with the credential a publish there sends: {@code override}
     * ({@code --repository}), else {@code publishRepository}. Maven Central is refused.
     */
    static Resolution yank(String publishRepository, Path manifest, @Nullable String override) {
        String repository = override != null ? override : publishRepository;
        String source = override != null ? "--repository" : "[publish] repository";
        if (repository.isEmpty()) {
            return Resolution.refused("no repository to yank from; set [publish] repository or pass -R");
        }
        if (RepositorySpec.CENTRAL.equals(repository)) return Resolution.refused(CENTRAL_YANK);
        URI repoUrl = locate(manifest, repository);
        if (repoUrl == null) return Resolution.refused(undeclared(source, repository));
        if (RepositorySpec.isCentralHost(repoUrl.getHost())) return Resolution.refused(CENTRAL_YANK);
        try {
            return Resolution.of(new PublishTarget(repoUrl, false, null, repositoryCredential(manifest, repoUrl)));
        } catch (RuntimeException e) {
            return Resolution.refused(String.valueOf(e.getMessage()));
        }
    }

    /**
     * The region and endpoint of the declared repository holding {@code repoUrl}, as a publish
     * there gets them; the AWS credential chain supplies the keys.
     */
    static ObjectStoreConfig objectStore(Path manifest, URI repoUrl) {
        RepositoriesScan.Repo repo = RepositoriesScan.holding(RepositoriesScan.scan(manifest), repoUrl.toString());
        return repo == null
                ? ObjectStoreConfig.EMPTY
                : new ObjectStoreConfig(repo.region(), repo.endpoint(), null, null, null);
    }

    /** The URL {@code repository} names: itself when it is a URL, else the declared repository's publish target. */
    private static @Nullable URI locate(Path manifest, String repository) {
        if (repository.contains("://")) return URI.create(repository);
        String declared = declaredTarget(manifest, repository);
        return declared == null ? null : URI.create(declared);
    }

    private static String undeclared(String source, String repository) {
        return source + " `" + repository + "` is not `central`, a URL or declared under [repositories]";
    }

    /** Where a publish to the declared repository {@code name} goes: its publish-url, else its url. */
    private static @Nullable String declaredTarget(Path manifest, String name) {
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(manifest)) {
            if (repo.name().equals(name)) return repo.publishTarget();
        }
        return null;
    }

    /** The Portal's user token: whatever is stored for the {@code central} id and bound to the Portal's origin. */
    private static RepoCredential centralCredential(URI portal) {
        return new RepoCredentialResolver().resolve(CENTRAL_CREDENTIAL, portal, Optional.empty());
    }

    /** What to do when a Central publish finds no token for the Portal at {@code portal}. */
    static String centralTokenHint(URI portal) {
        return "no Central Portal token; run `jk repo login " + CENTRAL_CREDENTIAL + " -U " + portal
                + " -s <token-name>` with the token's password on stdin\n"
                + "  on CI, set JK_REPO_CENTRAL_USERNAME, JK_REPO_CENTRAL_PASSWORD, JK_REPO_CENTRAL_HOST="
                + portal.getHost();
    }

    /** The credential bound to the declared repository whose url or publish-url {@code repoUrl} falls under. */
    private static RepoCredential repositoryCredential(Path manifest, URI repoUrl) {
        RepositoriesScan.Repo repo = RepositoriesScan.holding(RepositoriesScan.scan(manifest), repoUrl.toString());
        return new RepoCredentialResolver()
                .resolve(
                        repo == null ? null : repo.name(),
                        repoUrl,
                        repo == null ? Optional.empty() : repo.credentialOpt());
    }
}
