// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        try {
            Processes.Captured c = Processes.capture(List.of("git", "config", "--get", "core.longpaths"), GIT_WAIT);
            if (c.timedOut()) return null;
            if (c.exit() == 1) return false;
            if (c.exit() != 0) return null;
            return EnvValues.parseBool(c.output()).orElse(false);
        } catch (IOException e) {
            return null;
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
