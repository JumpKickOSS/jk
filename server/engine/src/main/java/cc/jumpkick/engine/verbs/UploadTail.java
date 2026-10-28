// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.runtime.base.ProjectInfoPlans;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The publish tail of an agent's {@code upload} job: the project's own {@code [publish]} target
 * and the credential the stored chain holds for it. An agent never names a URL; it uploads where
 * the manifest says releases go.
 */
final class UploadTail {

    private UploadTail() {}

    /**
     * @throws IllegalArgumentException with the fix when the project names no uploadable target
     */
    static TailArgs.Publish of(Path dir) {
        ProjectInfo info = ProjectInfoPlans.projectInfo(dir);
        if (info.error() != null) throw new IllegalArgumentException(info.error());
        String repository = info.publishRepository();
        if (repository.isEmpty()) {
            throw new IllegalArgumentException(
                    "upload publishes to the repository [publish] names: add `repository = \"<name>\"` to [publish]"
                            + " in jk.toml (a [repositories.<name>] entry). Without upload, publish is a dry run.");
        }
        if (RepositorySpec.CENTRAL.equals(repository)) {
            throw new IllegalArgumentException(
                    "a Central Portal upload needs a signing key and stays on the CLI: run `jk publish`.");
        }
        if (!repository.contains("://")) {
            throw new IllegalArgumentException(
                    "[publish] repository `" + repository + "` is not declared under [repositories].");
        }
        URI url = URI.create(repository);
        String matchedName = null;
        Optional<RepoCredential> inline = Optional.empty();
        for (RepositoriesScan.Repo repo : RepositoriesScan.scan(ManifestPaths.manifestIn(dir))) {
            String base = repo.url().endsWith("/") ? repo.url() : repo.url() + "/";
            if (repository.equals(repo.url()) || repository.startsWith(base)) {
                matchedName = repo.name();
                inline = repo.credentialOpt();
                break;
            }
        }
        RepoCredential credential = new RepoCredentialResolver().resolve(matchedName, url, inline);
        Path keyFile = null;
        String passphrase = null;
        if (info.publishSign()) {
            if (info.publishKeyFile().isEmpty()) {
                throw new IllegalArgumentException("[publish] sign = true needs key-file in [publish].");
            }
            String configured = info.publishKeyFile();
            keyFile = configured.startsWith("~/")
                    ? Path.of(System.getProperty("user.home", ".")).resolve(configured.substring(2))
                    : dir.resolve(configured).normalize();
            passphrase = BuildEnv.get("JK_GPG_PASSPHRASE");
        }
        return new TailArgs.Publish(
                url, null, null, null, false, false, keyFile, passphrase, false, false, false, credential, false, null);
    }
}
