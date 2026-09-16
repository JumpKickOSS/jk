// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A repository's use of the Maven local repository ({@code ~/.m2/repository}). A local-repo file
 * the repository's own checksum vouches for is adopted into the store instead of downloaded, and
 * a download is written through to the local repository when that slot is empty — for Maven's
 * benefit; the store is what jk reads. Both arms are live only when the resolving project's
 * {@code m2integration} and the {@code [m2] integration} setting agree.
 */
final class M2Adoption {

    private final String name;

    /** The HTTP client for the sidecar GET that confirms a candidate; null for a non-HTTP repository. */
    private final @Nullable Http http;

    private final RepoArtifactStore repoStore;
    private final boolean m2integration;

    M2Adoption(String name, @Nullable Http http, RepoArtifactStore repoStore, boolean m2integration) {
        this.name = Objects.requireNonNull(name, "name");
        this.http = http;
        this.repoStore = Objects.requireNonNull(repoStore, "repoStore");
        this.m2integration = m2integration;
    }

    /** A local-repo file adopted into the store: where it landed, its SHA-256 and its size. */
    record Adopted(Path placed, String sha256, long size) {}

    private boolean enabled() {
        return m2integration && JkM2Config.resolve().integration();
    }

    /**
     * Copy {@code placed} to Maven's slot for {@code relativePath}, with the {@code .sha1} /
     * {@code .md5} sidecars and {@code _remote.repositories} Maven expects. Best effort: a local
     * repository that cannot be written, or that holds other bytes at this slot, costs Maven a
     * fetch and jk nothing. A {@code relativePath} (from a possibly hostile GAV) that would escape
     * the local repository is refused.
     */
    void writeThrough(String relativePath, Path placed) {
        if (!enabled()) return;
        try {
            Path m2Target = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            if (Files.isRegularFile(m2Target)) return;
            M2CompatWriter.MavenHashes hashes = M2CompatWriter.copyToM2AndHash(placed, m2Target);
            M2CompatWriter.writeMavenSidecars(m2Target, hashes.sha1(), hashes.md5());
            M2CompatWriter.writeRemoteRepositories(
                    Objects.requireNonNull(m2Target.getParent()),
                    name,
                    m2Target.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            Log.debug("writeThrough: the Maven local repository is a courtesy copy", e);
        }
    }

    /**
     * Adopt {@code relativePath} out of the Maven local repository when its bytes match the checksum this
     * repository publishes for it.
     *
     * <p>The hash is fetched remotely rather than read from {@code jk-lock.toml} on purpose: it makes the
     * check work during resolve, when no lock entry exists yet, and it keeps the authority with the
     * repository instead of with a directory any {@code mvn install} can write to. A {@code .sha1} is
     * ~40 bytes against a jar that can be tens of megabytes, so the saving is bandwidth — it does not
     * reduce request count, and so does not by itself relieve a per-IP quota.
     *
     * <p>Empty on any doubt whatsoever: lookup disabled, no local file, no HTTP client, sidecar missing
     * or unparseable, or bytes that do not match. Every one of those falls through to the ordinary
     * download, so the worst case is one wasted small GET. An adopted artifact counts as verified:
     * the repository's own checksum vouched for it, exactly as it would for a download.
     */
    Optional<Adopted> tryAdopt(String relativePath, URI uri) {
        if (!enabled()) return Optional.empty();
        if (http == null) return Optional.empty();
        try {
            Path candidate = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            if (!Files.isRegularFile(candidate)) return Optional.empty();

            // Prefer the collision-resistant .sha256 sidecar; fall back to .sha1 only when the repo
            // doesn't publish one (SHA-1 is chosen-prefix broken, and its match becomes the lock pin
            // for bytes any `mvn install` could have seeded). The SHA-256 is computed once: it is
            // both the comparison and the memo the adoption records.
            String sha256 = Hashing.sha256Hex(candidate);
            String vouchAlgo;
            Optional<String> advertised = fetchSidecar(uri, ".sha256", 64);
            if (advertised.isPresent()) {
                vouchAlgo = "sha256";
                if (!sha256.equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            } else {
                vouchAlgo = "sha1";
                advertised = fetchSidecar(uri, ".sha1", 40);
                if (advertised.isEmpty()) return Optional.empty();
                if (!Hashing.fileHex("SHA-1", candidate).equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            }

            // Into the store: the build reads only what the store owns, so an adopted file is a copy
            // and the local repository keeps its own.
            repoStore.materialize(relativePath, candidate, sha256);
            Path placed = repoStore.locate(relativePath).orElse(null);
            if (placed == null) return Optional.empty();
            if (SessionContext.current().config().verboseOr(false)) {
                Log.info("jk: adopted " + relativePath + " from Maven local repo (" + vouchAlgo + " confirmed by "
                        + name + ")");
            }
            return Optional.of(new Adopted(placed, sha256, Files.size(placed)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The digest this repository publishes in the {@code suffix} sidecar beside {@code uri}; empty
     * when absent or not a {@code hexLength}-digit digest. Some repositories answer a missing
     * sidecar with an HTML error page under HTTP 200, which is why the body is validated and not
     * merely non-empty.
     */
    private Optional<String> fetchSidecar(URI uri, String suffix, int hexLength) {
        Http client = http;
        if (client == null) return Optional.empty(); // a non-HTTP transport publishes no sidecar this way
        try {
            var resp = client.get(URI.create(uri + suffix));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return Optional.empty();
            return Hashing.checksumFromSidecar(new String(resp.body(), StandardCharsets.UTF_8), hexLength);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
