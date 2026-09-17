// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.testing.FakeClock;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code build-info} step body over a real checkout: {@code git.properties} carries the keys
 * Boot's {@code GitProperties} reads, one commit renders the same bytes twice (a skip), a new
 * commit rewrites the file, {@code time = "build"} reads the clock, and a Boot module also gets
 * {@code META-INF/build-info.properties}.
 */
class PlannerBuildInfoTest {

    private static final Instant FIRST = Instant.parse("2026-06-01T13:47:52Z");
    private static final Instant SECOND = Instant.parse("2026-06-02T08:00:00Z");
    private static final String MANIFEST = """
            group = "com.example"
            name = "svc"
            version = "1.4.0"
            java = 25
            """;

    private final List<String> warnings = new ArrayList<>();
    private final FakeClock clock = new FakeClock().set(Instant.parse("2026-09-16T12:00:00Z"));

    @Test
    void git_properties_carry_the_commit_and_one_commit_renders_once(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        RevCommit first = commit(repo, "one", FIRST);
        Path module = Files.createDirectories(repo.resolve("svc"));
        Path classes = Files.createDirectories(module.resolve("target/classes/main"));
        JkBuild project = JkBuildParser.parse(MANIFEST + "\n[build-info]\n");
        JkBuild.BuildInfo info = Objects.requireNonNull(project.build().buildInfo());

        assertThat(PlannerBuildInfo.write(module, project, info, classes, clock, this::warn))
                .isTrue();
        Properties git = load(classes.resolve("git.properties"));
        assertThat(git.getProperty("git.commit.id")).isEqualTo(first.getName());
        assertThat(git.getProperty("git.commit.id.abbrev"))
                .isEqualTo(first.getName().substring(0, 7));
        assertThat(git.getProperty("git.branch")).isEqualTo("main");
        assertThat(git.getProperty("git.commit.time")).isEqualTo("2026-06-01T13:47:52+0000");
        assertThat(git.getProperty("git.build.time"))
                .as("the default reads the commit time, not the clock")
                .isEqualTo("2026-06-01T13:47:52+0000");
        assertThat(git.getProperty("git.build.version")).isEqualTo("1.4.0");
        assertThat(git.getProperty("git.dirty")).isEqualTo("false");
        assertThat(classes.resolve(PlannerBuildInfo.BOOT_ENTRY))
                .as("not a Boot module")
                .doesNotExist();
        assertThat(warnings).isEmpty();

        byte[] once = Files.readAllBytes(classes.resolve("git.properties"));
        assertThat(PlannerBuildInfo.write(module, project, info, classes, clock, this::warn))
                .as("the same commit is a skip")
                .isFalse();
        assertThat(PlannerBuildInfo.outOfSync(module, project, info, classes, clock))
                .isFalse();
        assertThat(Files.readAllBytes(classes.resolve("git.properties"))).isEqualTo(once);

        RevCommit second = commit(repo, "two", SECOND);
        assertThat(PlannerBuildInfo.outOfSync(module, project, info, classes, clock))
                .isTrue();
        assertThat(PlannerBuildInfo.write(module, project, info, classes, clock, this::warn))
                .isTrue();
        assertThat(load(classes.resolve("git.properties")).getProperty("git.commit.id"))
                .isEqualTo(second.getName());
    }

    @Test
    void a_boot_module_also_gets_build_info_properties_and_the_clock_only_when_asked(@TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        commit(repo, "one", FIRST);
        Path classes = Files.createDirectories(repo.resolve("target/classes/main"));
        JkBuild project = JkBuildParser.parse(MANIFEST + """

                [spring-boot]
                version = "4.0.0"

                [build-info]
                file = "app-git.properties"
                time = "build"
                """);
        JkBuild.BuildInfo info = Objects.requireNonNull(project.build().buildInfo());

        assertThat(PlannerBuildInfo.write(repo, project, info, classes, clock, this::warn))
                .isTrue();
        Properties git = load(classes.resolve("app-git.properties"));
        assertThat(git.getProperty("git.build.time")).isEqualTo("2026-09-16T12:00:00+0000");
        assertThat(git.getProperty("git.commit.time")).isEqualTo("2026-06-01T13:47:52+0000");
        Properties boot = load(classes.resolve(PlannerBuildInfo.BOOT_ENTRY));
        assertThat(boot.getProperty("build.artifact")).isEqualTo("svc");
        assertThat(boot.getProperty("build.group")).isEqualTo("com.example");
        assertThat(boot.getProperty("build.name")).isEqualTo("svc");
        assertThat(boot.getProperty("build.version")).isEqualTo("1.4.0");
        assertThat(boot.getProperty("build.time")).isEqualTo("2026-09-16T12:00:00+0000");

        clock.advance(Duration.ofMinutes(1));
        assertThat(PlannerBuildInfo.write(repo, project, info, classes, clock, this::warn))
                .as("the wall clock moved, so the files are rewritten")
                .isTrue();
    }

    @Test
    void without_a_commit_the_step_warns_and_removes_a_stale_file(@TempDir Path tmp) throws Exception {
        // A repository with no commit yet shadows whatever checkout the temp dir itself sits in.
        Path module = Files.createDirectories(tmp.resolve("exported"));
        Git.init().setDirectory(module.toFile()).call().close();
        Path classes = Files.createDirectories(module.resolve("target/classes/main"));
        Files.writeString(classes.resolve("git.properties"), "git.commit.id=stale\n");
        JkBuild project = JkBuildParser.parse(MANIFEST + "\n[build-info]\n");
        JkBuild.BuildInfo info = Objects.requireNonNull(project.build().buildInfo());

        assertThat(PlannerBuildInfo.write(module, project, info, classes, clock, this::warn))
                .isTrue();
        assertThat(classes.resolve("git.properties")).doesNotExist();
        assertThat(warnings).containsExactly(PlannerBuildInfo.NOT_A_REPOSITORY);
        assertThat(PlannerBuildInfo.write(module, project, info, classes, clock, this::warn))
                .isFalse();
    }

    @Test
    void a_dirty_tree_is_recorded(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        commit(repo, "one", FIRST);
        Files.writeString(repo.resolve("wip.txt"), "uncommitted");
        Path classes = Files.createDirectories(repo.resolve("target/classes/main"));
        JkBuild project = JkBuildParser.parse(MANIFEST + "\n[build-info]\n");
        PlannerBuildInfo.write(
                repo, project, Objects.requireNonNull(project.build().buildInfo()), classes, clock, this::warn);
        assertThat(load(classes.resolve("git.properties")).getProperty("git.dirty"))
                .isEqualTo("true");
    }

    private void warn(String code, String message) {
        warnings.add(message);
    }

    private static RevCommit commit(Path repo, String message, Instant when) throws Exception {
        Files.createDirectories(repo);
        boolean fresh = !Files.isDirectory(repo.resolve(".git"));
        try (Git git = fresh
                ? Git.init()
                        .setDirectory(repo.toFile())
                        .setInitialBranch("main")
                        .call()
                : Git.open(repo.toFile())) {
            // The build outputs the step writes must not count as dirt, as in any real checkout.
            if (fresh) Files.writeString(repo.resolve(".gitignore"), "target/\n");
            Files.writeString(repo.resolve(message + ".txt"), message);
            git.add().addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("t", "t@e", when, ZoneOffset.UTC);
            return git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        return p;
    }
}
