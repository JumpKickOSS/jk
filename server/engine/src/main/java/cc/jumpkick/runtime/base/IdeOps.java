// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.jdk.StableJdkPointer;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk ide} model math (thin-client contract): resolving the workspace, its
 * modules, external libraries (lockfile + Maven-layout jars), cross-module edges, and per-module JDK/SDK
 * handles all need the parsed models, so it runs engine-side and ships as an {@link IdeWireModel}.
 * The IDE-specific file generators are {@code cc.jumpkick.ide}, which the CLI and the engine both
 * run over this model.
 *
 * <p>The dependency <em>sync</em> is not here: {@code jk ide} runs one hosted {@code jk sync}
 * (client-rendered) before requesting the model; a caller with no hosted sync in front of it (a
 * test, the MCP tool) passes {@code fetchMissing} for the in-line fetch, so both paths build the
 * same model.
 *
 * <p>{@link StableJdkPointer#ensure} writes under the jk home — the engine and client share it, so
 * pointer maintenance is equally correct here at model-build time.
 */
public final class IdeOps {

    private IdeOps() {}

    public static IdeWireModel ideModel(Path startDir, Path cache, @Nullable Path jdksDir, boolean fetchMissing) {
        try {
            return build(startDir, cache, jdksDir, fetchMissing);
        } catch (IOException | RuntimeException e) {
            return IdeWireModel.error(Errors.text(e));
        }
    }

    private static IdeWireModel build(Path startDir, Path cache, @Nullable Path jdksDir, boolean fetchMissing)
            throws IOException {
        Cas cas = JkStores.storeCas();

        Path buildFile = ManifestPaths.manifestIn(startDir);
        if (!Files.exists(buildFile)) {
            return IdeWireModel.error("no jk.toml in " + startDir);
        }
        Workspace ws = workspace(startDir, JkBuildParser.parse(buildFile));
        Path wsRoot = ws.root();
        JkBuild rootBuild = ws.rootBuild();

        Map<Path, JkBuild> modules =
                rootBuild.isWorkspaceRoot() ? WorkspaceLoader.loadModules(wsRoot, rootBuild) : Map.of();
        Map<Path, JkBuild> allModules = new LinkedHashMap<>();
        if (modules.isEmpty()) allModules.put(wsRoot, rootBuild);
        else allModules.putAll(modules);

        // Library definitions across every module: name → (fileName, jar, sources).
        Map<String, String[]> allLibs = new LinkedHashMap<>(); // libName → {fileName, jar, sources|null}
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            collectLibDefs(me.getKey(), me.getValue(), modules, cas, allLibs, fetchMissing);
        }

        // Per-module JDK/SDK handles.
        JdkRegistry jdkRegistry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        StableJdkPointer pointer = jdksDir != null ? new StableJdkPointer(jdksDir) : StableJdkPointer.atDefaultRoot();
        List<String> sdkEntries = new ArrayList<>(); // "name|home|version"
        Set<String> seenSdk = new LinkedHashSet<>();
        Map<Path, String[]> sdkRefs = new LinkedHashMap<>(); // dir → {stable, sdkName, level, home, version}
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            sdkRefs.put(me.getKey(), sdkRefFor(me.getKey(), me.getValue(), jdkRegistry, pointer, sdkEntries, seenSdk));
        }
        String[] defaultSdk =
                defaultSdkRef(wsRoot, rootBuild, modules, sdkRefs, jdkRegistry, pointer, sdkEntries, seenSdk);

        List<Path> dirs = new ArrayList<>(allModules.keySet());
        Edges edges = edges(dirs, allModules, modules, allLibs);
        ModuleColumns m = moduleColumns(dirs, allModules, sdkRefs);
        LibColumns libs = libColumns(allLibs);

        return new IdeWireModel(
                null,
                wsRoot.toString(),
                rootBuild.project().name(),
                !modules.isEmpty(),
                m.moduleDirs(),
                m.names(),
                m.javaReleases(),
                m.mainClasses(),
                m.classesDirs(),
                m.testClassesDirs(),
                m.jdtClassesDirs(),
                m.jdtTestClassesDirs(),
                m.genSrcDirs(),
                m.genTestSrcDirs(),
                libs.names(),
                libs.files(),
                libs.jars(),
                libs.sources(),
                edges.siblingRefs(),
                edges.libEntries(),
                edges.processorJars(),
                m.sdkStableNames(),
                m.sdkNames(),
                m.sdkLevels(),
                m.sdkHomes(),
                m.sdkVersions(),
                defaultSdk[0],
                defaultSdk[1],
                Integer.parseInt(defaultSdk[2]),
                defaultSdk[3],
                defaultSdk[4],
                sdkEntries);
    }

    /** The workspace root and its parsed manifest, whichever module the IDE opened. */
    private record Workspace(Path root, JkBuild rootBuild) {}

    private static Workspace workspace(Path startDir, JkBuild rootBuild) throws IOException {
        Path wsRoot;
        if (rootBuild.isWorkspaceRoot()) {
            wsRoot = startDir;
        } else {
            var rootOpt = WorkspaceLocator.findRoot(startDir);
            wsRoot = rootOpt.orElse(startDir);
            rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(wsRoot));
        }
        // Canonicalize wsRoot so paths from BuildGraph (which calls toRealPath) and workspace-loader
        // paths are consistent — critical for correct relativize() on systems where the temp/project
        // dir is reached via a symlink (e.g. macOS /var/folders → /private/var/folders).
        try {
            wsRoot = wsRoot.toRealPath();
        } catch (IOException ignored) {
        }
        return new Workspace(wsRoot, rootBuild);
    }

    /** Per-module dependency edges, external-library refs, processor jars — each row prefixed by the module index. */
    private record Edges(List<String> siblingRefs, List<String> libEntries, List<String> processorJars) {}

    private static Edges edges(
            List<Path> dirs, Map<Path, JkBuild> allModules, Map<Path, JkBuild> modules, Map<String, String[]> allLibs)
            throws IOException {
        List<String> siblingRefs = new ArrayList<>(); // "i|name|scope"
        List<String> libEntries = new ArrayList<>(); // "i|libName|SCOPE1,SCOPE2"
        List<String> processorJars = new ArrayList<>(); // "i|path"
        for (int i = 0; i < dirs.size(); i++) {
            Path dir = dirs.get(i);
            JkBuild module = allModules.get(dir);
            for (String[] mr : siblingModuleRefs(dir, module, modules)) {
                siblingRefs.add(i + "|" + mr[0] + "|" + mr[1]);
            }
            for (String[] le : moduleLibEntries(dir, module, modules, allLibs)) {
                libEntries.add(i + "|" + le[0] + "|" + le[1]);
            }
            for (String jar : processorLibFiles(dir, module, modules, allLibs)) {
                processorJars.add(i + "|" + jar);
            }
        }
        return new Edges(siblingRefs, libEntries, processorJars);
    }

    /** Per-module facts + layout paths + SDK refs, one entry per module in {@code dirs} order. */
    private record ModuleColumns(
            List<String> moduleDirs,
            List<String> names,
            List<String> javaReleases,
            List<String> mainClasses,
            List<String> classesDirs,
            List<String> testClassesDirs,
            List<String> jdtClassesDirs,
            List<String> jdtTestClassesDirs,
            List<String> genSrcDirs,
            List<String> genTestSrcDirs,
            List<String> sdkStableNames,
            List<String> sdkNames,
            List<String> sdkLevels,
            List<String> sdkHomes,
            List<String> sdkVersions) {
        static ModuleColumns empty() {
            return new ModuleColumns(
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
        }
    }

    private static ModuleColumns moduleColumns(
            List<Path> dirs, Map<Path, JkBuild> allModules, Map<Path, String[]> sdkRefs) {
        ModuleColumns m = ModuleColumns.empty();
        for (Path dir : dirs) {
            JkBuild module = Objects.requireNonNull(allModules.get(dir), "module");
            BuildLayout layout = BuildLayout.of(dir, module);
            m.moduleDirs().add(dir.toString());
            m.names().add(module.project().name());
            m.javaReleases().add(String.valueOf(module.project().javaRelease()));
            m.mainClasses().add(module.mainClass() == null ? "" : module.mainClass());
            m.classesDirs().add(layout.classesDir().toString());
            m.testClassesDirs().add(layout.testClassesDir().toString());
            m.jdtClassesDirs().add(layout.jdtClassesDir().toString());
            m.jdtTestClassesDirs().add(layout.jdtTestClassesDir().toString());
            m.genSrcDirs().add(layout.generatedSourcesDir("annotations").toString());
            m.genTestSrcDirs()
                    .add(layout.generatedSourcesDir("annotations", "test").toString());
            String[] sdk = Objects.requireNonNull(sdkRefs.get(dir), "sdk ref");
            m.sdkStableNames().add(sdk[0]);
            m.sdkNames().add(sdk[1]);
            m.sdkLevels().add(sdk[2]);
            m.sdkHomes().add(sdk[3]);
            m.sdkVersions().add(sdk[4]);
        }
        return m;
    }

    /** The library table as columns: name, file name, jar, sources ("" when none). */
    private record LibColumns(List<String> names, List<String> files, List<String> jars, List<String> sources) {}

    private static LibColumns libColumns(Map<String, String[]> allLibs) {
        LibColumns libs = new LibColumns(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Map.Entry<String, String[]> e : allLibs.entrySet()) {
            libs.names().add(e.getKey());
            libs.files().add(e.getValue()[0]);
            libs.jars().add(e.getValue()[1]);
            libs.sources().add(e.getValue()[2] == null ? "" : e.getValue()[2]);
        }
        return libs;
    }

    // =========================================================================
    // JDK / SDK resolution
    // =========================================================================

    /**
     * Resolve a module's stable SDK handle as {@code {stableName, sdkName, languageLevel, javaHome,
     * version}}. The module's declared {@code project.jdk} level wins; the workspace lock's
     * {@code [jdk]} pin supplies the level only when the module declares none, and its vendor and
     * exact version only when it agrees with the level in force. Falls back to level 21 and the
     * default vendor (Temurin). When the JDK is installed, ensures the {@link StableJdkPointer} and
     * queues a {@code name|home|version} SDK entry (once per SDK).
     */
    private static String[] sdkRefFor(
            Path moduleDir,
            JkBuild module,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<String> sdkEntries,
            Set<String> seen)
            throws IOException {
        int declared = module.project().jdkMajor() > 0
                ? module.project().jdkMajor()
                : module.project().javaRelease();
        int level = declared;
        Lockfile.JdkPin lockJdk = readLockJdk(moduleDir);
        Integer pinMajor = lockJdk == null ? null : JdkKeywords.leadingMajor(lockJdk.version());
        // The lock governing a member is the WORKSPACE lock, one table for every module. It names
        // the toolchain jk resolved for the build, so it fills in a level the module never declared
        // and never overrides one it did — otherwise a workspace-wide pin flattens every module to
        // the same level and per-module levels become unexpressible.
        if (pinMajor != null && declared <= 0) level = pinMajor;
        if (level <= 0) level = 21;

        // Only a pin that agrees with this module's level describes this module's JDK; a module off
        // the pinned level resolves its own, or IntelliJ gets the pinned home under the wrong name.
        boolean pinFits = lockJdk != null && pinMajor != null && pinMajor == level;
        Optional<JdkHit> hit = Optional.empty();
        if (lockJdk != null && pinFits) {
            hit = LockPinMatch.choose(registry.listHits(), lockJdk);
        }
        if (hit.isEmpty()) hit = registry.findHitBySpec(String.valueOf(level));

        String vendor;
        String version = lockJdk != null && pinFits ? lockJdk.version() : null;
        if (hit.isPresent()) {
            JdkVendor v = hit.get().vendor();
            vendor = v.jbPrefix().orElse(v.vendor().toLowerCase(Locale.ROOT));
            if (version == null) version = hit.get().version();
        } else if (lockJdk != null && pinFits && !lockJdk.vendor().isBlank()) {
            vendor = lockJdk.vendor();
        } else {
            vendor = "temurin";
        }

        String stableName = vendor + "-" + level;
        String sdkName = "jk-" + stableName;
        if (hit.isPresent() && seen.add(sdkName)) {
            pointer.ensure(stableName, IntellijJdkDir.installDirOf(hit.get().home()));
            sdkEntries.add(sdkName + "|" + pointer.javaHome(stableName) + "|"
                    + (version != null ? version : String.valueOf(level)));
        }
        int langLevel = module.project().javaRelease() > 0 ? module.project().javaRelease() : level;
        return new String[] {
            stableName,
            sdkName,
            String.valueOf(langLevel),
            pointer.javaHome(stableName).toString(),
            version != null ? version : String.valueOf(level)
        };
    }

    /**
     * The project-default SDK: root {@code jdk}, else the highest module level. Reuses a
     * module's resolved handle when one matches that level; otherwise resolves the root build.
     */
    private static String[] defaultSdkRef(
            Path wsRoot,
            JkBuild root,
            Map<Path, JkBuild> modules,
            Map<Path, String[]> sdkRefs,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<String> sdkEntries,
            Set<String> seen)
            throws IOException {
        if (sdkRefs.containsKey(wsRoot)) return sdkRefs.get(wsRoot);
        int level = root.project().jdkMajor();
        if (level == 0)
            for (JkBuild m : modules.values())
                level = Math.max(level, m.project().jdkMajor());
        for (String[] r : sdkRefs.values()) if (Integer.parseInt(r[2]) == level) return r;
        return sdkRefFor(wsRoot, root, registry, pointer, sdkEntries, seen);
    }

    /** The resolved JDK pin stamped in the workspace {@code jk-lock.toml}, or null. */
    private static Lockfile.@Nullable JdkPin readLockJdk(Path moduleDir) {
        Path lf = LockPaths.lockFile(moduleDir);
        if (!Files.exists(lf)) return null;
        try {
            return LockfileReader.read(lf).jdk();
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    // =========================================================================
    // Library collection
    // =========================================================================

    /**
     * Collect all external (non-workspace) dep library definitions for one module as {@code
     * libName → {fileName, jarPath, sourcesPath|null}}. {@code fetchMissing} fetches missing JARs
     * in-line, for callers with no hosted workspace sync in front of them.
     */
    private static void collectLibDefs(
            Path moduleDir,
            JkBuild module,
            Map<Path, JkBuild> modules,
            Cas cas,
            Map<String, String[]> allLibs,
            boolean fetchMissing)
            throws IOException {
        Path lockFile = LockPaths.lockFile(moduleDir);
        if (!Files.exists(lockFile)) return;
        Lockfile lock = LockfileReader.read(lockFile);

        if (fetchMissing) {
            try {
                new CacheSync(cas, new Http()).sync(lock, CacheSync.ProgressObserver.NOOP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // best-effort; missing JARs are skipped below
                Log.debug("collectLibDefs: best-effort", e);
            }
        }

        Set<String> siblingCoords = siblingCoordinates(module, modules);
        boolean m2 = JkM2Config.resolve().integration();
        ArtifactLocator locator = new ArtifactLocator(cas.root(), m2 ? M2Dirs.localRepository() : null, m2);

        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue; // path/git dep
            if (isSibling(siblingCoords, pkg)) continue; // workspace sibling → module dep
            if (allLibs.containsKey(pkg.name() + ":" + pkg.version())) continue;

            if (pkg.name().indexOf(':') < 0) continue;
            Coordinate coord = pkg.coordinate();

            Path jar = locator.locate(pkg).orElse(null);
            if (jar == null) continue; // not yet synced / non-Maven dep

            Path sourcesPath = null;
            if (pkg.sourcesChecksum() != null) {
                Coordinate srcCoord =
                        new Coordinate(coord.group(), coord.artifact(), coord.version(), "sources", "jar");
                sourcesPath = locator.locate(
                                pkg.source(),
                                MavenLayout.artifactPath(srcCoord),
                                pkg.sourcesChecksumHex(),
                                srcCoord.toGav())
                        .orElse(null);
            }

            String libName = pkg.name() + ":" + pkg.version();
            allLibs.put(libName, new String[] {
                libFileName(libName), jar.toString(), sourcesPath == null ? null : sourcesPath.toString()
            });
        }
    }

    // =========================================================================
    // Per-module dependency lists
    // =========================================================================

    /**
     * Workspace siblings this module directly depends on, as {@code {name, scope}} using the
     * {@link IdeWireModel} scope vocabulary ({@code SCOPE_COMPILE|SCOPE_TEST|SCOPE_TEST_KIND|
     * SCOPE_COMPILE_TEST_KIND}). Tests-kind scopes are Mill testModuleDeps / Maven test-jar
     * ({@code kind = "tests"}) — IDE generators must also put the sibling's test classes on the
     * test classpath. At most one row per sibling.
     */
    private static List<String[]> siblingModuleRefs(
            Path moduleDir, @Nullable JkBuild declared, Map<Path, JkBuild> modules) throws IOException {
        JkBuild module = Objects.requireNonNull(declared, "module");
        List<String[]> result = new ArrayList<>();
        WorkspaceClasspath.Result mainCp =
                WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.EXPORT, Scope.MAIN));
        WorkspaceClasspath.Result testCp =
                WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.TEST, Scope.TEST_DEV));

        // Map jar → module name for all workspace siblings.
        Map<Path, String> jarToModule = new LinkedHashMap<>();
        Map<String, Path> nameToDir = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : modules.entrySet()) {
            BuildLayout layout = BuildLayout.of(me.getKey(), me.getValue());
            String name = me.getValue().project().name();
            jarToModule.put(layout.mainJar(), name);
            nameToDir.put(name, me.getKey());
        }

        // Use the full declared closure, not jars() — the latter is filtered to jars that already
        // exist on disk, so before a first build every sibling dependency would be dropped and the
        // IDE couldn't resolve any cross-module classes. The IDE compiles the modules itself; the
        // edge is what matters, not the artifact.
        Set<String> added = new LinkedHashSet<>();
        for (Path sj : mainCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new String[] {name, IdeWireModel.SCOPE_COMPILE});
        }
        for (Path sj : testCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new String[] {name, IdeWireModel.SCOPE_TEST});
        }
        // kind=tests edges: upgrade the existing row in place so generators expose sibling test
        // output without ever emitting a second module entry for the same sibling —
        // Eclipse JDT rejects duplicate classpath entries.
        Set<String> testsKinds = new LinkedHashSet<>();
        for (Scope scope : EnumSet.of(Scope.TEST, Scope.TEST_DEV)) {
            for (Dependency d : module.dependencies().of(scope)) {
                if (!d.isTestsKind()) continue;
                String name = siblingNameForDep(d, nameToDir, modules);
                if (name != null) testsKinds.add(name);
            }
        }
        for (String name : testsKinds) {
            boolean upgraded = false;
            for (int i = 0; i < result.size(); i++) {
                String[] row = result.get(i);
                if (!name.equals(row[0])) continue;
                if (IdeWireModel.SCOPE_TEST.equals(row[1])) {
                    result.set(i, new String[] {name, IdeWireModel.SCOPE_TEST_KIND});
                } else if (IdeWireModel.SCOPE_COMPILE.equals(row[1])) {
                    result.set(i, new String[] {name, IdeWireModel.SCOPE_COMPILE_TEST_KIND});
                }
                upgraded = true;
                break;
            }
            if (!upgraded && added.add(name)) {
                result.add(new String[] {name, IdeWireModel.SCOPE_TEST_KIND});
            }
        }
        return result;
    }

    /** Resolve a workspace/tests-kind edge to the sibling project name. */
    private static @Nullable String siblingNameForDep(
            Dependency d, Map<String, Path> nameToDir, Map<Path, JkBuild> modules) {
        if (d.isWorkspace()) {
            String n = d.workspaceName();
            return nameToDir.containsKey(n) ? n : null;
        }
        // After resolveSiblingCoordinates, module is group:artifact.
        String mod = d.module();
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            String coord = e.getValue().project().group() + ":"
                    + e.getValue().project().name();
            if (coord.equals(mod) || e.getValue().project().name().equals(mod)) {
                return e.getValue().project().name();
            }
        }
        return null;
    }

    /** External library references for one module as {@code {libName, "MAIN,TEST"}} — processor-only deps excluded. */
    private static List<String[]> moduleLibEntries(
            Path moduleDir, @Nullable JkBuild module, Map<Path, JkBuild> allModules, Map<String, String[]> allLibs)
            throws IOException {
        Path lockFile = LockPaths.lockFile(moduleDir);
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, allModules);

        List<String[]> result = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (isSibling(siblingCoords, pkg)) continue;
            // Processor-only deps belong on the annotation-processor path, not the compile classpath.
            if (processorOnly(pkg.scopes())) continue;
            String libName = pkg.name() + ":" + pkg.version();
            if (!allLibs.containsKey(libName)) continue;
            StringBuilder scopes = new StringBuilder();
            for (Scope s : pkg.scopes()) {
                if (scopes.length() > 0) scopes.append(',');
                scopes.append(s.name());
            }
            result.add(new String[] {libName, scopes.toString()});
        }
        return result;
    }

    /** The processor-scoped dependency JARs of a module — fed into the IDE's annotation-processing config. */
    private static List<String> processorLibFiles(
            Path moduleDir, @Nullable JkBuild module, Map<Path, JkBuild> modules, Map<String, String[]> allLibs)
            throws IOException {
        Path lockFile = LockPaths.lockFile(moduleDir);
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, modules);
        List<String> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (isSibling(siblingCoords, pkg)) continue;
            if (!pkg.inAnyScope(EnumSet.of(Scope.PROCESSOR))) continue;
            String[] def = allLibs.get(pkg.name() + ":" + pkg.version());
            if (def != null && def[1] != null) out.add(def[1]);
        }
        return out;
    }

    /** True when {@code PROCESSOR} is the only classpath-relevant scope on a package. */
    private static boolean processorOnly(List<Scope> scopes) {
        if (!scopes.contains(Scope.PROCESSOR)) return false;
        for (Scope s : scopes) {
            if (s == Scope.MAIN || s == Scope.EXPORT || s == Scope.PROVIDED || s == Scope.RUNTIME || s == Scope.TEST) {
                return false;
            }
        }
        return true;
    }

    /** Coordinates of all workspace siblings that this module could declare as deps. */
    /**
     * Lock rows are keyed by full package id ({@code g:a:type:classifier}) while sibling coords
     * are plain {@code group:artifact} — a raw contains(name()) never matches.
     */
    public static boolean isSibling(Set<String> siblingCoords, Lockfile.Artifact pkg) {
        return siblingCoords.contains(pkg.name())
                || siblingCoords.contains(pkg.moduleGroup() + ":" + pkg.moduleArtifact());
    }

    private static Set<String> siblingCoordinates(@Nullable JkBuild module, Map<Path, JkBuild> allModules) {
        Set<String> coords = new LinkedHashSet<>();
        for (JkBuild sib : allModules.values()) {
            if (sib != module) {
                coords.add(sib.project().group() + ":" + sib.project().name());
            }
        }
        return coords;
    }

    /** Library filename (no extension). Replaces non-safe chars with underscores. */
    private static String libFileName(String libName) {
        return libName.replace(':', '_').replace('.', '_').replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
