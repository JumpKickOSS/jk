// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.RepoTransports;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        this(repoBase, credential, ObjectStoreConfig.EMPTY);
    }

    /** Convenience for HTTP Basic auth; a blank username means anonymous. */
    public MavenPublisher(URI repoBase, String username, String password) {
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
        return new MavenPublisher(repoBase, credential, objectStore);
    }

    private MavenPublisher(URI repoBase, RepoCredential credential, ObjectStoreConfig objectStore) {
        this.repoBase = normalize(Objects.requireNonNull(repoBase, "repoBase"));
        this.credential = Objects.requireNonNull(credential, "credential");
        this.transport = RepoTransports.forUrl(this.repoBase, new Http(), objectStore);
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
    public record Result(Map<String, Integer> statusByPath, long bytes) {
        public Result {
            statusByPath = Map.copyOf(statusByPath);
        }
    }

    /**
     * Upload {@code artifacts} for {@code project} with no signing — every artifact gets its four
     * checksum files but no {@code.asc} or {@code.sigstore} sidecar. See {@link
     * #publish(JkBuild.Project, Iterable, SigningOptions)} for the signed variant.
     */
    public Result publish(JkBuild.Project project, Iterable<Artifact> artifacts)
            throws IOException, InterruptedException {
        return publish(project, artifacts, SigningOptions.none());
    }

    /**
     * Upload {@code artifacts} for {@code project}. Per artifact: body + four checksums; optional
     * {@code.asc} / {@code.sigstore} (each with their own checksums).
     */
    public Result publish(JkBuild.Project project, Iterable<Artifact> artifacts, SigningOptions signing)
            throws IOException, InterruptedException {
        if (signing == null) signing = SigningOptions.none();
        Map<String, Integer> results = new LinkedHashMap<>();
        long[] bytes = {0};
        String groupPath = project.group().replace('.', '/');
        String prefix = groupPath + "/" + project.name() + "/" + project.version() + "/";
        String stem = project.name() + "-" + project.version();

        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
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
        return new Result(results, bytes[0]);
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
    private void publishMetadata(JkBuild.Project project, String groupPath, Map<String, Integer> results, long[] bytes)
            throws IOException, InterruptedException {
        String relPath = groupPath + "/" + project.name() + "/maven-metadata.xml";
        URI uri = repoBase.resolve(relPath);
        MavenMetadata existing;
        try {
            existing = transport
                    .fetch(uri, credential)
                    .map(MavenMetadata::parse)
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
        // The transport carries the offline guard, auth header, and retry policy.
        int status = transport.put(uri, body, contentType, credential);
        out.put(relPath, status);
        bytes[0] += body.length;
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
