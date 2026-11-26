// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.release.ReleaseIndex;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.repo.ObjectStoreTransport;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.s3.AwsCredentialChain;
import cc.jumpkick.repo.s3.AwsCredentials;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.version.Versions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/**
 * A release in an object store: {@code {prefix}{version}/<file>} written create-only, and {@code
 * {prefix}latest.json} replaced by compare-and-swap. A file already there with the same bytes is
 * left alone, so a run that stopped halfway resumes; other bytes stop the run.
 */
public final class ReleaseBucket implements ReleaseStore {

    private static final int LATEST_ATTEMPTS = 5;

    private final ObjectStoreTransport store;
    private final ReleaseTarget.Bucket target;
    private final String version;

    public ReleaseBucket(ObjectStoreTransport store, ReleaseTarget.Bucket target, String version) {
        this.store = store;
        this.target = target;
        this.version = version;
    }

    /**
     * The bucket of {@code run}'s release. Endpoint and region come from a {@code [repositories]}
     * table whose url or publish-url covers the bucket, else from the AWS environment; the
     * credential from the AWS chain of the shell that ran {@code jk}.
     */
    public static ReleaseBucket open(ReleaseRun run) {
        ReleaseTarget.Bucket bucket = (ReleaseTarget.Bucket) run.target();
        URI base = bucket.base();
        ObjectStoreConfig declared = PublishPlans.objectStore(run.rootBuild(), base);
        ObjectStoreConfig cfg = declared == null ? ObjectStoreConfig.EMPTY : declared;
        if (!cfg.hasExplicitCredentials()) {
            Optional<AwsCredentials> creds = new AwsCredentialChain().resolve(cfg.region());
            String region = cfg.region() != null
                    ? cfg.region()
                    : creds.map(AwsCredentials::region).orElse(null);
            String endpoint = cfg.endpoint() != null ? cfg.endpoint() : BuildEnv.get("AWS_ENDPOINT_URL");
            cfg = new ObjectStoreConfig(
                    region,
                    endpoint,
                    creds.map(AwsCredentials::accessKeyId).orElse(null),
                    creds.map(AwsCredentials::secretAccessKey).orElse(null),
                    creds.map(AwsCredentials::sessionToken).orElse(null));
        }
        RepoTransport transport = RepoTransports.forUrl(base, new Http(), cfg);
        if (!(transport instanceof ObjectStoreTransport store)) {
            throw new IllegalStateException(base + " is not an object store");
        }
        return new ReleaseBucket(store, bucket, run.version());
    }

    /** {@code {prefix}{version}/{name}}. */
    public URI uri(String name) {
        return target.file(version, name);
    }

    @Override
    public String url(String name) {
        return uri(name).toString();
    }

    @Override
    public Put put(String name, Path file) throws IOException, InterruptedException {
        URI uri = uri(name);
        int status = store.putFile(uri, file, ReleaseStore.contentType(name), RepoCredential.ANONYMOUS);
        if (status >= 200 && status < 300) {
            SessionContext.current().io().remoteUp(Files.size(file));
            return Put.UPLOADED;
        }
        requireCreated(uri, status);
        try (InputStream local = Files.newInputStream(file)) {
            requireSameBytes(uri, local);
        }
        return Put.EXISTING;
    }

    @Override
    public Put put(String name, byte[] body) throws IOException, InterruptedException {
        URI uri = uri(name);
        int status = store.put(uri, body, ReleaseStore.contentType(name), RepoCredential.ANONYMOUS);
        if (status >= 200 && status < 300) {
            SessionContext.current().io().remoteUp(body.length);
            return Put.UPLOADED;
        }
        requireCreated(uri, status);
        requireSameBytes(uri, new ByteArrayInputStream(body));
        return Put.EXISTING;
    }

    @Override
    public boolean present(String name) throws IOException, InterruptedException {
        Optional<RepoTransport.Body> body = store.fetchBody(uri(name), RepoCredential.ANONYMOUS);
        if (body.isEmpty()) return false;
        body.get().in().close();
        return true;
    }

    /** The size and SHA-256 of {@code name}, read through once. */
    @Override
    public Optional<Held> held(String name) throws IOException, InterruptedException {
        Optional<InputStream> body = store.fetchStream(uri(name), RepoCredential.ANONYMOUS);
        if (body.isEmpty()) return Optional.empty();
        MessageDigest digest = Hashing.newSha256();
        long size = 0;
        try (InputStream in = body.get()) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
                size += n;
            }
        }
        return Optional.of(new Held(size, Hashing.hex(digest.digest())));
    }

    /** A bucket needs nothing before its first file. */
    @Override
    public void prepare() {}

    /** A bucket takes new files after {@code latest.json} names its version. */
    @Override
    public boolean published() {
        return false;
    }

    /** {@link #publishLatest}, said as a line. */
    @Override
    public String publish() throws IOException, InterruptedException {
        return ReleaseIndex.LATEST + " names " + publishLatest();
    }

    /**
     * Point {@code latest.json} at this release by compare-and-swap, unless it already names this
     * version or a later one, which it keeps. Returns the version it names afterwards.
     */
    public String publishLatest() throws IOException, InterruptedException {
        URI latest = target.latest();
        byte[] body = ReleaseIndex.latest(version);
        for (int attempt = 0; attempt < LATEST_ATTEMPTS; attempt++) {
            Optional<ObjectStoreTransport.Revision> previous = store.readRevision(latest, RepoCredential.ANONYMOUS);
            if (previous.isPresent()) {
                String named = ReleaseIndex.latestVersion(previous.get().body());
                if (named != null && Versions.compare(named, version) >= 0) return named;
            }
            int status = store.putRevision(latest, body, "application/json", RepoCredential.ANONYMOUS, previous);
            if (status >= 200 && status < 300) return version;
            if (status != 412 && status != 409 && !(status == 404 && previous.isPresent())) {
                throw new IOException("PUT " + latest + " returned " + status);
            }
        }
        throw new IOException(latest + " kept changing; re-run jk publish");
    }

    private static void requireCreated(URI uri, int status) throws IOException {
        if (status != 412 && status != 409) throw new IOException("PUT " + uri + " returned " + status);
    }

    private void requireSameBytes(URI uri, InputStream local) throws IOException, InterruptedException {
        try (InputStream held = store.fetchStream(uri, RepoCredential.ANONYMOUS)
                .orElseThrow(() -> new IOException(uri + ": missing after a write conflict"))) {
            byte[] expected = new byte[64 * 1024];
            byte[] actual = new byte[expected.length];
            int count;
            do {
                count = local.readNBytes(expected, 0, expected.length);
                if (held.readNBytes(actual, 0, actual.length) != count
                        || !Arrays.equals(expected, 0, count, actual, 0, count)) {
                    throw new IOException(uri + " is already there with other bytes; raise the version in jk.toml");
                }
            } while (count == expected.length);
        }
    }

    /** The bucket's manifest root, for a message. */
    @Override
    public String toString() {
        return target.version(version).toString();
    }
}
