// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** This machine: the registry through advapi32, volumes through the JDK's file stores, git by its exit. */
final class LiveHostFacts implements HostFacts {

    static final LiveHostFacts INSTANCE = new LiveHostFacts();

    /** How long git may take to answer {@code core.longpaths}. */
    private static final Duration GIT_WAIT = Duration.ofMillis(700);

    private LiveHostFacts() {}

    @Override
    public Registry registry() {
        return Advapi32Registry.live();
    }

    @Override
    public @Nullable String env(String name) {
        return ProcessEnv.get(name);
    }

    @Override
    public @Nullable Volume volume(Path path) {
        try {
            Path real = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath();
            FileStore store = Files.getFileStore(Files.exists(real) ? real : existingParent(real));
            Path root = real.getRoot();
            return new Volume(root == null ? store.name() : root.toString(), store.type());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Path existingParent(Path p) throws IOException {
        for (Path at = p; at != null; at = at.getParent()) if (Files.exists(at)) return at;
        throw new IOException("no existing parent of " + p);
    }

    @Override
    public @Nullable Boolean gitLongPaths() {
        Process p = null;
        try {
            p = new ProcessBuilder("git", "config", "--get", "core.longpaths")
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            if (!p.waitFor(GIT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) return null;
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (p.exitValue() == 1) return false;
            if (p.exitValue() != 0) return null;
            return EnvValues.parseBool(out).orElse(false);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    @Override
    public String processArch() {
        return System.getProperty("os.arch", "");
    }

    @Override
    public @Nullable String jdkArch() {
        // A JVM is the JDK in question; only the native client looks one up.
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) return processArch();
        String javaHome = ProcessEnv.get("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) return null;
        String release = read(Path.of(javaHome, "release"));
        if (release == null) return null;
        for (String line : release.split("\\R")) {
            if (line.startsWith("OS_ARCH="))
                return line.substring("OS_ARCH=".length()).replace("\"", "").strip();
        }
        return null;
    }

    @Override
    public boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public @Nullable String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
