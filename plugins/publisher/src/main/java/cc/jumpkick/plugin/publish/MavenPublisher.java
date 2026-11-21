// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.pom.PomXml;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.transport.RepoTransports;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Uploads jar/pom/sources + checksums to a Maven HTTP repository via PUT. Auth via {@link
 * RepoCredential}; GPG/Sigstore/SLSA/SBOM layered by signing options.
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
     * next: one the repository holds over the same payload is kept, as a held {@code .asc} is.
     */
    public record Artifact(String filenameSuffix, Path file, boolean envelope) {
        public Artifact(String filenameSuffix, Path file) {
            this(filenameSuffix, file, false);
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

    /** Whether the repository holds a file, and if so whether it holds these bytes. */
    private enum Presence {
        ABSENT,
        SAME,
        DIFFERENT
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
            // A published file never changes: one the repository already holds at these bytes is left
            // alone and so is each sidecar already beside it, which makes a re-run after a partial
            // publish (or one that adds signing) upload exactly what is missing; one it holds at
            // other bytes is a version somebody already used.
            boolean kept = a.envelope() && keepHeldEnvelope(relPath, a.file());
            Checksums.Set sums = Checksums.of(a.file());
            boolean there = a.envelope() ? kept : presence(relPath, sums.sha1()) == Presence.SAME;
            if (there) {
                alreadyThere.add(relPath);
                putMissingChecksums(relPath, sums, results, bytes);
            } else {
                putFileWithChecksums(relPath, a.file(), sums, contentType(a.filenameSuffix()), results, bytes);
            }
            if (signing.gpg() != null) {
                GpgSigner gpg = signing.gpg();
                putSignature(relPath + ".asc", there, () -> gpg.signArmored(a.file()), results, bytes);
            }
            if (signing.sigstore() != null) {
                SigstoreSigner sigstore = signing.sigstore();
                putSignature(relPath + ".sigstore", there, () -> sigstore.signBundle(a.file()), results, bytes);
            }
        }
        publishMetadata(project, groupPath, results, bytes);
        return new Result(results, bytes[0], alreadyThere);
    }

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
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        String timestamp = STAMP.format(now);
        String stamped = project.version().substring(0, project.version().length() - SNAPSHOT.length()) + "-"
                + timestamp + "-" + build;
        String stem = project.name() + "-" + stamped;
        StringBuilder entries = new StringBuilder();
        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
            putFileWithChecksums(
                    relPath, a.file(), Checksums.of(a.file()), contentType(a.filenameSuffix()), results, bytes);
            if (signing.gpg() != null) {
                putWithChecksums(
                        relPath + ".asc", signing.gpg().signArmored(a.file()), contentType(".asc"), results, bytes);
            }
            if (signing.sigstore() != null) {
                putWithChecksums(
                        relPath + ".sigstore",
                        signing.sigstore().signBundle(a.file()),
                        contentType(".sigstore"),
                        results,
                        bytes);
            }
            String classifier = a.classifier();
            entries.append("      <snapshotVersion>\n");
            if (!classifier.isEmpty())
                entries.append("        <classifier>")
                        .append(PomXml.escape(classifier))
                        .append("</classifier>\n");
            entries.append("        <extension>")
                    .append(PomXml.escape(a.extension()))
                    .append("</extension>\n        <value>")
                    .append(PomXml.escape(stamped))
                    .append("</value>\n        <updated>")
                    .append(timestamp.replace(".", ""))
                    .append("</updated>\n      </snapshotVersion>\n");
        }
        String metadata = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata modelVersion=\"1.1.0\">\n  <groupId>"
                + PomXml.escape(project.group()) + "</groupId>\n  <artifactId>" + PomXml.escape(project.name())
                + "</artifactId>\n  <version>" + PomXml.escape(project.version())
                + "</version>\n  <versioning>\n    <snapshot>\n      <timestamp>" + timestamp
                + "</timestamp>\n      <buildNumber>" + build + "</buildNumber>\n    </snapshot>\n"
                + "    <lastUpdated>" + timestamp.replace(".", "") + "</lastUpdated>\n    <snapshotVersions>\n"
                + entries + "    </snapshotVersions>\n  </versioning>\n</metadata>\n";
        putWithChecksums(metadataPath, metadata.getBytes(StandardCharsets.UTF_8), "application/xml", results, bytes);
        publishMetadata(project, groupPath, results, bytes);
        return new Result(results, bytes[0], Set.of());
    }

    /**
     * Whether {@code relPath} is in the repository, and at the bytes whose SHA-1 is {@code local}: the
     * {@code .sha1} beside it when there is one, else the file itself, streamed. A repository that
     * will not answer a read (no read permission, a write-only drop) reads as {@link
     * Presence#ABSENT}: the upload that follows is the authority, and says so if the file is there.
     *
     * @throws IOException when the repository holds the file at other bytes
     */
    private Presence presence(String relPath, String local) throws IOException, InterruptedException {
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
                if (raw.isEmpty()) return Presence.ABSENT;
                remote = Checksums.sha1Hex(raw.get());
            }
        } catch (IOException unreadable) {
            return Presence.ABSENT;
        }
        if (remote.equals(local)) return Presence.SAME;
        throw new IOException(repoBase.resolve(relPath)
                + " is already published with other bytes; raise the version in jk.toml"
                + "\n  repository sha1 " + remote + ", this build " + local);
    }

    /**
     * Write the artifact-level {@code maven-metadata.xml}.
     *
     * <p>Without it a resolver cannot enumerate an artifact's versions, so a published artifact is
     * unresolvable from a plain {@code file://} / static HTTP / object-store repo — even for an
     * exact version pin. Real repository managers synthesize this server-side, which is why the gap
     * only shows up on the simple targets jk advertises ({@code s3://}, {@code gs://}, {@code
     * file://}).
     *
     * <p>Existing remote versions are merged rather than clobbered, so publishing 0.2.0 after 0.1.0
     * leaves both listed.
     *
     * <p><b>Absent and unreadable are opposite cases.</b> {@link RepoTransport#fetch} already models
     * "no metadata yet" as an empty {@link java.util.Optional} and throws only on a genuine
     * transport failure, so a thrown read means we do <em>not</em> know the existing version list —
     * and a document that arrives but will not parse says exactly the same thing. Writing one
     * anyway would erase every previously published version from a repository that cannot
     * un-publish, so either failure aborts the publish <em>before</em> the destructive PUT. The
     * per-version artifacts are already uploaded and immutable, so re-running publish is the whole
     * recovery.
     */
    private void publishMetadata(Project project, String groupPath, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        String relPath = groupPath + "/" + project.name() + "/maven-metadata.xml";
        URI uri = repoBase.resolve(relPath);
        MavenMetadata existing;
        try {
            existing = transport
                    .fetch(uri, credential)
                    .map(body -> MavenMetadata.parse(body, project.group(), project.name()))
                    .orElseGet(() -> MavenMetadata.empty(project.group(), project.name()));
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(
                    "could not read " + uri + ": " + e.getMessage()
                            + "\n  left it unchanged; re-run publish to add " + project.version()
                            + " to the version list",
                    e);
        }
        putWithChecksums(relPath, existing.withVersion(project.version()).render(), "application/xml", results, bytes);
    }

    /**
     * Whether the repository already holds the envelope at {@code relPath} over the payload of the
     * one in {@code file}; when it does, its bytes replace {@code file}'s, so the sidecars and the
     * signatures beside it are of the copy that is published.
     *
     * @throws IOException when the repository holds an envelope over another payload
     */
    private boolean keepHeldEnvelope(String relPath, Path file) throws IOException, InterruptedException {
        Optional<byte[]> held = readable(relPath);
        if (held.isEmpty()) return false;
        if (!Dsse.samePayload(held.get(), Files.readAllBytes(file))) {
            throw new IOException(repoBase.resolve(relPath)
                    + " is already published over another statement; raise the version in jk.toml");
        }
        Files.write(file, held.get());
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
            putMissingChecksums(relPath, Checksums.of(held.get()), results, bytes);
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
    private void putFileWithChecksums(
            String relPath,
            Path file,
            Checksums.Set sums,
            String contentType,
            Map<String, Integer> results,
            long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        record(relPath, uri, transport.putFile(uri, file, contentType, credential), Files.size(file), results, bytes);
        putChecksums(relPath, sums, results, bytes);
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
        record(relPath, uri, transport.put(uri, body, contentType, credential), body.length, out, bytes);
    }

    /**
     * Note one upload's status and size, and throw when it failed. The transport carries the offline
     * guard, auth header, and retry policy. The guard is real inside this worker because
     * Publisher.run installs the spec's offline decision onto the worker JVM's session before
     * anything reaches here; Publisher.publish also refuses up front, so this is the backstop, not
     * the only check.
     */
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
        if (suffix.endsWith(".sigstore") || suffix.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }
}
