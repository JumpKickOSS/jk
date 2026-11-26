// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.credential.RepoCredential;
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
     * repository}. A dry run may name no repository and needs no credential.
     */
    static Resolution resolve(
            ProjectInfo info,
            Path projectDir,
            Path manifest,
            @Nullable String override,
            boolean dryRun,
            Function<String, @Nullable String> env) {
        boolean central = false;
        URI repoUrl = null;
        String repository = override != null ? override : info.publishRepository();
        String source = override != null ? "--repository" : "[publish] repository";
        if (!repository.isEmpty()) {
            if (RepositorySpec.CENTRAL.equals(repository)) {
                central = true;
                String portal = env.apply(PORTAL_ENV);
                repoUrl = portal == null || portal.isBlank() ? CENTRAL_PORTAL : URI.create(portal);
            } else if (repository.contains("://")) {
                repoUrl = URI.create(repository);
            } else {
                String declared = declaredUrl(manifest, repository);
                if (declared == null) {
                    return Resolution.refused(
                            source + " `" + repository + "` is not `central`, a URL or declared under [repositories]");
                }
                repoUrl = URI.create(declared);
            }
        } else if (!dryRun) {
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

    private static @Nullable String declaredUrl(Path manifest, String name) {
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(manifest)) {
            if (repo.name().equals(name)) return repo.url();
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

    private static RepoCredential repositoryCredential(Path manifest, URI repoUrl) {
        String matchedName = null;
        Optional<RepoCredential> inline = Optional.empty();
        String target = repoUrl.toString();
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(manifest)) {
            String base = repo.url();
            String basePrefix = base.endsWith("/") ? base : base + "/";
            if (target.equals(base) || target.startsWith(basePrefix)) {
                matchedName = repo.name();
                inline = repo.credentialOpt();
                break;
            }
        }
        return new RepoCredentialResolver().resolve(matchedName, repoUrl, inline);
    }
}
