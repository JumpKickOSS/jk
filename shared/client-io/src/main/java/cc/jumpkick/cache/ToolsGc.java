// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Garbage collection of the tool installs under {@code <store>/tools}: Node, Maven, Gradle, Kotlin and
 * the package managers, one directory per version ({@code tools/<slug>/<version>}), and the archives
 * a step unpacked once (scratch {@code unpacked/<sha256>}).
 *
 * <p>A tool install is big and cheap to fetch again, and a project picks the newest install that
 * satisfies it, so an older one is nearly always dead weight: two Node patch releases, never mind two
 * minors, are never both wanted. Each family therefore keeps the install used most recently for a long
 * while and every other one only briefly ({@link Rule}); an unpacked archive, which can be gigabytes,
 * has the one short window. How lately an install was used is the later of its directory's mtime
 * (stamped on use, see {@code PoolUse}) and the access ledger's last use; how often adds up to a longer
 * window, as for the CAS. All windows shrink with disk pressure.
 *
 * <p>An install no resolver stamps is not managed: the families are named by the caller.
 */
public final class ToolsGc {

    private ToolsGc() {}

    /** The windows of one family: {@code newest} for its most recently used install, {@code others} for the rest. */
    public record Rule(Duration newest, Duration others) {}

    /** The rule for an unpacked archive and for a family of tool installs. */
    public static final String UNPACKED = "unpacked";

    private static final Duration MIN_AGE = Duration.ofHours(1);

    /** Node and the package managers: one version is plenty, and the rest go within days. */
    static final Rule RUNTIME = new Rule(Duration.ofDays(30), Duration.ofDays(2));

    /** Build tools: a project pins one, so the other installs wait a little longer in case of a branch switch. */
    static final Rule BUILD_TOOL = new Rule(Duration.ofDays(90), Duration.ofDays(14));

    /** An unpacked archive can be gigabytes: a week from last use, however recent the others. */
    static final Rule ARCHIVE = new Rule(Duration.ofDays(7), Duration.ofDays(7));

    /** What a pass found: installs removed (or that would be), their bytes, and those kept. */
    public record Report(int deleted, long freedBytes, int kept, List<String> removed) {}

    /** The rule {@code family} is held to. */
    static Rule ruleFor(String family) {
        return switch (family) {
            case UNPACKED -> ARCHIVE;
            case "node", "pnpm", "yarn", "bun" -> RUNTIME;
            default -> BUILD_TOOL;
        };
    }

    /**
     * One pass: for each of {@code families} (plus {@link #UNPACKED}) under {@code toolsRoot}, remove the
     * installs past their window. {@code pressure} is {@code DiskPressure.factor}'s multiplier.
     */
    public static Report run(
            Path toolsRoot,
            Set<String> families,
            Map<String, AccessLedger.Use> uses,
            double pressure,
            long nowMillis,
            boolean dryRun)
            throws IOException {
        List<String> removed = new ArrayList<>();
        long[] freed = {0};
        int[] kept = {0};
        List<String> all = new ArrayList<>(families);
        all.add(UNPACKED);
        for (String family : all) {
            Path dir = toolsRoot.resolve(family);
            if (!Files.isDirectory(dir)) continue;
            Rule rule = ruleFor(family);
            record Install(Path dir, String version, long usedAt, int days) {}
            List<Install> installs = new ArrayList<>();
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (!attrs.isDirectory() || child.getFileName().toString().startsWith(".")) return true;
                String version = child.getFileName().toString();
                AccessLedger.Use use = uses.get(ToolUse.key(family, version));
                long mtime = attrs.lastModifiedTime().toMillis();
                installs.add(new Install(
                        child,
                        version,
                        use == null ? mtime : Math.max(mtime, use.lastMillis()),
                        use == null ? 1 : use.days()));
                return true;
            });
            installs.sort(Comparator.comparingLong(Install::usedAt).reversed());
            for (int i = 0; i < installs.size(); i++) {
                Install install = installs.get(i);
                Duration window = i == 0 ? rule.newest() : rule.others();
                double bonus =
                        install.days() <= 1 ? 1.0 : Math.min(2.0, 1.0 + 0.5 * Math.log(install.days()) / Math.log(2));
                long ttl = Math.max(MIN_AGE.toMillis(), (long) (window.toMillis() * bonus * pressure));
                if (nowMillis - install.usedAt() < ttl) {
                    kept[0]++;
                    continue;
                }
                long size = sizeOf(install.dir());
                if (!dryRun) PathUtil.deleteRecursively(install.dir());
                removed.add(family + "/" + install.version());
                freed[0] += size;
            }
        }
        return new Report(removed.size(), freed[0], kept[0], removed);
    }

    private static long sizeOf(Path dir) throws IOException {
        long[] bytes = {0};
        PathUtil.forEachRegularFile(dir, (file, attrs) -> bytes[0] += attrs.size());
        return bytes[0];
    }
}
