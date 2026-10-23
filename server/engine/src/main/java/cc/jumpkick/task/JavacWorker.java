// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code jk-java-compiler} worker as a compile input: the content of every entry its launch
 * classpath holds (a jar, or a workspace-built worker's classes), the jar the build forks ({@link PluginJar#JAVA_COMPILER}) first. Resolved once per
 * worker jar identity, so a reinstall that replaces the jar re-keys every javac compile and nothing
 * else does.
 */
final class JavacWorker {

    private JavacWorker() {}

    private record Memo(Path jar, StatIdentity identity, List<String> tokens) {}

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

    /** One {@code <file name>:<identity>} per entry of the worker's launch classpath, in launch order. */
    static List<String> tokens() throws IOException {
        Path jar = PluginJar.JAVA_COMPILER
                .locate(JkStores.storeCas())
                .toAbsolutePath()
                .normalize();
        StatIdentity now = StatIdentity.of(jar);
        Memo memo = MEMO.get(jar);
        if (memo != null && memo.identity().equals(now)) return memo.tokens();
        List<String> tokens = new ArrayList<>();
        for (Path entry : WorkerLaunchClasspath.paths(jar)) {
            Path abs = entry.toAbsolutePath().normalize();
            tokens.add(abs.getFileName() + ":" + ClasspathFingerprint.entry(abs));
        }
        List<String> frozen = List.copyOf(tokens);
        MEMO.put(jar, new Memo(jar, now, frozen));
        return frozen;
    }
}
