// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The GraalVM reachability-metadata repository for {@code jk native}: extract the locked release,
 * then match each locked {@code g:a:v} against its index ({@code tested-versions}, then
 * {@code latest}). Failures yield an empty list — a missing repository degrades the image, it does
 * not fail the build.
 *
 * <h2>Store, not cache</h2>
 *
 * The extracted tree lives under the <strong>artifact store</strong> ({@code JK_STORE_DIR}), beside
 * {@code repos/} and the artifact CAS, because it is the same kind of thing: a downloaded,
 * version-addressed Maven artifact, not a rebuildable action output. It was a cache tier, and that
 * was wrong in the direction that costs the user money — {@code jk cache nuke} took 27 MB that only
 * Maven Central can give back, and Sonatype's per-IP quota is sticky. Nothing under the cache root
 * may be something a nuke makes you re-download.
 *
 * <h2>Version, not constant</h2>
 *
 * Which release is extracted comes from {@code jk-lock.toml}'s {@code [native]} pin, resolved from
 * {@code [native] metadata-repository} by {@code jk lock}. It used to be a {@code static final
 * String} here, which meant the repository release was decided by whichever jk binary happened to
 * run — an input to {@code native-image} that no lock recorded and no user could choose.
 */
public final class ReachabilityMetadata {

    private static final String GROUP = "org.graalvm.buildtools";
    private static final String ARTIFACT = "graalvm-reachability-metadata";

    /** Store entry holding the extracted repositories, one subtree per locked release. */
    private static final String STORE_ENTRY = "native";

    private static final String REPOSITORY_DIR = "metadata-repository";

    private ReachabilityMetadata() {}

    /** The Maven coordinate of one repository release: the {@code repository} classifier zip. */
    public static Coordinate coordinate(String version) {
        return new Coordinate(GROUP, ARTIFACT, version, "repository", "zip");
    }

    /**
     * Where release {@code version} unpacks: {@code <store>/native/metadata-repository/<version>}.
     * The version is in the path so a lock bump lands beside the old tree instead of mixing with it.
     */
    public static Path repositoryRoot(Path storeRoot, String version) {
        return storeRoot.resolve(STORE_ENTRY).resolve(REPOSITORY_DIR).resolve(version);
    }

    /**
     * Matched config directories for {@code artifacts} (the RUNTIME lock entries), extracting the
     * locked repository release on first use. Logs matches through {@code log}; returns an empty
     * list when {@code pin} is null (the lock predates the pin, or no module declares
     * {@code [native]}), when the repository is unavailable (offline), or when nothing matches.
     */
    static List<Path> configDirs(
            Path storeRoot,
            RepoGroup repos,
            Lockfile.NativeMetadata pin,
            List<Lockfile.Artifact> artifacts,
            Consumer<String> log) {
        if (pin == null) {
            log.accept("no [native] metadata-repository in jk-lock.toml — run `jk lock`;"
                    + " building without reachability metadata");
            return List.of();
        }
        Path repoRoot;
        try {
            repoRoot = ensureExtracted(storeRoot, repos, pin);
        } catch (IOException e) {
            log.accept("reachability metadata unavailable (" + e.getMessage() + ") — building without it");
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        List<Path> dirs = new ArrayList<>();
        for (Lockfile.Artifact artifact : artifacts) {
            Path dir = match(repoRoot, artifact.moduleGroup(), artifact.moduleArtifact(), artifact.version(), log);
            if (dir != null) dirs.add(dir);
        }
        return dirs;
    }

    /**
     * The config dir for one coordinate, or null. Exact tested-version match wins; the
     * {@code latest} entry is the best-effort fallback.
     */
    private static Path match(Path repoRoot, String group, String artifact, String version, Consumer<String> log) {
        Path artifactDir = repoRoot.resolve(group).resolve(artifact);
        Path index = artifactDir.resolve("index.json");
        if (!Files.isRegularFile(index)) return null;
        List<?> entries;
        try {
            Object parsed = MiniJson.parse(Files.readString(index, StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> list)) return null;
            entries = list;
        } catch (IOException | RuntimeException e) {
            log.accept("reachability index unreadable for " + group + ":" + artifact + " — skipped");
            return null;
        }

        String exact = null;
        String latest = null;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> entry)) continue;
            Object metadataVersion = entry.get("metadata-version");
            if (!(metadataVersion instanceof String mv)) continue;
            if (entry.get("tested-versions") instanceof List<?> tested && tested.contains(version)) {
                exact = mv;
                break;
            }
            if (Boolean.TRUE.equals(entry.get("latest"))) latest = mv;
        }
        String chosen = exact != null ? exact : latest;
        if (chosen == null) return null;
        Path dir = artifactDir.resolve(chosen);
        if (!Files.isDirectory(dir)) return null;
        log.accept("reachability metadata: " + group + ":" + artifact + "@" + version + " → " + chosen
                + (exact == null ? " (latest, untested for this version)" : ""));
        return dir;
    }

    /**
     * Fetch + extract the locked repository release once; concurrent-safe via extract-to-temp +
     * atomic move, with a marker check for the fast path. The locked checksum, when the lock carries
     * one, is verified against the zip before anything is unpacked.
     */
    public static Path ensureExtracted(Path storeRoot, RepoGroup repos, Lockfile.NativeMetadata pin)
            throws IOException, InterruptedException {
        String version = pin.version();
        Path root = repositoryRoot(storeRoot, version);
        Path marker = root.resolve(".complete");
        if (Files.isRegularFile(marker)) return root;

        Coordinate coord = coordinate(version);
        String expected = pin.checksumHex();
        // The pinned fetch, so a stale local mirror is evicted rather than returned — the digest
        // check below would otherwise fail forever on a copy nothing ever replaces.
        Path zip = repos.tryFetchArtifact(coord, expected)
                .orElseThrow(() -> new IOException("cannot fetch " + coord))
                .fetched()
                .cachePath();
        if (expected != null) {
            // Verified here and not off the fetch's own sha: a repository that serves different
            // bytes than the lock pins passes its own sidecar check on the way in.
            String actual = Hashing.sha256Hex(zip);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("checksum mismatch for " + coord + ": jk-lock.toml pins sha256:" + expected
                        + " but the fetched zip is sha256:" + actual);
            }
        }

        Path tmp = Files.createTempDirectory(Files.createDirectories(root.getParent()), version + ".extract-");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = tmp.resolve(entry.getName()).normalize();
                if (!target.startsWith(tmp)) continue; // zip-slip guard
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    // transferTo, NOT a try-with-resources copy — closing the entry stream
                    // would close the whole ZipInputStream after the first file.
                    try (var out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                }
            }
        }
        Files.writeString(tmp.resolve(".complete"), version);
        try {
            AtomicWrites.publishDir(tmp, root);
        } catch (IOException e) {
            // Another build won the race (publishDir handles a non-atomic FS itself): fine if the
            // winner completed.
            PathUtil.deleteRecursively(tmp);
            if (!Files.isRegularFile(marker)) throw e;
        }
        return root;
    }
}
