// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.lock.ManifestPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * located to its root here but is not one the workspace builds; {@link #profilesListing} names the
 * profiles that list it, for the refusal that says so.
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
     * The ids of the profiles whose {@code <modules>} list {@code moduleDir}, read from the root's
     * pom.xml and every aggregator's below it; empty when only a top-level {@code <modules>} lists
     * it. A profile without an {@code <id>} is reported as {@code (unnamed)}.
     */
    public static Set<String> profilesListing(Path rootDir, Path moduleDir) {
        Path root = rootDir.toAbsolutePath().normalize();
        Path module = moduleDir.toAbsolutePath().normalize();
        Set<String> out = new LinkedHashSet<>();
        Set<Path> visited = new HashSet<>();
        Deque<Path> poms = new ArrayDeque<>(List.of(root.resolve(ManifestPaths.POM)));
        while (!poms.isEmpty() && visited.size() <= MAX_POMS) {
            Path pom = poms.poll();
            if (!visited.add(pom)) continue;
            for (Map.Entry<String, List<String>> profile : profileModules(pom).entrySet()) {
                for (String text : profile.getValue()) {
                    if (module.equals(childPom(pom, text).getParent())) out.add(profile.getKey());
                }
            }
            for (String text : modulesOf(pom)) {
                Path child = childPom(pom, text);
                if (child.startsWith(root) && Files.isRegularFile(child)) poms.add(child);
            }
        }
        return out;
    }

    /** Profile id to the {@code <module>} texts it declares, for the profiles of {@code pom} that declare any. */
    private static Map<String, List<String>> profileModules(Path pom) {
        try {
            Element project = DomXml.parse(pom).getDocumentElement();
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Element profiles : DomXml.childElements(project, "profiles")) {
                for (Element profile : DomXml.childElements(profiles, "profile")) {
                    List<String> modules = new ArrayList<>();
                    collect(profile, modules);
                    if (modules.isEmpty()) continue;
                    String id = DomXml.childElements(profile, "id").stream()
                            .map(e -> e.getTextContent().trim())
                            .filter(text -> !text.isEmpty())
                            .findFirst()
                            .orElse("(unnamed)");
                    out.computeIfAbsent(id, k -> new ArrayList<>()).addAll(modules);
                }
            }
            return out;
        } catch (IOException | RuntimeException notAPom) {
            return Map.of();
        }
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
