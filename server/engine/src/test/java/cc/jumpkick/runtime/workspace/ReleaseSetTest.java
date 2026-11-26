// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [publish] skip = true} keeps a member out of {@code jk publish} and {@code jk install}, and
 * a member that ships may not name a skipped one where a consumer resolves it (a
 * workspace's test harnesses, examples and site tool went to ~/.m2 with {@code jk -a install}, and
 * would have gone to Central).
 */
class ReleaseSetTest {

    @TempDir
    Path tmp;

    private Path workspace(String coreDeps) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "testing/kit", "examples/demo"]
                """);
        member("core", coreDeps);
        member("testing/kit", "[publish]\nskip = true\n");
        member("examples/demo", "[publish]\nskip = true\n\n[dependencies]\ncore.workspace = true\n");
        return tmp;
    }

    private void member(String rel, String extra) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(rel));
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        Files.writeString(dir.resolve("jk.toml"), "name = \"" + name + "\"\n\n" + extra);
        Path src = Files.createDirectories(dir.resolve("src/main/java/ex/" + name));
        Files.writeString(src.resolve("A.java"), "package ex." + name + "; public final class A {}\n");
    }

    private List<BuildGraph.BuildUnit> units() throws Exception {
        return List.of(unit("core"), unit("testing/kit"), unit("examples/demo"));
    }

    private BuildGraph.BuildUnit unit(String rel) throws Exception {
        Path dir = tmp.resolve(rel);
        var build = JkBuildParser.parse(dir.resolve("jk.toml"));
        return new BuildGraph.BuildUnit(
                dir, build, "com.example:" + build.project().name(), BuildGraph.Origin.MODULE);
    }

    private WorkspaceRequest request(WorkspaceSpec spec) {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(spec);
    }

    private List<String> refusals(WorkspaceRequest request) throws Exception {
        return ReleaseSet.refusals(
                request, WorkspacePreflightPhase.manifests(units()), WorkspacePreflightPhase.selection(request));
    }

    private @Nullable String note(WorkspaceRequest request) throws Exception {
        return ReleaseSet.note(
                request, WorkspacePreflightPhase.manifests(units()), WorkspacePreflightPhase.selection(request));
    }

    private static WorkspaceSpec install() {
        return WorkspaceSpec.install(Set.of(), Map.of(), null, null);
    }

    private static TailArgs.Publish publishArgs() {
        return new TailArgs.Publish(
                null,
                false,
                true,
                null,
                null,
                RepoCredential.ANONYMOUS,
                false,
                List.of(PublishLane.LIBRARY),
                false,
                null);
    }

    @Test
    void a_skipped_member_has_no_jar_to_ship_and_the_run_names_it() throws Exception {
        workspace("");
        BuildGraph.BuildUnit kit = unit("testing/kit");
        assertThat(ModuleJar.skipsRelease(kit.manifest())).isTrue();
        assertThat(ModuleJar.packagingStep(kit.manifest(), kit.dir()))
                .as("it still packages")
                .isNotNull();
        assertThat(ModuleJar.shippedStep(kit.manifest(), kit.dir())).isNull();
        assertThat(ModuleJar.shippedStep(unit("core").manifest(), unit("core").dir()))
                .isNotNull();

        assertThat(refusals(request(install()))).isEmpty();
        assertThat(note(request(install()))).isEqualTo("[publish] skip keeps 2 modules out of jk install: kit, demo");
        assertThat(note(request(WorkspaceSpec.publish(Set.of(), publishArgs()))))
                .isEqualTo("[publish] skip keeps 2 modules out of jk publish: kit, demo");
        assertThat(note(request(WorkspaceSpec.DEFAULT)))
                .as("a build ships nothing")
                .isNull();
    }

    @Test
    void a_shipped_member_that_depends_on_a_skipped_one_is_refused_before_it_builds() throws Exception {
        workspace("[dependencies]\nkit.workspace = true\n");

        assertThat(refusals(request(install())))
                .containsExactly("core depends on kit, which [publish] skip keeps out of jk install, so nothing that"
                        + " uses core could resolve it; remove skip from testing/kit/jk.toml, or make kit a"
                        + " [test-dependencies] entry of core");
        assertThat(refusals(request(WorkspaceSpec.publish(Set.of(), publishArgs()))))
                .singleElement()
                .asString()
                .contains("keeps out of jk publish");
        assertThat(refusals(request(WorkspaceSpec.DEFAULT)))
                .as("a build ships nothing")
                .isEmpty();
        assertThat(refusals(request(WorkspaceSpec.install(Set.of(tmp.resolve("examples/demo")), Map.of(), null, null))))
                .as("a selection that ships no member naming kit")
                .isEmpty();
    }

    @Test
    void a_framework_packager_is_refused_for_maven_repositories() throws Exception {
        workspace("""
                [spring-boot]
                version = "4.0.0"
                """);
        assertThat(refusals(request(WorkspaceSpec.publish(Set.of(), publishArgs()))))
                .containsExactly("spring-boot: use the release lane for packaged apps");
        assertThat(refusals(request(WorkspaceSpec.install(Set.of(), Map.of(), null, InstallDestination.M2))))
                .containsExactly("spring-boot: use the release lane for packaged apps");
        assertThat(refusals(request(WorkspaceSpec.install(Set.of(), Map.of(), null, InstallDestination.JK))))
                .isEmpty();
    }

    @Test
    void a_skipped_member_in_a_shipped_member_s_test_tables_is_fine() throws Exception {
        workspace("[test-dependencies]\nkit.workspace = true\n");
        assertThat(refusals(request(install()))).isEmpty();
    }
}
