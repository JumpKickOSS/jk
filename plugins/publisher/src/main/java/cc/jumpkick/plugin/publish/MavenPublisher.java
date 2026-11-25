// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.pom.PomXml;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.repo.ObjectStoreTransport;
import cc.jumpkick.repo.ObjectStoreTransport.Revision;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.sigstore.Dsse;
import cc.jumpkick.sigstore.SigstoreSigner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Publishes Maven artifacts, metadata, checksums and optional signatures.
 */
public final class MavenPublisher {

    private final URI repoBase;
    private final RepoTransport transport;
    private final RepoCredential credential;

    /** Authenticate with an explicit credential (anonymous → no auth header). */
    public MavenPublisher(URI repoBase, RepoCredential credential) {
        this(repoBase, credential, ObjectStoreConfig.EMPTY, new Http());
    }

    /** Convenience for HTTP Basic auth; a blank username means anonymous. */
    public MavenPublisher(URI repoBase, @Nullable String username, @Nullable String password) {
        this(
                repoBase,
                (username == null || username.isEmpty())
                        ? RepoCredential.ANONYMOUS
                        : new RepoCredential.Basic(username, password == null ? "" : password));
    }

    /**
     * As {@link #MavenPublisher(URI, RepoCredential)} but with per-target object-store config
     * (region/endpoint/keys) applied to {@code s3://} / {@code gs://} destinations; ignored for
     * {@code http(s)}. A factory rather than a constructor so {@code (URI, null, null)} stays
     * unambiguous against the Basic-auth constructor.
     */
    public static MavenPublisher withObjectStore(
            URI repoBase, RepoCredential credential, ObjectStoreConfig objectStore) {
        return withObjectStore(repoBase, credential, objectStore, new Http());
    }

    /** As {@link #withObjectStore(URI, RepoCredential, ObjectStoreConfig)}, over {@code http}. */
    static MavenPublisher withObjectStore(
            URI repoBase, RepoCredential credential, ObjectStoreConfig objectStore, Http http) {
        return new MavenPublisher(repoBase, credential, objectStore, http);
    }

    private MavenPublisher(URI repoBase, RepoCredential credential, ObjectStoreConfig objectStore, Http http) {
        this(repoBase, credential, RepoTransports.forUrl(repoBase, http, objectStore));
    }

    /** Over {@code transport} itself: a test hands in one that records what each upload carried. */
    static MavenPublisher over(URI repoBase, RepoCredential credential, RepoTransport transport) {
        return new MavenPublisher(repoBase, credential, transport);
    }

    private MavenPublisher(URI repoBase, RepoCredential credential, RepoTransport transport) {
        this.repoBase = normalize(Objects.requireNonNull(repoBase, "repoBase"));
        this.credential = Objects.requireNonNull(credential, "credential");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * One file of the publication, named by what follows {@code name-version}: {@code .<extension>}
     * or {@code -<classifier>.<extension>}. The extension runs from the first dot, so {@code
     * .intoto.json} is the extension {@code intoto.json}, not {@code json}. Its bytes stay in {@code
     * file}: checksums, signatures and the upload each stream it, so a large jar never sits in memory.
     * An {@code envelope} is a {@link Dsse} envelope, whose signature differs from one signing to the
     * next: one the repository holds over the same payload is kept, as a held {@code .asc} is. A
     * {@code bundle} is the Sigstore bundle made with the file, uploaded as its {@code .sigstore} in
     * place of one signed over the file.
     */
    public record Artifact(
            String filenameSuffix,
            Path file,
            boolean envelope,
            @Nullable Path bundle) {
        public Artifact(String filenameSuffix, Path file) {
            this(filenameSuffix, file, false, null);
        }

        public Artifact {
            Objects.requireNonNull(filenameSuffix, "filenameSuffix");
            Objects.requireNonNull(file, "file");
            int dot = filenameSuffix.indexOf('.', 1);
            boolean named = filenameSuffix.startsWith(".")
                    ? filenameSuffix.length() > 1
                    : filenameSuffix.startsWith("-") && dot > 1 && dot < filenameSuffix.length() - 1;
            if (!named) {
                throw new IllegalArgumentException(
                        "filenameSuffix must be .<extension> or -<classifier>.<extension>, got: " + filenameSuffix);
            }
        }

        /** The classifier, or empty for the main artifact of its extension. */
        String classifier() {
            return filenameSuffix.startsWith("-") ? filenameSuffix.substring(1, filenameSuffix.indexOf('.')) : "";
        }

        /** Everything after the first dot. */
        String extension() {
            return filenameSuffix.substring(filenameSuffix.indexOf('.') + 1);
        }
    }

    /**
     * Per-path upload status plus the total payload {@code bytes} PUT (bodies only, no framing).
     * Every recorded status is 2xx: {@link #put} throws on anything else, so a {@code Result} only
     * exists for a publish that fully succeeded.
     */
    public record Result(Map<String, Integer> statusByPath, long bytes, Set<String> alreadyThere) {
        public Result {
            statusByPath = Map.copyOf(statusByPath);
            alreadyThere = Set.copyOf(alreadyThere);
        }
    }

    /**
     * Upload {@code artifacts} for {@code project} with no signing — every artifact gets its four
     * checksum files but no {@code .asc} or {@code .sigstore} sidecar. See {@link
     * #publish(Project, Iterable, SigningOptions)} for the signed variant.
     */
    public Result publish(Project project, Iterable<Artifact> artifacts) throws IOException, InterruptedException {
        return publish(project, artifacts, SigningOptions.none());
    }

    /**
     * Upload {@code artifacts} for {@code project}. Per artifact: body + four checksums; optional
     * {@code .asc} / {@code .sigstore} (each with their own checksums).
     */
    public Result publish(Project project, Iterable<Artifact> artifacts, SigningOptions signing)
            throws IOException, InterruptedException {
        if (signing == null) signing = SigningOptions.none();
        if (project.version().endsWith(SNAPSHOT)) return publishSnapshot(project, artifacts, signing);
        Map<String, Integer> results = new LinkedHashMap<>();
        Set<String> alreadyThere = new LinkedHashSet<>();
        long[] bytes = {0};
        String groupPath = project.group().replace('.', '/');
        String prefix = groupPath + "/" + project.name() + "/" + project.version() + "/";
        String stem = project.name() + "-" + project.version();

        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
            boolean kept = a.envelope() && keepHeldEnvelope(relPath, a);
            Checksums.Set sums = Checksums.of(a.file());
            boolean there;
            if (!(transport instanceof ObjectStoreTransport)
                    && (a.envelope() ? kept : alreadyPublished(relPath, sums.sha1()))) {
                there = true;
                putMissingChecksums(relPath, sums, results, bytes);
            } else {
                there = putFileWithChecksums(relPath, a.file(), sums, contentType(a.filenameSuffix()), results, bytes);
            }
            if (there) alreadyThere.add(relPath);
            if (signing.gpg() != null) {
                GpgSigner gpg = signing.gpg();
                putSignature(relPath + ".asc", there, () -> gpg.signArmored(a.file()), results, bytes);
            }
            if (a.bundle() != null) {
                Path bundle = a.bundle();
                putSignature(relPath + ".sigstore", there, () -> Files.readAllBytes(bundle), results, bytes);
            } else if (signing.sigstore() != null) {
                SigstoreSigner sigstore = signing.sigstore();
                putSignature(relPath + ".sigstore", there, () -> sigstore.signBundle(a.file()), results, bytes);
            }
        }
        publishMetadata(project, groupPath, results, bytes);
        return new Result(results, bytes[0], alreadyThere);
    }

    private static final int METADATA_RETRIES = 5;

    private static final String SNAPSHOT = "-SNAPSHOT";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss");

    private static final Pattern BUILD_NUMBER = Pattern.compile("<buildNumber>\\s*(\\d+)\\s*</buildNumber>");

    private Clock clock = Clock.systemUTC();

    /** The clock that stamps a snapshot's files; a test pins it. */
    MavenPublisher withClock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * A snapshot goes up in Maven's unique form: {@code name-1.0.1-20261025.201500-3.jar} beside a
     * version-level {@code maven-metadata.xml} naming the newest build, so a repository that refuses
     * redeploys takes every publish and a consumer resolves the latest one.
     */
    private Result publishSnapshot(Project project, Iterable<Artifact> artifacts, SigningOptions signing)
            throws IOException, InterruptedException {
        Map<String, Integer> results = new LinkedHashMap<>();
        long[] bytes = {0};
        String groupPath = project.group().replace('.', '/');
        String prefix = groupPath + "/" + project.name() + "/" + project.version() + "/";
        String metadataPath = prefix + "maven-metadata.xml";
        if (transport instanceof ObjectStoreTransport store) {
            List<Artifact> files = new ArrayList<>();
            artifacts.forEach(files::add);
            int minimumBuild = 1;
            for (int attempt = 0; attempt <= METADATA_RETRIES; attempt++) {
                Optional<Revision> previous = store.readRevision(repoBase.resolve(metadataPath), credential);
                SnapshotMetadata snapshot = SnapshotMetadata.read(previous, repoBase.resolve(metadataPath));
                int build = Math.max(minimumBuild, snapshot.build() + 1);
                try {
                    byte[] body =
                            uploadSnapshot(project, prefix, files, signing, build, snapshot.entries(), results, bytes);
                    if (putMetadataRevision(store, metadataPath, body, previous, results, bytes)) {
                        publishMetadataChecksums(store, metadataPath, results, bytes);
                        publishMetadata(project, groupPath, results, bytes);
                        return new Result(results, bytes[0], Set.of());
                    }
                } catch (PublishedBytesConflict conflict) {
                    minimumBuild = build + 1;
                }
                if (attempt < METADATA_RETRIES) backoff(attempt);
            }
            throw concurrentMetadata(metadataPath);
        }

        int build = 1;
        try {
            Optional<byte[]> previous = transport.fetch(repoBase.resolve(metadataPath), credential);
            if (previous.isPresent()) {
                Matcher m = BUILD_NUMBER.matcher(new String(previous.get(), StandardCharsets.UTF_8));
                if (m.find()) build = Integer.parseInt(m.group(1)) + 1;
            }
        } catch (IOException unreadable) {
            throw new IOException(
                    "could not read " + repoBase.resolve(metadataPath) + " to number the " + project.version()
                            + " build: " + unreadable.getMessage(),
                    unreadable);
        }
        byte[] body = uploadSnapshot(project, prefix, artifacts, signing, build, new LinkedHashMap<>(), results, bytes);
        putWithChecksums(metadataPath, body, "application/xml", results, bytes);
        publishMetadata(project, groupPath, results, bytes);
        return new Result(results, bytes[0], Set.of());
    }

    private byte[] uploadSnapshot(
            Project project,
            String prefix,
            Iterable<Artifact> artifacts,
            SigningOptions signing,
            int build,
            Map<SnapshotMetadata.Key, SnapshotMetadata.Entry> entries,
            Map<String, Integer> results,
            long[] bytes)
            throws IOException, InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        String timestamp = STAMP.format(now);
        String stamped = project.version().substring(0, project.version().length() - SNAPSHOT.length()) + "-"
                + timestamp + "-" + build;
        String stem = project.name() + "-" + stamped;
        String updated = timestamp.replace(".", "");
        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
            putFileWithChecksums(
                    relPath, a.file(), Checksums.of(a.file()), contentType(a.filenameSuffix()), results, bytes);
            if (signing.gpg() != null) {
                putWithChecksums(
                        relPath + ".asc", signing.gpg().signArmored(a.file()), contentType(".asc"), results, bytes);
            }
            Path bundle = a.bundle();
            SigstoreSigner sigstore = signing.sigstore();
            byte[] sigstoreBundle = bundle != null
                    ? Files.readAllBytes(bundle)
                    : sigstore != null ? sigstore.signBundle(a.file()) : null;
            if (sigstoreBundle != null) {
                putWithChecksums(relPath + ".sigstore", sigstoreBundle, contentType(".sigstore"), results, bytes);
            }
            entries.put(
                    new SnapshotMetadata.Key(a.classifier(), a.extension()),
                    new SnapshotMetadata.Entry(stamped, updated));
        }
        String metadata = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata modelVersion=\"1.1.0\">\n  <groupId>"
                + PomXml.escape(project.group()) + "</groupId>\n  <artifactId>" + PomXml.escape(project.name())
                + "</artifactId>\n  <version>" + PomXml.escape(project.version())
                + "</version>\n  <versioning>\n    <snapshot>\n      <timestamp>" + timestamp
                + "</timestamp>\n      <buildNumber>" + build + "</buildNumber>\n    </snapshot>\n"
                + "    <lastUpdated>" + updated + "</lastUpdated>\n    <snapshotVersions>\n"
                + SnapshotMetadata.renderEntries(entries) + "    </snapshotVersions>\n  </versioning>\n</metadata>\n";
        return metadata.getBytes(StandardCharsets.UTF_8);
    }

    /** Check a Maven repo's SHA-1 sidecar or stream the artifact when it has none. */
    private boolean alreadyPublished(String relPath, String local) throws IOException, InterruptedException {
        String remote;
        try {
            Optional<byte[]> sidecar = transport.fetch(repoBase.resolve(relPath + ".sha1"), credential);
            if (sidecar.isPresent()) {
                remote = new String(sidecar.get(), StandardCharsets.US_ASCII)
                        .strip()
                        .split("\\s+")[0]
                        .toLowerCase(Locale.ROOT);
            } else {
                Optional<InputStream> raw = transport.fetchStream(repoBase.resolve(relPath), credential);
                if (raw.isEmpty()) return false;
                remote = Checksums.sha1Hex(raw.get());
            }
        } catch (IOException unreadable) {
            return false;
        }
        if (remote.equals(local)) return true;
        throw new IOException(repoBase.resolve(relPath)
                + " is already published with other bytes; raise the version in jk.toml"
                + "\n  repository sha1 " + remote + ", this build " + local);
    }

    private void publishMetadata(Project project, String groupPath, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        String relPath = groupPath + "/" + project.name() + "/maven-metadata.xml";
        URI uri = repoBase.resolve(relPath);
        if (transport instanceof ObjectStoreTransport store) {
            for (int attempt = 0; attempt <= METADATA_RETRIES; attempt++) {
                Optional<Revision> previous = store.readRevision(uri, credential);
                byte[] body = mergeVersions(project, uri, previous.map(Revision::body));
                if (putMetadataRevision(store, relPath, body, previous, results, bytes)) {
                    publishMetadataChecksums(store, relPath, results, bytes);
                    return;
                }
                if (attempt < METADATA_RETRIES) backoff(attempt);
            }
            throw concurrentMetadata(relPath);
        }

        Optional<byte[]> previous;
        try {
            previous = transport.fetch(uri, credential);
        } catch (IOException e) {
            throw metadataReadFailure(project, uri, e);
        }
        putWithChecksums(relPath, mergeVersions(project, uri, previous), "application/xml", results, bytes);
    }

    private static byte[] mergeVersions(Project project, URI uri, Optional<byte[]> previous) throws IOException {
        try {
            return previous.map(body -> MavenMetadata.parse(body, project.group(), project.name()))
                    .orElseGet(() -> MavenMetadata.empty(project.group(), project.name()))
                    .withVersion(project.version())
                    .render();
        } catch (IllegalArgumentException e) {
            throw metadataReadFailure(project, uri, e);
        }
    }

    private static IOException metadataReadFailure(Project project, URI uri, Exception cause) {
        return new IOException(
                "could not read " + uri + ": " + cause.getMessage() + "\n  left it unchanged; re-run publish to add "
                        + project.version() + " to the version list",
                cause);
    }

    private boolean putMetadataRevision(
            ObjectStoreTransport store,
            String path,
            byte[] body,
            Optional<Revision> previous,
            Map<String, Integer> results,
            long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(path);
        int status = store.putRevision(uri, body, "application/xml", credential, previous);
        if (revisionChanged(status, previous)) return false;
        record(path, uri, status, body.length, results, bytes);
        return true;
    }

    private static boolean revisionChanged(int status, Optional<Revision> previous) {
        return status == 412 || status == 409 || (status == 404 && previous.isPresent());
    }

    /** Repair checksums from the current metadata if another publisher advances it mid-write. */
    private void publishMetadataChecksums(
            ObjectStoreTransport store, String path, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(path);
        for (int attempt = 0; attempt <= METADATA_RETRIES; attempt++) {
            Revision current = metadataRevision(store, uri);
            boolean stable = true;
            for (String[] sidecar : sidecars(Checksums.of(current.body()))) {
                String sidePath = path + sidecar[0];
                URI sideUri = repoBase.resolve(sidePath);
                byte[] body = sidecar[1].getBytes(StandardCharsets.US_ASCII);
                Optional<Revision> previous = store.readRevision(sideUri, credential);
                if (previous.isPresent() && Arrays.equals(previous.get().body(), body)) continue;
                if (!current.validator().equals(metadataRevision(store, uri).validator())) {
                    stable = false;
                    break;
                }
                int status = store.putRevision(sideUri, body, "text/plain", credential, previous);
                if (revisionChanged(status, previous)) {
                    stable = false;
                    break;
                }
                record(sidePath, sideUri, status, body.length, results, bytes);
            }
            if (stable
                    && current.validator().equals(metadataRevision(store, uri).validator())) return;
            if (attempt < METADATA_RETRIES) backoff(attempt);
        }
        throw concurrentMetadata(path);
    }

    private Revision metadataRevision(ObjectStoreTransport store, URI uri) throws IOException, InterruptedException {
        return store.readRevision(uri, credential).orElseThrow(() -> new IOException(uri + ": missing metadata"));
    }

    private IOException concurrentMetadata(String path) {
        return new IOException(repoBase.resolve(path) + ": concurrent updates; re-run publish");
    }

    private static void backoff(int attempt) throws InterruptedException {
        long delay = 10L << attempt;
        Thread.sleep(ThreadLocalRandom.current().nextLong(delay, 2 * delay));
    }

    /**
     * Whether the repository already holds the envelope at {@code relPath} over the payload of the
     * one in {@code a}'s file; when it does, its bytes replace the file's, so the sidecars and the
     * signatures beside it are of the copy that is published. An envelope whose bundle was made with
     * it is kept only beside its held {@code .sigstore}: no other bundle verifies its signature, so
     * without one the fresh envelope goes up over it with its own bundle.
     *
     * @throws IOException when the repository holds an envelope over another payload
     */
    private boolean keepHeldEnvelope(String relPath, Artifact a) throws IOException, InterruptedException {
        Optional<byte[]> held = readable(relPath);
        if (held.isEmpty()) return false;
        if (!Dsse.samePayload(held.get(), Files.readAllBytes(a.file()))) {
            throw new IOException(repoBase.resolve(relPath)
                    + " is already published over another statement; raise the version in jk.toml");
        }
        if (a.bundle() != null && readable(relPath + ".sigstore").isEmpty()) return false;
        Files.write(a.file(), held.get());
        return true;
    }

    /** Signs on demand: a signature already published beside a published body is not re-signed. */
    @FunctionalInterface
    private interface Signer {
        byte[] sign() throws IOException, InterruptedException;
    }

    /**
     * The signature at {@code relPath} with its checksums. Beside a body that was already there,
     * a signature the repository holds is kept (a fresh one would differ in its timestamp) and only
     * its missing checksums go up.
     */
    private void putSignature(
            String relPath, boolean bodyThere, Signer signer, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        Optional<byte[]> held = bodyThere ? readable(relPath) : Optional.empty();
        if (held.isPresent()) {
            Checksums.Set sums = Checksums.of(held.get());
            if (transport instanceof ObjectStoreTransport) {
                putChecksums(relPath, sums, results, bytes);
            } else {
                putMissingChecksums(relPath, sums, results, bytes);
            }
        } else {
            putWithChecksums(
                    relPath, signer.sign(), contentType(relPath.substring(relPath.lastIndexOf('.'))), results, bytes);
        }
    }

    /** Each of {@code relPath}'s four checksum files the repository does not hold yet. */
    private void putMissingChecksums(String relPath, Checksums.Set sums, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        for (String[] f : sidecars(sums)) {
            if (readable(relPath + f[0]).isPresent()) continue;
            put(relPath + f[0], f[1].getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
        }
    }

    /** The four checksum sidecars of one file: suffix, then hex digest. */
    private static String[][] sidecars(Checksums.Set sums) {
        return new String[][] {
            {".md5", sums.md5()}, {".sha1", sums.sha1()}, {".sha256", sums.sha256()}, {".sha512", sums.sha512()}
        };
    }

    /** The file at {@code relPath}, or empty when it is absent or the repository will not say. */
    private Optional<byte[]> readable(String relPath) throws InterruptedException {
        try {
            return transport.fetch(repoBase.resolve(relPath), credential);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private void putWithChecksums(
            String relPath, byte[] body, String contentType, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        put(relPath, body, contentType, results, bytes);
        putChecksums(relPath, Checksums.of(body), results, bytes);
    }

    /** As {@link #putWithChecksums}, the body streamed from {@code file} whose digests are {@code sums}. */
    private boolean putFileWithChecksums(
            String relPath,
            Path file,
            Checksums.Set sums,
            String contentType,
            Map<String, Integer> results,
            long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        int status = transport.putFile(uri, file, contentType, credential);
        boolean there = status == 412 && transport instanceof ObjectStoreTransport;
        if (there) {
            try (InputStream local = Files.newInputStream(file)) {
                requireSameBytes(uri, local);
            }
        } else {
            record(relPath, uri, status, Files.size(file), results, bytes);
        }
        putChecksums(relPath, sums, results, bytes);
        return there;
    }

    private void putChecksums(String relPath, Checksums.Set sums, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        for (String[] f : sidecars(sums)) {
            put(relPath + f[0], f[1].getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
        }
    }

    private void put(String relPath, byte[] body, String contentType, Map<String, Integer> out, long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        int status = transport.put(uri, body, contentType, credential);
        if (status == 412 && transport instanceof ObjectStoreTransport) {
            try (InputStream local = new ByteArrayInputStream(body)) {
                requireSameBytes(uri, local);
            }
        } else {
            record(relPath, uri, status, body.length, out, bytes);
        }
    }

    private void requireSameBytes(URI uri, InputStream local) throws IOException, InterruptedException {
        try (InputStream held = transport
                .fetchStream(uri, credential)
                .orElseThrow(() -> new IOException(uri + ": missing after write conflict"))) {
            byte[] expected = new byte[64 * 1024];
            byte[] actual = new byte[expected.length];
            int count;
            do {
                count = local.readNBytes(expected, 0, expected.length);
                if (held.readNBytes(actual, 0, actual.length) != count
                        || !Arrays.equals(expected, 0, count, actual, 0, count)) {
                    throw new PublishedBytesConflict(uri);
                }
            } while (count == expected.length);
        }
    }

    private static final class PublishedBytesConflict extends IOException {
        PublishedBytesConflict(URI uri) {
            super(uri + " is already published with other bytes; raise the version in jk.toml");
        }
    }

    private static void record(String relPath, URI uri, int status, long size, Map<String, Integer> out, long[] bytes)
            throws IOException {
        out.put(relPath, status);
        bytes[0] += size;
        if (status == 409) {
            throw new IOException("PUT " + uri + " returned 409: the repository does not allow it to be replaced;"
                    + " raise the version in jk.toml");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("PUT " + uri + " returned " + status);
        }
    }

    private static String contentType(String suffix) {
        if (suffix.endsWith(".jar")) return "application/java-archive";
        if (suffix.endsWith(".pom") || suffix.endsWith(".xml")) return "application/xml";
        if (suffix.endsWith(".asc")) return "application/pgp-signature";
        if (suffix.endsWith(".sigstore") || suffix.endsWith(".json") || suffix.endsWith(".jsonl"))
            return "application/json";
        return "application/octet-stream";
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }
}
