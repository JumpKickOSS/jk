// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-safe (no engine classpath) probe of whether host {@code calibration.toml} still needs a
 * bootstrap run. Used by {@code jk explain} to show a short "Calibrating host…" wedge before the
 * plan; the engine's {@code Calibration.ensure} performs the real work.
 */
public final class HostCalibrationStatus {

    private static final Pattern MEASURED = Pattern.compile("(?m)^\\s*measured\\s*=\\s*true\\s*$");
    private static final Pattern SCHEMA = Pattern.compile("(?m)^\\s*schema\\s*=\\s*(\\d+)\\s*$");
    /** Match engine schema 3+ multi-probe files (schema 4 adds learned rings). */
    private static final int MIN_SCHEMA = 3;

    private static final long FAILURE_BACKOFF_MS = TimeUnit.HOURS.toMillis(24);

    private HostCalibrationStatus() {}

    static Path calibrationFile() {
        return JkDirs.builds().resolve("calibration.toml");
    }

    static Path failureMarker() {
        return JkDirs.builds().resolve("calibration.failed");
    }

    /**
     * {@code true} when the next explain/build ETA path is likely to run the multi-second host
     * probe (missing/stale/unmeasured file, and not in failure backoff).
     */
    public static boolean needsBootstrapProbe() {
        if (failedRecently()) return false;
        Path f = calibrationFile();
        if (!Files.isRegularFile(f)) return true;
        try {
            String text = Files.readString(f);
            if (!MEASURED.matcher(text).find()) return true;
            Matcher m = SCHEMA.matcher(text);
            if (!m.find()) return true;
            int schema = Integer.parseInt(m.group(1));
            return schema < MIN_SCHEMA;
        } catch (IOException | NumberFormatException e) {
            return true;
        }
    }

    private static boolean failedRecently() {
        try {
            Path marker = failureMarker();
            if (!Files.isRegularFile(marker)) return false;
            long at = Long.parseLong(Files.readString(marker).trim());
            return System.currentTimeMillis() - at < FAILURE_BACKOFF_MS;
        } catch (Exception e) {
            return false;
        }
    }
}
