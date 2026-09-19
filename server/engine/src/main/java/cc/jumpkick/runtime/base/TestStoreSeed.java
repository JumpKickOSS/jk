// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Linking;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

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
 * POM came along, written under the key the metadata cache reads for the Central URL. A jar the
 * host stored without its POM — Maven-local adoption records the jar and not the POM — is
 * completed from {@link M2Dirs#localRepository()} (the gate's shared test-m2 under
 * {@code JK_M2_LOCAL}) and from {@code ~/.m2} when the file is there, and the rest of those trees
 * there (POMs and jars the host never stored) is copied for exact fetches a solve still makes. A
 * body the sandbox already holds is left alone — an index the store fetched itself outranks a
 * synthesised one — and nothing here is fetched, so the seed is a warmth, never a network cost.
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
        return seed(hostStore, sandboxStore, null);
    }

    /**
     * Complete {@code store}'s JUnit trees from every Maven local repository that may hold the POMs:
     * {@link M2Dirs#localRepository()} first ({@code JK_M2_LOCAL} / the gate's shared test-m2), then
     * {@code ~/.m2/repository}. A jar-only {@code ~/.m2} from Maven-local adoption is not enough on
     * its own; the sandbox test-m2 usually has the POMs a prior fetch left there.
     */
    public static int complete(Path store) throws IOException {
        return seedFromLocalRepos(store, store);
    }

    /**
     * Seed {@code sandboxStore} from {@code hostStore}, completing missing JUnit POMs from each
     * candidate local repository ({@link M2Dirs#localRepository()}, then {@code ~/.m2/repository}).
     */
    public static int seedFromLocalRepos(Path hostStore, Path sandboxStore) throws IOException {
        int materialised = 0;
        for (Path m2 : localRepos()) {
            materialised += seed(hostStore, sandboxStore, m2);
        }
        return materialised;
    }

    /**
     * Local-repository roots that may hold JUnit POMs. {@link M2Dirs#localRepository()} first so a
     * test JVM's {@code JK_M2_LOCAL} wins over a jar-only {@code ~/.m2}.
     */
    static List<Path> localRepos() {
        Path local = M2Dirs.localRepository().toAbsolutePath().normalize();
        Path user = Path.of(System.getProperty("user.home"), ".m2", "repository")
                .toAbsolutePath()
                .normalize();
        if (local.equals(user)) return List.of(local);
        return List.of(local, user);
    }

    /**
     * As {@link #seed(Path, Path)}, completing missing POMs from {@code m2} and copying the rest of
     * those trees' POMs and jars for exact fetches. One store named twice still completes from
     * {@code m2}: a sandbox the parent already linked jars into still needs the POMs.
     */
    public static int seed(Path hostStore, Path sandboxStore, @Nullable Path m2) throws IOException {
        Path host = hostStore.toAbsolutePath().normalize();
        Path sandbox = sandboxStore.toAbsolutePath().normalize();
        boolean same = host.equals(sandbox);
        if (same && m2 == null) return 0;
        Path hostCentral = host.resolve("repos").resolve(RepositorySpec.CENTRAL);
        if (!Files.isDirectory(hostCentral)) return 0;
        Path sandboxCentral = sandbox.resolve("repos").resolve(RepositorySpec.CENTRAL);
        int[] materialised = {0};
        Map<Path, TreeSet<String>> pomVersions = new LinkedHashMap<>();
        Map<Path, TreeSet<String>> hostVersions = new LinkedHashMap<>();
        for (String tree : TREES) {
            PathUtil.forEachRegularFile(hostCentral.resolve(tree), (file, attrs) -> {
                if (!same) {
                    Path target =
                            sandboxCentral.resolve(hostCentral.relativize(file).toString());
                    if (!Files.exists(target)) {
                        Linking.linkOrCopy(file, target);
                        materialised[0]++;
                    }
                }
                Path version = file.getParent();
                Path artifact = version == null ? null : version.getParent();
                if (version == null || artifact == null) return;
                String ver = version.getFileName().toString();
                hostVersions
                        .computeIfAbsent(artifact, a -> new TreeSet<>(Versions::compare))
                        .add(ver);
                if (file.getFileName().toString().endsWith(".pom")) {
                    pomVersions
                            .computeIfAbsent(artifact, a -> new TreeSet<>(Versions::compare))
                            .add(ver);
                }
            });
        }
        Path m2Root = m2 == null ? null : m2.toAbsolutePath().normalize();
        if (m2Root != null && Files.isDirectory(m2Root) && !m2Root.equals(sandbox)) {
            fillMissingPoms(hostCentral, sandboxCentral, m2Root, hostVersions, pomVersions, materialised);
            copyM2Trees(m2Root, sandboxCentral, materialised);
        }
        for (Map.Entry<Path, TreeSet<String>> e : pomVersions.entrySet()) {
            if (writeVersionList(sandbox, hostCentral.relativize(e.getKey()), e.getValue())) materialised[0]++;
        }
        return materialised[0];
    }

    /**
     * A version directory the host has as a jar but not a POM: copy the POM from {@code m2} when it
     * is there, so the version list can advertise it.
     */
    private static void fillMissingPoms(
            Path hostCentral,
            Path sandboxCentral,
            Path m2,
            Map<Path, TreeSet<String>> hostVersions,
            Map<Path, TreeSet<String>> pomVersions,
            int[] materialised)
            throws IOException {
        for (Map.Entry<Path, TreeSet<String>> e : hostVersions.entrySet()) {
            Path artifact = e.getKey();
            Path artifactRel = hostCentral.relativize(artifact);
            Path artifactName = artifactRel.getFileName();
            if (artifactName == null) continue;
            String artifactId = artifactName.toString();
            Set<String> havePom = pomVersions.get(artifact);
            for (String version : e.getValue()) {
                if (havePom != null && havePom.contains(version)) continue;
                String pomName = artifactId + "-" + version + ".pom";
                Path source = m2.resolve(artifactRel).resolve(version).resolve(pomName);
                if (!Files.isRegularFile(source)) continue;
                Path target =
                        sandboxCentral.resolve(artifactRel).resolve(version).resolve(pomName);
                Linking.linkOrCopy(source, target);
                materialised[0]++;
                pomVersions
                        .computeIfAbsent(artifact, a -> new TreeSet<>(Versions::compare))
                        .add(version);
            }
        }
    }

    /**
     * POMs and jars under the JUnit trees in {@code m2} that the host never stored: exact pins a
     * solve still makes (a BOM import, a transitive the selected launcher names) without advertising
     * those versions as {@code latest}.
     */
    private static void copyM2Trees(Path m2, Path sandboxCentral, int[] materialised) throws IOException {
        for (String tree : TREES) {
            Path m2Tree = m2.resolve(tree);
            if (!Files.isDirectory(m2Tree)) continue;
            PathUtil.forEachRegularFile(m2Tree, (file, attrs) -> {
                String name = file.getFileName().toString();
                if (!name.endsWith(".pom") && !name.endsWith(".jar")) return;
                Path target = sandboxCentral.resolve(m2.relativize(file).toString());
                if (Files.exists(target)) return;
                Linking.linkOrCopy(file, target);
                materialised[0]++;
            });
        }
    }

    /**
     * The bytes a Central-layout {@code relativePath} would fetch, read from {@code store}: the
     * artifact under {@code repos/central}, or the version list a seed wrote for the artifact whose
     * {@code maven-metadata.xml} is asked for. Empty for anything the store does not hold, so a
     * loopback stand-in for a repository manager can answer the JUnit Platform from disk and relay
     * only what it must.
     */
    public static Optional<byte[]> seeded(Path store, String relativePath) throws IOException {
        Path central = store.resolve("repos").resolve(RepositorySpec.CENTRAL);
        Path relative = Path.of(relativePath);
        Path body = central.resolve(relative).normalize();
        if (!body.startsWith(central)) return Optional.empty();
        if (Files.isRegularFile(body)) return Optional.of(Files.readAllBytes(body));
        Path artifactDir = relative.getParent();
        if (artifactDir == null
                || !"maven-metadata.xml".equals(relative.getFileName().toString())) {
            return Optional.empty();
        }
        Path list = store.resolve("metadata").resolve(metadataKey(artifactDir));
        return Files.isRegularFile(list) ? Optional.of(Files.readAllBytes(list)) : Optional.empty();
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
