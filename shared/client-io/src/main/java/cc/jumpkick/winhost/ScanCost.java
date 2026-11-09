// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a folder costs a build: the time to write, close and read back a batch of build-sized files
 * in a scratch folder inside it, scaled to 1,000 files. Real-time scanning is most of that cost on a
 * scanned folder, so the same probe before and after an exclusion measures what the exclusion saved.
 * The contents are random, so no scan cache answers for them.
 */
public final class ScanCost {

    /** Files in one probe, and bytes in each: a small module's classes. */
    static final int FILES = 300;

    static final int BYTES = 8 * 1024;

    /** Measures a folder; the seam tests replace. */
    @FunctionalInterface
    public interface Probe {
        /** Milliseconds per 1,000 files in {@code folder}; -1 when it could not be measured. */
        long perThousand(Path folder);
    }

    /** This machine's probe. */
    public static final Probe LIVE = ScanCost::perThousand;

    private ScanCost() {}

    static long perThousand(Path folder) {
        if (!Files.isDirectory(folder)) return -1;
        Path scratch = folder.resolve(
                ".jk-scan-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        byte[] bytes = new byte[BYTES];
        try {
            Files.createDirectory(scratch);
            long start = Clock.SYSTEM.nanos();
            for (int i = 0; i < FILES; i++) {
                ThreadLocalRandom.current().nextBytes(bytes);
                Files.write(scratch.resolve("C" + i + ".class"), bytes);
            }
            for (int i = 0; i < FILES; i++) Files.readAllBytes(scratch.resolve("C" + i + ".class"));
            long nanos = Clock.SYSTEM.nanos() - start;
            return nanos * 1000L / FILES / 1_000_000L;
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            PathUtil.deleteRecursively(scratch);
        }
    }

    /** {@code millis} as a person reads it: {@code 1.9 s} or {@code 420 ms}. */
    public static String seconds(long millis) {
        return millis >= 1000 ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0) : millis + " ms";
    }
}
