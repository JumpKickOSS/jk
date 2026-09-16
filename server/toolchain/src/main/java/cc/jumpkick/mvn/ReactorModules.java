// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.repo.Pom;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.jspecify.annotations.Nullable;

/**
 * The modules of one Maven reactor, found the way Maven finds them: the root's {@code <modules>}
 * plus those of every profile active on this machine, followed recursively through aggregator
 * modules. An aggregator (packaging {@code pom} with {@code <modules>}) is a parent and a list, not
 * a module the workspace builds; every other pom.xml the walk reaches is a leaf, named by its
 * directory relative to the root with {@code /} separators ({@code websocket/spi}).
 */
final class ReactorModules {

    /** One module the workspace lists: its relative path, its pom.xml and its effective model. */
    record Leaf(String path, Path pomFile, EffectiveModel model) {}

    /**
     * A pom.xml the reactor registered that is not a workspace module: an aggregator, or a module
     * only an inactive profile lists. {@code path} is root-relative; {@code why} completes "names
     * `path/pom.xml`, …"; {@code classpath} is what a {@code <type>pom</type>} edge to it puts on
     * the dependent's classpath under Maven (the POM's effective compile and runtime dependencies,
     * versions resolved), empty when the effective model is not built here.
     */
    record Unbuilt(String path, String why, List<Pom.Dep> classpath, boolean aggregator) {

        /** True when a {@code <type>pom</type>} edge to it is rewritten onto the dependent without loss. */
        boolean carriesOnto(boolean platform) {
            return platform && aggregator;
        }

        /**
         * The report row for {@code module}, a dependency of a member, that names this POM. {@code
         * written} are the dependencies of this POM the dependent received in the pom edge's place.
         */
        String row(String module, boolean platform, List<String> written) {
            String subject =
                    (platform ? "`<type>pom</type>` on " : "") + module + " names `" + path + "/pom.xml`, " + why;
            if (!platform) {
                return subject + "; the lock would fetch it from a repository, where a reactor module is not"
                        + " published, so the dependency was not written.";
            }
            String row = subject + "; the lock fetches a `[platform]` entry from a repository, where a reactor POM"
                    + " is not published, so no row is written";
            if (classpath.isEmpty()) return row + ".";
            if (written.isEmpty()) {
                return row + "; its own dependencies " + modules(classpath) + ", which Maven adds to the"
                        + " dependent's classpath through the pom, are declared by this module already.";
            }
            return row + "; its own dependencies " + String.join(", ", written) + ", which Maven adds to the"
                    + " dependent's classpath through the pom, are written on this module in its place, a"
                    + " workspace module among them as a workspace edge.";
        }

        private static String modules(List<Pom.Dep> deps) {
            return String.join(", ", deps.stream().map(Pom.Dep::module).toList());
        }
    }

    /**
     * What the walk found: the leaves the workspace builds, the BOM leaves it does not — a
     * {@code pom}-packaged module with no {@code <modules>} whose own POM is a {@code
     * <dependencyManagement>} table and nothing else, with no sources to compile and no jar to
     * package, whose managed versions reach the members through their effective models — the
     * registered POMs that are neither, keyed by {@code group:artifact}, and every pom.xml the
     * reactor registered (the root, each leaf, each aggregator and the modules of inactive
     * profiles) in registration order.
     */
    record Reactor(List<Leaf> modules, List<Leaf> boms, Map<String, Unbuilt> unbuilt, Set<Path> pomFiles) {}

    /** Who listed a registered pom.xml: the listing POM and the profile, {@code null} for its top-level {@code <modules>}. */
    private record Listing(Path pomFile, @Nullable String profile) {}

    private final Path projectDir;
    private final ReactorModelResolver reactor;
    private final ImportReport.Builder report;
    private final Set<Path> registered = new LinkedHashSet<>();
    private final Map<Path, Model> raws = new HashMap<>();
    private final Map<Path, Listing> listedBy = new HashMap<>();
    private final Set<Path> walked = new HashSet<>();

    private ReactorModules(Path projectDir, ReactorModelResolver reactor, ImportReport.Builder report) {
        this.projectDir = projectDir;
        this.reactor = reactor;
        this.report = report;
    }

    /** True when the POM lists modules anywhere: at the top level or inside a profile. */
    static boolean declaresModules(Model raw) {
        if (!raw.getModules().isEmpty()) return true;
        for (Profile profile : raw.getProfiles()) {
            if (!profile.getModules().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Register every pom.xml of the reactor with {@code reactor}, then walk the effective models
     * from the root down. Registration follows the raw {@code <modules>} of every profile, active
     * or not, so any POM of the tree can answer as a parent or a BOM for any other; the walk
     * follows only what Maven would build here.
     */
    static Reactor collect(
            Path rootFile, byte[] rootXml, Model rootRaw, ReactorModelResolver reactor, ImportReport.Builder report)
            throws IOException {
        Path projectDir = Objects.requireNonNull(rootFile.getParent());
        ReactorModules modules = new ReactorModules(projectDir, reactor, report);
        modules.register(rootFile, rootXml, rootRaw);
        Reactor found = new Reactor(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), Set.of());
        modules.walked.add(rootFile);
        modules.walk(rootFile, reactor.effective(rootFile), found);
        modules.unwalked(found.unbuilt());
        return new Reactor(found.modules(), found.boms(), found.unbuilt(), Set.copyOf(modules.registered));
    }

    /**
     * True for a BOM's own POM: only a {@code <dependencyManagement>} table, with no dependencies
     * and no plugins of its own. Judged on the raw model, since the effective one inherits the
     * parent's management and every leaf would read as a BOM.
     */
    static boolean isBom(Model raw) {
        DependencyManagement management = raw.getDependencyManagement();
        if (management == null || management.getDependencies().isEmpty()) return false;
        if (!raw.getDependencies().isEmpty()) return false;
        return raw.getBuild() == null || raw.getBuild().getPlugins().isEmpty();
    }

    private void register(Path pomFile, byte[] xml, Model raw) throws IOException {
        if (!registered.add(pomFile)) return;
        raws.put(pomFile, raw);
        reactor.add(pomFile, xml, raw);
        for (String module : raw.getModules()) register(pomFile, null, module);
        for (Profile profile : raw.getProfiles()) {
            for (String module : profile.getModules()) register(pomFile, profile.getId(), module);
        }
    }

    /** Register {@code module} of {@code pomFile}, remembering who listed it first. */
    private void register(Path pomFile, @Nullable String profile, String module) throws IOException {
        Path child = childPom(pomFile, module);
        if (!Files.isRegularFile(child) || registered.contains(child)) return;
        listedBy.put(child, new Listing(pomFile, profile));
        register(child);
    }

    private void register(Path pomFile) throws IOException {
        if (registered.contains(pomFile)) return;
        byte[] xml = Files.readAllBytes(pomFile);
        register(pomFile, xml, EffectiveModel.rawModel(xml));
    }

    private void walk(Path pomFile, EffectiveModel em, Reactor found) throws IOException {
        for (String module : em.model().getModules()) {
            Path childPom = childPom(pomFile, module);
            if (!Files.isRegularFile(childPom)) {
                report.error("workspace module `" + module + "` has no pom.xml at " + childPom);
                continue;
            }
            String path = relativePath(childPom);
            if (path == null) {
                report.error(
                        "module `" + module + "` of " + relativeOrRoot(pomFile) + " lies outside the root directory;"
                                + " a jk workspace lists modules under its root, so it was skipped.");
                continue;
            }
            if (!walked.add(childPom)) continue;
            register(childPom);
            EffectiveModel child = reactor.effective(childPom);
            Model model = child.model();
            Leaf leaf = new Leaf(path, childPom, child);
            if (!"pom".equals(model.getPackaging())) found.modules().add(leaf);
            if (!model.getModules().isEmpty()) {
                found.unbuilt().put(model.getGroupId() + ":" + model.getArtifactId(), aggregator(path, model));
                walk(childPom, child, found);
            } else if ("pom".equals(model.getPackaging())) {
                (isBom(child.raw()) ? found.boms() : found.modules()).add(leaf);
            }
        }
    }

    private static Unbuilt aggregator(String path, Model model) {
        List<Pom.Dep> classpath = new ArrayList<>();
        for (Dependency d : model.getDependencies()) {
            String scope = d.getScope();
            boolean onClasspath =
                    scope == null || scope.isBlank() || "compile".equals(scope) || "runtime".equals(scope);
            if (onClasspath && !d.isOptional()) classpath.add(EffectiveModel.toDep(d));
        }
        String modules = "`" + String.join("`, `", model.getModules()) + "`";
        return new Unbuilt(
                path,
                "the aggregator of " + modules + " (packaging `pom` with `<modules>`), which is a parent and a list"
                        + " rather than a module the workspace builds",
                List.copyOf(classpath),
                true);
    }

    /** Every registered pom.xml the walk did not reach is a module Maven would not build here either. */
    private void unwalked(Map<String, Unbuilt> unbuilt) {
        for (Path pomFile : registered) {
            if (walked.contains(pomFile)) continue;
            String path = relativePath(pomFile);
            Listing listing = listedBy.get(pomFile);
            Model raw = raws.get(pomFile);
            if (path == null || listing == null || raw == null) continue;
            Parent parent = raw.getParent();
            String group = raw.getGroupId() != null ? raw.getGroupId() : parent != null ? parent.getGroupId() : null;
            String lister = relativeOrRoot(listing.pomFile());
            String why = listing.profile() == null
                    ? "which only " + lister + " lists, itself not built here"
                    : "which only profile `" + listing.profile() + "` of " + lister + " lists, and that profile is"
                            + " not active on this machine (activate it with Maven's `-P` and re-import, or list"
                            + " the module at the top level)";
            unbuilt.putIfAbsent(group + ":" + raw.getArtifactId(), new Unbuilt(path, why, List.of(), false));
        }
    }

    /** {@code <module>} names a directory, or the pom.xml itself. */
    private static Path childPom(Path pomFile, String module) {
        Path target = Objects.requireNonNull(pomFile.getParent())
                .resolve(module.trim())
                .normalize();
        return Files.isRegularFile(target) ? target : target.resolve("pom.xml");
    }

    /** The module directory relative to the root, {@code /}-separated; {@code null} outside the root. */
    private @Nullable String relativePath(Path childPom) {
        Path dir = Objects.requireNonNull(childPom.getParent()).normalize();
        if (!dir.startsWith(projectDir) || dir.equals(projectDir)) return null;
        return projectDir.relativize(dir).toString().replace(File.separatorChar, '/');
    }

    private String relativeOrRoot(Path pomFile) {
        String path = relativePath(pomFile);
        return path == null ? "the root pom.xml" : "`" + path + "`";
    }
}
