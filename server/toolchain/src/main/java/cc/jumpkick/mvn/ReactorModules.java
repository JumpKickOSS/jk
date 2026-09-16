// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
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
     * What the walk found: the leaves the workspace builds, and the BOM leaves it does not — a
     * {@code pom}-packaged module with no {@code <modules>} whose own POM is a {@code
     * <dependencyManagement>} table and nothing else. A BOM has no sources to compile and no jar to
     * package; its managed versions reach the members through their effective models.
     */
    record Reactor(List<Leaf> modules, List<Leaf> boms) {}

    private final Path projectDir;
    private final ReactorModelResolver reactor;
    private final ImportReport.Builder report;
    private final Set<Path> registered = new HashSet<>();
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
        Reactor found = new Reactor(new ArrayList<>(), new ArrayList<>());
        modules.walked.add(rootFile);
        modules.walk(rootFile, reactor.effective(rootFile), found);
        return found;
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
        reactor.add(pomFile, xml, raw);
        List<String> paths = new ArrayList<>(raw.getModules());
        for (Profile profile : raw.getProfiles()) paths.addAll(profile.getModules());
        for (String module : paths) {
            Path child = childPom(pomFile, module);
            if (Files.isRegularFile(child)) register(child);
        }
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
                walk(childPom, child, found);
            } else if ("pom".equals(model.getPackaging())) {
                (isBom(child.raw()) ? found.boms() : found.modules()).add(leaf);
            }
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
