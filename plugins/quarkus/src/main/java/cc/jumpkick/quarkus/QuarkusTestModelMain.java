// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.model.command.Exit;
import io.quarkus.bootstrap.app.ApplicationModelSerializer;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultSourceDir;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Forked entry point for the {@code @QuarkusTest} application model.
 *
 * <p>Args: {@code moduleDir classesDir outDir group artifact version runtimeListFile
 * quarkusVersion platformPropsFile repositoriesFile offline}
 *
 * <p>Quarkus's test bootstrap reads a serialized {@code ApplicationModel} from the {@link
 * #SERIALIZED_TEST_APP_MODEL} system property before it looks for a Maven or Gradle workspace —
 * the seam its Gradle plugin uses. jk writes that model: the locked test closure resolved the way
 * {@link QuarkusAugmentMain} resolves the runtime closure, the application artifact pointing at
 * {@code target/classes/main} as its one root, and a workspace module naming the module directory
 * so the test harness knows whose tests these are, and the deployment jars the resolve had to
 * download kept beside the model. With the model in hand the bootstrap indexes the application
 * archive once, augments once per test profile, and never reads a {@code pom.xml}.
 */
public final class QuarkusTestModelMain {

    /** Quarkus's {@code BootstrapConstants.SERIALIZED_TEST_APP_MODEL}. */
    static final String SERIALIZED_TEST_APP_MODEL = "quarkus-internal-test.serialized-app-model.path";

    /** The serialized model's file name under the step's output dir. */
    static final String MODEL_FILE = "test-app-model.json";

    /** Where the jars the resolve had to download live, beside the model. */
    static final String LIB_DIR = "lib";

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            System.err.println("usage: QuarkusTestModelMain moduleDir classesDir outDir group artifact version"
                    + " runtimeListFile quarkusVersion platformPropsFile repositoriesFile offline");
            System.exit(Exit.USAGE);
        }
        Path moduleDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path outDir = Path.of(args[2]).toAbsolutePath().normalize();
        String group = args[3];
        String artifact = args[4];
        String version = args[5];
        Path runtimeList = Path.of(args[6]).toAbsolutePath().normalize();
        String quarkusVersion = args[7];
        Path platformProps = Path.of(args[8]).toAbsolutePath().normalize();
        RepositoryRoutes routes =
                RepositoryRoutes.read(Path.of(args[9]).toAbsolutePath().normalize());
        boolean offline = EnvValues.parseBool(args[10]).orElse(false);

        LockedClosure locked = LockedClosure.parse(runtimeList);
        System.err.println("jk-quarkus-test-model: locked test closure="
                + locked.artifacts().size() + (offline ? " offline" : ""));

        Files.createDirectories(outDir);
        Path scratch = Files.createDirectories(outDir.resolve(".jk-quarkus-bootstrap"));
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        Path appJar = scratch.resolve("app.jar");
        AppJar.write(classesDir, appJar);

        ApplicationModel resolved = QuarkusAugmentMain.resolveModel(
                locked, localRepo, offline, routes, group, artifact, version, appJar, classesDir, quarkusVersion);
        QuarkusAugmentMain.injectPlatform(resolved, quarkusVersion, platformProps, offline);
        ApplicationModel model = LockedAppModel.withApplicationModule(
                resolved, workspaceModule(moduleDir, classesDir, group, artifact, version));
        // The resolve downloads what no mirror had into its private local repository under the
        // `.jk-` scratch, which the action cache never keeps; the model outlives it in lib/.
        model = LockedAppModel.withPathsRelocated(model, scratch, outDir.resolve(LIB_DIR));

        Path file = outDir.resolve(MODEL_FILE);
        ApplicationModelSerializer.serialize(model, file);
        System.out.println("jk-quarkus-test-model: " + file);
    }

    /**
     * The module as Quarkus's workspace sees it: the module directory, {@code jk.toml} as its build
     * file, its build directory (the one holding the {@code classes} tree {@code classesDir} sits
     * in), and the main source set whose output is {@code classesDir} — the only class tree the
     * model roots. The source directories are named when they exist under the conventional
     * layout; the bootstrap reads output trees, not sources, to load a test. The build file is
     * load-bearing: the serialized module carries its build files only when there is one, and the
     * reader requires the list.
     */
    static WorkspaceModule workspaceModule(
            Path moduleDir, Path classesDir, String group, String artifact, String version) {
        List<SourceDir> sources = new ArrayList<>();
        List<SourceDir> resources = new ArrayList<>();
        for (String dir : List.of("src/main/java", "src/main/kotlin", "src")) {
            Path src = moduleDir.resolve(dir);
            if (Files.isDirectory(src)) {
                sources.add(new DefaultSourceDir(src, classesDir, null));
                break;
            }
        }
        for (String dir : List.of("src/main/resources", "resources")) {
            Path res = moduleDir.resolve(dir);
            if (Files.isDirectory(res)) {
                resources.add(new DefaultSourceDir(res, classesDir, null));
                break;
            }
        }
        return WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of(group, artifact, version))
                .setModuleDir(moduleDir)
                .setBuildFile(moduleDir.resolve(ManifestNames.MANIFEST))
                .setBuildDir(buildDirOf(classesDir, moduleDir))
                .addArtifactSources(new DefaultArtifactSources(ArtifactSources.MAIN, sources, resources))
                .build();
    }

    /** {@code <build>/classes/main} names {@code <build>}; a classes dir shaped otherwise falls back to the module. */
    private static Path buildDirOf(Path classesDir, Path moduleDir) {
        Path classes = classesDir.getParent();
        Path build = classes == null ? null : classes.getParent();
        return build == null ? moduleDir : build;
    }

    private QuarkusTestModelMain() {}
}
