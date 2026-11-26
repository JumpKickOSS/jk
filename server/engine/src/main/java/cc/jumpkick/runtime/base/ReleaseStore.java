// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.release.ReleaseIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a release's files go: an object-store prefix or a GitHub Release, opened by {@link
 * ReleaseStores#open}. Every write is create-only and resumable: a file already there with the
 * same bytes is left alone and other bytes stop the run.
 */
public interface ReleaseStore {

    /** What a create-only write found. */
    enum Put {
        UPLOADED,
        EXISTING
    }

    /** A file in the release: its size and lower-case hex SHA-256. */
    record Held(long size, String sha256) {}

    /** Upload {@code file} as {@code name}, create-only. */
    Put put(String name, Path file) throws IOException, InterruptedException;

    /** Upload {@code body} as {@code name}, create-only. */
    Put put(String name, byte[] body) throws IOException, InterruptedException;

    /** True when the release holds {@code name}. */
    boolean present(String name) throws IOException, InterruptedException;

    /** The size and SHA-256 of {@code name}; empty when the release does not hold it. */
    Optional<Held> held(String name) throws IOException, InterruptedException;

    /** The download URL {@code index.json} records for {@code name}, or {@code null} when the target names its own. */
    @Nullable
    String url(String name);

    /** True when the release is already out and takes no new file. */
    boolean published() throws IOException, InterruptedException;

    /**
     * Ready the release to take files, once, before any module uploads: GitHub creates the draft
     * here, so modules that upload at the same time find the one draft.
     */
    void prepare() throws IOException, InterruptedException;

    /** Make the release public; returns the line that says what happened. */
    String publish() throws IOException, InterruptedException;

    /** The {@code Content-Type} a release file is uploaded with. */
    static String contentType(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".asc")) return "application/pgp-signature";
        if (name.endsWith(".jar") || name.endsWith(".war")) return "application/java-archive";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".tar.gz")) return "application/gzip";
        if (name.equals(ReleaseIndex.SUMS)) return "text/plain";
        return "application/octet-stream";
    }
}
