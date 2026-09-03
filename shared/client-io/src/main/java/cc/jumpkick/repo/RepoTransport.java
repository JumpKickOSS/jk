// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * Artifact transport selected by repository URL scheme ({@code http(s)}, {@code s3://}, {@code
 * gs://}, {@code file://}). Credentials are per-call so one instance can serve many repos.
 */
public interface RepoTransport {

    /**
     * Fetch the bytes at {@code uri}. Returns empty when the artifact is not found (e.g. HTTP 404 /
     * missing object) so callers can try the next repo; throws {@link IOException} for genuine
     * transport failures.
     */
    Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException;

    /**
     * Fetch the bytes at {@code uri} as a stream, so the caller can pump them to disk (e.g. the CAS)
     * without ever holding the whole payload in memory. Returns empty on not-found, mirroring {@link
     * #fetch}; the caller owns closing the returned stream.
     *
     * <p>The default buffers via {@link #fetch} for transports without a native streaming body;
     * {@link HttpTransport} overrides it to hand back the live response body, which is what keeps a
     * cold-cache resolve of large artifacts off the heap.
     */
    default Optional<InputStream> fetchStream(URI uri, RepoCredential credential)
            throws IOException, InterruptedException {
        return fetch(uri, credential).map(ByteArrayInputStream::new);
    }

    /**
     * Upload {@code body} to {@code uri} and return a status code — the HTTP status for {@link
     * HttpTransport}, or a 2xx-range value for object stores on success. The caller decides what
     * counts as success; a transport-level failure throws {@link IOException}.
     */
    int put(URI uri, byte[] body, String contentType, RepoCredential credential)
            throws IOException, InterruptedException;
}
