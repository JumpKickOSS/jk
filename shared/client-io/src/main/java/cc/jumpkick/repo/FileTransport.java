// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link RepoTransport} for {@code file://} repositories — a local directory tree used as a Maven
 * repo (offline mirrors, tests, air-gapped setups). Reads and writes the filesystem directly;
 * credentials are irrelevant and ignored. A missing file is "not found" (empty), matching the HTTP
 * 404 path.
 */
public final class FileTransport implements RepoTransport {

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential ignored) throws IOException {
        Path path = Path.of(uri);
        if (!Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(Files.readAllBytes(path));
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential ignored) throws IOException {
        // Temp and atomic move: a repository file may be a hard link, and an in-place write would
        // reach every other name of it.
        AtomicWrites.replace(Path.of(uri), body);
        return 201; // created — mirrors a successful HTTP PUT
    }
}
