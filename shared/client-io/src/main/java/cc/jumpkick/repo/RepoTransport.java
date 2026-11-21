// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * A fetched body and the URL that answered it. That is {@code uri} itself unless the transport
     * sent the request elsewhere: {@link HttpTransport} sends a request for Central to Central's mirror
     * while Central refuses this host, deciding so as the request goes out, and follows redirects.
     */
    record Answer(byte[] body, URI from) {}

    /**
     * As {@link #fetch}, naming the URL that answered, for a caller that must know who said what the
     * bytes are. The default answers from {@code uri}, for transports that never send a request
     * elsewhere.
     */
    default Optional<Answer> fetchAnswer(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        return fetch(uri, credential).map(body -> new Answer(body, uri));
    }

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
     * A streamed body and the length the server declared for it: {@code length} is the byte count
     * the stream will deliver, or {@code -1} when the server sent none (chunked, or a compressed
     * body whose decoded size is unknown). The caller owns closing {@code in}.
     */
    record Body(InputStream in, long length) {}

    /**
     * As {@link #fetchStream}, with the declared length beside the stream so a caller can show
     * progress. The default declares nothing; {@link HttpTransport} reads {@code Content-Length}.
     */
    default Optional<Body> fetchBody(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        return fetchStream(uri, credential).map(in -> new Body(in, -1L));
    }

    /**
     * Upload {@code body} to {@code uri} and return a status code — the HTTP status for {@link
     * HttpTransport}, or a 2xx-range value for object stores on success. The caller decides what
     * counts as success; a transport-level failure throws {@link IOException}.
     */
    int put(URI uri, byte[] body, String contentType, RepoCredential credential)
            throws IOException, InterruptedException;

    /**
     * As {@link #put}, the body read from {@code file}. The default reads the file whole for
     * transports without a streaming upload; {@link HttpTransport}, {@link FileTransport} and the
     * object stores override it so an upload never holds the file in memory.
     */
    default int putFile(URI uri, Path file, String contentType, RepoCredential credential)
            throws IOException, InterruptedException {
        return put(uri, Files.readAllBytes(file), contentType, credential);
    }
}
