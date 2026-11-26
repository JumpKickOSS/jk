// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A worker as an input of the work it does: every entry of its launch classpath, the jar the build
 * forks first, by content. A worker's jar holds its own module's classes only, so the libraries its
 * POM names (the plugin SDK, {@code jk-host}, …) decide what it does as surely as the jar: jk's
 * javac wrapper decides what javac is asked and what is kept of its output, the test runner what
 * runs and how its outcome is read, a plugin what its steps write.
 */
public final class WorkerClosure {

    private WorkerClosure() {}

    private record Memo(List<Path> paths, List<String> tokens) {}

    /**
     * The launch classpath as it stands: its entries, and the stat of each one a build rewrites in
     * place (a workspace {@code target/} jar). Every other entry is a store or CAS path, whose bytes
     * never change under it.
     */
    private record Stamp(List<Path> paths, List<@Nullable FileStamp> stats) implements StampedMemo.Settling {
        @Override
        public boolean settled() {
            for (FileStamp s : stats) {
                if (s != null && !s.settled()) return false;
            }
            return true;
        }
    }

    /** By the worker jar, while its launch classpath names the same entries at the same stats. */
    private static final StampedMemo<Path, Stamp, Memo> MEMO = StampedMemo.bounded(64);

    /** The worker's launch classpath, its own jar first. */
    public static List<Path> paths(PluginJar worker) throws IOException {
        return memo(located(worker)).paths();
    }

    /** One {@code <file name>:<identity>} per entry of the worker's launch classpath, in launch order. */
    public static List<String> tokens(PluginJar worker) throws IOException {
        return memo(located(worker)).tokens();
    }

    /**
     * One token for {@code workerJar}'s whole launch classpath, for a key that names the worker that
     * does its work. A jar whose classpath is the jar alone, or does not resolve, is keyed as itself
     * and the POM beside it; a launch of one that does not resolve fails later, naming what is
     * missing.
     */
    public static String token(Path workerJar) throws IOException {
        Path jar = workerJar.toAbsolutePath().normalize();
        List<String> tokens;
        try {
            tokens = memo(jar).tokens();
        } catch (IllegalStateException unresolved) {
            return unresolved(jar);
        }
        if (tokens.size() == 1) return unresolved(jar);
        return "closure:" + Hashing.sha256Hex(String.join("\n", tokens).getBytes(StandardCharsets.UTF_8));
    }

    /** The jar and the POM beside it, by content: what decides a classpath that does not resolve here. */
    private static String unresolved(Path jar) throws IOException {
        String name = String.valueOf(jar.getFileName());
        Path pom = jar.resolveSibling(
                name.endsWith(".jar") ? name.substring(0, name.length() - 4) + ".pom" : name + ".pom");
        String token = ClasspathFingerprint.entry(jar);
        return Files.isRegularFile(pom) ? token + "|pom:" + ClasspathFingerprint.entry(pom) : token;
    }

    private static Path located(PluginJar worker) throws IOException {
        Path jar = worker.locate(JkStores.storeCas()).toAbsolutePath().normalize();
        if (FileStamp.of(jar) == null) throw new NoSuchFileException(jar.toString());
        return jar;
    }

    private static Memo memo(Path jar) throws IOException {
        List<Path> paths = new ArrayList<>();
        List<@Nullable FileStamp> stats = new ArrayList<>();
        for (Path entry : WorkerLaunchClasspath.paths(jar)) {
            Path abs = entry.toAbsolutePath().normalize();
            paths.add(abs);
            stats.add(BuildLayout.isBuildOutput(abs) ? FileStamp.of(abs) : null);
        }
        try {
            return MEMO.get(jar, new Stamp(List.copyOf(paths), stats), () -> {
                try {
                    return closure(paths);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Memo closure(List<Path> paths) throws IOException {
        List<String> tokens = new ArrayList<>(paths.size());
        for (Path abs : paths) tokens.add(abs.getFileName() + ":" + ClasspathFingerprint.entry(abs));
        return new Memo(List.copyOf(paths), List.copyOf(tokens));
    }
}
