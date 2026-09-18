// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The oldest SpotBugs release the step can run on a JDK: SpotBugs reads the class files of the
 * JDK it runs on as well as the module's, and a release whose class reader predates that JDK's
 * class file version fails every class with {@code Unsupported class file major version}. The
 * floors are the releases measured to read each LTS line's class files; a JDK between two lines
 * takes the older line's floor, the lower bound that holds for it.
 */
final class SpotBugsFloor {

    /** JDK feature release → the oldest SpotBugs release that reads its class files. */
    private static final NavigableMap<Integer, String> FLOORS = new TreeMap<>(Map.of(
            17, "4.2.2",
            21, "4.8.0",
            25, "4.9.4"));

    private SpotBugsFloor() {}

    /** The floor for {@code jdk}: the entry of the newest LTS line at or below it; empty below the first line. */
    static Optional<String> floorFor(int jdk) {
        Map.Entry<Integer, String> floor = FLOORS.floorEntry(jdk);
        return floor == null ? Optional.empty() : Optional.of(floor.getValue());
    }

    /**
     * Refuse {@code version} when it is below the floor of the JDK at {@code javaHome}, naming the
     * floor; a JDK whose release file names no feature version, or a version that is not dotted
     * numbers, is left to SpotBugs itself.
     */
    static void check(String version, Path javaHome) {
        int jdk = feature(javaHome);
        if (jdk <= 0) return;
        Optional<String> floor = floorFor(jdk);
        if (floor.isEmpty() || !below(version, floor.get())) return;
        throw new IllegalStateException("SpotBugs " + version + " cannot read the class files of JDK " + jdk
                + ", which this step runs on; the oldest release that can is " + floor.get()
                + ": set [lint] spotbugs-version = \"" + floor.get() + "\" or newer");
    }

    /** True when {@code version} orders before {@code floor}, segment by numeric segment; false when either is not numeric. */
    static boolean below(String version, String floor) {
        String[] a = version.trim().split("\\.");
        String[] b = floor.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = segment(a, i);
            int y = segment(b, i);
            if (x < 0 || y < 0) return false;
            if (x != y) return x < y;
        }
        return false;
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The feature release of the JDK at {@code javaHome}, from its {@code release} file's {@code
     * JAVA_VERSION} ({@code "25.0.4.1"} is 25, {@code "1.8.0_402"} is 8); {@code 0} when unreadable.
     */
    static int feature(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) return 0;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return 0;
        }
        String version = props.getProperty("JAVA_VERSION", "").replace("\"", "").trim();
        if (version.startsWith("1.")) version = version.substring(2);
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
    }
}
