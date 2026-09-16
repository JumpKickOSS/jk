// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * The compile-classpath entries that register a javac annotation processor through
 * {@code META-INF/services/javax.annotation.processing.Processor}: exactly the set javac's own
 * classpath discovery runs when no processor path is named, found up front so the engine can hand
 * javac a processor path, load the processors itself and name them.
 *
 * <p>A jar's answer is memoized on {@code (size, mtime)}: a classpath of a hundred jars is probed
 * once per jar, not once per compile request, and a rewritten jar re-probes. A directory entry (a
 * workspace sibling's classes tree) is a single file-existence check and is not memoized.
 */
public final class ClasspathProcessors {

    /** The service registration javac discovers processors by. */
    public static final String SERVICE = "META-INF/services/javax.annotation.processing.Processor";

    private ClasspathProcessors() {}

    private record Stamp(long size, long modified) {}

    private record Memo(Stamp stamp, boolean registers) {}

    /** Clear-on-overflow: a resident engine otherwise keeps one row per jar it ever probed. */
    private static final int MEMO_CAP = 4_096;

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

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
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(entry, BasicFileAttributes.class);
        } catch (IOException absent) {
            return false;
        }
        Stamp stamp = new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis());
        Path key = entry.toAbsolutePath().normalize();
        Memo memo = MEMO.get(key);
        if (memo != null && memo.stamp().equals(stamp)) return memo.registers();
        boolean registers = jarRegistersProcessor(entry);
        if (MEMO.size() >= MEMO_CAP) MEMO.clear();
        MEMO.put(key, new Memo(stamp, registers));
        return registers;
    }

    private static boolean jarRegistersProcessor(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(SERVICE) != null;
        } catch (IOException unreadable) {
            return false; // javac reports an unreadable classpath entry itself
        }
    }
}
