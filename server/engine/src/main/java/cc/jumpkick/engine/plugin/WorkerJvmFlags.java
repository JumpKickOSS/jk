// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Map<Path, Integer> FEATURES = new ConcurrentHashMap<>();

    /** {@code flags} less those {@code javaHome}'s JDK is too old for. */
    public static List<String> forJdk(Path javaHome, List<String> flags) {
        if (flags.isEmpty()) return flags;
        int feature = FEATURES.computeIfAbsent(javaHome, JvmOptions::hostFeature);
        return forFeature(feature, flags);
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
