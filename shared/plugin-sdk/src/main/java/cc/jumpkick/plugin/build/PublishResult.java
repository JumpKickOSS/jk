// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of a {@link PublishExtension#publish} run — the number of artifact {@code files}
 * handled, whether it was a {@code dryRun} (assembled but not uploaded), how many {@code bytes}
 * went over the wire (0 for a dry run), and the files it {@code written} under the module's build
 * output for the caller to take (the SBOM documents). A failure is signalled by throwing, not by
 * this record.
 */
public record PublishResult(int files, boolean dryRun, long bytes, List<Path> written) {

    public PublishResult {
        written = List.copyOf(written);
    }

    /** A completed upload of {@code files} artifacts, byte count unknown. */
    public static PublishResult uploaded(int files) {
        return new PublishResult(files, false, 0L, List.of());
    }

    /** A completed upload of {@code files} artifacts totalling {@code bytes} of payload. */
    public static PublishResult uploaded(int files, long bytes) {
        return new PublishResult(files, false, bytes, List.of());
    }

    /** A dry run that assembled {@code files} artifacts without uploading. */
    public static PublishResult dryRun(int files) {
        return new PublishResult(files, true, 0L, List.of());
    }

    /** The same outcome, naming the files the run left under the module's build output. */
    public PublishResult withWritten(List<Path> written) {
        return new PublishResult(files, dryRun, bytes, written);
    }
}
