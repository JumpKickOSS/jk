// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * The outcome of a {@link PublishExtension#publish} run — the number of artifact {@code files}
 * handled, whether it was a {@code dryRun} (assembled but not uploaded), and how many {@code bytes}
 * went over the wire (0 for a dry run). A failure is signalled by throwing, not by this record.
 */
public record PublishResult(int files, boolean dryRun, long bytes) {

    /** A completed upload of {@code files} artifacts, byte count unknown. */
    public static PublishResult uploaded(int files) {
        return new PublishResult(files, false, 0L);
    }

    /** A completed upload of {@code files} artifacts totalling {@code bytes} of payload. */
    public static PublishResult uploaded(int files, long bytes) {
        return new PublishResult(files, false, bytes);
    }

    /** A dry run that assembled {@code files} artifacts without uploading. */
    public static PublishResult dryRun(int files) {
        return new PublishResult(files, true, 0L);
    }
}
