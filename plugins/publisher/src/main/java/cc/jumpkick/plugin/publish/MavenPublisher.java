// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.RepoTransports;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
        this.repoBase = normalize(Objects.requireNonNull(repoBase, "repoBase"));
        this.credential = Objects.requireNonNull(credential, "credential");
        this.transport = RepoTransports.forUrl(this.repoBase, http, objectStore);
    }

    public record Artifact(String filenameSuffix, byte[] body) {
        public Artifact {
            Objects.requireNonNull(filenameSuffix, "filenameSuffix");
            Objects.requireNonNull(body, "body");
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
            // alone, which makes a re-run after a partial publish finish the rest; one it holds at
            // other bytes is a version somebody already used.
            if (presence(relPath, a.body()) == Presence.SAME) {
                alreadyThere.add(relPath);
                continue;
            }
            putWithChecksums(relPath, a.body(), contentType(a.filenameSuffix()), results, bytes);

            if (signing.gpg() != null) {
                byte[] asc = signing.gpg().signArmored(a.body());
                putWithChecksums(relPath + ".asc", asc, contentType(".asc"), results, bytes);
            }
            if (signing.sigstore() != null) {
                byte[] bundle = signing.sigstore().signBundle(a.body());
                putWithChecksums(relPath + ".sigstore", bundle, contentType(".sigstore"), results, bytes);
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
                    "could not read " + repoBase.resolve(metadataPath) + " (" + unreadable.getMessage()
                            + ") — refusing to guess the next build number of " + project.version() + ".",
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
            putWithChecksums(relPath, a.body(), contentType(a.filenameSuffix()), results, bytes);
            if (signing.gpg() != null) {
                putWithChecksums(
                        relPath + ".asc", signing.gpg().signArmored(a.body()), contentType(".asc"), results, bytes);
            }
            if (signing.sigstore() != null) {
                putWithChecksums(
                        relPath + ".sigstore",
                        signing.sigstore().signBundle(a.body()),
                        contentType(".sigstore"),
                        results,
                        bytes);
            }
            String suffix = a.filenameSuffix();
            int dot = suffix.lastIndexOf('.');
            String classifier = suffix.startsWith("-") ? suffix.substring(1, dot) : "";
            entries.append("      <snapshotVersion>\n");
            if (!classifier.isEmpty())
                entries.append("        <classifier>").append(classifier).append("</classifier>\n");
            entries.append("        <extension>")
                    .append(suffix.substring(dot + 1))
                    .append("</extension>\n        <value>")
                    .append(stamped)
                    .append("</value>\n        <updated>")
                    .append(timestamp.replace(".", ""))
                    .append("</updated>\n      </snapshotVersion>\n");
        }
        String metadata = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata modelVersion=\"1.1.0\">\n  <groupId>"
                + project.group() + "</groupId>\n  <artifactId>" + project.name() + "</artifactId>\n  <version>"
                + project.version() + "</version>\n  <versioning>\n    <snapshot>\n      <timestamp>" + timestamp
                + "</timestamp>\n      <buildNumber>" + build + "</buildNumber>\n    </snapshot>\n"
                + "    <lastUpdated>" + timestamp.replace(".", "") + "</lastUpdated>\n    <snapshotVersions>\n"
                + entries + "    </snapshotVersions>\n  </versioning>\n</metadata>\n";
        putWithChecksums(metadataPath, metadata.getBytes(StandardCharsets.UTF_8), "application/xml", results, bytes);
        publishMetadata(project, groupPath, results, bytes);
        return new Result(results, bytes[0], Set.of());
    }

    /**
     * Whether {@code relPath} is in the repository, and at {@code body}'s bytes: the {@code .sha1}
     * beside it when there is one, else the file itself. A repository that will not answer a read
     * (no read permission, a write-only drop) reads as {@link Presence#ABSENT}: the upload that
     * follows is the authority, and says so if the file is there.
     *
     * @throws IOException when the repository holds the file at other bytes
     */
    private Presence presence(String relPath, byte[] body) throws IOException, InterruptedException {
        String local = Checksums.of(body).sha1();
        String remote;
        try {
            Optional<byte[]> sidecar = transport.fetch(repoBase.resolve(relPath + ".sha1"), credential);
            if (sidecar.isPresent()) {
                remote = new String(sidecar.get(), StandardCharsets.US_ASCII)
                        .strip()
                        .split("\\s+")[0]
                        .toLowerCase(Locale.ROOT);
            } else {
                Optional<byte[]> raw = transport.fetch(repoBase.resolve(relPath), credential);
                if (raw.isEmpty()) return Presence.ABSENT;
                remote = Checksums.of(raw.get()).sha1();
            }
        } catch (IOException unreadable) {
            return Presence.ABSENT;
        }
        if (remote.equals(local)) return Presence.SAME;
        throw new IOException(repoBase.resolve(relPath)
                + " is already published with other bytes (the repository's sha1 " + remote + ", this build's " + local
                + "): a published version cannot change. Raise the version in jk.toml.");
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
                    "could not read " + uri + " (" + e.getMessage()
                            + ") — refusing to replace it with a single-version document. The "
                            + project.version() + " artifacts are uploaded; re-run publish to update the version list.",
                    e);
        }
        putWithChecksums(relPath, existing.withVersion(project.version()).render(), "application/xml", results, bytes);
    }

    private void putWithChecksums(
            String relPath, byte[] body, String contentType, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        put(relPath, body, contentType, results, bytes);
        Checksums.Set sums = Checksums.of(body);
        put(relPath + ".md5", sums.md5().getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
        put(relPath + ".sha1", sums.sha1().getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
        put(relPath + ".sha256", sums.sha256().getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
        put(relPath + ".sha512", sums.sha512().getBytes(StandardCharsets.US_ASCII), "text/plain", results, bytes);
    }

    private void put(String relPath, byte[] body, String contentType, Map<String, Integer> out, long[] bytes)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        // The transport carries the offline guard, auth header, and retry policy. The guard is real
        // inside this worker because Publisher.run installs the spec's offline decision onto the
        // worker JVM's session before anything reaches here; Publisher.publish also refuses up
        // front, so this is the backstop, not the only check.
        int status = transport.put(uri, body, contentType, credential);
        out.put(relPath, status);
        bytes[0] += body.length;
        if (status == 409) {
            throw new IOException("PUT " + uri + " returned 409: the repository already holds this file and does not"
                    + " allow it to be replaced. Raise the version in jk.toml.");
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
