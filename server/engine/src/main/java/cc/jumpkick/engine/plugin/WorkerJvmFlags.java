// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.host.Log;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The JVM flags a worker's own JDK understands. The engine runs on a pinned JDK 25 or newer and
 * picks its tuning there, but a module pinned to {@code jdk = 21} forks its compiler on JDK 21, and
 * a JDK refuses to start on an option it does not know: {@code --sun-misc-unsafe-memory-access}
 * (JEP 498, 23), the AOT cache flags (JEP 483/514) and compact object headers (25). Every worker
 * launch passes through {@link PluginLoader#command}, which drops what the worker's JDK is too old
 * for; the engine's own choice of flags is never the worker's.
 */
public final class WorkerJvmFlags {

    private WorkerJvmFlags() {}

    /** A flag, by its spelling up to the value, and the first JDK feature release that takes it. */
    record Since(String prefix, int feature) {}

    /** Which flags need a newer JDK than the floor, and from which feature release. */
    static final List<Since> SINCE = List.of(
            new Since("--sun-misc-unsafe-memory-access=", 23),
            new Since("-XX:AOTCache=", 25),
            new Since("-XX:AOTCacheOutput=", 25),
            new Since("-XX:AOTConfiguration=", 25),
            new Since("-XX:AOTMode=", 25),
            new Since("-Xlog:aot", 25),
            new Since("-XX:+UseCompactObjectHeaders", 25),
            new Since("-XX:-UseCompactObjectHeaders", 25));

    /**
     * Each worker JDK's feature release, stamped by its {@code release} file as a stat sees it
     * through any link: a {@code JAVA_HOME} that is a pointer ({@code current}) repointed at another
     * JDK reads that JDK's file.
     */
    private static final StampedMemo<Path, FileStamp, Integer> FEATURES = StampedMemo.bounded(64);

    /** {@code flags} less those {@code javaHome}'s JDK is too old for. */
    public static List<String> forJdk(Path javaHome, List<String> flags) {
        if (flags.isEmpty()) return flags;
        return forFeature(feature(javaHome), flags);
    }

    /** {@code javaHome}'s feature release, read again whenever its {@code release} file changes. */
    static int feature(Path javaHome) {
        FileStamp stamp = FileStamp.of(javaHome.resolve("release"));
        if (stamp == null) return JvmOptions.hostFeature(javaHome);
        return FEATURES.get(javaHome, stamp, () -> JvmOptions.hostFeature(javaHome));
    }

    /** {@code flags} less those JDK {@code feature} is too old for. */
    static List<String> forFeature(int feature, List<String> flags) {
        List<String> out = null;
        for (int i = 0; i < flags.size(); i++) {
            String flag = flags.get(i);
            int since = since(flag);
            if (feature >= since) {
                if (out != null) out.add(flag);
                continue;
            }
            if (out == null) out = new ArrayList<>(flags.subList(0, i));
            Log.debug("worker JVM flag " + flag + " needs JDK " + since + "; the worker's JDK is " + feature);
        }
        return out == null ? flags : out;
    }

    /** The first feature release that takes {@code flag}; 0 for a flag every supported JDK takes. */
    static int since(String flag) {
        for (Since s : SINCE) {
            if (flag.startsWith(s.prefix())) return s.feature();
        }
        return 0;
    }
}
