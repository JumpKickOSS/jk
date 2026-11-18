// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Whether a JDK lets the environment probe hand the caller's environment on to the programs a test
 * starts. The probe repoints {@code java.lang.ProcessBuilder}'s call to {@code ProcessImpl.start}
 * by its exact descriptor; a JDK that starts programs another way leaves it as compiled, and a
 * program a test started would see the test JVM's own environment only, a promise broken without a
 * word. So before a run relies on it the probe is asked once per JDK, in a JVM of that JDK with
 * nothing else to do ({@code EnvProbe.CHECK}), and a run on a JDK that refuses it gives its test
 * JVMs the caller's whole environment in their process environment instead, keyed on every value
 * ({@link SuiteEnvDelivery#unprobedEnvironment}). The verdict is kept beside the probe's jar, by the
 * JDK's home, its {@code release} file and the jar.
 */
public final class EnvProbeHook {

    private EnvProbeHook() {}

    /** What the probe writes when the hook takes ({@code EnvProbe.HOOKED}). */
    static final String HOOKED = "hooked";

    private static final String CHECK = "check:";
    private static final String AGENT = "cc.jumpkick.testrunner.env.EnvProbe";
    private static final String JAVAAGENT = "-javaagent:";

    private static volatile @Nullable String pretend;

    /**
     * Answer {@code why} for every JDK ({@code null}: ask each one again): a test stands in for a
     * JDK that starts programs another way.
     */
    public static void pretendForTests(@Nullable String why) {
        pretend = why;
    }

    /**
     * Null when the probe {@code flag} names hands the caller's environment on to the programs a
     * test JVM on {@code javaHome} starts; else why not, one clause. A check that cannot run reads
     * as a refusal for this run and is asked again on the next.
     */
    public static @Nullable String unhooked(Path javaHome, String flag) {
        String said = pretend;
        if (said != null) return said;
        Path jar = jarOf(flag);
        if (jar == null) return "the probe's jar could not be named";
        String id = id(javaHome, jar);
        Path kept = jar.resolveSibling("hook-" + id + ".txt");
        String verdict = read(kept);
        if (verdict == null) {
            verdict = ask(javaHome, jar, kept);
            if (verdict == null) return "the check of " + javaHome + " did not run";
        }
        return verdict.equals(HOOKED) ? null : verdict.replaceFirst("^unhooked: ", "");
    }

    /** The probe's jar in its {@code -javaagent:<jar>=<dir>} flag. */
    static @Nullable Path jarOf(String flag) {
        int end = flag.indexOf(".jar=");
        if (!flag.startsWith(JAVAAGENT) || end < 0) return null;
        return Path.of(flag.substring(JAVAAGENT.length(), end + 4));
    }

    /** The JDK by its home and its {@code release} file, with the probe's jar: either changing asks again. */
    private static String id(Path javaHome, Path jar) {
        String release;
        try {
            release = Files.readString(javaHome.resolve("release"), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException none) {
            release = "";
        }
        Path name = jar.getFileName();
        return Hashing.sha256Hex(javaHome.toAbsolutePath().normalize() + "\n" + release + "\n" + name)
                .substring(0, 16);
    }

    private static @Nullable String read(Path kept) {
        try {
            String v = Files.readString(kept, StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? null : v;
        } catch (IOException | RuntimeException none) {
            return null;
        }
    }

    /** Run the probe's check on {@code javaHome}, keeping its verdict at {@code kept}; null when it did not run. */
    private static @Nullable String ask(Path javaHome, Path jar, Path kept) {
        Path out = kept.resolveSibling(
                kept.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.deleteIfExists(out);
            Processes.Captured ran = Processes.capture(
                    List.of(
                            JdkFingerprint.java(javaHome).toString(),
                            "-Xshare:auto",
                            "-XX:TieredStopAtLevel=1",
                            "-Xmx32m",
                            JAVAAGENT + jar.toAbsolutePath() + "=" + CHECK + out.toAbsolutePath(),
                            "-cp",
                            jar.toAbsolutePath().toString(),
                            AGENT),
                    Duration.ofSeconds(60));
            String verdict = read(out);
            if (ran.timedOut() || verdict == null) {
                Log.debug("env probe: the hook check on " + javaHome + " said nothing (exit " + ran.exit() + "): "
                        + ran.output());
                return null;
            }
            AtomicWrites.replace(kept, verdict);
            return verdict;
        } catch (IOException | RuntimeException failed) {
            Log.debug("env probe: the hook check on " + javaHome + " did not run", failed);
            return null;
        } finally {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
                // a stray check file under the cache is swept with it
            }
        }
    }
}
