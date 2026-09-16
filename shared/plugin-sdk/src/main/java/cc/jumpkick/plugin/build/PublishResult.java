// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a {@link PublishExtension#publish} run — the number of artifact {@code files}
 * handled, whether it was a {@code dryRun} (assembled but not uploaded), how many {@code bytes}
 * went over the wire (0 for a dry run), the files it {@code written} under the module's build
 * output for the caller to take (the SBOM documents, a Central bundle), the {@code bundle} entries
 * of a Central bundle, and the Portal {@code deployment} when there was one. A failure is
 * signalled by throwing, not by this record; a deployment the Portal rejected is a failure that
 * still carries its facts, so the publisher throws after reporting it.
 */
public record PublishResult(
        int files,
        boolean dryRun,
        long bytes,
        List<Path> written,
        List<String> bundle,
        @Nullable Deployment deployment) {

    public PublishResult {
        written = List.copyOf(written);
        bundle = List.copyOf(bundle);
    }

    /** A Central Portal deployment: the id the Portal assigned, the state the poll ended in, its validation errors. */
    public record Deployment(String id, String state, List<String> errors) {
        public Deployment {
            errors = List.copyOf(errors);
        }
    }

    /** A completed upload of {@code files} artifacts, byte count unknown. */
    public static PublishResult uploaded(int files) {
        return new PublishResult(files, false, 0L, List.of(), List.of(), null);
    }

    /** A completed upload of {@code files} artifacts totalling {@code bytes} of payload. */
    public static PublishResult uploaded(int files, long bytes) {
        return new PublishResult(files, false, bytes, List.of(), List.of(), null);
    }

    /** A dry run that assembled {@code files} artifacts without uploading. */
    public static PublishResult dryRun(int files) {
        return new PublishResult(files, true, 0L, List.of(), List.of(), null);
    }

    /** The same outcome, naming the files the run left under the module's build output. */
    public PublishResult withWritten(List<Path> written) {
        return new PublishResult(files, dryRun, bytes, written, bundle, deployment);
    }

    /** The same outcome, listing the entries of the Central bundle it assembled. */
    public PublishResult withBundle(List<String> bundle) {
        return new PublishResult(files, dryRun, bytes, written, bundle, deployment);
    }

    /** The same outcome, carrying the Portal deployment it ended on. */
    public PublishResult withDeployment(Deployment deployment) {
        return new PublishResult(files, dryRun, bytes, written, bundle, deployment);
    }
}
