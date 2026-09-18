// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.FetchTimings;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The network leg of a {@link MavenRepo} fetch: one URL streamed to a temp file in the
 * repository's store tree under the host's permit, hashed as it lands and checked against the
 * checksum the repository publishes beside it. The bytes travel through the JDK's 16 KiB copy
 * buffer, so a download in flight costs its connection and that buffer, never its payload.
 */
final class DownloadLeg {

    /** A verified download: the temp file it landed in, its SHA-256 and its size. */
    record Downloaded(Path path, String sha256, long size) {}

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final RepoCredential credential;
    /** The repository's tree under {@code <store>/repos/}, where the download temps land. */
    private final Path shard;

    private final boolean allowUnverified;

    /**
     * Artifacts this run whose bytes the repository's published checksum confirmed — downloads and
     * Maven-local adoptions alike.
     */
    private final AtomicInteger verifiedUpstream = new AtomicInteger();

    /** Artifact downloads this run pinned without a published checksum because the repository allows it. */
    private final AtomicInteger unverifiedAllowed = new AtomicInteger();

    /** One sentence per artifact this run could verify against an {@code .md5} sidecar alone. */
    private final Set<String> weakChecksumNotes = ConcurrentHashMap.newKeySet();

    DownloadLeg(
            String name,
            URI baseUrl,
            RepoTransport transport,
            RepoCredential credential,
            Path shard,
            boolean allowUnverified) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.shard = Objects.requireNonNull(shard, "shard");
        this.allowUnverified = allowUnverified;
    }

    /**
     * Download {@code uri} under its host's permit. {@code abort} is checked before the permit is
     * asked for and again once it is granted — a task that queued behind slow downloads must not
     * start a fetch for a lock that failed while it waited — and never mid-transfer, so a leg in
     * progress always completes cleanly.
     */
    Downloaded run(
            Coordinate coord,
            URI uri,
            String relativePath,
            boolean mirror,
            MavenRepo.Leg leg,
            @Nullable String expectedSha256,
            BooleanSupplier abort)
            throws IOException, InterruptedException {
        checkAbort(abort, coord);
        return rateLimited(uri, () -> {
            checkAbort(abort, coord);
            return downloadAndVerify(coord, uri, relativePath, mirror, leg, expectedSha256);
        });
    }

    /** Per-host concurrency cap around the network leg only; {@code file://} is not capped. */
    private static Downloaded rateLimited(URI uri, HostRateLimiter.ThrowingSupplier<Downloaded, IOException> work)
            throws IOException, InterruptedException {
        String host = uri.getHost();
        boolean limitHost = host != null && !host.isBlank() && !"file".equalsIgnoreCase(uri.getScheme());
        return limitHost ? HostRateLimiter.shared().run(host, work) : work.get();
    }

    static void checkAbort(BooleanSupplier abort, Coordinate coord) throws MavenRepo.FetchAbortedException {
        if (abort.getAsBoolean()) {
            throw new MavenRepo.FetchAbortedException(
                    "fetch aborted before starting " + coord + " (lock already failed)");
        }
    }

    private Downloaded downloadAndVerify(
            Coordinate coord,
            URI uri,
            String relativePath,
            boolean mirror,
            MavenRepo.Leg leg,
            @Nullable String expected)
            throws IOException, InterruptedException {
        long t0 = Clock.SYSTEM.nanos();
        Files.createDirectories(shard);
        Path tmp = Files.createTempFile(shard, ".put-", ".tmp");
        // The sidecars travel beside the body, so the check costs no round trip of its own.
        ChecksumSidecars sidecars = mirror ? ChecksumSidecars.start(transport, credential, uri) : null;
        MessageDigest digest = Hashing.newSha256();
        long size;
        try (InputStream in = transport
                        .fetchStream(uri, credential)
                        .orElseThrow(
                                () -> new MavenRepo.ArtifactNotFoundException("not found in " + name + ": " + uri));
                OutputStream out = new DigestOutputStream(Files.newOutputStream(tmp), digest)) {
            size = in.transferTo(out);
        } catch (IOException | InterruptedException e) {
            // The sidecars were only ever worth reading beside a body that arrived.
            if (sidecars != null) sidecars.cancel();
            Files.deleteIfExists(tmp);
            throw e;
        }
        Downloaded stored = new Downloaded(tmp, Hashing.hex(digest.digest()), size);
        long ms = (Clock.SYSTEM.nanos() - t0) / 1_000_000L;
        if (sidecars != null) {
            try {
                verifyUpstreamChecksum(coord, sidecars, relativePath, stored, leg, expected);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        // Successful VERIFIED fetch only — a download that fails its upstream checksum must not
        // train the host fetch-duration prior.
        if (ms > 0) {
            try {
                FetchTimings.record(ms);
            } catch (RuntimeException e) {
                // advisory
                Log.debug("downloadAndVerify: advisory", e);
            }
        }
        return stored;
    }

    /**
     * Check the download against the lock pin when there is one — first, so bytes the lock rejects
     * are discarded before anything places them — then against the checksum this repository
     * publishes beside it: {@code .sha256}, else {@code .sha1}, else {@code .md5}; a mismatch fails
     * closed, and an {@code .md5}-only match is accepted with a note ({@link #weakChecksumNotes}).
     * With no sidecar at all the bytes are accepted only when the pin vouches for them, when the
     * repository is on local disk, or under {@code allow-unverified = true}; otherwise the fetch is
     * refused, because a pin taken from unverified bytes would protect every later build with a
     * checksum of whatever arrived.
     */
    private void verifyUpstreamChecksum(
            Coordinate coord,
            ChecksumSidecars sidecars,
            String relativePath,
            Downloaded stored,
            MavenRepo.Leg leg,
            @Nullable String expectedSha256)
            throws IOException, InterruptedException {
        String actualSha256 = stored.sha256();
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new MavenRepo.ChecksumMismatchException("checksum mismatch for " + coord + " from " + name + " ("
                    + relativePath + "): jk-lock.toml pins sha256 " + expectedSha256 + " but got " + actualSha256);
        }
        Optional<ChecksumSidecars.Published> published = sidecars.strongest();
        if (published.isPresent()) {
            ChecksumSidecars.Algorithm algorithm = published.get().algorithm();
            String expected = published.get().hex();
            String actual = algorithm == ChecksumSidecars.Algorithm.SHA256
                    ? actualSha256
                    : Hashing.fileHex(algorithm.jca, stored.path());
            if (!expected.equalsIgnoreCase(actual)) {
                throw new MavenRepo.ChecksumMismatchException("upstream checksum mismatch for " + coord + " from "
                        + name + " (" + relativePath + "): expected " + algorithm.label + " " + expected + " but got "
                        + actual);
            }
            if (algorithm == ChecksumSidecars.Algorithm.MD5) {
                weakChecksumNotes.add(coord + " from " + name + " is verified against its .md5 sidecar alone: the"
                        + " repository publishes no .sha256 or .sha1 for it, and md5 is the weakest digest a"
                        + " repository publishes; the lock pins its bytes by sha256 from here on");
            }
            if (leg == MavenRepo.Leg.ARTIFACT) verifiedUpstream.incrementAndGet();
            return;
        }
        // Post-lock: the pin is the authority, and it was taken when the sidecar was checked; it
        // matched above, so a sidecar-less repository needs no further vouching.
        if (expectedSha256 != null) return;
        if ("file".equalsIgnoreCase(baseUrl.getScheme())) return;
        if (!allowUnverified) {
            throw new MavenRepo.MissingChecksumException("no upstream checksum for " + coord + " from " + name + " ("
                    + relativePath + "): the repository publishes no .sha256, .sha1 or .md5 sidecar, so the"
                    + " bytes cannot be verified before they are pinned. Set allow-unverified = true on"
                    + " [repositories." + name + "] to pin them anyway.");
        }
        if (leg == MavenRepo.Leg.ARTIFACT) unverifiedAllowed.incrementAndGet();
    }

    /** Count an artifact the repository's checksum vouched for without a download (a Maven-local adoption). */
    void countVerified() {
        verifiedUpstream.incrementAndGet();
    }

    /** Artifacts this run confirmed by the checksum this repository publishes (downloads and adoptions). */
    int verifiedUpstream() {
        return verifiedUpstream.get();
    }

    /** Artifact downloads this run pinned with no published checksum, under {@code allow-unverified}. */
    int unverifiedAllowed() {
        return unverifiedAllowed.get();
    }

    /** One sentence per artifact this run verified against an {@code .md5} sidecar alone, sorted. */
    List<String> weakChecksumNotes() {
        List<String> out = new ArrayList<>(weakChecksumNotes);
        out.sort(null);
        return List.copyOf(out);
    }
}
