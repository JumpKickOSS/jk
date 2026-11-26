// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Takes a published version out of an artifact's {@code maven-metadata.xml}, so ranges, {@code
 * latest} and new resolves stop choosing it while its files stay for every lock that pins it.
 * With {@code delete} the version's files go too. The metadata is written as a publish writes it
 * ({@link MetadataWrites}).
 */
public final class MavenYank {

    /**
     * What a yank of {@code coordinate} found and did. {@code listed} says the version was in the
     * version list; {@code before} and {@code after} are the list read and the list written (the
     * same when nothing changed). {@code files} are the repository paths deleted, or on a dry run
     * the ones a delete would remove; {@code refused} maps each path the repository would not delete to
     * its status.
     */
    public record Outcome(
            String coordinate,
            boolean listed,
            List<String> before,
            List<String> after,
            List<String> files,
            Map<String, Integer> refused) {
        public Outcome {
            before = List.copyOf(before);
            after = List.copyOf(after);
            files = List.copyOf(files);
            refused = Map.copyOf(refused);
        }

        /** True when the yank found nothing to change. */
        public boolean nothing() {
            return !listed && files.isEmpty() && refused.isEmpty();
        }
    }

    private final URI repoBase;
    private final RepoTransport transport;
    private final RepoCredential credential;

    public MavenYank(URI repoBase, RepoTransport transport, RepoCredential credential) {
        String base = repoBase.toString();
        this.repoBase = base.endsWith("/") ? repoBase : URI.create(base + "/");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credential = Objects.requireNonNull(credential, "credential");
    }

    /**
     * Delist {@code group:artifact:version}, then with {@code delete} remove every file under its
     * version directory. A {@code dryRun} reads and lists but writes and deletes nothing.
     */
    public Outcome yank(String group, String artifact, String version, boolean delete, boolean dryRun)
            throws IOException, InterruptedException {
        String artifactDir = group.replace('.', '/') + "/" + artifact + "/";
        String metadataPath = artifactDir + "maven-metadata.xml";
        URI metadataUri = repoBase.resolve(metadataPath);
        AtomicReference<@Nullable MavenMetadata> read = new AtomicReference<>();
        MetadataWrites.Edit edit = current -> {
            MavenMetadata metadata = current.isEmpty() ? null : parse(current.get(), group, artifact, metadataUri);
            read.set(metadata);
            if (metadata == null || !metadata.versions().contains(version)) return null;
            return metadata.withoutVersion(version).render();
        };
        if (dryRun) {
            edit.apply(transport.fetch(metadataUri, credential));
        } else {
            new MetadataWrites(repoBase, transport, credential, MavenYank::requireWritten).update(metadataPath, edit);
        }
        MavenMetadata metadata = read.get();
        List<String> before = metadata == null ? List.of() : metadata.versions();
        boolean listed = before.contains(version);
        List<String> after =
                listed && metadata != null ? metadata.withoutVersion(version).versions() : before;

        List<String> files = new ArrayList<>();
        Map<String, Integer> refused = new LinkedHashMap<>();
        if (delete) {
            String versionDir = artifactDir + version + "/";
            List<String> held = held(versionDir);
            if (dryRun) {
                files.addAll(held);
            } else {
                for (String path : held) {
                    int status = transport.delete(repoBase.resolve(path), credential);
                    if (status >= 200 && status < 300) {
                        files.add(path);
                    } else if (status != 404) {
                        refused.put(path, status);
                    }
                }
                if (transport instanceof FileTransport && refused.isEmpty()) {
                    transport.delete(repoBase.resolve(versionDir), credential);
                }
            }
        }
        return new Outcome(group + ":" + artifact + ":" + version, listed, before, after, files, refused);
    }

    /**
     * The repository paths under {@code versionDir}. A web repository that lists none is asked to
     * delete the directory itself, which Artifactory and Reposilite do and others refuse.
     */
    private List<String> held(String versionDir) throws IOException, InterruptedException {
        List<String> names;
        try {
            names = transport.list(repoBase.resolve(versionDir), credential);
        } catch (IOException unlisted) {
            if (!(transport instanceof HttpTransport)) throw unlisted;
            names = List.of();
        }
        if (names.isEmpty() && transport instanceof HttpTransport) return List.of(versionDir);
        return names.stream().map(name -> versionDir + name).toList();
    }

    private static MavenMetadata parse(byte[] body, String group, String artifact, URI uri) throws IOException {
        try {
            return MavenMetadata.parse(body, group, artifact);
        } catch (IllegalArgumentException e) {
            throw new IOException("could not read " + uri + ": " + e.getMessage() + "; left it unchanged", e);
        }
    }

    private static void requireWritten(String relPath, URI uri, int status, long size) throws IOException {
        if (status < 200 || status >= 300) throw new IOException("PUT " + uri + " returned " + status);
    }
}
