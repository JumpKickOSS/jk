// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.repo.MavenMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk yank} against a {@code file://} repository the project's {@code [publish]} names. */
@Tag("integration")
class YankCommandTest {

    private static final String DIR = "com/example/widget/";

    @TempDir
    Path project;

    @TempDir
    Path repo;

    @BeforeEach
    void seed() throws IOException {
        Files.writeString(project.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.3.0"
                java     = 25

                [repositories.releases]
                url = "%s"

                [publish]
                repository = "releases"
                """.formatted(repo.toUri()));
        write(
                DIR + "maven-metadata.xml",
                MavenMetadata.empty("com.example", "widget")
                        .withVersion("1.0.0")
                        .withVersion("1.1.0")
                        .withVersion("1.2.0")
                        .render());
        for (String v : new String[] {"1.0.0", "1.1.0", "1.2.0"}) {
            write(DIR + v + "/widget-" + v + ".jar", ("jar " + v).getBytes(StandardCharsets.UTF_8));
            write(DIR + v + "/widget-" + v + ".pom", ("pom " + v).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void yank_delists_the_version_and_keeps_its_files() throws Exception {
        int exit = run("yank", "-C", project.toString(), "1.2.0");

        assertThat(exit).isZero();
        MavenMetadata now = metadata();
        assertThat(now.versions()).containsExactly("1.0.0", "1.1.0");
        assertThat(now.latest()).isEqualTo("1.1.0");
        assertThat(now.release()).isEqualTo("1.1.0");
        assertThat(repo.resolve(DIR + "1.2.0/widget-1.2.0.jar")).exists();
    }

    @Test
    void a_second_yank_says_there_is_nothing_to_yank() throws Exception {
        assertThat(run("yank", "-C", project.toString(), "1.2.0")).isZero();
        byte[] after = Files.readAllBytes(repo.resolve(DIR + "maven-metadata.xml"));

        String out = Capture.stdout(() -> run("yank", "-C", project.toString(), "1.2.0"));

        assertThat(out).contains("com.example:widget:1.2.0 is not listed; nothing to yank");
        assertThat(Files.readAllBytes(repo.resolve(DIR + "maven-metadata.xml"))).isEqualTo(after);
    }

    @Test
    void delete_removes_the_files_and_warns_about_locks() throws Exception {
        Capture.Streams streams = Capture.both(() -> run("yank", "-C", project.toString(), "1.1.0", "--delete"));

        assertThat(repo.resolve(DIR + "1.1.0")).doesNotExist();
        assertThat(repo.resolve(DIR + "1.0.0/widget-1.0.0.jar")).exists();
        assertThat(metadata().versions()).containsExactly("1.0.0", "1.2.0");
        assertThat(streams.err()).contains("deleted 2 files; locks that pin 1.1.0 no longer build");
    }

    @Test
    void a_dry_run_prints_the_change_and_makes_none() throws Exception {
        byte[] before = Files.readAllBytes(repo.resolve(DIR + "maven-metadata.xml"));

        String out = Capture.stdout(() -> run("yank", "-C", project.toString(), "1.2.0", "--delete", "--dry-run"));

        assertThat(out)
                .contains("1.0.0, 1.1.0, 1.2.0 -> 1.0.0, 1.1.0")
                .contains("would delete " + DIR + "1.2.0/widget-1.2.0.jar");
        assertThat(Files.readAllBytes(repo.resolve(DIR + "maven-metadata.xml"))).isEqualTo(before);
        assertThat(repo.resolve(DIR + "1.2.0/widget-1.2.0.jar")).exists();
    }

    @Test
    void one_coordinate_with_a_repository_url_needs_no_project(@TempDir Path elsewhere) throws Exception {
        int exit = run(
                "yank",
                "-C",
                elsewhere.toString(),
                "com.example:widget:1.0.0",
                "-R",
                repo.toUri().toString());

        assertThat(exit).isZero();
        assertThat(metadata().versions()).containsExactly("1.1.0", "1.2.0");
    }

    @Test
    void json_output_is_one_yank_row() throws Exception {
        String out = Capture.stdout(() -> run("yank", "-C", project.toString(), "1.2.0", "-O", "jsonl"));

        assertThat(out.strip().lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("\"type\":\"yank\"")
                .contains("\"coordinate\":\"com.example:widget:1.2.0\"")
                .contains("\"after\":[\"1.0.0\",\"1.1.0\"]"));
    }

    @Test
    void maven_central_is_refused() throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.3.0"
                java     = 25

                [publish]
                repository = "central"
                """);

        String err = Capture.stderr(() -> run("yank", "-C", project.toString(), "1.2.0"));

        assertThat(err).contains("Maven Central never delists or deletes a published version");
        assertThat(metadata().versions()).contains("1.2.0");
    }

    private MavenMetadata metadata() throws IOException {
        return MavenMetadata.parse(
                Files.readAllBytes(repo.resolve(DIR + "maven-metadata.xml")), "com.example", "widget");
    }

    private void write(String path, byte[] body) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, body);
    }
}
