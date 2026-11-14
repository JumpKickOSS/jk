// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ReleaseRepoDigests;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Holds a first-party worker found in a store other than {@code repos/jk-local} (the official
 * repository's, Central's) to the digests its release signed, before the engine runs it: a jar can
 * reach those stores without the fetch that checks it (an ordinary resolve of the coordinate, a
 * store an older engine filled, a copied store). The jar and its POM, which decides the worker's
 * class path, must both be the bytes the release lists. A pair is checked once per engine while
 * its files keep their stat identity.
 *
 * <p>The verified digest list of each release is kept beside the official repository's store
 * ({@code jk-repo-<version>.sha256}), so an engine offline can still check what an earlier fetch
 * stored. The shelf, {@code repos/jk-local}, is the install's own and is pinned by its manifest
 * instead.
 */
final class StoredWorkers {

    private StoredWorkers() {}

    /** What a stored worker is to this engine. */
    sealed interface Verdict {
        /** The release lists these bytes. */
        record Vouched() implements Verdict {}

        /** The release lists other bytes, or none: never run. */
        record Refused(String reason) implements Verdict {}

        /** No digest list to check against without the network. */
        record Unknown() implements Verdict {}
    }

    /** Signed digests of one release, fetched on demand. */
    @FunctionalInterface
    interface SignedDigests {
        ReleaseRepoDigests fetch(String version) throws IOException;
    }

    /** The jar and POM as they stood when checked. */
    private record Stamps(StampedMemo.FileStamp jar, StampedMemo.FileStamp pom) implements StampedMemo.Settling {
        @Override
        public boolean settled() {
            return jar.settled() && pom.settled();
        }
    }

    private static final StampedMemo<Path, Stamps, Boolean> VOUCHED =
            StampedMemo.<Path, Stamps, Boolean>bounded(256).keeping(Boolean::booleanValue);

    /**
     * Check {@code jar} (found at {@code relPath} in a store under {@code cas}) and its POM.
     * {@code signed} is asked for the release's digests when none are kept; {@code null} checks
     * against the kept list only.
     */
    static Verdict check(Cas cas, String relPath, Path jar, @Nullable SignedDigests signed) {
        Path pom = jar.resolveSibling(pomName(jar));
        StampedMemo.FileStamp jarStamp = StampedMemo.FileStamp.of(jar);
        StampedMemo.FileStamp pomStamp = StampedMemo.FileStamp.of(pom);
        if (jarStamp == null || pomStamp == null) return new Verdict.Refused(pom.getFileName() + " is missing");
        Verdict[] verdict = {new Verdict.Vouched()};
        VOUCHED.get(jar.toAbsolutePath().normalize(), new Stamps(jarStamp, pomStamp), () -> {
            verdict[0] = verify(cas, relPath, jar, pom, signed);
            return verdict[0] instanceof Verdict.Vouched;
        });
        return verdict[0];
    }

    private static Verdict verify(Cas cas, String relPath, Path jar, Path pom, @Nullable SignedDigests signed) {
        String version = versionOf(relPath);
        ReleaseRepoDigests digests = kept(cas, version);
        if (digests == null) {
            if (signed == null) return new Verdict.Unknown();
            try {
                digests = signed.fetch(version);
            } catch (IOException e) {
                return new Verdict.Refused("its release digests are not available: " + e.getMessage());
            }
            keep(cas, digests);
        }
        String pomRel = relPath.substring(0, relPath.length() - ".jar".length()) + ".pom";
        try {
            String refusal = mismatch(digests, relPath, jar);
            if (refusal == null) refusal = mismatch(digests, pomRel, pom);
            if (refusal != null) {
                Log.warn("jk engine: not running " + jar + ": " + refusal);
                return new Verdict.Refused(refusal);
            }
        } catch (IOException unreadable) {
            return new Verdict.Refused(jar.getFileName() + " is unreadable: " + unreadable.getMessage());
        }
        return new Verdict.Vouched();
    }

    private static @Nullable String mismatch(ReleaseRepoDigests digests, String relPath, Path file) throws IOException {
        String name = file.getFileName().toString();
        String expected = digests.sha256(relPath).orElse(null);
        if (expected == null) return "the signed jk " + digests.version() + " release lists no " + name;
        String actual = Hashing.sha256Hex(file);
        if (actual.equals(expected)) return null;
        return name + " in the store has sha256 " + actual + ", but the signed jk " + digests.version()
                + " release lists " + expected;
    }

    /** Where the verified digest list of {@code version} is kept under {@code cas}'s store. */
    static Path keptFile(Cas cas, String version) {
        Path root = Objects.requireNonNull(
                RepoArtifactStore.forRepository(cas.root(), RepositorySpec.JUMPKICK_NAME, PluginJar.officialRepoBase())
                        .root(),
                "official repository store");
        return root.resolve(ReleaseRepoDigests.fileName(version));
    }

    /** Keep a verified digest list for engines that check without the network; best effort. */
    static void keep(Cas cas, ReleaseRepoDigests digests) {
        try {
            Path file = keptFile(cas, digests.version());
            Files.createDirectories(Objects.requireNonNull(file.getParent(), "store root"));
            AtomicWrites.replace(file, digests.render());
        } catch (IOException | RuntimeException e) {
            Log.debug("jk engine: release digests not kept", e);
        }
    }

    private static @Nullable ReleaseRepoDigests kept(Cas cas, String version) {
        Path file = keptFile(cas, version);
        if (!Files.isRegularFile(file)) return null;
        try {
            return ReleaseRepoDigests.parse(version, Files.readAllBytes(file));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String pomName(Path jar) {
        String name = jar.getFileName().toString();
        return name.substring(0, name.length() - ".jar".length()) + ".pom";
    }

    private static String versionOf(String relPath) {
        String[] segments = relPath.split("/");
        return segments.length < 3 ? "" : segments[segments.length - 2];
    }

    /** Forget every check this engine made; for tests. */
    static void forget() {
        VOUCHED.clear();
    }
}
