// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.giter8.Giter8Maven;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolResolver;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolves that run with no project in hand — a script's dependencies, a tool by its
 * coordinate (and {@code jk format}'s formatter jars, which resolve the same way), a giter8
 * template's {@code maven()} property — ask the repositories the build asks: with a {@code
 * settings.xml} mirror for {@code *}, every request reaches the mirror, and with a {@code
 * config.toml} override of {@code central}, the override. The sandbox denies Central's host, so a
 * request that went around the mirror fails rather than passing unseen.
 */
class SidePathMirrorsTest {

    private static final String GROUP = "com.example.sidepath";
    private static final String ARTIFACT = "lib";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub stub = new MavenStub(http);

    /** Fresh per run, so neither the process memos nor a warm store can answer for the stub. */
    private final String version = MavenStub.freshVersion();

    private @Nullable String priorSettings;

    @TempDir
    Path tmp;

    @BeforeEach
    void isolate() throws Exception {
        priorSettings = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", home.toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        RunNotices.clear();
        stub.leaf(GROUP, ARTIFACT, version);
    }

    @AfterEach
    void restore() {
        if (priorSettings == null) System.clearProperty(MavenSettings.SETTINGS_PROPERTY);
        else System.setProperty(MavenSettings.SETTINGS_PROPERTY, priorSettings);
    }

    private void mirrorEverythingToTheStub() throws Exception {
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, """
                <settings>
                  <mirrors>
                    <mirror><id>corp</id><mirrorOf>*</mirrorOf><url>%s</url></mirror>
                  </mirrors>
                </settings>
                """.formatted(http.baseUrl()));
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, settings.toString());
    }

    private Cas cas() {
        return new Cas(tmp.resolve("store"));
    }

    private Coordinate coordinate() {
        return Coordinate.of(GROUP, ARTIFACT, version);
    }

    private String jarPath() {
        return MavenStub.path(GROUP, ARTIFACT, version, ".jar");
    }

    @Test
    void a_script_without_repositories_of_its_own_resolves_through_the_settings_xml_mirror() throws Exception {
        mirrorEverythingToTheStub();

        RepoGroup repos = ScriptPlans.buildRepos(ScriptHeader.empty(), null, new Http(), cas());

        assertThat(repos.repos()).isNotEmpty().allSatisfy(repo -> assertThat(repo.mirror())
                .isPresent());
        assertThat(repos.tryFetchArtifact(coordinate())).isPresent();
        assertThat(http.requested()).contains(jarPath());
    }

    @Test
    void a_repository_a_script_header_names_is_routed_through_the_mirror_that_stands_in_for_it() throws Exception {
        mirrorEverythingToTheStub();
        ScriptHeader header = new ScriptHeader(
                List.of(),
                null,
                List.of(URI.create("https://repo.example.invalid/maven2/")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);

        RepoGroup repos = ScriptPlans.buildRepos(header, null, new Http(), cas());

        MavenRepo only = repos.repos().getFirst();
        assertThat(repos.repos()).hasSize(1);
        assertThat(only.baseUrl()).hasToString("https://repo.example.invalid/maven2/");
        assertThat(only.mirror()).isPresent();
        assertThat(repos.tryFetchArtifact(coordinate())).isPresent();
        assertThat(http.requested()).contains(jarPath());
    }

    @Test
    void a_tool_by_its_coordinate_resolves_through_the_settings_xml_mirror() throws Exception {
        mirrorEverythingToTheStub();

        ToolEnv env = new ToolResolver(RepoGroupBuilder.buildDefault(null, cas()))
                .resolve(ToolCoordSpec.parse(GROUP + ":" + ARTIFACT + ":" + version), "lib", "Main", List.of());

        assertThat(env.primary()).isEqualTo(coordinate());
        assertThat(http.requested())
                .contains(MavenStub.path(GROUP, ARTIFACT, version, ".pom"))
                .contains(jarPath());
    }

    @Test
    void a_giter8_maven_property_reads_the_versions_the_mirror_lists() throws Exception {
        mirrorEverythingToTheStub();

        String picked = Giter8Maven.fromRepositories(() -> RepoGroupBuilder.buildDefault(cas()), false)
                .latest(GROUP, ARTIFACT, true);

        assertThat(picked).isEqualTo(version);
        assertThat(http.requested()).contains(MavenStub.metadataPath(GROUP, ARTIFACT));
    }

    @Test
    void a_config_toml_override_of_central_answers_a_resolve_with_no_project() throws Exception {
        Path config = Path.of(System.getProperty("jk.env.JK_HOME")).resolve("config.toml");
        Files.writeString(config, """
                [repositories.central]
                url = "%s"
                """.formatted(http.baseUrl()));

        RepoGroup repos = RepoGroupBuilder.buildDefault(null, cas());

        assertThat(repos.tryFetchArtifact(coordinate())).isPresent();
        assertThat(http.requested()).contains(jarPath());
    }

    @Test
    void a_pinned_url_stands_alone_under_centrals_name() {
        URI pinned = URI.create(http.baseUrl());

        RepoGroup repos = RepoGroupBuilder.buildDefault(pinned, cas());

        assertThat(repos.repos()).singleElement().satisfies(repo -> {
            assertThat(repo.name()).isEqualTo("central");
            assertThat(repo.baseUrl()).isEqualTo(pinned);
        });
    }
}
