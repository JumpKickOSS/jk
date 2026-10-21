// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Gives the test's sandbox home the engine and first-party workers of the {@code jk} on PATH, copied
 * from that client's own install ({@code <home>/lib/jk-engine} and {@code <home>/store/repos/jk-local}).
 * Without them the client fetches both from the release site, which has nothing for a version built
 * from a checkout and not yet published.
 */
final class InstalledJk {

    private static final String ENGINE_DIR = "lib/jk-engine";
    private static final String ENGINE_MANIFEST = "jk-engine.toml";
    private static final String WORKERS = "repos/jk-local/cc/jumpkick";

    private InstalledJk() {}

    /**
     * Seed the sandbox named by {@code JK_HOME} / {@code JK_STORE_DIR}. A no-op when no {@code jk}
     * is on PATH, when its install has no engine, or when the environment names no sandbox apart
     * from that install.
     */
    static void seedSandbox() throws IOException {
        Path sandbox = envPath("JK_HOME");
        Optional<Path> install = binary().map(InstalledJk::installHome);
        if (sandbox == null || install.isEmpty()) return;
        Path home = install.get();
        Path manifest = home.resolve(ENGINE_DIR).resolve(ENGINE_MANIFEST);
        if (!Files.isRegularFile(manifest) || (Files.exists(sandbox) && Files.isSameFile(home, sandbox))) return;
        String version = field(manifest, "version");
        String jar = field(manifest, "jar");
        if (version == null || jar == null) return;

        Path engineDir = Files.createDirectories(sandbox.resolve(ENGINE_DIR));
        copyIfChanged(home.resolve(ENGINE_DIR).resolve(jar), engineDir.resolve(jar));
        copyIfChanged(manifest, engineDir.resolve(ENGINE_MANIFEST));

        Path store = envPath("JK_STORE_DIR");
        Path workers = home.resolve("store").resolve(WORKERS);
        if (!Files.isDirectory(workers)) return;
        Path into = (store != null ? store : sandbox.resolve("store")).resolve(WORKERS);
        try (Stream<Path> walk = Files.walk(workers)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path rel = workers.relativize(file);
                // <artifact>/<version>/<file>, or guards/<artifact>/<version>/<file>
                Path versionDir = rel.getParent();
                if (versionDir == null || !version.equals(String.valueOf(versionDir.getFileName()))) continue;
                copyIfChanged(file, into.resolve(rel.toString()));
            }
        }
    }

    private static void copyIfChanged(Path from, Path to) throws IOException {
        if (Files.isRegularFile(to) && Files.mismatch(from, to) == -1) return;
        Files.createDirectories(to.getParent());
        Path tmp = Files.createTempFile(to.getParent(), ".seed-", ".tmp");
        Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The {@code jk} the plugin runs, resolved to a file: {@link JkBin#path()}, searched on PATH when bare. */
    private static Optional<Path> binary() {
        String bin = JkBin.path();
        if (bin.contains(File.separator) || bin.contains("/")) {
            Path p = Path.of(bin);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : List.of(bin, bin + ".exe", bin + ".cmd", bin + ".bat")) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** {@code <home>} of a client installed at {@code <home>/bin/jk}. */
    private static Path installHome(Path binary) {
        try {
            Path real = binary.toRealPath();
            Path bin = real.getParent();
            Path home = bin == null ? null : bin.getParent();
            return home == null ? real : home;
        } catch (IOException e) {
            return binary;
        }
    }

    private static @Nullable String field(Path toml, String key) throws IOException {
        for (String line : Files.readAllLines(toml)) {
            String t = line.strip();
            if (!t.startsWith(key)) continue;
            String rest = t.substring(key.length()).strip();
            if (!rest.startsWith("=")) continue;
            String value = rest.substring(1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
        }
        return null;
    }

    private static @Nullable Path envPath(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : Path.of(v);
    }
}
