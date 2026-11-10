// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.config.DiskPressure;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Garbage collection of the artifact store's CAS, which has no other collector.
 *
 * <p>What the store CAS holds is mostly what installs leave behind: every install shelves each
 * first-party jar, the engine jar and the native client, and an engine is pinned to one install's
 * set by its {@link ShelfManifest}. The store keeps the current install and the one before it, for
 * a rollback; every older install's set is garbage the moment the next lands, and each is 55 MB or
 * more. A blob is therefore <em>pinned</em> when the current or previous install's manifest or
 * engine pointer names it, a {@link PreparedUpdate} waits to install it, or it is the client on
 * {@code PATH}; every other blob is on a countdown from its last use, set by what it is and how
 * big, shortened by disk pressure:
 *
 * <ul>
 *   <li>an installed jar (an engine jar, a first-party worker jar) no kept install names belongs to
 *       a retired install, so it goes as soon as {@link Policy#minAge} allows;
 *   <li>native binaries are heavy, so 12 hours from last use, the newest one kept for a rollback;
 *   <li>large jars a week;
 *   <li>other jars and everything else weeks, since a miss costs a copy from the Maven local
 *       repository or one download.
 * </ul>
 *
 * <p>A blob younger than {@link Policy#minAge} is never touched: its writer may not have recorded it
 * anywhere yet.
 */
public final class StoreCasGc {

    private StoreCasGc() {}

    /** What a blob is, which decides how long it is worth keeping. */
    public enum Kind {
        /** An executable: Mach-O, ELF or PE. */
        NATIVE,
        /**
         * A jar an install shelved: an engine jar ({@code Main-Class} {@code EngineMain}) or a
         * first-party worker jar ({@code PluginMain}, every class under {@code cc/jumpkick/}). A
         * third-party plugin runs under the same host but keeps its classes in its own package.
         */
        INSTALLED_JAR,
        /** A zip over {@link Policy#largeBytes}. */
        LARGE_JAR,
        /** Any other zip. */
        JAR,
        /** Anything else. */
        OTHER
    }

    /**
     * The retention windows. {@code pressure} is {@code DiskPressure.factor}'s multiplier, applied to
     * every window; {@code keepNative} is how many of the newest unpinned natives are kept regardless.
     */
    public record Policy(
            Duration nativeTtl,
            int keepNative,
            Duration installedJarTtl,
            Duration largeJarTtl,
            long largeBytes,
            Duration jarTtl,
            Duration otherTtl,
            Duration minAge,
            double pressure,
            long budgetBytes) {

        public static Policy defaults(double pressure) {
            return new Policy(
                    Duration.ofHours(12),
                    1,
                    Duration.ofHours(1),
                    Duration.ofDays(7),
                    50L * 1024 * 1024,
                    Duration.ofDays(21),
                    Duration.ofDays(30),
                    Duration.ofHours(1),
                    pressure,
                    Long.MAX_VALUE);
        }

        /** This policy keeping the newest {@code count} unpinned natives. */
        public Policy withKeepNative(int count) {
            return new Policy(
                    nativeTtl,
                    count,
                    installedJarTtl,
                    largeJarTtl,
                    largeBytes,
                    jarTtl,
                    otherTtl,
                    minAge,
                    pressure,
                    budgetBytes);
        }

        /** This policy holding the store to {@code bytes}: past it, whatever has the least life left goes first. */
        public Policy withBudget(long bytes) {
            return new Policy(
                    nativeTtl,
                    keepNative,
                    installedJarTtl,
                    largeJarTtl,
                    largeBytes,
                    jarTtl,
                    otherTtl,
                    minAge,
                    pressure,
                    bytes);
        }

        /**
         * How much longer a blob used on {@code days} distinct days lives: 1.5x for two, 2x for four,
         * doubling again per doubling of days, to a cap that is lower for the kinds that are dear to hold.
         */
        double reuseBonus(Kind kind, int days) {
            if (days <= 1 || kind == Kind.INSTALLED_JAR) return 1.0;
            double cap = kind == Kind.NATIVE || kind == Kind.LARGE_JAR ? 2.0 : 4.0;
            return Math.min(cap, 1.0 + 0.5 * (Math.log(days) / Math.log(2)));
        }

        Duration ttl(Kind kind, int days) {
            Duration base =
                    switch (kind) {
                        case NATIVE -> nativeTtl;
                        case INSTALLED_JAR -> installedJarTtl;
                        case LARGE_JAR -> largeJarTtl;
                        case JAR -> jarTtl;
                        case OTHER -> otherTtl;
                    };
            long millis = (long) (base.toMillis() * reuseBonus(kind, days) * pressure);
            return Duration.ofMillis(Math.max(millis, minAge.toMillis()));
        }
    }

    /** What a pass found: blobs removed (or that would be), their bytes, and those it kept. */
    public record Report(int deleted, long freedBytes, int kept, int pinned) {}

    /** The blobs an installed engine or the client on {@code PATH} still depends on, as lower-case sha256 hex. */
    public static Set<String> pinnedShas() {
        Set<String> pinned = new HashSet<>();
        EngineInstall install = EngineInstall.current();
        for (Path shelf : List.of(install.shelfFile(), install.previousShelfFile())) {
            ShelfManifest.read(shelf).ifPresent(m -> {
                pinned.add(m.engineSha256());
                pinned.addAll(m.jars().values());
                pinned.addAll(m.poms().values());
            });
        }
        pinned.addAll(install.pinnedEngineShas());
        PreparedUpdate.read(install.engineHome()).ifPresent(p -> {
            pinned.add(p.engineSha256());
            pinned.add(p.clientSha256());
        });
        Path bin = JkDirs.binDir();
        for (String name : List.of("jk", "jk.exe", "jkx", "jkx.exe")) {
            Path client = bin.resolve(name);
            if (Files.isRegularFile(client)) {
                try {
                    pinned.add(Hashing.sha256Hex(client));
                } catch (IOException unreadable) {
                    // an unreadable client pins nothing it could be compared with
                }
            }
        }
        return pinned;
    }

    /** What {@code blob} is, from its first bytes and its size. */
    static Kind classify(Path blob, long size, long largeBytes) {
        byte[] head = new byte[4];
        int n;
        try (InputStream in = Files.newInputStream(blob)) {
            n = in.readNBytes(head, 0, 4);
        } catch (IOException e) {
            return Kind.OTHER;
        }
        if (n >= 4) {
            int magic = (head[0] & 0xff) << 24 | (head[1] & 0xff) << 16 | (head[2] & 0xff) << 8 | (head[3] & 0xff);
            boolean macho = magic == 0xCFFAEDFE || magic == 0xCEFAEDFE || magic == 0xFEEDFACF || magic == 0xFEEDFACE;
            // 0xCAFEBABE is both a fat Mach-O and a class file; a class file is small, a fat binary is not.
            boolean fat = magic == 0xCAFEBABE && size > 1_000_000;
            boolean elf = magic == 0x7F454C46;
            if (macho || fat || elf) return Kind.NATIVE;
        }
        if (n >= 2 && head[0] == 'M' && head[1] == 'Z') return Kind.NATIVE;
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') {
            if (installedJar(blob)) return Kind.INSTALLED_JAR;
            return size >= largeBytes ? Kind.LARGE_JAR : Kind.JAR;
        }
        return Kind.OTHER;
    }

    private static final String ENGINE_MAIN = "cc.jumpkick.engine.EngineMain";
    private static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    /** True when {@code blob} is a jar an install shelved: see {@link Kind#INSTALLED_JAR}. */
    static boolean installedJar(Path blob) {
        try (ZipFile zip = new ZipFile(blob.toFile())) {
            Manifest manifest = manifestOf(zip);
            String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            if (ENGINE_MAIN.equals(main)) return true;
            if (!WORKER_MAIN.equals(main)) return false;
            boolean anyClass = false;
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                if (!name.startsWith("cc/jumpkick/")) return false;
                anyClass = true;
            }
            return anyClass;
        } catch (IOException | RuntimeException notAJar) {
            return false;
        }
    }

    private static @Nullable Manifest manifestOf(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new Manifest(in);
        }
    }

    /**
     * One pass over {@code storeCas}: every unpinned blob past its window goes, except the newest
     * {@link Policy#keepNative()} natives. {@code dryRun} reports without deleting.
     */
    public static Report run(
            Cas storeCas,
            Set<String> pinned,
            Map<String, AccessLedger.Use> uses,
            Policy policy,
            long nowMillis,
            boolean dryRun)
            throws IOException {
        return run(storeCas, pinned, uses, policy, nowMillis, dryRun, SafePoint.NONE);
    }

    /**
     * As {@link #run(Cas, Set, Map, Policy, long, boolean)}, asking {@code safePoint} before each
     * file of the scan (which reads every unpinned blob's head) and before each blob it deletes.
     * Blobs here are independent of one another, so a stop between two leaves the store whole.
     */
    public static Report run(
            Cas storeCas,
            Set<String> pinned,
            Map<String, AccessLedger.Use> uses,
            Policy policy,
            long nowMillis,
            boolean dryRun,
            SafePoint safePoint)
            throws IOException {
        Path shaRoot = storeCas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) return new Report(0, 0L, 0, 0);

        record Blob(Path file, String hex, Kind kind, long size, long usedAt, int days) {
            long age(long now) {
                return now - usedAt;
            }
        }
        List<Blob> unpinned = new ArrayList<>();
        int[] pinnedCount = {0};
        long[] total = {0};
        PathUtil.forEachRegularFile(shaRoot, (file, attrs) -> {
            safePoint.check();
            if (file.getFileName().toString().startsWith(".put-")) return;
            Optional<String> hex = storeCas.hashFromPath(file);
            if (hex.isEmpty()) return;
            total[0] += attrs.size();
            if (pinned.contains(hex.get())) {
                pinnedCount[0]++;
                return;
            }
            long size = attrs.size();
            AccessLedger.Use use = uses.get(key(hex.get()));
            long mtime = attrs.lastModifiedTime().toMillis();
            unpinned.add(new Blob(
                    file,
                    hex.get(),
                    classify(file, size, policy.largeBytes()),
                    size,
                    use == null ? mtime : Math.max(mtime, use.lastMillis()),
                    use == null ? 1 : use.days()));
        });

        Set<Path> keep = new HashSet<>();
        unpinned.stream()
                .filter(b -> b.kind() == Kind.NATIVE)
                .sorted(Comparator.comparingLong(Blob::usedAt).reversed())
                .limit(policy.keepNative())
                .forEach(b -> keep.add(b.file()));

        int deleted = 0;
        long freed = 0;
        List<Blob> survivors = new ArrayList<>();
        for (Blob blob : unpinned) {
            long age = blob.age(nowMillis);
            boolean expired = age >= policy.ttl(blob.kind(), blob.days()).toMillis();
            if (!expired || keep.contains(blob.file()) || age < policy.minAge().toMillis()) {
                survivors.add(blob);
                continue;
            }
            safePoint.check();
            if (!dryRun) Files.deleteIfExists(blob.file());
            deleted++;
            freed += blob.size();
            total[0] -= blob.size();
        }
        // Over budget: the blob with the least countdown left goes first, whatever its kind.
        if (total[0] > policy.budgetBytes()) {
            survivors.sort(
                    Comparator.comparingLong(b -> policy.ttl(b.kind(), b.days()).toMillis() - b.age(nowMillis)));
            List<Blob> remaining = new ArrayList<>(survivors);
            for (Blob blob : survivors) {
                if (total[0] <= policy.budgetBytes()) break;
                if (keep.contains(blob.file())
                        || blob.age(nowMillis) < policy.minAge().toMillis()) continue;
                safePoint.check();
                if (!dryRun) Files.deleteIfExists(blob.file());
                remaining.remove(blob);
                deleted++;
                freed += blob.size();
                total[0] -= blob.size();
            }
            survivors = remaining;
        }
        return new Report(deleted, freed, survivors.size(), pinnedCount[0]);
    }

    /**
     * The pass an install runs once it has landed: the store as the cache prune collects it, so the
     * install it replaced two back goes now rather than at the next weekly prune. Best-effort; a
     * failure leaves the store as it was.
     */
    public static Report afterInstall() {
        return pass(JkStores.store(), false);
    }

    /**
     * What the next pass over the store at {@code storeRoot} would reclaim, removing nothing: the
     * blobs of retired installs and every other blob past its window, as {@code jk storage usage}
     * reports them.
     */
    public static Report preview(Path storeRoot) {
        return pass(storeRoot, true);
    }

    private static Report pass(Path storeRoot, boolean dryRun) {
        try {
            JkCacheConfig.DiskSpace space = JkCacheConfig.DiskSpace.probe(storeRoot);
            Policy policy = Policy.defaults(DiskPressure.factor(space)).withBudget(DiskPressure.budgetBytes(space));
            AccessLedger ledger = AccessLedger.at(storeRoot.resolve(".access"));
            return run(new Cas(storeRoot), pinnedShas(), ledger.load(), policy, Clock.SYSTEM.millis(), dryRun);
        } catch (IOException | RuntimeException e) {
            return new Report(0, 0L, 0, 0);
        }
    }

    /** The ledger key of the blob with {@code sha256Hex}; the store CAS records its uses under it. */
    public static String key(String sha256Hex) {
        return "cas:" + sha256Hex;
    }
}
