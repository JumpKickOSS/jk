// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.protocol.CacheInventoryAck;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Cache/store inventory for {@code jk cache usage}, {@code jk storage usage}, and {@code jk repo}. */
public final class CacheInventoryOps {

    private CacheInventoryOps() {}

    public record Request(
            String query, Path cache, Path store, List<String> terms, List<String> coords, boolean dryRun) {}

    public static CacheInventoryAck run(Request req) throws IOException {
        String query = req.query() == null ? "" : req.query();
        Path cache = req.cache() != null ? req.cache() : JkDirs.cache();
        Path store = req.store() != null ? req.store() : JkStores.store();
        return switch (query) {
            case "usage" -> cacheUsage(cache);
            case "store-usage" -> storeUsage(cache, req.store());
            case "repo-search" -> repoSearch(cache, req.terms() == null ? List.of() : req.terms());
            case "repo-refresh" -> repoRefresh(cache, req.coords() == null ? List.of() : req.coords());
            case "wipe-store" -> wipeStore(store, req.dryRun());
            default -> CacheInventoryAck.error("unknown cache inventory query: " + query);
        };
    }

    private static CacheInventoryAck cacheUsage(Path cacheRoot) throws IOException {
        long[] classFiles = {0, 0};
        long[] testResults = {0, 0};
        long[] normalJars = {0, 0};
        long[] shadowJars = {0, 0};
        long[] minifiedJars = {0, 0};
        long[] nativeBins = {0, 0};
        long[] ociImages = {0, 0};

        Cas cas = new Cas(cacheRoot);
        Set<String> seenShas = new HashSet<>();
        Path keysDir = cacheRoot.resolve("actions").resolve("keys");
        if (Files.isDirectory(keysDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(keysDir)) {
                for (Path keyFile : stream) {
                    if (!Files.isRegularFile(keyFile)) continue;
                    String body;
                    try {
                        body = Files.readString(keyFile, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    String taskName = taskNameFromKeyBody(body);
                    long[] bucket = bucketCounters(
                            taskName,
                            classFiles,
                            testResults,
                            normalJars,
                            shadowJars,
                            minifiedJars,
                            nativeBins,
                            ociImages);
                    // Unbucketed keys must not consume seenShas: a blob shared with a bucketed
                    // key would then count (or not) by directory-stream order (JK-2161).
                    if (bucket == null) continue;
                    if (bucket == testResults) {
                        testResults[0]++;
                        try {
                            testResults[1] += Files.size(keyFile);
                        } catch (IOException ignored) {
                        }
                    }
                    for (String sha : outputShasFromKeyBody(body)) {
                        if (!seenShas.add(sha)) continue;
                        Path blob = cas.pathFor(sha);
                        if (!Files.isRegularFile(blob)) continue;
                        bucket[0]++;
                        try {
                            bucket[1] += Files.size(blob);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        DiskUsage.Stats eventLogs = DiskUsage.of(cacheRoot.resolve("runs"));
        DiskUsage.Stats stamps = DiskUsage.of(cacheRoot.resolve("format-stamps"));
        DiskUsage.Stats total = DiskUsage.of(cacheRoot);
        List<String> stats = List.of(
                pack("classFiles", classFiles[0], classFiles[1]),
                pack("testResults", testResults[0], testResults[1]),
                pack("eventLogs", eventLogs.files(), eventLogs.bytes()),
                pack("normalJars", normalJars[0], normalJars[1]),
                pack("shadowJars", shadowJars[0], shadowJars[1]),
                pack("minifiedJars", minifiedJars[0], minifiedJars[1]),
                pack("nativeBins", nativeBins[0], nativeBins[1]),
                pack("ociImages", ociImages[0], ociImages[1]),
                pack("stamps", stamps.files(), stamps.bytes()));
        return CacheInventoryAck.usage("usage", stats, total.files(), total.bytes());
    }

    private static CacheInventoryAck storeUsage(Path cacheRoot, Path requestedStore) throws IOException {
        // Honor the client's store root exactly like wipe-store does — the client resolves
        // JK_STORE_DIR from ITS environment, and usage must count the same tree nuke would
        // wipe (JK-2161).
        Path storeRoot = requestedStore != null ? requestedStore : JkStores.storeRootFor(cacheRoot);
        Path storeCas = storeRoot.resolve("sha256");
        Path lib = storeRoot.resolve("lib");
        Path repos = storeRoot.resolve("repos");

        Set<Object> seen = new HashSet<>();
        long jarFiles = 0, jarBytes = 0;
        long execFiles = 0, execBytes = 0;
        long ociFiles = 0, ociBytes = 0;

        if (Files.isDirectory(storeCas)) {
            try (var walk = Files.walk(storeCas)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    if (!attrs.isRegularFile()) continue;
                    Object key = attrs.fileKey();
                    if (key == null) key = p.toAbsolutePath().normalize();
                    long size = seen.add(key) ? attrs.size() : 0L;
                    switch (sniffArtifactKind(p)) {
                        case EXECUTABLE -> {
                            execFiles++;
                            execBytes += size;
                        }
                        case OCI -> {
                            ociFiles++;
                            ociBytes += size;
                        }
                        case JAR, OTHER -> {
                            jarFiles++;
                            jarBytes += size;
                        }
                    }
                }
            }
        }

        Stat reposExtra = walkExclusiveAdding(repos, seen);
        jarFiles += reposExtra.files;
        jarBytes += reposExtra.bytes;
        Stat workers = walkExclusiveAdding(lib, seen);
        long totalFiles = jarFiles + execFiles + ociFiles + workers.files;
        long totalBytes = jarBytes + execBytes + ociBytes + workers.bytes;
        List<String> stats = List.of(
                pack("jars", jarFiles, jarBytes),
                pack("executables", execFiles, execBytes),
                pack("oci", ociFiles, ociBytes),
                pack("workers", workers.files, workers.bytes));
        return CacheInventoryAck.usage("store-usage", stats, totalFiles, totalBytes);
    }

    private static CacheInventoryAck repoSearch(Path cacheRoot, List<String> terms) {
        List<String> lower = terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        List<RepoArtifactStore.Module> hits = RepoArtifactStore.allModules(cacheRoot).stream()
                .filter(m -> allMatch(
                        lower, m.group().toLowerCase(Locale.ROOT), m.artifact().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(RepoArtifactStore.Module::moduleKey))
                .toList();
        List<String> entries = new ArrayList<>();
        for (RepoArtifactStore.Module m : hits) {
            List<String> versions = new ArrayList<>(m.versions());
            versions.sort((a, b) -> Versions.compare(b, a));
            entries.add(m.group() + "|" + m.artifact() + "|" + String.join(",", versions));
        }
        return CacheInventoryAck.repoSearch(entries);
    }

    private static CacheInventoryAck repoRefresh(Path cacheRoot, List<String> coords) {
        Path reposRoot = JkStores.storeRootFor(cacheRoot).resolve("repos");
        List<String> repoNames = repoNames(reposRoot);
        List<String> lines = new ArrayList<>();
        int evicted = 0;
        int missed = 0;
        for (String spec : coords) {
            Coordinate coord;
            try {
                coord = Coordinate.parse(spec);
            } catch (IllegalArgumentException e) {
                return CacheInventoryAck.error(String.valueOf(e.getMessage()));
            }
            String relPath = MavenLayout.artifactPath(coord);
            List<String> hitRepos = new ArrayList<>();
            for (String repo : repoNames) {
                if (RepoArtifactStore.forRepoName(cacheRoot, repo).evict(relPath)) hitRepos.add(repo);
            }
            String packed =
                    coord.group() + "|" + coord.artifact() + "|" + coord.version() + "|" + String.join(",", hitRepos);
            if (hitRepos.isEmpty()) {
                missed++;
            } else {
                evicted++;
            }
            lines.add(packed);
        }
        return CacheInventoryAck.repoRefresh(lines, evicted, missed);
    }

    private static CacheInventoryAck wipeStore(Path storeRoot, boolean dryRun) throws IOException {
        long[] stats = {0L, 0L};
        if (storeRoot == null || !Files.isDirectory(storeRoot)) return CacheInventoryAck.wipe(0, 0);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storeRoot)) {
            for (Path child : stream) {
                countTree(child, stats);
                if (!dryRun) PathUtil.deleteRecursivelyOrThrow(child);
            }
        }
        return CacheInventoryAck.wipe(stats[0], stats[1]);
    }

    private static void countTree(Path root, long[] stats) {
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                stats[0]++;
                try {
                    stats[1] += Files.size(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static List<String> repoNames(Path reposRoot) {
        if (!Files.isDirectory(reposRoot)) return List.of("central", "local");
        try (var s = Files.list(reposRoot)) {
            List<String> names = s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            return names.isEmpty() ? List.of("central", "local") : names;
        } catch (IOException e) {
            return List.of("central", "local");
        }
    }

    private static String pack(String name, long files, long bytes) {
        return name + "|" + files + "|" + bytes;
    }

    private static boolean allMatch(List<String> terms, String... fields) {
        for (String t : terms) {
            boolean found = false;
            for (String f : fields) {
                if (f.contains(t)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String taskNameFromKeyBody(String body) {
        for (String line : body.split("\n")) {
            if (!line.startsWith("TASK ")) continue;
            String id = line.substring("TASK ".length()).trim();
            int at = id.indexOf('@');
            String name = at < 0 ? id : id.substring(0, at);
            return name.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static List<String> outputShasFromKeyBody(String body) {
        List<String> shas = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.startsWith("OUTPUT ")) continue;
            String rest = line.substring("OUTPUT ".length()).trim();
            int sp = rest.indexOf(' ');
            String maybe = sp < 0 ? rest : rest.substring(0, sp);
            if (maybe.length() == 64 && isHex(maybe)) shas.add(maybe.toLowerCase(Locale.ROOT));
        }
        return shas;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private static long[] bucketCounters(
            String taskName,
            long[] classFiles,
            long[] testResults,
            long[] normalJars,
            long[] shadowJars,
            long[] minifiedJars,
            long[] nativeBins,
            long[] ociImages) {
        if (taskName.isEmpty()) return null;
        if (TaskNames.RUN_TESTS.equals(taskName)) return testResults;
        if (TaskNames.PACKAGE_JAR.equals(taskName)) return normalJars;
        if (TaskNames.PACKAGE_ASSEMBLY.equals(taskName)) return shadowJars;
        if (TaskNames.PACKAGE_MINIFIED.equals(taskName)) return minifiedJars;
        if (TaskNames.NATIVE_IMAGE.equals(taskName)) return nativeBins;
        if (TaskNames.WRITE_IMAGE.equals(taskName)) return ociImages;
        if (taskName.startsWith("compile-") || TaskNames.ASSEMBLE_CLASSES.equals(taskName)) return classFiles;
        return null;
    }

    private enum ArtifactKind {
        JAR,
        EXECUTABLE,
        OCI,
        OTHER
    }

    private static ArtifactKind sniffArtifactKind(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase() : "";
        if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war") || name.endsWith(".ear")) {
            return ArtifactKind.JAR;
        }
        if (name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".oci")) {
            return ArtifactKind.OCI;
        }
        // One read covers both probes: bytes 0-7 for the magic, 257-261 for the ustar tag —
        // the store walk opens every extensionless file, so one syscall per file, not two.
        byte[] head;
        try (var in = Files.newInputStream(file)) {
            head = in.readNBytes(262);
        } catch (IOException ignored) {
            return ArtifactKind.OTHER;
        }
        if (head.length >= 4
                && head[0] == 'P'
                && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)
                && (head[3] == 4 || head[3] == 6 || head[3] == 8)) {
            return ArtifactKind.JAR;
        }
        if (head.length >= 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
            return ArtifactKind.EXECUTABLE;
        }
        if (head.length >= 4) {
            int be = ((head[0] & 0xff) << 24)
                    | ((head[1] & 0xff) << 16)
                    | ((head[2] & 0xff) << 8)
                    | (head[3] & 0xff);
            if (be == 0xFEEDFACE || be == 0xFEEDFACF || be == 0xCAFEBABE || be == 0xCFFAEDFE || be == 0xCEFAEDFE) {
                return ArtifactKind.EXECUTABLE;
            }
        }
        if (head.length == 262
                && head[257] == 'u'
                && head[258] == 's'
                && head[259] == 't'
                && head[260] == 'a'
                && head[261] == 'r') {
            return ArtifactKind.OCI;
        }
        return ArtifactKind.OTHER;
    }

    private record Stat(long files, long bytes) {}

    private static Stat walkExclusiveAdding(Path dir, Set<Object> seenKeys) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return new Stat(0, 0);
        long files = 0;
        long bytes = 0;
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException unreadable) {
                    continue;
                }
                if (!attrs.isRegularFile()) continue;
                files++;
                Object key = attrs.fileKey();
                if (key == null) key = p.toAbsolutePath().normalize();
                if (seenKeys.add(key)) bytes += attrs.size();
            }
        }
        return new Stat(files, bytes);
    }
}
