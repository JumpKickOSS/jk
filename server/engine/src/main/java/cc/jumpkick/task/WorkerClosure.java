// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One of jk's own workers as an input of the work it does: every entry of its launch classpath, the
 * jar the build forks first, by content. jk's javac wrapper decides what javac is asked and what is
 * kept of its output; the test runner decides what runs and how its outcome is read. Under a release
 * version their jars carry no version that moves, so their bytes are the identity. Resolved once per
 * worker jar identity.
 */
public final class WorkerClosure {

    private WorkerClosure() {}

    private record Memo(List<Path> paths, List<String> tokens) {}

    /** By the worker jar's settled stat; a handful of workers per engine. */
    private static final StampedMemo<Path, FileStamp, Memo> MEMO = StampedMemo.bounded(64);

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
        FileStamp stamp = FileStamp.of(jar);
        if (stamp == null) throw new NoSuchFileException(jar.toString());
        try {
            return MEMO.get(jar, stamp, () -> {
                try {
                    return closure(jar);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Memo closure(Path jar) throws IOException {
        List<Path> paths = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (Path entry : WorkerLaunchClasspath.paths(jar)) {
            Path abs = entry.toAbsolutePath().normalize();
            paths.add(abs);
            tokens.add(abs.getFileName() + ":" + ClasspathFingerprint.entry(abs));
        }
        return new Memo(List.copyOf(paths), List.copyOf(tokens));
    }
}
