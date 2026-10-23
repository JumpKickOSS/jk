// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JarPackager;
import cc.jumpkick.compile.PackagedTree;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tree a module's packagers write: its classes with the plugin-contributed classes laid over
 * them, read where they lie. What the jar holds is exactly this tree, so nothing outside it — a
 * file a previous build left in {@code target} — can reach the jar.
 */
class PackagedClassesTest {

    private static final String MANIFEST = """
            group = "com.example"
            name = "svc"
            version = "0.1.0"
            """;

    private static final String AOT_STEP = "micronaut-aot";

    @Test
    void a_leftover_from_a_previous_build_does_not_reach_the_tree(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("Kept.class"), "kept");
        Files.writeString(f.contributed.resolve("Generated.class"), "generated");
        // What the old staging dir held, left behind by an older jk.
        Path stage = Files.createDirectories(f.layout.moduleTargetDir().resolve("package-classes"));
        Files.writeString(stage.resolve("Stale.class"), "stale");

        assertThat(contents(PlannerSupport.packagedClasses(f.classes, contributed(f))))
                .containsExactly(Map.entry("Generated.class", "generated"), Map.entry("Kept.class", "kept"));
    }

    @Test
    void contributed_classes_land_next_to_the_main_classes(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.createDirectories(f.classes.resolve("com/example"));
        Files.writeString(f.classes.resolve("com/example/App.class"), "app");
        Files.createDirectories(f.contributed.resolve("com/example/aot"));
        Files.writeString(f.contributed.resolve("com/example/aot/Optimized.class"), "opt");

        assertThat(contents(PlannerSupport.packagedClasses(f.classes, contributed(f))))
                .containsEntry("com/example/App.class", "app")
                .containsEntry("com/example/aot/Optimized.class", "opt");
    }

    /**
     * A plugin worker whose contribution is a workspace sibling that is itself a plugin — grails
     * over spring-boot: both carry {@code META-INF/services/cc.jumpkick.plugin.Plugin}, and letting
     * the sibling's file win made the grails jar register SpringBootPlugin, so the worker reported
     * "no plugin with protocol prefix ##JKGR:".
     */
    @Test
    void service_registrations_from_every_source_are_merged(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Path own = Files.createDirectories(f.classes.resolve("META-INF/services"));
        Files.writeString(own.resolve("cc.jumpkick.plugin.Plugin"), "cc.jumpkick.grails.GrailsPlugin\n");
        Files.writeString(own.resolve("java.nio.file.spi.FileSystemProvider"), "x.OnlyMine\n");
        Path sibling = Files.createDirectories(f.contributed.resolve("META-INF/services"));
        Files.writeString(
                sibling.resolve("cc.jumpkick.plugin.Plugin"),
                "# the sibling's own registration\ncc.jumpkick.boot.SpringBootPlugin\ncc.jumpkick.grails.GrailsPlugin\n");
        Files.writeString(f.contributed.resolve("Sibling.class"), "sib");

        Map<String, String> tree = contents(PlannerSupport.packagedClasses(f.classes, contributed(f)));

        assertThat(tree.get("META-INF/services/cc.jumpkick.plugin.Plugin"))
                .as("the module's own provider first, the sibling's after it, each once")
                .isEqualTo("cc.jumpkick.grails.GrailsPlugin\ncc.jumpkick.boot.SpringBootPlugin\n");
        assertThat(tree.get("META-INF/services/java.nio.file.spi.FileSystemProvider"))
                .as("a file one source owns is taken as is")
                .isEqualTo("x.OnlyMine\n");
        assertThat(tree).containsEntry("Sibling.class", "sib");
    }

    /**
     * A worker vendors a workspace sibling's class tree, and a sibling that is itself a plugin
     * carries its own {@code jk-plugin.toml} and {@code templates/} at that root. The module's
     * descriptor is the one the jar ships; the sibling's root resources do not reach it at all.
     */
    @Test
    void a_vendored_sibling_s_descriptor_and_templates_do_not_reach_the_tree_or_the_jar(@TempDir Path tmp)
            throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("jk-plugin.toml"), "[plugin]\nid = \"grails\"\ntable = \"grails\"\n");
        Files.createDirectories(f.classes.resolve("templates/java/grails"));
        Files.writeString(f.classes.resolve("templates/java/grails/hello.g8"), "own");
        Files.writeString(
                f.contributed.resolve("jk-plugin.toml"), "[plugin]\nid = \"spring-boot\"\ntable = \"spring-boot\"\n");
        Files.createDirectories(f.contributed.resolve("templates/java/spring-boot"));
        Files.writeString(f.contributed.resolve("templates/java/spring-boot/hello.g8"), "sibling");
        Files.createDirectories(f.contributed.resolve("cc/jumpkick/boot"));
        Files.writeString(f.contributed.resolve("cc/jumpkick/boot/BootJarPackager.class"), "vendored");

        PackagedTree packaged = PlannerSupport.packagedClasses(f.classes, contributed(f));
        Map<String, String> tree = contents(packaged);

        assertThat(tree.get("jk-plugin.toml")).contains("id = \"grails\"");
        assertThat(tree).containsEntry("templates/java/grails/hello.g8", "own");
        assertThat(tree.keySet()).noneMatch(n -> n.startsWith("templates/java/spring-boot"));
        assertThat(tree)
                .as("the sibling's classes are still vendored")
                .containsEntry("cc/jumpkick/boot/BootJarPackager.class", "vendored");

        Path jar = tmp.resolve("jk-grails.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(packaged, jar));
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry descriptor = jf.getJarEntry("jk-plugin.toml");
            assertThat(descriptor).isNotNull();
            assertThat(new String(jf.getInputStream(descriptor).readAllBytes(), StandardCharsets.UTF_8))
                    .contains("table = \"grails\"");
            assertThat(jf.stream()
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith("templates/spring-boot")
                                    || n.startsWith("templates/java/spring-boot")))
                    .isEmpty();
            assertThat(jf.getJarEntry("cc/jumpkick/boot/BootJarPackager.class")).isNotNull();
        }
    }

    @Test
    void a_contribution_that_is_not_a_plugin_tree_is_taken_whole(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.createDirectories(f.contributed.resolve("META-INF/native-image"));
        Files.writeString(f.contributed.resolve("META-INF/native-image/reflect.json"), "[]");
        Files.writeString(f.contributed.resolve("application.yml"), "aot: true");

        assertThat(contents(PlannerSupport.packagedClasses(f.classes, contributed(f))))
                .containsEntry("META-INF/native-image/reflect.json", "[]")
                .containsEntry("application.yml", "aot: true");
    }

    @Test
    void nothing_contributed_is_the_classes_dir(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.writeString(f.classes.resolve("Kept.class"), "kept");
        // The declared step exists but never ran, so its scratch dir is absent.
        PathUtil.deleteRecursivelyOrThrow(f.contributed);

        PackagedTree tree = PlannerSupport.packagedClasses(f.classes, contributed(f));

        assertThat(tree.root()).isEqualTo(f.classes);
        assertThat(contents(tree)).containsExactly(Map.entry("Kept.class", "kept"));
        assertThat(f.layout.moduleTargetDir().resolve("package-classes")).doesNotExist();
    }

    @Test
    void a_jar_from_the_tree_is_the_jar_from_the_same_files_on_disk(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        Files.createDirectories(f.classes.resolve("META-INF/services"));
        Files.writeString(f.classes.resolve("META-INF/services/p.Plugin"), "a.Own\n");
        Files.writeString(f.classes.resolve("App.class"), "app");
        Files.createDirectories(f.contributed.resolve("META-INF/services"));
        Files.writeString(f.contributed.resolve("META-INF/services/p.Plugin"), "b.Sibling\n");
        Files.writeString(f.contributed.resolve("Gen.class"), "gen");
        PackagedTree tree = PlannerSupport.packagedClasses(f.classes, contributed(f));
        Path onDisk = tmp.resolve("materialized");
        tree.copyInto(onDisk);

        Path fromTree = new JarPackager().packageJar(JarPackager.JarRequest.of(tree, tmp.resolve("tree.jar")));
        Path fromDir = new JarPackager().packageJar(JarPackager.JarRequest.of(onDisk, tmp.resolve("dir.jar")));

        assertThat(Files.readAllBytes(fromTree)).isEqualTo(Files.readAllBytes(fromDir));
    }

    /** Each entry's content, by name. */
    private static Map<String, String> contents(PackagedTree tree) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (PackagedTree.Entry e : tree.entries()) {
            byte[] bytes = e.file() != null ? Files.readAllBytes(e.file()) : e.bytes();
            out.put(e.name(), new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static List<Path> contributed(Fixture f) {
        return PlannerSupport.existingContributedDirs(f.decls, f.layout);
    }

    private record Fixture(BuildLayout layout, Path classes, Path contributed, PluginDeclarations decls) {}

    private static Fixture fixture(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("svc"));
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);

        Path classes = Files.createDirectories(layout.classesDir());
        var step = new TaskDecl(
                AOT_STEP,
                List.of(),
                List.of(),
                List.of("generated"),
                List.of("generated/classes"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                false,
                null);
        var decls = new PluginDeclarations(List.of(step), null, List.of());
        Path contributed = Files.createDirectories(
                PluginBuild.taskScratch(layout, AOT_STEP).resolve("generated/classes"));
        return new Fixture(layout, classes, contributed, decls);
    }
}
