// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;

/**
 * The checksum sidecars a Maven repository publishes beside an artifact, strongest first: {@code
 * .sha256}, then {@code .sha1}, then {@code .md5} as the last resort. The two SHA sidecars are read
 * at once, on the io pool, so a caller can start them beside the artifact download and pay one
 * round trip for the three files instead of three in a row; the {@code .md5} is asked for only when
 * both are absent. A sidecar whose body is not a digest of the expected length (an HTML error page
 * under HTTP 200) counts as absent. A body fetch that fails {@linkplain #cancel cancels} the two
 * reads, so a repository that stopped answering is not held to for the request timeout twice over.
 *
 * <p>Every read runs under the host's permit ({@link DownloadLeg#rateLimited}), so the three files
 * of one artifact count against the host cap like any other request. The limiter is a plain
 * semaphore: a caller must not hold the host's permit while it joins a read.
 */
final class ChecksumSidecars {

    /** A digest algorithm a repository publishes a sidecar for. */
    enum Algorithm {
        SHA256(".sha256", 64, "SHA-256", "sha256"),
        SHA1(".sha1", 40, "SHA-1", "sha1"),
        MD5(".md5", 32, "MD5", "md5");

        final String suffix;
        final int hexLength;
        /** The {@link java.security.MessageDigest} name. */
        final String jca;
        /** The word a diagnostic uses. */
        final String label;

        Algorithm(String suffix, int hexLength, String jca, String label) {
            this.suffix = suffix;
            this.hexLength = hexLength;
            this.jca = jca;
            this.label = label;
        }
    }

    /** One published digest: which algorithm, and its lower-case hex. */
    record Published(Algorithm algorithm, String hex) {}

    private final RepoTransport transport;
    private final RepoCredential credential;
    private final URI artifact;
    /** Null in {@linkplain #startSha1First sha1-first} mode, where it is read only when no {@code .sha1} is. */
    private final @Nullable Future<Optional<String>> sha256;

    private final Future<Optional<String>> sha1;

    private ChecksumSidecars(RepoTransport transport, RepoCredential credential, URI artifact, boolean sha1First) {
        this.transport = transport;
        this.credential = credential;
        this.artifact = artifact;
        this.sha256 = sha1First ? null : JkThreads.io().submit(() -> read(Algorithm.SHA256));
        this.sha1 = JkThreads.io().submit(() -> read(Algorithm.SHA1));
    }

    /** Start reading the {@code .sha256} and {@code .sha1} beside {@code artifact}. */
    static ChecksumSidecars start(RepoTransport transport, RepoCredential credential, URI artifact) {
        return new ChecksumSidecars(transport, credential, artifact, false);
    }

    /**
     * Start reading the {@code .sha1} alone beside {@code artifact}: one request, for a repository
     * that publishes one for every file. Maven Central requires {@code .sha1} and {@code .md5} of
     * every upload and leaves {@code .sha256} optional, so asking for both there spends a request on
     * a file that is often absent. SHA-1 is strong enough for what the read is for: bytes that match
     * a digest someone else published need a second preimage, and the known SHA-1 attacks are
     * collisions, which need the attacker to make both files. The {@code .sha256}, then the {@code
     * .md5}, are asked for only when no {@code .sha1} is published.
     */
    static ChecksumSidecars startSha1First(RepoTransport transport, RepoCredential credential, URI artifact) {
        return new ChecksumSidecars(transport, credential, artifact, true);
    }

    /** The strongest SHA digest published, or empty when the repository publishes neither. */
    Optional<Published> strongestSha() throws IOException, InterruptedException {
        try {
            if (sha256 == null) {
                Optional<String> weak = join(sha1);
                if (weak.isPresent()) return Optional.of(new Published(Algorithm.SHA1, weak.get()));
                return read(Algorithm.SHA256).map(hex -> new Published(Algorithm.SHA256, hex));
            }
            Optional<String> strong = join(sha256);
            if (strong.isPresent()) return Optional.of(new Published(Algorithm.SHA256, strong.get()));
            return join(sha1).map(hex -> new Published(Algorithm.SHA1, hex));
        } finally {
            cancel();
        }
    }

    /** Stop the two SHA reads: a leg still parked in its request is interrupted and ends at once. */
    void cancel() {
        if (sha256 != null) sha256.cancel(true);
        sha1.cancel(true);
    }

    /** {@link #strongestSha()}, falling back to the {@code .md5} read now; empty when none is published. */
    Optional<Published> strongest() throws IOException, InterruptedException {
        Optional<Published> sha = strongestSha();
        if (sha.isPresent()) return sha;
        return read(Algorithm.MD5).map(hex -> new Published(Algorithm.MD5, hex));
    }

    private Optional<String> read(Algorithm algorithm) throws IOException, InterruptedException {
        URI sidecar = URI.create(artifact + algorithm.suffix);
        Optional<byte[]> body = DownloadLeg.rateLimited(sidecar, () -> transport.fetch(sidecar, credential));
        if (body.isEmpty()) return Optional.empty();
        return Hashing.checksumFromSidecar(new String(body.get(), StandardCharsets.UTF_8), algorithm.hexLength);
    }

    private static Optional<String> join(Future<Optional<String>> read) throws IOException, InterruptedException {
        try {
            return read.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
        }
    }
}
