// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Lays a web archive out in a directory, as Maven's war plugin does, and archives it: the webapp
 * directory's files at the root, the module's classes under {@code WEB-INF/classes} and its runtime
 * jars under {@code WEB-INF/lib}. The archive is reproducible in the way {@link JarPackager}'s jars are.
 */
public final class WarPackager {

    static final String CLASSES = "WEB-INF/classes";
    static final String LIB = "WEB-INF/lib";

    /**
     * Write {@code request.explodedDir()} anew, then {@code request.warFile()} from it. The web
     * roots rank in order, the webapp first: the first root to carry a path wins, so they are copied
     * last-first, and every later copy of a path is reported in the result.
     */
    public War packageWar(WarRequest request) throws IOException {
        Path exploded = request.explodedDir();
        PathUtil.deleteRecursivelyOrThrow(exploded);
        Files.createDirectories(exploded);
        List<Map.Entry<Path, String>> roots = new ArrayList<>();
        if (request.webapp() != null) roots.add(Map.entry(request.webapp(), ""));
        roots.addAll(request.webContent().entrySet());
        List<Shadowed> shadowed = shadowed(roots);
        for (Map.Entry<Path, String> root : roots.reversed()) {
            PathUtil.copyTree(root.getKey(), exploded.resolve(root.getValue()), PathUtil.Copy.OVERWRITE_ALWAYS);
        }
        Path classes = exploded.resolve(CLASSES);
        request.classes().copyInto(classes);
        deleteStamps(classes);
        Path lib = exploded.resolve(LIB);
        Files.createDirectories(lib);
        for (Map.Entry<String, Path> jar : libNames(request.libs()).entrySet()) {
            Files.copy(jar.getValue(), lib.resolve(jar.getKey()), StandardCopyOption.REPLACE_EXISTING);
        }
        Path war = new JarPackager()
                .packageJar(
                        JarPackager.JarRequest.of(exploded, request.warFile()).withAttributes(request.attributes()));
        return new War(war, List.copyOf(shadowed));
    }

    /**
     * Each war path that more than one web root carries, against the first root to carry it. Each
     * root pairs with its war-relative prefix ({@code ""} for the war root), in rank order.
     */
    private static List<Shadowed> shadowed(List<Map.Entry<Path, String>> roots) throws IOException {
        Map<String, Path> owners = new HashMap<>();
        List<Shadowed> shadowed = new ArrayList<>();
        for (Map.Entry<Path, String> entry : roots) {
            Path root = entry.getKey();
            String prefix = entry.getValue();
            List<String> paths = new ArrayList<>();
            PathUtil.forEachRegularFile(root, (file, attrs) -> {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                paths.add(prefix.isEmpty() ? relative : prefix + "/" + relative);
            });
            paths.sort(null);
            for (String path : paths) {
                Path owner = owners.putIfAbsent(path, root);
                if (owner != null) shadowed.add(new Shadowed(path, owner, root));
            }
        }
        return shadowed;
    }

    /**
     * Each jar's name in {@code WEB-INF/lib}: its own file name, or, for jars that share one, the
     * name prefixed with the directory above the artifact's (the last segment of a repository
     * path's group), and a counter past that.
     */
    static Map<String, Path> libNames(List<Path> jars) {
        Map<String, Integer> uses = new HashMap<>();
        for (Path jar : jars) uses.merge(fileName(jar), 1, Integer::sum);
        Map<String, Path> named = new LinkedHashMap<>();
        for (Path jar : jars) {
            String name = fileName(jar);
            if (uses.getOrDefault(name, 0) > 1) name = groupHint(jar) + "-" + name;
            String unique = name;
            for (int n = 2; named.containsKey(unique); n++) unique = n + "-" + name;
            named.put(unique, jar);
        }
        return named;
    }

    private static String fileName(Path jar) {
        return String.valueOf(jar.getFileName());
    }

    /** {@code <group>/<artifact>/<version>/<file>}: the group's last segment, or {@code dup} for a shorter path. */
    private static String groupHint(Path jar) {
        Path version = jar.getParent();
        Path artifact = version == null ? null : version.getParent();
        Path group = artifact == null ? null : artifact.getParent();
        Path name = group == null ? null : group.getFileName();
        return name == null ? "dup" : name.toString();
    }

    private static void deleteStamps(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> stamps = new ArrayList<>();
        try (Stream<Path> files = Files.find(dir, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
            files.filter(p -> BuildStamps.isStampFile(String.valueOf(p.getFileName())))
                    .forEach(stamps::add);
        }
        for (Path stamp : stamps) Files.deleteIfExists(stamp);
    }

    /**
     * Input to {@link #packageWar}.
     *
     * @param webapp the web resources directory; {@code null} when the module has none
     * @param libs the runtime jars, in classpath order
     * @param attributes the archive manifest's attributes beyond {@code Manifest-Version}
     * @param webContent directories copied into the war after the webapp, in declaration order, each
     *     under its war-relative path ({@code ""} for the root): a node sibling's build output
     */
    public record WarRequest(
            PackagedTree classes,
            @Nullable Path webapp,
            List<Path> libs,
            Path explodedDir,
            Path warFile,
            Map<String, String> attributes,
            Map<Path, String> webContent) {

        /** The files of one classes directory. */
        public WarRequest(
                Path classes,
                @Nullable Path webapp,
                List<Path> libs,
                Path explodedDir,
                Path warFile,
                Map<String, String> attributes,
                Map<Path, String> webContent) {
            this(PackagedTree.of(classes), webapp, libs, explodedDir, warFile, attributes, webContent);
        }

        public WarRequest {
            Objects.requireNonNull(classes, "classes");
            Objects.requireNonNull(explodedDir, "explodedDir");
            Objects.requireNonNull(warFile, "warFile");
            libs = List.copyOf(libs);
            webContent = Collections.unmodifiableMap(new LinkedHashMap<>(webContent));
            attributes = Map.copyOf(attributes);
        }
    }

    /** The archive written, and each war path a later web root also carried. */
    public record War(Path file, List<Shadowed> shadowed) {}

    /** {@code path} in the war comes from {@code winner}; {@code loser}'s copy was left out. */
    public record Shadowed(String path, Path winner, Path loser) {}
}
