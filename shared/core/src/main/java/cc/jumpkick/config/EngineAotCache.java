// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The engine's own JEP 514 startup cache: what a fresh engine maps so its first build does not pay
 * for loading and linking the engine cold. On petclinic a no-op on a fresh engine is 0.69 s with
 * the cache and 1.17 s without; the mapping costs ~9 MiB of idle PSS.
 *
 * <p>One file per engine jar and JDK, under {@code <state>/engine-aot/}: the JVM maps a cache only
 * for the exact classpath it was recorded with, so the key is the jar's path, size and mtime and
 * the JDK home. The spawner maps it when present ({@link #spawnFlags}); an idle engine records it
 * when it is missing, by driving a trainer engine started with {@link #RECORD_ENV}. {@code
 * JK_ENGINE_AOT=off} turns both off. HotSpot 25+ only; any other JDK starts without.
 */
public final class EngineAotCache {

    /** {@code off}, {@code false} or {@code 0}: neither map nor record. */
    public static final String ENV = "JK_ENGINE_AOT";

    /** Internal: the spawned engine records its cache to this path instead of mapping one. */
    public static final String RECORD_ENV = "JK_ENGINE_AOT_RECORD";

    private EngineAotCache() {}

    /** The directory engine caches live in: beside, never inside, the worker caches' sweep. */
    public static Path dir(Path stateDir) {
        return stateDir.resolve("engine-aot");
    }

    /**
     * The cache for the engine {@code engineJar} on {@code javaHome}, or null when either cannot be
     * read. The JDK is named by its {@code release} file, not its path: the spawner's path and the
     * engine's own {@code java.home} can name one JDK two ways.
     */
    public static @Nullable Path cacheFile(Path stateDir, Path javaHome, Path engineJar) {
        try {
            Path jar = engineJar.toAbsolutePath().normalize();
            BasicFileAttributes a = Files.readAttributes(jar, BasicFileAttributes.class);
            String jdk = Hashing.sha256Hex(Files.readAllBytes(javaHome.resolve("release")));
            String key = Hashing.sha256Hex(jdk + "|" + jar + "|" + a.size() + "|"
                            + a.lastModifiedTime().toMillis())
                    .substring(0, 16);
            return dir(stateDir).resolve("engine-" + JkVersion.VERSION + "-" + key + ".aot");
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether caches are on in {@code env}. */
    public static boolean enabled(Function<String, @Nullable String> env) {
        return EnvValues.bool(env, ENV).orElse(true);
    }

    /**
     * The flags the spawner adds to the engine's JVM: record when {@link #RECORD_ENV} names a path,
     * else map the cache when one exists, else none. Never throws.
     */
    public static List<String> spawnFlags(
            Path stateDir, Path javaHome, Path engineJar, Function<String, @Nullable String> env) {
        try {
            if (!enabled(env) || !eligible(javaHome)) return List.of();
            String record = env.apply(RECORD_ENV);
            if (record != null && !record.isBlank()) {
                return List.of("-XX:AOTCacheOutput=" + record.trim(), "-Xlog:aot=error");
            }
            Path cache = cacheFile(stateDir, javaHome, engineJar);
            if (cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0) {
                return List.of("-XX:AOTCache=" + cache, "-Xlog:aot=off");
            }
        } catch (IOException | RuntimeException e) {
            // An accelerator, never a dependency: the engine starts without it.
        }
        return List.of();
    }

    /** HotSpot 25 or later, read from the JDK's {@code release} file; Graal and older JDKs are not. */
    static boolean eligible(Path javaHome) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(javaHome.resolve("release"))) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        String version = p.getProperty("JAVA_VERSION", "").replace("\"", "").trim();
        String vendor = p.getProperty("IMPLEMENTOR", "").toLowerCase(Locale.ROOT);
        if (vendor.contains("graal") || p.getProperty("GRAALVM_VERSION") != null) return false;
        try {
            return Integer.parseInt(version.split("[.+-]")[0]) >= 25;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
