// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.model.Scope;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The IDE-agnostic, resolved view of a workspace, computed engine-side (the model math needs the
 * parsed modules + lockfiles; see {@code IdeOps}), shipped as an {@link IdeWireModel} and rebuilt
 * here by {@link #fromWire} for every {@link IdeGenerator}. Everything here is IDE-neutral; each
 * generator turns it into that IDE's on-disk files.
 *
 * @param wsRoot canonicalized workspace root
 * @param rootName the root project's name (display + single-project run config)
 * @param modules workspace modules by directory (empty for a single-project build)
 * @param allModules unified set: {@code modules}, or {@code {wsRoot: root}} for a single project
 * @param allLibs every external dependency, by coordinate {@code group:artifact:version}
 * @param siblingRefs per-module cross-module dependency edges
 * @param libEntries per-module external-library references (raw jk scopes)
 * @param processorJars per-module annotation-processor JARs
 * @param sdkRefs per-module resolved JDK handle
 * @param defaultSdk the project-default JDK handle
 * @param sdkEntries stable SDK entries to register into IntelliJ's {@code jdk.table.xml}
 * @param ideConfigDir IDE config root override for SDK registration ({@code --ide-config-dir}), or
 *     {@code null} for the host's real IDE config directories
 */
public record IdeModel(
        Path wsRoot,
        String rootName,
        Map<Path, IdeModule> modules,
        Map<Path, IdeModule> allModules,
        Map<String, LibDef> allLibs,
        Map<Path, List<ModuleRef>> siblingRefs,
        Map<Path, List<LibEntry>> libEntries,
        Map<Path, List<Path>> processorJars,
        Map<Path, SdkRef> sdkRefs,
        SdkRef defaultSdk,
        List<IntellijSdkRegistrar.SdkEntry> sdkEntries,
        @Nullable Path ideConfigDir) {

    /** Rebuild the generator-facing model from the engine's wire form. */
    public static IdeModel fromWire(IdeWireModel wire, @Nullable Path ideConfigDir) {
        Path wsRoot = Path.of(wire.wsRoot());

        List<Path> dirs = new ArrayList<>(wire.moduleDirs().size());
        Map<Path, IdeModule> allModules = new LinkedHashMap<>();
        Map<Path, SdkRef> sdkRefs = new LinkedHashMap<>();
        for (int i = 0; i < wire.moduleDirs().size(); i++) {
            Path dir = Path.of(wire.moduleDirs().get(i));
            dirs.add(dir);
            String main = wire.mainClasses().get(i);
            allModules.put(
                    dir,
                    new IdeModule(
                            wire.names().get(i),
                            parseInt(wire.javaReleases().get(i)),
                            main.isEmpty() ? null : main,
                            Path.of(wire.classesDirs().get(i)),
                            Path.of(wire.testClassesDirs().get(i)),
                            Path.of(wire.jdtClassesDirs().get(i)),
                            Path.of(wire.jdtTestClassesDirs().get(i)),
                            Path.of(wire.genSrcDirs().get(i)),
                            Path.of(wire.genTestSrcDirs().get(i))));
            sdkRefs.put(
                    dir,
                    new SdkRef(
                            wire.sdkStableNames().get(i),
                            wire.sdkNames().get(i),
                            parseInt(wire.sdkLevels().get(i)),
                            Path.of(wire.sdkHomes().get(i)),
                            wire.sdkVersions().get(i)));
        }
        Map<Path, IdeModule> modules = wire.workspace() ? allModules : Map.of();

        Map<String, LibDef> allLibs = new LinkedHashMap<>();
        for (int i = 0; i < wire.libNames().size(); i++) {
            String sources = wire.libSources().get(i);
            allLibs.put(
                    wire.libNames().get(i),
                    new LibDef(
                            wire.libNames().get(i),
                            wire.libFiles().get(i),
                            Path.of(wire.libJars().get(i)),
                            sources.isEmpty() ? null : Path.of(sources)));
        }

        Map<Path, List<ModuleRef>> siblingRefs = new LinkedHashMap<>();
        for (String row : wire.siblingRefs()) {
            String[] parts = row.split("\\|", 3);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            siblingRefs.computeIfAbsent(dir, d -> new ArrayList<>()).add(new ModuleRef(parts[1], parts[2]));
        }
        Map<Path, List<LibEntry>> libEntries = new LinkedHashMap<>();
        for (String row : wire.libEntries()) {
            String[] parts = row.split("\\|", 3);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            List<Scope> scopes = new ArrayList<>();
            for (String s : parts[2].split(",")) {
                if (!s.isBlank()) scopes.add(Scope.valueOf(s));
            }
            libEntries.computeIfAbsent(dir, d -> new ArrayList<>()).add(new LibEntry(parts[1], List.copyOf(scopes)));
        }
        Map<Path, List<Path>> processorJars = new LinkedHashMap<>();
        for (String row : wire.processorJars()) {
            String[] parts = row.split("\\|", 2);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            processorJars.computeIfAbsent(dir, d -> new ArrayList<>()).add(Path.of(parts[1]));
        }

        SdkRef defaultSdk = new SdkRef(
                wire.defSdkStableName(),
                wire.defSdkName(),
                wire.defSdkLevel(),
                Path.of(wire.defSdkHome()),
                wire.defSdkVersion());

        List<IntellijSdkRegistrar.SdkEntry> sdkEntries = new ArrayList<>();
        for (String row : wire.sdkEntries()) {
            String[] parts = row.split("\\|", 3);
            sdkEntries.add(new IntellijSdkRegistrar.SdkEntry(parts[0], Path.of(parts[1]), parts[2]));
        }

        return new IdeModel(
                wsRoot,
                wire.rootName(),
                modules,
                allModules,
                allLibs,
                siblingRefs,
                libEntries,
                processorJars,
                sdkRefs,
                defaultSdk,
                sdkEntries,
                ideConfigDir);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
