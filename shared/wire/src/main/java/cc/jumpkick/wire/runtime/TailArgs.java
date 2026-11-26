// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.InstallDestination;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a goal's tail needs from the client beyond the build: the arguments the client resolved
 * (paths, flags, credentials), never anything the engine can read from the project itself. One
 * variant per goal with a tail; a goal without one carries none.
 */
public sealed interface TailArgs {

    /**
     * {@code jk sync}: also fetch every library's sources jar (for the IDE), and {@code repoUrl}, a
     * single repository that replaces the declared ones (tests).
     */
    record Sync(boolean sources, @Nullable URI repoUrl) implements TailArgs {}

    /** {@code jk native}: the main class and extra {@code native-image} arguments. */
    record Native(@Nullable String main, List<String> extraArgs) implements TailArgs {
        public Native {
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            if (main != null && main.isBlank()) main = null;
        }
    }

    /**
     * {@code jk image}, which builds and loads the image and never pushes it: {@code tarball} is
     * tri-state like {@code --tarball}: {@code null} (load into the daemon), {@code ""} (the default
     * layout path), or an explicit path.
     */
    record Image(
            @Nullable String main,
            @Nullable String tag,
            @Nullable String tarball,
            @Nullable String docker) implements TailArgs {}

    /**
     * {@code jk install} and its re-shelve pass: the local Maven repo root (null for {@code ~/.m2})
     * and the {@code --destination} override (null defers to {@code jk.toml}, then {@code config.toml}).
     */
    record Install(@Nullable Path m2Dir, @Nullable InstallDestination destination) implements TailArgs {}

    /** {@code jk train}: the profile filter, and whether to retrain an up-to-date surface. */
    record Train(@Nullable String profile, boolean force) implements TailArgs {}

    /**
     * {@code jk publish}: the library repository, the PGP key and passphrase the client resolved,
     * the credential, the {@link PublishLane lanes} the run ships to and whether it only stages this
     * host's release files, and the token a forge release is written with ({@code null} when the
     * release goes to a bucket or the run is dry; {@code releaseJobToken} when it is GitLab CI's
     * {@code CI_JOB_TOKEN}). Signing, SBOM and provenance come from the
     * project's {@code [publish]} table and the engine's view of CI, never from the client.
     */
    record Publish(
            @Nullable URI repoUrl,
            boolean allowSnapshot,
            boolean dryRun,
            @Nullable Path keyFile,
            @Nullable String gpgPassphrase,
            RepoCredential credential,
            boolean central,
            List<PublishLane> lanes,
            boolean stage,
            @Nullable String releaseToken,
            boolean releaseJobToken)
            implements TailArgs {
        public Publish {
            lanes = List.copyOf(lanes);
        }

        /** True when the run ships to {@code lane}. */
        public boolean runs(PublishLane lane) {
            return lanes.contains(lane);
        }
    }
}
