// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** Object-store uploads create keys by default. Mutable objects use an opaque revision from a read. */
public interface ObjectStoreTransport extends RepoTransport {

    /** A body with its HTTP ETag and the provider token used to detect changes. */
    record Revision(byte[] body, String validator, String etag) {}

    Optional<Revision> readRevision(URI uri, RepoCredential credential) throws IOException, InterruptedException;

    /** Replace the observed revision, or create the key when {@code previous} is empty. */
    int putRevision(URI uri, byte[] body, String contentType, RepoCredential credential, Optional<Revision> previous)
            throws IOException, InterruptedException;
}
