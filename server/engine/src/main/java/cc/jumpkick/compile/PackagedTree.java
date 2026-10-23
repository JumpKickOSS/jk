// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The files a packager writes as entries, by entry name: one classes tree, or a module's classes
 * with trees laid over it (a plugin's contributed classes), read where they lie rather than copied
 * into one directory first. Laying a tree over replaces an entry of the same name, as a copy would;
 * an entry can also be set to bytes or removed. Files are the entries, a link to one read through
 * as {@link PathUtil#forEachRegularFile} reads it; a directory is implied by the files under it.
 */
public final class PackagedTree {

    /** One entry: a file to copy, or bytes written in its place. */
    public record Entry(String name, @Nullable Path file, byte @Nullable [] bytes) {}

    private final Path root;
    private final @Nullable TreeMap<String, Entry> entries;

    private PackagedTree(Path root, @Nullable TreeMap<String, Entry> entries) {
        this.root = root;
        this.entries = entries;
    }

    /** Every regular file under {@code dir}, listed when a packager reads it. */
    public static PackagedTree of(Path dir) {
        return new PackagedTree(Objects.requireNonNull(dir, "dir"), null);
    }

    /** The tree the entries are rooted at: the module's own classes. */
    public Path root() {
        return root;
    }

    /** The entries, sorted by name; an absent root has none. */
    public List<Entry> entries() throws IOException {
        if (entries != null) return List.copyOf(entries.values());
        TreeMap<String, Entry> listed = new TreeMap<>();
        addTree(listed, root, dir -> false);
        return List.copyOf(listed.values());
    }

    /** Write every entry under {@code dir}, a file with its modification time. */
    public void copyInto(Path dir) throws IOException {
        for (Entry e : entries()) {
            Path target = dir.resolve(e.name());
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path file = e.file();
            if (file != null) {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(target, Files.getLastModifiedTime(file));
            } else {
                Files.write(target, Objects.requireNonNull(e.bytes()));
            }
        }
    }

    /** {@link #root}'s files, then each {@link #layer}'s, later names replacing earlier ones. */
    public static Builder builder(Path root) throws IOException {
        TreeMap<String, Entry> start = new TreeMap<>();
        addTree(start, root, dir -> false);
        return new Builder(root, start);
    }

    /** Builds a laid-over tree. */
    public static final class Builder {
        private final Path root;
        private final TreeMap<String, Entry> entries;

        private Builder(Path root, TreeMap<String, Entry> entries) {
            this.root = root;
            this.entries = entries;
        }

        /** {@code tree}'s files over the entries so far, leaving out any subdirectory {@code skip} accepts. */
        public Builder layer(Path tree, Predicate<Path> skip) throws IOException {
            addTree(entries, tree, skip);
            return this;
        }

        /** {@code name} as {@code file}. */
        public Builder put(String name, Path file) {
            entries.put(name, new Entry(name, file, null));
            return this;
        }

        /** {@code name} as {@code bytes}. */
        public Builder put(String name, byte[] bytes) {
            entries.put(name, new Entry(name, null, bytes.clone()));
            return this;
        }

        /** No entry {@code name}. */
        public Builder remove(String name) {
            entries.remove(name);
            return this;
        }

        public PackagedTree build() {
            return new PackagedTree(root, new TreeMap<>(entries));
        }
    }

    private static void addTree(Map<String, Entry> into, Path tree, Predicate<Path> skip) throws IOException {
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(tree, skip, (file, attrs) -> files.add(file));
        for (Path file : files) {
            String name = tree.relativize(file).toString().replace(File.separatorChar, '/');
            into.put(name, new Entry(name, file, null));
        }
    }
}
