// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.task.JarClassReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The compile-classpath entries that register a javac annotation processor through
 * {@code META-INF/services/javax.annotation.processing.Processor}: exactly the set javac's own
 * classpath discovery runs when no processor path is named, found up front so the engine knows
 * whether the classpath is a processor path at all and can name what runs.
 *
 * <p>A jar's answer is memoized on {@code (size, mtime)}: a classpath of a hundred jars is probed
 * once per jar, not once per compile request, and a rewritten jar re-probes. A directory entry (a
 * workspace sibling's classes tree) is a single file-existence check and is not memoized.
 */
public final class ClasspathProcessors {

    /** The service registration javac discovers processors by. */
    public static final String SERVICE = "META-INF/services/javax.annotation.processing.Processor";

    private ClasspathProcessors() {}

    /** Clear-on-overflow: a resident engine otherwise keeps one row per jar it ever probed. */
    private static final StampedMemo<Path, FileStamp, Boolean> MEMO = StampedMemo.bounded(4_096);

    /** The entries of {@code classpath} that register a processor, in classpath order. */
    public static List<Path> discover(List<Path> classpath) {
        List<Path> out = new ArrayList<>();
        for (Path entry : classpath) {
            if (registersProcessor(entry)) out.add(entry);
        }
        return List.copyOf(out);
    }

    /** True when {@code entry} (a jar or a classes directory) carries the service registration. */
    public static boolean registersProcessor(Path entry) {
        if (Files.isDirectory(entry)) return Files.isRegularFile(entry.resolve(SERVICE));
        FileStamp stamp = FileStamp.of(entry);
        if (stamp == null) return false;
        return MEMO.get(entry.toAbsolutePath().normalize(), stamp, () -> jarRegistersProcessor(entry));
    }

    /**
     * The processor classes {@code entry} registers, in service-file order: the lines of its
     * registration without comments and blanks. Empty for an entry that registers none or cannot
     * be read.
     */
    public static List<String> processorNames(Path entry) {
        byte @Nullable [] registration;
        try {
            if (Files.isDirectory(entry)) {
                Path file = entry.resolve(SERVICE);
                if (!Files.isRegularFile(file)) return List.of();
                registration = Files.readAllBytes(file);
            } else {
                try (JarClassReader jar = JarClassReader.open(entry)) {
                    registration = jar.entry(SERVICE);
                }
                if (registration == null) return List.of();
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : new String(registration, StandardCharsets.UTF_8).split("\\R")) {
            int hash = line.indexOf('#');
            String name = (hash < 0 ? line : line.substring(0, hash)).strip();
            if (!name.isEmpty()) names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * Read through {@link JarClassReader}, which streams the central directory: {@code ZipFile}
     * holds all of it in heap, fifty megabytes for an SDK bundle jar.
     */
    private static boolean jarRegistersProcessor(Path jar) {
        try (JarClassReader reader = JarClassReader.open(jar)) {
            return reader.entry(SERVICE) != null;
        } catch (IOException unreadable) {
            return false; // javac reports an unreadable classpath entry itself
        }
    }
}
