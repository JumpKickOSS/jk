// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParser;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
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
import org.jspecify.annotations.Nullable;

/** Cache/store inventory for {@code jk cache usage}, {@code jk storage usage}, and {@code jk repo}. */
public final class CacheInventoryOps {

    private CacheInventoryOps() {}

    public record Request(
            @Nullable String query,
            @Nullable Path cache,
            @Nullable Path store,
            List<String> terms,
            List<String> coords,
            boolean dryRun) {}

    public static CacheInventoryAck run(Request req) throws IOException {
        String query = req.query() == null ? "" : req.query();
        Path cache = req.cache() != null ? req.cache() : JkDirs.cache();
        // Every store-tier query honors the client's store root; repos/ lives under the
        // STORE (production passes cas.root() — the store — to RepoArtifactStore), so the
        // repo queries must too: pointing them at <cache>/repos made jk repo search return
        // nothing and jk repo refresh never evict.
        Path store = req.store() != null ? req.store() : JkStores.store();
        return switch (query) {
            case "usage" -> cacheUsage(cache);
            case "store-usage" -> storeUsage(store);
            case "repo-search" -> repoSearch(store, req.terms() == null ? List.of() : req.terms());
            case "repo-refresh" -> repoRefresh(store, req.coords() == null ? List.of() : req.coords());
            case "wipe-store" -> wipeStore(store, req.dryRun());
            case "workers" -> workers(store);
            case "drop-workers" -> dropWorkers(store, req.dryRun());
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
        Path keysDir = ActionTree.KEYS.under(CacheTree.ACTIONS.under(cacheRoot));
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
                    // key would then count (or not) by directory-stream order.
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

        DiskUsage.Stats stamps = DiskUsage.of(CacheTree.FORMAT_STAMPS.under(cacheRoot));
        // Total is the action cache — the exact bytes the budget bounds. Every other tier under
        // this root is bounded on its own terms (see CacheTier), so folding them in would measure
        // one tier's usage against another tier's line; they are reported beside the total as
        // `stamps` and `derived`. Zinc analysis sits under actions/ and comes out for the same
        // reason: its own budget, its own row.
        DiskUsage.Stats[] budgeted =
                DiskUsage.exclusive(CacheTree.ACTIONS.under(cacheRoot), CacheTree.CACHE_CAS.under(cacheRoot));
        DiskUsage.Stats incremental = incrementalStats(CacheTree.ACTIONS.under(cacheRoot));
        DiskUsage.Stats derived = derivedStats(cacheRoot);
        List<String> stats = List.of(
                pack("classFiles", classFiles[0], classFiles[1]),
                pack("testResults", testResults[0], testResults[1]),
                pack("normalJars", normalJars[0], normalJars[1]),
                pack("shadowJars", shadowJars[0], shadowJars[1]),
                pack("minifiedJars", minifiedJars[0], minifiedJars[1]),
                pack("nativeBins", nativeBins[0], nativeBins[1]),
                pack("ociImages", ociImages[0], ociImages[1]),
                pack("incremental", incremental.files(), incremental.bytes()),
                pack("stamps", stamps.files(), stamps.bytes()),
                pack("derived", derived.files(), derived.bytes()));
        return CacheInventoryAck.usage(
                "usage",
                stats,
                Math.max(0L, DiskUsage.totalFiles(budgeted) - incremental.files()),
                Math.max(0L, DiskUsage.totalBytes(budgeted) - incremental.bytes()));
    }

    /**
     * The tiers with no row of their own: small derived caches bounded by count or supersession
     * rather than by the action budget. Read off {@link CacheTree} so the report cannot fall
     * behind the table.
     */
    private static DiskUsage.Stats derivedStats(Path cacheRoot) throws IOException {
        long files = 0;
        long bytes = 0;
        for (CacheTree tier : CacheTree.cached()) {
            if (tier == CacheTree.ACTIONS || tier == CacheTree.CACHE_CAS || tier == CacheTree.FORMAT_STAMPS) continue;
            DiskUsage.Stats stats = DiskUsage.of(tier.under(cacheRoot));
            files += stats.files();
            bytes += stats.bytes();
        }
        return new DiskUsage.Stats(files, bytes);
    }

    /** Zinc analysis trees under {@code actions/} — separately budgeted, so counted separately. */
    private static DiskUsage.Stats incrementalStats(Path actionsDir) throws IOException {
        long files = 0;
        long bytes = 0;
        for (Path dir : ActionTree.incrementalUnder(actionsDir)) {
            DiskUsage.Stats tree = DiskUsage.of(dir);
            files += tree.files();
            bytes += tree.bytes();
        }
        return new DiskUsage.Stats(files, bytes);
    }

    private static CacheInventoryAck storeUsage(Path storeRoot) throws IOException {
        Path storeCas = storeRoot.resolve("sha256");
        Path lib = storeRoot.resolve("lib");
        Path repos = storeRoot.resolve("repos");

        Set<Object> seen = new HashSet<>();
        DiskUsage.SameFileKeys sameFile = new DiskUsage.SameFileKeys();
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
                    Object key = identityKey(p, attrs, sameFile);
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

        Stat reposExtra = walkExclusiveAdding(repos, seen, sameFile);
        jarFiles += reposExtra.files;
        jarBytes += reposExtra.bytes;
        // One sameFile across all three walks, or a repos link to a CAS blob counts twice.
        Stat workers = walkExclusiveAdding(lib, seen, sameFile);
        long totalFiles = jarFiles + execFiles + ociFiles + workers.files;
        long totalBytes = jarBytes + execBytes + ociBytes + workers.bytes;
        DiskUsage.Stats mavenLocal;
        try {
            mavenLocal = DiskUsage.of(M2Dirs.localRepository());
        } catch (Exception e) {
            mavenLocal = new DiskUsage.Stats(0, 0);
        }
        List<String> stats = List.of(
                pack("jars", jarFiles, jarBytes),
                pack("executables", execFiles, execBytes),
                pack("oci", ociFiles, ociBytes),
                pack("workers", workers.files, workers.bytes),
                pack("maven-local", mavenLocal.files(), mavenLocal.bytes()));
        return CacheInventoryAck.usage("store-usage", stats, totalFiles, totalBytes);
    }

    private static CacheInventoryAck repoSearch(Path storeRoot, List<String> terms) {
        List<String> lower = terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        List<RepoArtifactStore.Module> hits = RepoArtifactStore.allModules(storeRoot).stream()
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

    private static CacheInventoryAck repoRefresh(Path storeRoot, List<String> coords) {
        Path reposRoot = storeRoot.resolve("repos");
        List<String> repoNames = repoNames(reposRoot);
        List<String> lines = new ArrayList<>();
        int evicted = 0;
        int missed = 0;
        for (String spec : coords) {
            Coordinate coord;
            try {
                coord = Coordinate.parse(spec);
            } catch (IllegalArgumentException e) {
                return CacheInventoryAck.error(Errors.text(e));
            }
            String relPath = MavenLayout.artifactPath(coord);
            List<String> hitRepos = new ArrayList<>();
            for (String repo : repoNames) {
                if (RepoArtifactStore.forRepoName(storeRoot, repo).evict(relPath)) hitRepos.add(repo);
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

    /**
     * Every first-party plugin worker the store holds (or a {@code -D} override names), with the
     * launch classpath the engine rebuilds from its POM — the same resolution a fork performs, so
     * what {@code jk doctor} prints is what the next {@code jk image} runs on. A worker whose
     * classpath does not resolve is still a row, carrying the error instead of a size.
     */
    private static CacheInventoryAck workers(Path storeRoot) {
        Cas cas = new Cas(storeRoot);
        List<String> lines = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (PluginJar worker : PluginJar.values()) {
            Path jar = worker.locateStored(cas);
            if (jar == null) continue;
            jar = jar.toAbsolutePath().normalize();
            String source = workerSource(worker, jar);
            Path pom = PomRuntimeClasspath.pomOf(jar);
            int declared = pom == null ? 0 : declaredRuntimeDeps(pom);
            List<Path> classpath;
            String error = "";
            try {
                classpath = WorkerLaunchClasspath.paths(jar);
            } catch (RuntimeException e) {
                classpath = List.of();
                error = Errors.text(e).replace('|', '/').replace('\n', ' ');
            }
            lines.add(String.join(
                    "|",
                    worker.artifactId(),
                    JkVersion.VERSION,
                    source,
                    jar.toString(),
                    pom == null ? "" : pom.toString(),
                    Integer.toString(declared),
                    Integer.toString(classpath.size()),
                    error));
            for (Path entry : classpath) entries.add(worker.artifactId() + "|" + entry);
        }
        return CacheInventoryAck.workers(lines, entries);
    }

    /** {@code override} for a {@code -D<jar property>} jar, else the {@code repos/<name>} the jar sits in. */
    private static String workerSource(PluginJar worker, Path jar) {
        String override = System.getProperty(worker.jarProperty());
        if (override != null && !override.isBlank()) return "override";
        Path cur = jar.getParent();
        while (cur != null && cur.getParent() != null) {
            Path parentName = cur.getParent().getFileName();
            if (parentName != null && "repos".equals(parentName.toString())) {
                return String.valueOf(cur.getFileName());
            }
            cur = cur.getParent();
        }
        return "path";
    }

    private static int declaredRuntimeDeps(Path pom) {
        try {
            int n = 0;
            for (Pom.Dep d : PomParser.parse(Files.readAllBytes(pom)).dependencies()) {
                String scope = d.scope();
                if (scope == null || scope.isBlank() || "compile".equals(scope) || "runtime".equals(scope)) n++;
            }
            return n;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Delete every installed version of every first-party plugin worker — jar, POM and memo
     * sidecars under {@code repos/jk-local}, {@code repos/jumpkick} and {@code repos/central} — and
     * forget the launch classpaths and POM models memoised from them. The closure jars stay: they
     * are shared with project resolution and the next fork re-walks them from the re-fetched
     * published POM. {@code dryRun} counts without deleting.
     */
    private static CacheInventoryAck dropWorkers(Path storeRoot, boolean dryRun) throws IOException {
        List<String> lines = new ArrayList<>();
        long files = 0;
        long bytes = 0;
        Path repos = storeRoot.resolve("repos");
        for (PluginJar worker : PluginJar.values()) {
            for (String repo :
                    List.of(RepoArtifactResolver.JK_LOCAL, RepositorySpec.JUMPKICK_NAME, RepositorySpec.CENTRAL)) {
                Path artifactDir = repos.resolve(repo)
                        .resolve(PluginJar.GROUP.replace('.', '/'))
                        .resolve(worker.artifactId());
                if (!Files.isDirectory(artifactDir)) continue;
                List<String> versions = new ArrayList<>();
                PathUtil.forEachChild(artifactDir, (child, attrs) -> {
                    if (attrs.isDirectory()) versions.add(String.valueOf(child.getFileName()));
                    return true;
                });
                versions.sort(null);
                DiskUsage.Stats stats = DiskUsage.of(artifactDir);
                files += stats.files();
                bytes += stats.bytes();
                for (String version : versions) lines.add(worker.artifactId() + "|" + version + "|" + repo);
                if (!dryRun) PathUtil.deleteRecursivelyOrThrow(artifactDir);
            }
        }
        if (!dryRun) {
            PomRuntimeClasspath.dropResolved();
            EffectivePomBuilder.clearProcessCache();
        }
        return CacheInventoryAck.droppedWorkers(lines, files, bytes);
    }

    /** Delete the store root; its writers recreate their own subtrees on demand. */
    private static CacheInventoryAck wipeStore(Path storeRoot, boolean dryRun) throws IOException {
        if (storeRoot == null || !Files.isDirectory(storeRoot)) return CacheInventoryAck.wipe(0, 0);
        // Unique-inode bytes (POSIX ino/dev or Windows fileKey) so leftover hard links under
        // sha256/ and repos/ are not counted twice.
        DiskUsage.Stats stats = DiskUsage.of(storeRoot);
        if (!dryRun) {
            // Exclusive against store writers: the boot-time warmup clones templates and fetches
            // plugin jars into this very store — on Windows a concurrent writer's open .put- temp
            // turns the delete into a sharing violation, and a write landing after the delete
            // recreates the store the nuke just reported gone.
            try (var held = StoreWriteGate.wipe()) {
                // Readers matter too, and a trainer is the reader closest to hand: its classpath is
                // store jars, it outlives the request that started it, and stopping the *engines*
                // never reached it. Kill and reap before deleting.
                //
                // Then delete more than once. Warmup decides to train, resolves a classpath, and
                // only later forks — so a trainer can appear after a quiesce that correctly found
                // none, and Windows fails the delete on the jar it just opened. The second pass
                // sees that fork registered and kills it, and hygiene has stood down by then
                // (StoreWriteGate.wipedSinceStart), so there is no third racer.
                List<Long> killed = new ArrayList<>();
                IOException last = null;
                for (int attempt = 0; attempt < WIPE_ATTEMPTS; attempt++) {
                    killed.addAll(PluginAot.quiesceTrainers(TRAINER_QUIESCE_MILLIS));
                    try {
                        PathUtil.deleteRecursivelyOrThrow(storeRoot);
                        last = null;
                        break;
                    } catch (IOException stillHeld) {
                        last = stillHeld;
                    }
                }
                if (last != null) throw new IOException(stillHeldMessage(last, killed), last);
            }
        }
        return CacheInventoryAck.wipe(stats.files(), stats.bytes());
    }

    /** Delete passes; see the wipe loop for why one is not enough. */
    private static final int WIPE_ATTEMPTS = 3;

    /** How long the wipe waits for killed trainers to actually exit before deleting. */
    private static final long TRAINER_QUIESCE_MILLIS = 10_000;

    /**
     * A locked-file failure that names what the wipe already ruled out. "Used by another process"
     * with only a path leaves the user hunting a JVM in Task Manager — the one thing this codebase
     * keeps promising not to make them do — so say which of our own processes were stopped first,
     * and thereby that the holder is something else.
     */
    private static String stillHeldMessage(IOException cause, List<Long> killedTrainers) {
        String base = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        if (killedTrainers.isEmpty()) {
            return base + " — the engines were stopped and no jk trainer was running, so the holder"
                    + " is a process outside jk (an editor, an antivirus scan, or a shell in that directory)";
        }
        return base + " — stopped " + killedTrainers.size() + " jk AOT trainer(s) (pid " + killedTrainers
                + ") first, so the holder is a process outside jk";
    }

    private static List<String> repoNames(Path reposRoot) {
        if (!Files.isDirectory(reposRoot)) {
            return List.of(RepositorySpec.CENTRAL, RepoArtifactResolver.JK_LOCAL);
        }
        try (var s = Files.list(reposRoot)) {
            List<String> names = s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            return names.isEmpty() ? List.of(RepositorySpec.CENTRAL, RepoArtifactResolver.JK_LOCAL) : names;
        } catch (IOException e) {
            return List.of(RepositorySpec.CENTRAL, RepoArtifactResolver.JK_LOCAL);
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

    private static long @Nullable [] bucketCounters(
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
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
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
            int be = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
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

    private static Stat walkExclusiveAdding(Path dir, Set<Object> seenKeys, DiskUsage.SameFileKeys sameFile)
            throws IOException {
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
                Object key = identityKey(p, attrs, sameFile);
                if (seenKeys.add(key)) bytes += attrs.size();
            }
        }
        return new Stat(files, bytes);
    }

    /**
     * Stable identity for hard-link dedupe: {@link BasicFileAttributes#fileKey()} where the
     * provider has one, else the {@link DiskUsage.SameFileKeys} stand-in.
     */
    private static Object identityKey(Path path, BasicFileAttributes attrs, DiskUsage.SameFileKeys sameFile) {
        Object key = attrs.fileKey();
        return key != null ? key : sameFile.identity(path, attrs.size());
    }
}
