// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The workspace as the model kinds read it: every member's manifest (memoised by size and
 * modification time, so the model lane parses nothing on an unchanged tree) and the module edges
 * those manifests declare, resolved exactly as the build graph resolves them.
 */
public final class WorkspaceModel {

    private record Memo(long size, FileTime mtime, JkBuild build) {}

    private static final Map<Path, Memo> MANIFESTS = new ConcurrentHashMap<>();

    private final Path root;
    private final Map<String, JkBuild> byModule = new TreeMap<>();
    private final Map<String, String> byCoord = new LinkedHashMap<>();
    private final Map<String, String> byName = new LinkedHashMap<>();

    private WorkspaceModel(Path root) {
        this.root = root;
    }

    public static WorkspaceModel of(Path root, List<Path> modules) throws IOException {
        WorkspaceModel m = new WorkspaceModel(root);
        for (Path dir : modules) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.isRegularFile(manifest)) continue;
            JkBuild build = parse(manifest);
            String rel = rel(root, dir);
            m.byModule.put(rel, build);
            m.byCoord.put(build.project().group() + ":" + build.project().name(), rel);
            m.byName.put(build.project().name(), rel);
        }
        return m;
    }

    private static JkBuild parse(Path manifest) throws IOException {
        BasicFileAttributes a = Files.readAttributes(manifest, BasicFileAttributes.class);
        Memo hit = MANIFESTS.get(manifest);
        if (hit != null && hit.size() == a.size() && hit.mtime().equals(a.lastModifiedTime())) return hit.build();
        JkBuild build = JkBuildParser.parse(manifest);
        MANIFESTS.put(manifest, new Memo(a.size(), a.lastModifiedTime(), build));
        return build;
    }

    public Set<String> modules() {
        return byModule.keySet();
    }

    /** The workspace-relative path of the module whose manifest {@code name} is this, or {@code null}. */
    public @Nullable String moduleNamed(String name) {
        return byName.get(name);
    }

    /** Sibling modules {@code module} depends on in {@code scopes}, as workspace-relative paths. */
    public Set<String> edgesFrom(String module, List<Scope> scopes) {
        JkBuild build = byModule.get(module);
        if (build == null) return Set.of();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        byCoord.forEach((c, rel) -> dirByCoord.put(c, root.resolve(rel)));
        byName.forEach((n, rel) -> dirByName.put(n, root.resolve(rel)));
        Set<String> out = new TreeSet<>();
        for (Path p : ModuleOrder.modulePrereqs(root.resolve(module), build, dirByCoord, dirByName, scopes)) {
            String rel = rel(root, p);
            if (!rel.equals(module)) out.add(rel);
        }
        return out;
    }

    /**
     * The workspace-relative spelling of a module directory, the one every evaluator keys its
     * sites and baseline entries by: {@code ""} for the root, {@code ../sibling} for a member outside
     * it — never an absolute path, which would make the baseline machine-specific.
     */
    public static String rel(Path root, Path dir) {
        Path r = root.toAbsolutePath().normalize();
        Path d = dir.toAbsolutePath().normalize();
        return r.equals(d) ? "" : r.relativize(d).toString().replace('\\', '/');
    }

    /** Every workspace class's module by internal name, spelled by {@link #rel}: the facts of each index, keyed once. */
    public static Map<String, String> classModules(Path root, List<Path> modules) {
        Map<String, String> out = new HashMap<>();
        WorkspaceFacts.classModuleDirs(root, modules).forEach((c, dir) -> out.put(c, rel(root, dir)));
        return out;
    }
}
