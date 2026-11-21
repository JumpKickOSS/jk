// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * This process's cgroup v2 group and the groups above it, found through {@code /proc/self/cgroup}
 * rather than taken to be the mount's root. On a host the root of {@code /sys/fs/cgroup} is the
 * host's own, which has no {@code memory.max}: a limit set on the engine's unit or slice (a systemd
 * {@code MemoryMax=}, say) sits on a group below it, and so does the pressure of that group. In a
 * container with its own cgroup namespace the line reads {@code 0::/}, and the chain is the root
 * alone.
 *
 * <p>Each method takes the filesystem root it reads {@code proc/} and {@code sys/fs/cgroup/} under,
 * so a test can hand it a tree of its own.
 */
final class OwnCgroup {

    private OwnCgroup() {}

    /** The root of the running host's filesystem. */
    static final Path HOST = Path.of("/");

    /**
     * The group {@code root}'s {@code proc/self/cgroup} names on the unified hierarchy, under
     * {@code root}'s {@code sys/fs/cgroup}; null when there is no such line, its path leaves the
     * mount, or no directory is there (a container that sees the host's paths through its own mount).
     */
    static @Nullable Path own(Path root) {
        Path mount = mount(root);
        String path = unifiedPath(read(root.resolve("proc/self/cgroup")));
        if (path == null) return null;
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..") || rel.indexOf('\0') >= 0) return null;
        Path own = mount.resolve(rel).normalize();
        if (!own.startsWith(mount.normalize()) || !Files.isDirectory(own)) return null;
        return own;
    }

    /**
     * {@link #own} and every group above it up to the mount's root, own first. The mount's root alone
     * when {@link #own} is null, which is where these files were read before the group was looked up.
     */
    static List<Path> chain(Path root) {
        Path own = own(root);
        Path mount = mount(root).normalize();
        return own == null ? List.of(mount) : upFrom(own, mount);
    }

    /** {@code group} and every group above it up to {@code mount}, {@code group} first. */
    static List<Path> upFrom(Path group, Path mount) {
        Path top = mount.normalize();
        List<Path> out = new ArrayList<>();
        for (Path p = group.normalize(); p != null && p.startsWith(top); p = p.getParent()) out.add(p);
        return List.copyOf(out);
    }

    /** The smallest finite {@code memory.max} along {@code chain}; {@code -1} when none is finite. */
    static long tightestMax(List<Path> chain) {
        long tightest = -1L;
        for (Path group : chain) {
            long max = limit(read(group.resolve("memory.max")));
            if (max > 0 && (tightest < 0 || max < tightest)) tightest = max;
        }
        return tightest;
    }

    /**
     * {@code root}'s {@code sys/fs/cgroup}. {@code /sys/fs/cgroup} itself for the host, so a reader
     * of the running host does not build the path again on every sample.
     */
    static Path mount(Path root) {
        return root.equals(HOST) ? HOST_MOUNT : root.resolve("sys/fs/cgroup");
    }

    private static final Path HOST_MOUNT = Path.of("/sys/fs/cgroup");

    /** The path of the {@code 0::} line of a {@code /proc/<pid>/cgroup} body; null when it has none. */
    static @Nullable String unifiedPath(@Nullable String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String[] parts = line.split(":", 3);
            // The unified hierarchy is hierarchy 0; a v1 line names a controller in the middle field.
            if (parts.length == 3 && parts[0].length() == 1 && parts[0].charAt(0) == '0') {
                String path = parts[2].trim();
                return path.isEmpty() ? "/" : path;
            }
        }
        return null;
    }

    /** A single-number cgroup file's bytes; {@code -1} for absent, blank, {@code max}, or the kernel's unlimited. */
    static long limit(@Nullable String text) {
        if (text == null) return -1L;
        String s = text.trim();
        if (s.isEmpty() || "max".equals(s)) return -1L;
        try {
            long v = Long.parseLong(s);
            return v <= 0 || v >= MemoryProbe.CGROUP_UNLIMITED ? -1L : v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * A usage file's bytes, such as {@code memory.current}: {@code 0} for an empty group, {@code -1}
     * when it is absent or does not parse.
     */
    static long usage(@Nullable String text) {
        if (text == null) return -1L;
        try {
            long v = Long.parseLong(text.trim());
            return v < 0 ? -1L : v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Null when the file cannot be read; an empty file is an empty string. Read through an {@link
     * InputStream}: cgroup and proc files report a size of zero, which a size-based read takes as empty.
     */
    static @Nullable String read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
