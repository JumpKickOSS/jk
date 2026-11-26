// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.RepositoriesScan;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.runtime.base.ProjectInfoPlans;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The publish tail of an agent's {@code publish} job: the project's own {@code [publish]} target
 * and, for an upload, the credential the stored chain holds for it. An agent never names a URL; it
 * uploads where the manifest says releases go.
 */
final class UploadTail {

    private UploadTail() {}

    /**
     * A dry run: nothing is sent and no credential is read. Its destination is the URL the
     * {@code [publish]} repository writes to, or none when the manifest names no repository URL;
     * a project with a {@code [publish] release} bucket also plans its release lane, and one with an
     * {@code [image] registry} the images it would push.
     */
    static TailArgs.Publish dryRun(Path dir) {
        ProjectInfo info = ProjectInfoPlans.projectInfo(dir);
        // A broken manifest is the job's to report; the tail only names a destination.
        String repository = info.error() == null ? info.publishRepository() : "";
        URI url = repository.contains("://") ? URI.create(repository) : null;
        List<PublishLane> lanes = new ArrayList<>(List.of(PublishLane.LIBRARY));
        String release = info.error() == null ? info.publishRelease() : "";
        if (!release.isEmpty()
                && ReleaseTarget.refusal(release) == null
                && ReleaseTarget.parse(release) instanceof ReleaseTarget.Bucket) {
            lanes.add(PublishLane.RELEASE);
        }
        if (info.error() == null && !info.imageRegistry().isEmpty()) lanes.add(PublishLane.IMAGE);
        return new TailArgs.Publish(
                url, false, true, null, null, RepoCredential.ANONYMOUS, false, lanes, false, null, false);
    }

    /**
     * @throws IllegalArgumentException with the fix when the project names no uploadable target
     */
    static TailArgs.Publish of(Path dir) {
        ProjectInfo info = ProjectInfoPlans.projectInfo(dir);
        if (info.error() != null) throw new IllegalArgumentException(info.error());
        String repository = info.publishRepository();
        if (repository.isEmpty()) {
            throw new IllegalArgumentException(
                    "upload needs a target: set [publish] repository = \"<name>\" in jk.toml");
        }
        if (RepositorySpec.CENTRAL.equals(repository)) {
            throw new IllegalArgumentException("a Central Portal upload needs a signing key; run jk publish");
        }
        if (!repository.contains("://")) {
            throw new IllegalArgumentException(
                    "[publish] repository `" + repository + "` is not declared under [repositories].");
        }
        URI url = URI.create(repository);
        RepositoriesScan.Repo repo =
                RepositoriesScan.holding(RepositoriesScan.scan(ManifestPaths.manifestIn(dir)), repository);
        RepoCredential credential = new RepoCredentialResolver()
                .resolve(
                        repo == null ? null : repo.name(), url, repo == null ? Optional.empty() : repo.credentialOpt());
        Path keyFile = null;
        String passphrase = null;
        if (info.publishSign() && !info.publishKeyFile().isEmpty()) {
            String configured = info.publishKeyFile();
            keyFile = configured.startsWith("~/")
                    ? Path.of(System.getProperty("user.home", ".")).resolve(configured.substring(2))
                    : dir.resolve(configured).normalize();
            passphrase = BuildEnv.get("JK_GPG_PASSPHRASE");
        }
        return new TailArgs.Publish(
                url,
                false,
                false,
                keyFile,
                passphrase,
                credential,
                false,
                List.of(PublishLane.LIBRARY),
                false,
                null,
                false);
    }
}
