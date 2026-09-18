// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkLts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Which JDK a Gradle distribution can run on. Each Gradle line supports running on JDKs up to a
 * ceiling ({@link #maxRunningJdk}); a wrapper pinned to 8.3 refuses JDK 21 with an unsupported
 * class-file error, so the model query runs on the engine's own JDK when the ceiling allows,
 * otherwise on the newest installed JDK under it, and when none is installed on the newest LTS the
 * distribution runs on, provisioned as the toolchain provisions a manifest's {@code jdk} pin.
 */
final class GradleJvmCompatibility {

    /** The JDK the fork runs on: its home and major. */
    record Pick(Path javaHome, int major) {}

    /** Installs the JDK of {@code major} and answers its home. */
    interface JdkInstall {
        Path install(int major) throws IOException, InterruptedException;
    }

    private GradleJvmCompatibility() {}

    /** The newest JDK major Gradle {@code version} runs on; an unparsable version is taken as current. */
    static int maxRunningJdk(String version) {
        int[] v = majorMinor(version);
        if (v == null) return 25;
        int major = v[0];
        int minor = v[1];
        if (major >= 10) return 25;
        if (major == 9) return minor >= 1 ? 25 : 24;
        if (major == 8) {
            if (minor >= 14) return 24;
            if (minor >= 10) return 23;
            if (minor >= 8) return 22;
            if (minor >= 5) return 21;
            if (minor >= 3) return 20;
            return 19;
        }
        if (major == 7) {
            if (minor >= 6) return 19;
            if (minor == 5) return 18;
            if (minor >= 3) return 17;
            return 16;
        }
        if (major == 6) {
            if (minor >= 7) return 15;
            if (minor >= 3) return 14;
            return 13;
        }
        if (major == 5) return minor >= 4 ? 12 : 11;
        return 8;
    }

    /** The oldest JDK major Gradle {@code version} runs on. */
    static int minRunningJdk(String version) {
        int[] v = majorMinor(version);
        return v != null && v[0] >= 9 ? 17 : 8;
    }

    private static int @Nullable [] majorMinor(String version) {
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[] {major, minor};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The JDK to run Gradle {@code gradleVersion} on: the engine's own ({@code runningHome}, {@code
     * runningMajor}) when the distribution accepts it, else the newest of {@code installed} within
     * the distribution's range, else the one {@code install} provisions — the newest LTS in the range
     * ({@link #installMajor}); an {@link IOException} naming the range when that install fails.
     */
    static Pick pick(
            String gradleVersion,
            Path runningHome,
            int runningMajor,
            Supplier<List<JdkHit>> installed,
            JdkInstall install)
            throws IOException, InterruptedException {
        int max = maxRunningJdk(gradleVersion);
        int min = minRunningJdk(gradleVersion);
        if (runningMajor >= min && runningMajor <= max) return new Pick(runningHome, runningMajor);
        Pick best = null;
        for (JdkHit hit : installed.get()) {
            Integer major = JdkKeywords.leadingMajor(hit.version());
            if (major == null || major < min || major > max) continue;
            if (best == null || major > best.major()) best = new Pick(hit.home(), major);
        }
        if (best != null) return best;
        int major = installMajor(min, max);
        try {
            return new Pick(install.install(major), major);
        } catch (IOException e) {
            throw new IOException(
                    "Gradle " + gradleVersion + " runs on JDK " + min + " to " + max
                            + " and none is installed (the engine runs on JDK " + runningMajor + "); installing JDK "
                            + major
                            + " failed: " + e.getMessage() + ". `jk jdk install " + major
                            + "` and import again, or move the wrapper to a newer Gradle.",
                    e);
        }
    }

    /** The JDK to provision for a range no installed JDK falls in: the newest LTS in it, else its ceiling. */
    static int installMajor(int min, int max) {
        for (int major = max; major >= min; major--) {
            if (JdkLts.isLtsMajor(major)) return major;
        }
        return max;
    }
}
