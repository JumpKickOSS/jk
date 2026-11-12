// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Whether the jar an install would ship for a module it does not rebuild holds the classes compiled
 * in that module's classes tree; one that does not is rebuilt. A clean claim that skips a module
 * installs the jar already on disk as it is: when that jar was packaged from other classes, the
 * install ships them. One did: a
 * snapshot trusted a build's clean claim, installed the -SNAPSHOT jars an earlier snapshot left,
 * and the engine and client it installed failed every command (JK-MAG71).
 */
final class InstallFreshness {

    private InstallFreshness() {}

    /**
     * Why {@code module}'s jar must not be installed as it is, or {@code null} when it holds every
     * class its classes tree does, byte for byte. A module with no jar or no classes tree yet, or one
     * that relocates its classes into the jar, has nothing to compare and passes.
     */
    static @Nullable String stale(Path moduleDir, JkBuild module) {
        if (module.relocates()) return null;
        BuildLayout layout = BuildLayout.of(moduleDir, module);
        Path jar = layout.mainJar();
        Path classes = layout.classesDir();
        if (!Files.isRegularFile(jar) || !Files.isDirectory(classes)) return null;
        try {
            List<String> differ = differing(jar, classes);
            if (differ.isEmpty()) return null;
            return jar.getFileName() + " does not hold the classes compiled in " + classes + " ("
                    + differ.size() + (differ.size() == 1 ? " class differs" : " classes differ") + ", first "
                    + differ.getFirst() + "): rebuilding it rather than installing it as it is";
        } catch (IOException e) {
            return jar.getFileName() + " could not be compared with " + classes + " (" + e.getMessage()
                    + "): rebuilding it rather than installing it as it is";
        }
    }

    /** The class files under {@code classes} that {@code jar} lacks or holds other bytes of, by entry name. */
    static List<String> differing(Path jar, Path classes) throws IOException {
        Map<String, Long> crcs = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")) crcs.put(e.getName(), e.getCrc());
            }
        }
        List<String> differ = new ArrayList<>();
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            String name = classes.relativize(file).toString().replace('\\', '/');
            if (!name.endsWith(".class")) return;
            Long inJar = crcs.get(name);
            if (inJar == null || inJar != crc(file)) differ.add(name);
        });
        differ.sort(null);
        return differ;
    }

    private static long crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }
}
