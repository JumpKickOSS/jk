// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.lock.ManifestPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * The module directories a Maven reactor lists, read from the raw {@code pom.xml} files alone: the
 * root's {@code <modules>} and those of every profile, followed through aggregators. This is the
 * bootstrap twin of {@link WorkspaceScan}'s TOML scan for a directory that is built from its POM;
 * it needs no Maven and runs in the native client.
 *
 * <p>The shadow manifest the engine renders is the module list a build reads, and it follows only
 * the profiles Maven activates on this machine, so a module listed by an inactive profile is
 * located to its root here but is not one the workspace builds.
 */
public final class PomReactorScan {

    private PomReactorScan() {}

    private static final int MAX_DEPTH = 8192;

    /** Bounds the walk of one reactor; a tree past this is a cycle through symlinks, not a build. */
    private static final int MAX_POMS = 4096;

    /** The {@code <module>} entries of each pom.xml, from the memo while the file's stamp matches. */
    private static final StampedMemo<Path, StampedMemo.FileStamp, List<String>> MODULES = StampedMemo.create();

    /** True when {@code pom} lists modules anywhere: at the top level or inside a profile. */
    public static boolean declaresModules(Path pom) {
        return !modulesOf(pom).isEmpty();
    }

    /**
     * Every module directory of the reactor rooted at {@code rootDir}, aggregators included,
     * relative to the root with {@code /} separators. A module whose pom.xml is missing or lies
     * outside the root is skipped, as the import skips it.
     */
    public static Set<String> memberDirs(Path rootDir) {
        Path root = rootDir.toAbsolutePath().normalize();
        Set<String> out = new LinkedHashSet<>();
        walk(root, root.resolve(ManifestPaths.POM), out, new HashSet<>());
        return out;
    }

    /**
     * The outermost strict ancestor of {@code dir} that is built from its POM and whose reactor
     * lists {@code dir}, or empty. Outermost, because a nested aggregator lists its children too
     * and the workspace is the whole tree.
     */
    public static Optional<Path> reactorRootOf(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        Path candidate = normalized;
        Path found = null;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            if (ManifestPaths.isShadowed(parent)) {
                String rel = parent.relativize(normalized).toString().replace(File.separatorChar, '/');
                if (memberDirs(parent).contains(rel)) found = parent;
            }
            candidate = parent;
        }
        return Optional.ofNullable(found);
    }

    /**
     * The {@code <module>} texts of one POM as written, top level first, then every profile's.
     * Empty for an absent file or one jk's XML parser refuses.
     */
    static List<String> modulesOf(Path pom) {
        Path file = pom.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null) return List.of();
        return MODULES.get(file, stamp, () -> read(file));
    }

    private static List<String> read(Path pom) {
        try {
            Element project = DomXml.parse(pom).getDocumentElement();
            List<String> out = new ArrayList<>();
            collect(project, out);
            for (Element profiles : DomXml.childElements(project, "profiles")) {
                for (Element profile : DomXml.childElements(profiles, "profile")) collect(profile, out);
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException notAPom) {
            return List.of();
        }
    }

    private static void collect(Element parent, List<String> out) {
        for (Element modules : DomXml.childElements(parent, "modules")) {
            for (Element module : DomXml.childElements(modules, "module")) {
                String text = module.getTextContent().trim();
                if (!text.isEmpty()) out.add(text);
            }
        }
    }

    private static void walk(Path root, Path pom, Set<String> out, Set<Path> visited) {
        if (!visited.add(pom) || visited.size() > MAX_POMS) return;
        for (String module : modulesOf(pom)) {
            Path child = childPom(pom, module);
            Path dir = child.getParent();
            if (dir == null || !dir.startsWith(root) || dir.equals(root) || !Files.isRegularFile(child)) continue;
            out.add(root.relativize(dir).toString().replace(File.separatorChar, '/'));
            walk(root, child, out, visited);
        }
    }

    /** {@code <module>} names a directory, or the pom.xml itself. */
    private static Path childPom(Path pom, String module) {
        Path target =
                Objects.requireNonNull(pom.getParent()).resolve(module.trim()).normalize();
        return Files.isRegularFile(target) ? target : target.resolve(ManifestPaths.POM);
    }
}
