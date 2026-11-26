// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Processes;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What the engine adds to an image push's provenance, for a JVM and a node module alike: the commit
 * the module was built from, and whether the worker signs the statement with Sigstore.
 */
final class ImagePushProvenance {

    private static final Duration GIT_HEAD_WAIT = Duration.ofSeconds(5);

    private ImagePushProvenance() {}

    /**
     * Why a push of {@code config} cannot be signed as it asks, or null when it can or is not asked
     * to: the signature is over the provenance a push attaches, and a Dockerfile-mode push or {@code
     * referrers = false} attaches none. Said before anything ships, never after an unsigned push.
     */
    static @Nullable String unsignable(ImageConfig config) {
        if (!config.sigstore()) return null;
        if (config.dockerFile() != null && !config.dockerFile().isBlank()) {
            return "[image] sigstore: a Dockerfile-mode push attaches no provenance to sign";
        }
        return config.referrers() ? null : "[image] sigstore: referrers = false attaches no provenance to sign";
    }

    /**
     * What a push's provenance needs from the engine beyond the image: the commit the module's
     * checkout is at, and whether the worker signs it.
     */
    static void write(SpecWriter sw, ImageConfig config, Path moduleDir) {
        String commit = sourceCommit(moduleDir);
        if (commit != null) sw.configString("sourceCommit", commit);
        if (config.sigstore()) sw.configBool("sigstore", true);
    }

    /** The full commit {@code HEAD} names in {@code dir}'s checkout, or null outside one or when git cannot say. */
    static @Nullable String sourceCommit(Path dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                            GitCommand.of("-C", dir.toString(), "rev-parse", "--verify", "--quiet", "HEAD"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Processes.Captured c = Processes.capture(pb, JobWorkers.startDetached(pb), GIT_HEAD_WAIT);
            String out = c.output().strip();
            return c.ok() && out.matches("[0-9a-f]{40}([0-9a-f]{24})?") ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
