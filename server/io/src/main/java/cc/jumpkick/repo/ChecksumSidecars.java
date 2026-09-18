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

/**
 * The checksum sidecars a Maven repository publishes beside an artifact, strongest first: {@code
 * .sha256}, then {@code .sha1}, then {@code .md5} as the last resort. The two SHA sidecars are read
 * at once, on the io pool, so a caller can start them beside the artifact download and pay one
 * round trip for the three files instead of three in a row; the {@code .md5} is asked for only when
 * both are absent. A sidecar whose body is not a digest of the expected length (an HTML error page
 * under HTTP 200) counts as absent. A body fetch that fails {@linkplain #cancel cancels} the two
 * reads, so a repository that stopped answering is not held to for the request timeout twice over.
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
    private final Future<Optional<String>> sha256;
    private final Future<Optional<String>> sha1;

    private ChecksumSidecars(RepoTransport transport, RepoCredential credential, URI artifact) {
        this.transport = transport;
        this.credential = credential;
        this.artifact = artifact;
        this.sha256 = JkThreads.io().submit(() -> read(Algorithm.SHA256));
        this.sha1 = JkThreads.io().submit(() -> read(Algorithm.SHA1));
    }

    /** Start reading the {@code .sha256} and {@code .sha1} beside {@code artifact}. */
    static ChecksumSidecars start(RepoTransport transport, RepoCredential credential, URI artifact) {
        return new ChecksumSidecars(transport, credential, artifact);
    }

    /** The strongest SHA digest published, or empty when the repository publishes neither. */
    Optional<Published> strongestSha() throws IOException, InterruptedException {
        Optional<String> strong = join(sha256);
        if (strong.isPresent()) return Optional.of(new Published(Algorithm.SHA256, strong.get()));
        return join(sha1).map(hex -> new Published(Algorithm.SHA1, hex));
    }

    /** Stop the two SHA reads: a leg still parked in its request is interrupted and ends at once. */
    void cancel() {
        sha256.cancel(true);
        sha1.cancel(true);
    }

    /** {@link #strongestSha()}, falling back to the {@code .md5} read now; empty when none is published. */
    Optional<Published> strongest() throws IOException, InterruptedException {
        Optional<Published> sha = strongestSha();
        if (sha.isPresent()) return sha;
        return read(Algorithm.MD5).map(hex -> new Published(Algorithm.MD5, hex));
    }

    private Optional<String> read(Algorithm algorithm) throws IOException, InterruptedException {
        Optional<byte[]> body = transport.fetch(URI.create(artifact + algorithm.suffix), credential);
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
