// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a publish run's release lane did: where the release lives, whether the run staged this
 * host's platform files or was the final run, every file of the release, what its last step did
 * or a dry run would do ({@code latest.json names 1.4.7}, {@code published v1.4.7}; {@code null}
 * when nothing), the Central deployments it published once the release was out, and the notes it
 * has for the reader (an unsigned release), one line each.
 */
public record ReleaseOutcome(
        String destination,
        boolean stage,
        boolean dryRun,
        List<File> files,
        @Nullable String finish,
        List<String> released,
        List<String> notes) {

    public ReleaseOutcome {
        files = List.copyOf(files);
        released = List.copyOf(released);
        notes = List.copyOf(notes);
    }

    /** {@code staging}: a platform's own file, which a stage run may upload. */
    public static final String STAGING = "staging";

    /** {@code final}: a file only the final run writes. */
    public static final String FINAL = "final";

    /** Sent by this run. */
    public static final String UPLOADED = "uploaded";

    /** Already there with the same bytes; left alone. */
    public static final String EXISTING = "existing";

    /** Uploaded by another platform's stage run. */
    public static final String STAGED = "staged";

    /** A dry run's file: nothing was sent. */
    public static final String PLANNED = "planned";

    /**
     * One file: its name in the release, the module that built it ({@code ""} for the release's own
     * documents), its platform ({@code null} when neutral), its size and SHA-256 ({@code -1} and
     * {@code ""} when a dry run cannot know them), {@link #STAGING} or {@link #FINAL}, and what the
     * run did with it.
     */
    public record File(
            String name,
            String module,
            @Nullable String platform,
            long size,
            String sha256,
            String role,
            String status) {}
}
