// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Errors;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.ModuleEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Client project summary: parse, select, and describe. Failures ride {@code error}; this never
 * throws.
 */
public final class ProjectInfoPlans {

    private ProjectInfoPlans() {}

    /** Summarize the project at {@code dir}. */
    public static ProjectInfo projectInfo(Path dir) {
        return projectInfo(dir, null, null);
    }

    /**
     * As {@link #projectInfo(Path)} with optional {@code -m}/{@code --affected-since} filters.
     * When either selector is set, {@code moduleDirs}/{@code moduleNames} are the selection
     * (empty match is success, not an error). Invalid selectors ride {@code error}.
     */
    public static ProjectInfo projectInfo(Path dir, @Nullable String modulesSpec, @Nullable String affectedSince) {
        return projectInfo(dir, modulesSpec, affectedSince, false, true);
    }

    /**
     * {@code counts=false} skips the source/test tree walks — identity/selection callers on hot
     * paths (build/compile/release loops) never need them; only {@code jk status} does.
     * {@code affectedWip} is {@code --affected}.
     */
    public static ProjectInfo projectInfo(
            Path dir,
            @Nullable String modulesSpec,
            @Nullable String affectedSince,
            boolean affectedWip,
            boolean counts) {
        try {
            Path buildFile = ManifestPaths.manifestIn(dir);
            if (!Files.exists(buildFile)) {
                return ProjectInfo.error("no jk.toml in " + dir);
            }
            // parse() is already applyWorkspace(dir, parseLocal(file)) — calling it again here
            // re-walked the root, re-parsed it, and re-loaded every member a second time.
            JkBuild build = JkBuildParser.parse(buildFile);
            build = applyLockModulePin(dir, build);
            Workspace ws = workspace(dir, build);
            List<String> moduleDirs = ws.moduleDirs();
            List<String> moduleNames = ws.moduleNames();
            if ((modulesSpec != null && !modulesSpec.isBlank())
                    || (affectedSince != null && !affectedSince.isBlank())
                    || affectedWip) {
                Selection sel = select(dir, build, ws, modulesSpec, affectedSince, affectedWip);
                if (sel.error() != null) {
                    return ProjectInfo.error(sel.error());
                }
                moduleDirs = sel.moduleDirs();
                moduleNames = sel.moduleNames();
            }
            Counts c = counts ? count(dir, moduleDirs) : new Counts(0, 0);
            Path lockFile = LockPaths.lockFile(dir);
            boolean hasLock = Files.exists(lockFile);
            String lockJdk = hasLock ? lockJdk(lockFile) : "";
            return summary(dir, buildFile, build, ws, moduleDirs, moduleNames, c, lockFile, hasLock, lockJdk);
        } catch (RuntimeException | IOException e) {
            return ProjectInfo.error(Errors.text(e));
        }
    }

    /**
     * The workspace around {@code dir}: its root (null when standalone), the root's build, every
     * member, and each module's effective toolchain ({@code dir → <spec>@<java>}, the root's
     * inheritance applied). A member list the loader refused leaves {@code toolchains} empty —
     * the build reports that refusal for real.
     */
    private record Workspace(
            @Nullable Path wsRoot,
            String workspaceRootDir,
            JkBuild rootBuild,
            List<JkBuild> envSources,
            List<String> moduleDirs,
            List<String> moduleNames,
            Map<String, String> toolchains) {}

    private static Workspace workspace(Path dir, JkBuild build) throws IOException {
        String workspaceRootDir = "";
        List<JkBuild> envSources = new ArrayList<>(List.of(build));
        List<String> moduleDirs = new ArrayList<>();
        List<String> moduleNames = new ArrayList<>();
        Map<String, String> toolchains = new LinkedHashMap<>();
        Path wsRoot = null;
        JkBuild rootBuild = build;
        if (build.isWorkspaceRoot()) {
            wsRoot = dir;
            workspaceRootDir = dir.toString();
        } else {
            var root = WorkspaceLocator.findRoot(dir);
            if (root.isPresent()) {
                wsRoot = root.get();
                workspaceRootDir = wsRoot.toString();
                try {
                    rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(wsRoot));
                } catch (Exception ignored) {
                    rootBuild = build;
                }
            }
        }
        if (wsRoot != null && rootBuild.isWorkspaceRoot()) {
            try {
                for (var e : WorkspaceLoader.loadModules(wsRoot, rootBuild).entrySet()) {
                    String abs = e.getKey().toAbsolutePath().normalize().toString();
                    moduleDirs.add(abs);
                    moduleNames.add(e.getValue().project().name());
                    envSources.add(e.getValue()); // a member declares what the root does not
                    toolchains.put(abs, toolchain(e.getValue()));
                }
            } catch (Exception ignored) {
                for (String m : rootBuild.workspaceModules()) {
                    Path abs = wsRoot.resolve(m).toAbsolutePath().normalize();
                    moduleDirs.add(abs.toString());
                    moduleNames.add(abs.getFileName().toString());
                }
            }
        } else {
            String abs = dir.toAbsolutePath().normalize().toString();
            moduleDirs.add(abs);
            moduleNames.add(build.project().name());
            toolchains.put(abs, toolchain(build));
        }
        return new Workspace(wsRoot, workspaceRootDir, rootBuild, envSources, moduleDirs, moduleNames, toolchains);
    }

    /** A module's effective toolchain on the wire — the same spec and level its {@code ensure-jdk} resolves with. */
    private static String toolchain(JkBuild module) {
        return new ProjectInfo.Toolchain(
                        sanitizeJdk(module.project().jdk()), module.project().javaRelease())
                .encode();
    }

    /** The {@code -m}/{@code --affected-since}/{@code --affected} selection, or the selector's error. */
    private record Selection(
            List<String> moduleDirs,
            List<String> moduleNames,
            @Nullable String error) {}

    private static Selection select(
            Path dir,
            JkBuild build,
            Workspace ws,
            @Nullable String modulesSpec,
            @Nullable String affectedSince,
            boolean affectedWip) {
        Path selectRoot = ws.wsRoot() != null ? ws.wsRoot() : dir;
        JkBuild selectBuild = ws.wsRoot() != null ? ws.rootBuild() : build;
        var hit = ModuleSelection.resolveOptional(selectRoot, selectBuild, modulesSpec, affectedSince, affectedWip);
        if (hit != null && !hit.ok()) {
            return new Selection(List.of(), List.of(), hit.errorMessage());
        }
        List<String> filteredDirs = new ArrayList<>();
        List<String> filteredNames = new ArrayList<>();
        if (hit != null) {
            for (Path p : hit.moduleDirs()) {
                String abs = p.toAbsolutePath().normalize().toString();
                int idx = ws.moduleDirs().indexOf(abs);
                filteredDirs.add(abs);
                filteredNames.add(
                        idx >= 0 ? ws.moduleNames().get(idx) : p.getFileName().toString());
            }
        }
        return new Selection(filteredDirs, filteredNames, null);
    }

    private record Counts(int sources, int tests) {}

    private static Counts count(Path dir, List<String> moduleDirs) {
        int sourceCount = 0, testCount = 0;
        List<Path> countDirs = moduleDirs.isEmpty()
                ? List.of(dir)
                : moduleDirs.stream().map(Path::of).toList();
        for (Path mod : countDirs) {
            sourceCount += countSources(mod, true);
            testCount += countSources(mod, false);
        }
        return new Counts(sourceCount, testCount);
    }

    /** The lock's JDK fingerprint, or empty when the lock is unreadable (jdk-unknown, not an error). */
    private static String lockJdk(Path lockFile) {
        try {
            var pin = LockfileReader.read(lockFile).jdk();
            return pin != null ? pin.fingerprint() : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private static ProjectInfo summary(
            Path dir,
            Path buildFile,
            JkBuild build,
            Workspace ws,
            List<String> moduleDirs,
            List<String> moduleNames,
            Counts counts,
            Path lockFile,
            boolean hasLock,
            String lockJdk)
            throws IOException {
        var format = build.format();
        var boot = build.pluginConfig(JkBuild.SPRING_BOOT_ID).orElse(null);
        var testTags = JkBuildParser.parseTestTags(buildFile);
        return new ProjectInfo(
                null,
                sanitizeIdentity(build.project().group()),
                build.project().name(),
                sanitizeIdentity(build.project().version()),
                sanitizeJdk(build.project().jdk()),
                build.project().javaRelease(),
                build.project().isKotlin(),
                build.project().kotlin() == null ? "" : build.project().kotlin().raw(),
                build.project().isGroovy(),
                build.project().groovy() == null ? "" : build.project().groovy().raw(),
                SourceLayout.isSimpleLayout(build.project(), dir),
                build.isWorkspaceRoot(),
                ws.workspaceRootDir(),
                moduleDirs,
                build.isApplication(),
                build.mainClass() == null ? "" : build.mainClass(),
                build.assembly(),
                build.applicationOpt()
                        .map(JkBuild.Application::config)
                        .filter(c -> c != null && !c.isBlank())
                        .orElse(""),
                build.nativeMode().name(),
                orEmpty(build.graal()),
                build.isSpringBoot(),
                boot == null ? "" : boot.stringOpt("version").orElse(""),
                orEmpty(format.style()),
                orEmpty(format.java()),
                orEmpty(format.kotlin()),
                format.optimizeImports(),
                format.importOrder(),
                format.removeUnusedImports(),
                hasLock,
                lockJdk,
                layoutOf(build, dir, BuildLayout::mainJar),
                layoutOf(build, dir, BuildLayout::assemblyJar),
                layoutOf(build, dir, BuildLayout::nativeBinary),
                layoutOf(build, dir, BuildLayout::nativeLibrary),
                pathDeps(build),
                layoutOf(build, dir, BuildLayout::sourcesJar),
                layoutOf(build, dir, BuildLayout::javadocJar),
                VariantApply.envRefs(ws.envSources()),
                moduleNames,
                counts.sources(),
                counts.tests(),
                build.nativeExplicitlyDisabled(),
                layoutOf(build, dir, BuildLayout::classesDir),
                layoutOf(build, dir, BuildLayout::testClassesDir),
                layoutOf(build, dir, BuildLayout::kotlinClassesDir),
                layoutOf(build, dir, BuildLayout::groovyClassesDir),
                layoutOf(build, dir, BuildLayout::testResultsDir),
                testTags.includeTags(),
                testTags.excludeTags(),
                hasLock && LockFreshness.isStale(dir, lockFile),
                build.project().isScala(),
                build.project().scala() == null ? "" : build.project().scala().raw(),
                CompileSupport.coordinatorOnly(build, dir),
                build.installOpt().map(JkBuild.Install::productLib).orElse(""),
                build.installOpt().map(JkBuild.Install::productBin).orElse(""),
                ws.toolchains());
    }

    private static String sanitizeIdentity(String value) {
        if (value == null || value.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(value)) return "";
        return value;
    }

    private static String sanitizeJdk(@Nullable String jdk) {
        if (jdk == null || jdk.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(jdk)) return "";
        return jdk;
    }

    /** Apply a matching {@code [[module]]} lock pin for display identity. */
    private static JkBuild applyLockModulePin(Path cwd, JkBuild build) {
        try {
            Path lockFile = LockPaths.lockFile(cwd);
            if (!Files.isRegularFile(lockFile)) return build;
            Lockfile lock = LockfileReader.read(lockFile);
            if (lock.modules().isEmpty()) return build;
            Path owner = LockPaths.lockOwnerDir(cwd).toAbsolutePath().normalize();
            String rel = owner.relativize(cwd.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            if (rel.isEmpty()) rel = ".";
            final String pathKey = rel;
            ModuleEntry pin = lock.modules().stream()
                    .filter(m ->
                            pathKey.equals(m.path()) || build.project().name().equals(m.name()))
                    .findFirst()
                    .orElse(null);
            if (pin == null) return build;
            var p = build.project();
            String group = blankOrSentinel(p.group()) ? pin.group() : p.group();
            String version = blankOrSentinel(p.version()) ? pin.version() : p.version();
            int javaRelease = p.java() > 0 ? p.java() : (pin.java() != null ? pin.java() : 0);
            if (group.equals(p.group())
                    && version.equals(p.version())
                    && javaRelease == p.java()
                    && !p.inheritsFromWorkspace()) {
                return build;
            }
            var resolved = new Project(
                    group,
                    p.name(),
                    version,
                    p.jdk(),
                    javaRelease,
                    p.kotlin(),
                    p.groovy(),
                    p.scala(),
                    p.sourcesMode(),
                    p.javadocMode(),
                    p.description(),
                    p.m2integration(),
                    p.m2install(),
                    p.layout(),
                    Set.of(),
                    p.jdkSpec());
            return build.withProject(resolved);
        } catch (Exception e) {
            return build;
        }
    }

    private static boolean blankOrSentinel(String value) {
        return value == null || value.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(value);
    }

    private static int countSources(Path module, boolean main) {
        AtomicInteger n = new AtomicInteger();
        List<Path> roots = new ArrayList<>();
        if (main) {
            roots.add(module.resolve("src/main/java"));
            roots.add(module.resolve("src/main/kotlin"));
            roots.add(module.resolve("src/main/groovy"));
        } else {
            roots.add(module.resolve("src/test/java"));
            roots.add(module.resolve("src/test/kotlin"));
            roots.add(module.resolve("src/test/groovy"));
            roots.add(module.resolve("test/src"));
        }
        for (Path root : roots.stream().distinct().toList()) {
            if (!Files.isDirectory(root)) continue;
            n.addAndGet(InputTrees.of(root)
                    .withExtensions(".java", ".kt", ".groovy")
                    .size());
        }
        return n.get();
    }

    /** Libraries declared as path dependencies — publish/export refuse them (consume-only). */
    private static List<String> pathDeps(JkBuild build) {
        List<String> out = new ArrayList<>();
        for (var byScope : build.dependencies().byScope().entrySet()) {
            for (var dep : byScope.getValue()) {
                if (dep.isPath()) out.add(dep.library());
            }
        }
        return out;
    }

    private static String layoutOf(JkBuild build, Path dir, Function<BuildLayout, Path> f) {
        try {
            return f.apply(BuildLayout.of(dir, build)).toAbsolutePath().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
