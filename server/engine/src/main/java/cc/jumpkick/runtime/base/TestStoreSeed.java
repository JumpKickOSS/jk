// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.EmptyArchive;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Warms a sandbox with the JUnit Platform the developer's machine already holds, so a suite's
 * engine — nested or in-process — locks its fixtures' injected test roots without a repository in
 * reach.
 *
 * <p>Every fixture project a suite locks gets {@code junit-platform-launcher} — and, with no test
 * dependencies of its own, {@code junit-jupiter} — injected as {@code latest}. A floating selector
 * needs the repository's version list, so a cold store means one {@code maven-metadata.xml} fetch
 * from Central per cold engine, and a Central that is throttling this host turns every one of them
 * red at once. Central's artifacts live in the Maven local repository, so the seed copies the JUnit
 * Platform trees from this machine's local repositories ({@link M2Dirs#localRepository()}, then
 * {@code ~/.m2/repository}) into the sandbox's own, writes the memos that vouch for them under the
 * sandbox store's {@code repos/central}, and gives each artifact a version list naming exactly the
 * versions whose POM is there, written under the key the metadata cache reads for the Central URL.
 * A file is always copied, never linked: a sandbox runs arbitrary test code against its local
 * repository, and a write through any name of a shared inode would land in the developer's.
 * Only a file whose {@code _remote.repositories} says Central served it: a local repository holds
 * whatever any repository answered under a coordinate — a test's stub server, an {@code mvn
 * install} — and those bytes would be pinned as Central's. A body the sandbox already holds is left
 * alone — an index the store fetched itself outranks a synthesised one — and nothing here is
 * fetched, so the seed is a warmth, never a network cost.
 *
 * <p>Only those trees, on purpose. The rest of what a fixture may need — worker POM graphs, the
 * compilers — is exact-pinned and fetched once into the warm sandbox.
 */
public final class TestStoreSeed {

    /** The trees seeded and given version lists: the JUnit Platform closure, as Maven-layout paths. */
    static final List<String> TREES = List.of("org/junit", "org/opentest4j", "org/apiguardian", "org/jspecify");

    /** Central's base, as {@code MavenRepo} resolves a metadata path against it. */
    static final String CENTRAL_BASE = RepositorySpec.MAVEN_CENTRAL.url().toString();

    private TestStoreSeed() {}

    /** The injected test roots a fixture lock floats to {@code latest}, as Maven-layout paths. */
    static final List<String> TEST_ROOTS =
            List.of("org/junit/platform/junit-platform-launcher", "org/junit/jupiter/junit-jupiter");

    /**
     * Whether the Maven local repository holds a POM for some version of every injected test root:
     * what the seed needs to write their version lists. A repository filled from a lock alone holds
     * the jars only.
     */
    public static boolean holdsTestRoots() {
        Path central = M2Dirs.localRepository();
        for (String root : TEST_ROOTS) {
            Path artifact = central.resolve(root);
            String name = artifact.getFileName().toString();
            boolean[] found = {false};
            try {
                PathUtil.forEachChild(artifact, (v, attrs) -> {
                    found[0] = Files.isRegularFile(v.resolve(name + "-" + v.getFileName() + ".pom"));
                    return !found[0];
                });
            } catch (IOException e) {
                return false;
            }
            if (!found[0]) return false;
        }
        return true;
    }

    /**
     * Seed {@code sandboxStore} and this process's own Maven local repository
     * ({@link M2Dirs#localRepository()}, the one a test JVM's {@code JK_M2_LOCAL} names). Returns
     * how many files were materialised.
     */
    public static int seed(Path sandboxStore) throws IOException {
        return seed(sandboxStore, M2Dirs.localRepository());
    }

    /**
     * Seed {@code sandboxStore} and {@code sandboxM2} — the local repository the sandbox's JVMs will
     * run with — from every other local repository on this machine that holds Central's JUnit
     * Platform: this process's own, then {@code ~/.m2/repository}.
     */
    public static int seed(Path sandboxStore, Path sandboxM2) throws IOException {
        Path target = sandboxM2.toAbsolutePath().normalize();
        int[] materialised = {0};
        for (Path source : sources(target)) {
            if (Files.isDirectory(source)) copyTrees(source, target, materialised);
        }
        Path memos = sandboxStore.resolve("repos").resolve(RepositorySpec.CENTRAL);
        Map<Path, TreeSet<String>> pomVersions = new LinkedHashMap<>();
        for (String tree : TREES) {
            if (!Files.isDirectory(target.resolve(tree))) continue;
            PathUtil.forEachRegularFile(target.resolve(tree), (file, attrs) -> {
                String name = file.getFileName().toString();
                boolean pom = name.endsWith(".pom");
                if (!pom && !name.endsWith(".jar")) return;
                if (!centralServed(file) || EmptyArchive.is(file)) return;
                Path version = file.getParent();
                Path artifact = version == null ? null : version.getParent();
                if (version == null || artifact == null) return;
                if (pom) {
                    pomVersions
                            .computeIfAbsent(target.relativize(artifact), a -> new TreeSet<>(Versions::compare))
                            .add(version.getFileName().toString());
                }
                if (vouch(memos, target.relativize(file).toString(), file)) materialised[0]++;
            });
        }
        for (Map.Entry<Path, TreeSet<String>> e : pomVersions.entrySet()) {
            if (writeVersionList(sandboxStore, e.getKey(), e.getValue())) materialised[0]++;
        }
        return materialised[0];
    }

    /** The local repositories to copy from: this process's own, then the user's, never {@code target}. */
    private static List<Path> sources(Path target) {
        List<Path> out = new ArrayList<>();
        Path own = M2Dirs.localRepository().toAbsolutePath().normalize();
        Path user = Path.of(System.getProperty("user.home"), ".m2", "repository")
                .toAbsolutePath()
                .normalize();
        for (Path candidate : List.of(own, user)) {
            if (!candidate.equals(target) && !out.contains(candidate)) out.add(candidate);
        }
        return out;
    }

    /**
     * POMs and jars under the JUnit trees in {@code source} that Central served, copied into
     * {@code target} where the slot is empty or holds a hollow stand-in.
     */
    private static void copyTrees(Path source, Path target, int[] materialised) throws IOException {
        for (String tree : TREES) {
            Path sourceTree = source.resolve(tree);
            if (!Files.isDirectory(sourceTree)) continue;
            PathUtil.forEachRegularFile(sourceTree, (file, attrs) -> {
                String name = file.getFileName().toString();
                if (!name.endsWith(".pom") && !name.endsWith(".jar")) return;
                if (!centralServed(file) || EmptyArchive.is(file)) return;
                Path destination = target.resolve(source.relativize(file).toString());
                if (!seedable(destination)) return;
                copyInto(file, destination);
                copyInto(
                        file.resolveSibling("_remote.repositories"),
                        destination.resolveSibling("_remote.repositories"));
                materialised[0]++;
            });
        }
    }

    /** True when a memo now vouches for {@code file}: written when absent or no longer describing it. */
    private static boolean vouch(Path memoTree, String relative, Path file) throws IOException {
        Path memo = ArtifactMemo.jkPath(memoTree, relative);
        Optional<ArtifactMemo> held = ArtifactMemo.read(memo);
        if (held.isPresent() && held.get().describes(file)) return false;
        ArtifactMemo.ofBlob(file, RepoArtifactStore.inferGav(relative), Hashing.sha256Hex(file))
                .write(memo);
        return true;
    }

    /**
     * Replace {@code target} with a copy of {@code source} through a temp sibling and an atomic
     * move, so no other name of the file {@code target} named sees the write. Timestamps are kept.
     */
    static void copyInto(Path source, Path target) throws IOException {
        Path dir = Objects.requireNonNull(target.getParent(), "a file has a directory");
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".part");
        boolean moved = false;
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            AtomicWrites.moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /** A slot the seed may fill: empty, or holding a hollow stand-in that the real file replaces. */
    private static boolean seedable(Path target) throws IOException {
        if (!Files.exists(target)) return true;
        if (!EmptyArchive.is(target)) return false;
        Files.deleteIfExists(target);
        return true;
    }

    /**
     * True when the {@code _remote.repositories} beside {@code file} carries the line {@code <file
     * name>>central=}, the way Maven Resolver and jk's own download record which repository
     * answered, or carries no line for the file while every line it does carry names Central. No
     * hint, an empty one, or another repository's id: not Central's bytes.
     */
    static boolean centralServed(Path file) {
        Path dir = file.getParent();
        Path name = file.getFileName();
        if (dir == null || name == null) return false;
        Path hint = dir.resolve("_remote.repositories");
        if (!Files.isRegularFile(hint)) return false;
        String own = name + ">";
        String central = ">" + RepositorySpec.CENTRAL + "=";
        boolean anyLine = false;
        boolean allCentral = true;
        try {
            for (String raw : Files.readAllLines(hint)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith(own)) return line.equals(own + RepositorySpec.CENTRAL + "=");
                anyLine = true;
                allCentral &= line.endsWith(central);
            }
        } catch (IOException e) {
            return false;
        }
        // No line of its own: a version directory every recorded file of which Central served
        // vouches for its siblings — Maven fetches one version's files from one repository, and a
        // hint that lost a line is the common way a POM ends up unrecorded.
        return anyLine && allCentral;
    }

    /**
     * The bytes a Central-layout {@code relativePath} would fetch, read from the local repository
     * Central's artifacts live in, or from {@code store}: the version list a seed wrote for the artifact whose
     * {@code maven-metadata.xml} is asked for. Empty for anything the store does not hold, so a
     * loopback stand-in for a repository manager can answer the JUnit Platform from disk and relay
     * only what it must.
     */
    public static Optional<byte[]> seeded(Path store, String relativePath) throws IOException {
        Path central = M2Dirs.localRepository().toAbsolutePath().normalize();
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

    /**
     * The comment a synthesised version list carries, so a later seed can tell it from an index
     * the store fetched itself: the store's own fetch is left alone; a synthesised list is
     * rewritten whenever the host's versions differ from what it names, so a warm slot can lock a
     * version the host store gained after the slot was first seeded.
     */
    static final String SEED_MARK = "<!-- synthesised by the jk test-store seed; the store's own fetch replaces it -->";

    /**
     * The metadata body for {@code artifactDir} under the sandbox: written when absent, rewritten
     * when it is a synthesised list that no longer names the host's versions, left alone when the
     * store fetched it itself. True when a body was written.
     */
    private static boolean writeVersionList(Path sandbox, Path artifactDir, TreeSet<String> versions)
            throws IOException {
        Path body = sandbox.resolve("metadata").resolve(metadataKey(artifactDir));
        String xml = versionList(artifactDir, versions);
        if (Files.exists(body)) {
            String current = Files.readString(body);
            if (!current.contains(SEED_MARK) || current.equals(xml)) return false;
        }
        Files.createDirectories(body.getParent());
        Files.writeString(body, xml);
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
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append(SEED_MARK)
                .append("\n<metadata>\n");
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
