// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ObjectStoreTransport.Revision;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * Read, edit and write one {@code maven-metadata.xml} with its four checksum files. On an object
 * store the write is a compare-and-swap on the revision read, retried when another writer moved
 * it; elsewhere it is a read then a put, as a repository manager offers nothing better.
 */
public final class MetadataWrites {

    /** Attempts after the first before a write that keeps losing gives up. */
    public static final int RETRIES = 5;

    /** The new document for what the repository holds now, or {@code null} to leave it as it is. */
    @FunctionalInterface
    public interface Edit {
        byte @Nullable [] apply(Optional<byte[]> current) throws IOException;
    }

    /** Takes each write's status; throws to fail the write. */
    @FunctionalInterface
    public interface Recorder {
        void record(String relPath, URI uri, int status, long size) throws IOException;
    }

    private final URI repoBase;
    private final RepoTransport transport;
    private final RepoCredential credential;
    private final Recorder recorder;

    /** {@code repoBase} ends with {@code /}; every path here is relative to it. */
    public MetadataWrites(URI repoBase, RepoTransport transport, RepoCredential credential, Recorder recorder) {
        this.repoBase = repoBase;
        this.transport = transport;
        this.credential = credential;
        this.recorder = recorder;
    }

    /**
     * Apply {@code edit} to the document at {@code relPath} and write the result with its
     * checksums; true when it wrote. On an object store the edit runs again on each fresh read.
     */
    public boolean update(String relPath, Edit edit) throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        if (transport instanceof ObjectStoreTransport store) {
            for (int attempt = 0; attempt <= RETRIES; attempt++) {
                Optional<Revision> previous = store.readRevision(uri, credential);
                byte[] body = edit.apply(previous.map(Revision::body));
                if (body == null) return false;
                if (putRevision(store, relPath, body, previous)) {
                    putChecksums(store, relPath);
                    return true;
                }
                if (attempt < RETRIES) backoff(attempt);
            }
            throw concurrent(relPath);
        }
        Optional<byte[]> current;
        try {
            current = transport.fetch(uri, credential);
        } catch (IOException unreadable) {
            throw new IOException(
                    "could not read " + uri + ": " + unreadable.getMessage() + "; left it unchanged", unreadable);
        }
        byte[] body = edit.apply(current);
        if (body == null) return false;
        put(relPath, body, "application/xml");
        for (String[] sidecar : sidecars(body)) {
            put(relPath + sidecar[0], sidecar[1].getBytes(StandardCharsets.US_ASCII), "text/plain");
        }
        return true;
    }

    /** Replace the revision {@code previous} at {@code relPath}; false when another writer moved it first. */
    public boolean putRevision(ObjectStoreTransport store, String relPath, byte[] body, Optional<Revision> previous)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        int status = store.putRevision(uri, body, "application/xml", credential, previous);
        if (revisionChanged(status, previous)) return false;
        recorder.record(relPath, uri, status, body.length);
        return true;
    }

    /** Write the checksums of the document now at {@code relPath}, repaired from a fresh read if another writer moves it mid-write. */
    public void putChecksums(ObjectStoreTransport store, String relPath) throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            Revision current = revision(store, uri);
            boolean stable = true;
            for (String[] sidecar : sidecars(current.body())) {
                String sidePath = relPath + sidecar[0];
                URI sideUri = repoBase.resolve(sidePath);
                byte[] body = sidecar[1].getBytes(StandardCharsets.US_ASCII);
                Optional<Revision> previous = store.readRevision(sideUri, credential);
                if (previous.isPresent() && Arrays.equals(previous.get().body(), body)) continue;
                if (!current.validator().equals(revision(store, uri).validator())) {
                    stable = false;
                    break;
                }
                int status = store.putRevision(sideUri, body, "text/plain", credential, previous);
                if (revisionChanged(status, previous)) {
                    stable = false;
                    break;
                }
                recorder.record(sidePath, sideUri, status, body.length);
            }
            if (stable && current.validator().equals(revision(store, uri).validator())) return;
            if (attempt < RETRIES) backoff(attempt);
        }
        throw concurrent(relPath);
    }

    /** The failure for a document other writers kept moving through every retry. */
    public IOException concurrent(String relPath) {
        return new IOException(repoBase.resolve(relPath) + ": concurrent updates; try again");
    }

    /** A short randomised pause before retry {@code attempt + 1}. */
    public static void backoff(int attempt) throws InterruptedException {
        long delay = 10L << attempt;
        Thread.sleep(ThreadLocalRandom.current().nextLong(delay, 2 * delay));
    }

    private void put(String relPath, byte[] body, String contentType) throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        recorder.record(relPath, uri, transport.put(uri, body, contentType, credential), body.length);
    }

    private Revision revision(ObjectStoreTransport store, URI uri) throws IOException, InterruptedException {
        return store.readRevision(uri, credential).orElseThrow(() -> new IOException(uri + ": missing metadata"));
    }

    private static boolean revisionChanged(int status, Optional<Revision> previous) {
        return status == 412 || status == 409 || (status == 404 && previous.isPresent());
    }

    /** The four checksum files of {@code body}: suffix, then hex digest. */
    private static String[][] sidecars(byte[] body) {
        return new String[][] {
            {".md5", Hashing.hashHex("MD5", body)},
            {".sha1", Hashing.hashHex("SHA-1", body)},
            {".sha256", Hashing.sha256Hex(body)},
            {".sha512", Hashing.hashHex("SHA-512", body)}
        };
    }
}
