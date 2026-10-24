// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
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

/**
 * Garbage collection of the artifact store's CAS, which has no other collector.
 *
 * <p>What the store CAS holds is mostly what installs leave behind: every {@code jk install} shelves
 * each first-party jar and the native client, and an engine is pinned to one install's set by its
 * {@link ShelfManifest}. The sets of the installs before are garbage the moment the next lands, and
 * the native client is 35 MB or more of it. A blob is therefore <em>pinned</em> when an installed
 * engine's manifest names it or it is the client on {@code PATH}; every other blob is on a countdown
 * set by what it is and how big, shortened by disk pressure:
 *
 * <ul>
 *   <li>native binaries are expensive to hold and to rebuild alike, so a few days, with the newest
 *       few kept whatever their age;
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
            Duration largeJarTtl,
            long largeBytes,
            Duration jarTtl,
            Duration otherTtl,
            Duration minAge,
            double pressure,
            long budgetBytes) {

        public static Policy defaults(double pressure) {
            return new Policy(
                    Duration.ofDays(3),
                    2,
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
                    nativeTtl, count, largeJarTtl, largeBytes, jarTtl, otherTtl, minAge, pressure, budgetBytes);
        }

        /** This policy holding the store to {@code bytes}: past it, whatever has the least life left goes first. */
        public Policy withBudget(long bytes) {
            return new Policy(
                    nativeTtl, keepNative, largeJarTtl, largeBytes, jarTtl, otherTtl, minAge, pressure, bytes);
        }

        /**
         * How much longer a blob used on {@code days} distinct days lives: 1.5x for two, 2x for four,
         * doubling again per doubling of days, to a cap that is lower for the kinds that are dear to hold.
         */
        double reuseBonus(Kind kind, int days) {
            if (days <= 1) return 1.0;
            double cap = kind == Kind.NATIVE || kind == Kind.LARGE_JAR ? 2.0 : 4.0;
            return Math.min(cap, 1.0 + 0.5 * (Math.log(days) / Math.log(2)));
        }

        Duration ttl(Kind kind, int days) {
            Duration base =
                    switch (kind) {
                        case NATIVE -> nativeTtl;
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
        ShelfManifest.read(install.shelfFile()).ifPresent(m -> {
            pinned.add(m.engineSha256());
            pinned.addAll(m.jars().values());
            pinned.addAll(m.poms().values());
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
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') return size >= largeBytes ? Kind.LARGE_JAR : Kind.JAR;
        return Kind.OTHER;
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

    /** The ledger key of the blob with {@code sha256Hex}; the store CAS records its uses under it. */
    public static String key(String sha256Hex) {
        return "cas:" + sha256Hex;
    }
}
