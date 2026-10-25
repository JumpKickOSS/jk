// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.InstallTarget;
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

    /** {@code jk native}: the main class and extra {@code native-image} arguments. */
    record Native(@Nullable String main, List<String> extraArgs) implements TailArgs {
        public Native {
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            if (main != null && main.isBlank()) main = null;
        }
    }

    /**
     * {@code jk image}: {@code tarball} is tri-state like {@code --tarball}: {@code null} (no
     * tarball), {@code ""} (the default layout path), or an explicit path.
     */
    record Image(
            @Nullable String main,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarball,
            @Nullable String docker)
            implements TailArgs {}

    /**
     * {@code jk install} and its re-shelve pass: the local Maven repo root (null for {@code ~/.m2})
     * and the {@code --target} override (null defers to {@code jk.toml}, then {@code config.toml});
     * {@code snapshot} is {@code jk snapshot}: every module installs at its version plus {@code -SNAPSHOT}.
     */
    record Install(@Nullable Path m2Dir, @Nullable InstallTarget target, boolean snapshot) implements TailArgs {}

    /** {@code jk train}: the profile filter, and whether to retrain an up-to-date surface. */
    record Train(@Nullable String profile, boolean force) implements TailArgs {}

    /** {@code jk publish}: where to, how signed, and the credential the client resolved. */
    record Publish(
            @Nullable URI repoUrl,
            @Nullable String region,
            @Nullable String endpoint,
            @Nullable Path jar,
            boolean allowSnapshot,
            boolean dryRun,
            @Nullable Path keyFile,
            @Nullable String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            RepoCredential credential,
            boolean central,
            @Nullable String publishingType)
            implements TailArgs {}
}
