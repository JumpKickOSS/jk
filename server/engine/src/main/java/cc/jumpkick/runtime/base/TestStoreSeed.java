// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Linking;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Warms a sandbox store with the JUnit Platform the host store already holds, so a suite's engine —
 * nested or in-process — locks its fixtures' injected test roots without a repository in reach.
 *
 * <p>Every fixture project a suite locks gets {@code junit-platform-launcher} — and, with no test
 * dependencies of its own, {@code junit-jupiter} — injected as {@code latest}. A floating selector
 * needs the repository's version list, so a cold store means one {@code maven-metadata.xml} fetch
 * from Central per cold engine, and a Central that is throttling this host turns every one of them
 * red at once. The host running the gate has just built jk, whose own lock carries that closure:
 * the JUnit Platform trees are hard-linked (copied where the volumes differ) under the sandbox's
 * {@code repos/central}, and each artifact gets a version list naming exactly the versions whose
 * POM came along, written under the key the metadata cache reads for the Central URL. A body the
 * sandbox already holds is left alone — an index the store fetched itself outranks a synthesised
 * one — and nothing here is fetched, so the seed is a warmth, never a network cost.
 *
 * <p>Only those trees, on purpose. The rest of what a fixture may need — worker POM graphs, the
 * compilers — is exact-pinned and fetched once into the warm sandbox; a whole-store link would make
 * every sandbox weigh the whole store to the slot reaper's byte cap and its sizing walk.
 */
public final class TestStoreSeed {

    /** The trees seeded and given version lists: the JUnit Platform closure, as Maven-layout paths. */
    static final List<String> TREES = List.of("org/junit", "org/opentest4j", "org/apiguardian", "org/jspecify");

    /** Central's base, as {@code MavenRepo} resolves a metadata path against it. */
    static final String CENTRAL_BASE = RepositorySpec.MAVEN_CENTRAL.url().toString();

    private TestStoreSeed() {}

    /**
     * Seed {@code sandboxStore} from {@code hostStore}; a no-op when they are one store or the host
     * holds no Central tree. Returns how many files were materialised.
     */
    public static int seed(Path hostStore, Path sandboxStore) throws IOException {
        Path host = hostStore.toAbsolutePath().normalize();
        Path sandbox = sandboxStore.toAbsolutePath().normalize();
        if (host.equals(sandbox)) return 0;
        Path hostCentral = host.resolve("repos").resolve(RepositorySpec.CENTRAL);
        if (!Files.isDirectory(hostCentral)) return 0;
        Path sandboxCentral = sandbox.resolve("repos").resolve(RepositorySpec.CENTRAL);
        int[] materialised = {0};
        Map<Path, TreeSet<String>> pomVersions = new LinkedHashMap<>();
        for (String tree : TREES) {
            PathUtil.forEachRegularFile(hostCentral.resolve(tree), (file, attrs) -> {
                Path target =
                        sandboxCentral.resolve(hostCentral.relativize(file).toString());
                if (!Files.exists(target)) {
                    Linking.linkOrCopy(file, target);
                    materialised[0]++;
                }
                if (!file.getFileName().toString().endsWith(".pom")) return;
                Path version = file.getParent();
                Path artifact = version == null ? null : version.getParent();
                if (version == null || artifact == null) return;
                pomVersions
                        .computeIfAbsent(artifact, a -> new TreeSet<>(Versions::compare))
                        .add(version.getFileName().toString());
            });
        }
        for (Map.Entry<Path, TreeSet<String>> e : pomVersions.entrySet()) {
            if (writeVersionList(sandbox, hostCentral.relativize(e.getKey()), e.getValue())) materialised[0]++;
        }
        return materialised[0];
    }

    /** The metadata body for {@code artifactDir} under the sandbox, unless one is already there. */
    private static boolean writeVersionList(Path sandbox, Path artifactDir, TreeSet<String> versions)
            throws IOException {
        Path body = sandbox.resolve("metadata").resolve(metadataKey(artifactDir));
        if (Files.exists(body)) return false;
        Files.createDirectories(body.getParent());
        Files.writeString(body, versionList(artifactDir, versions));
        return true;
    }

    /** The cache key {@code MavenMetadataCache} derives for the artifact's Central metadata URL. */
    static String metadataKey(Path artifactDir) {
        return Hashing.sha256Hex(CENTRAL_BASE + slashes(artifactDir) + "/maven-metadata.xml");
    }

    /** A {@code maven-metadata.xml} naming {@code versions}, newest as {@code latest} and {@code release}. */
    static String versionList(Path artifactDir, TreeSet<String> versions) {
        List<String> segments = new ArrayList<>();
        for (Path p : artifactDir) segments.add(p.toString());
        String artifactId = segments.remove(segments.size() - 1);
        String groupId = String.join(".", segments);
        String newest = versions.last();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
        xml.append("  <groupId>").append(groupId).append("</groupId>\n");
        xml.append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        xml.append("  <versioning>\n");
        xml.append("    <latest>").append(newest).append("</latest>\n");
        xml.append("    <release>").append(newest).append("</release>\n");
        xml.append("    <versions>\n");
        for (String v : versions) xml.append("      <version>").append(v).append("</version>\n");
        xml.append("    </versions>\n  </versioning>\n</metadata>\n");
        return xml.toString();
    }

    private static String slashes(Path relative) {
        List<String> segments = new ArrayList<>();
        for (Path p : relative) segments.add(p.toString());
        return String.join("/", segments);
    }
}
