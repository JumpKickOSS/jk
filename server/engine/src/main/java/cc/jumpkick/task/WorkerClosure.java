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
 * One of jk's own workers as an input of the work it does: every entry of its launch classpath, the
 * jar the build forks first, by content. jk's javac wrapper decides what javac is asked and what is
 * kept of its output; the test runner decides what runs and how its outcome is read. Under a release
 * version their jars carry no version that moves, so their bytes are the identity. Resolved once per
 * worker jar identity.
 */
public final class WorkerClosure {

    private WorkerClosure() {}

    private record Memo(StatIdentity identity, List<Path> paths, List<String> tokens) {}

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

    /** The worker's launch classpath, its own jar first. */
    public static List<Path> paths(PluginJar worker) throws IOException {
        return memo(worker).paths();
    }

    /** One {@code <file name>:<identity>} per entry of the worker's launch classpath, in launch order. */
    public static List<String> tokens(PluginJar worker) throws IOException {
        return memo(worker).tokens();
    }

    private static Memo memo(PluginJar worker) throws IOException {
        Path jar = worker.locate(JkStores.storeCas()).toAbsolutePath().normalize();
        StatIdentity now = StatIdentity.of(jar);
        Memo memo = MEMO.get(jar);
        if (memo != null && memo.identity().equals(now)) return memo;
        List<Path> paths = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (Path entry : WorkerLaunchClasspath.paths(jar)) {
            Path abs = entry.toAbsolutePath().normalize();
            paths.add(abs);
            tokens.add(abs.getFileName() + ":" + ClasspathFingerprint.entry(abs));
        }
        Memo fresh = new Memo(now, List.copyOf(paths), List.copyOf(tokens));
        MEMO.put(jar, fresh);
        return fresh;
    }
}
